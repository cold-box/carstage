package com.carstage.app

import com.carstage.app.ResumeOffers.Offer
import com.carstage.app.ResumeOffers.Reason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ResumeOffersTest {
    private val inCall = MutableStateFlow(false)
    private val notice = MutableStateFlow<String?>(null)
    private var parked = true
    private var parkedReads = 0
    private val minute = 60_000L

    private val logged = mutableListOf<String>()

    private fun TestScope.offers() =
        ResumeOffers(backgroundScope, inCall, parked = { parkedReads++; parked }, notice = notice, log = { logged += it }, now = { testScheduler.currentTime })

    /** Shows [text] as the stop's notice and starts waiting for [reason] to be over. */
    private fun ResumeOffers.stop(reason: Reason, text: String = "stopped") {
        notice.value = text
        stoppedFor(reason, text)
    }

    @Test
    fun afterACallTheOfferIsReadyOnceTheCallEnds() = runTest {
        val offers = offers()
        inCall.value = true
        offers.stop(Reason.CALL)
        advanceTimeBy(5 * minute)
        assertEquals(Offer(Reason.CALL, ready = false), offers.offer.value)
        inCall.value = false
        runCurrent()
        assertEquals(Offer(Reason.CALL, ready = true), offers.offer.value)
    }

    @Test
    fun aCallEndedWhileDrivingIsOfferedOnlyOnceParked() = runTest {
        val offers = offers()
        inCall.value = true
        parked = false
        offers.stop(Reason.CALL)
        inCall.value = false
        advanceTimeBy(5 * minute)
        assertEquals(Offer(Reason.CALL, ready = false), offers.offer.value)
        parked = true
        advanceTimeBy(ResumeOffers.POLL_MS + 1)
        assertEquals(Offer(Reason.CALL, ready = true), offers.offer.value)
    }

    @Test
    fun afterADriveTheOfferIsReadyOnceParkedAgain() = runTest {
        val offers = offers()
        parked = false
        offers.stop(Reason.DRIVE)
        advanceTimeBy(10 * minute)
        assertEquals("still driving", Offer(Reason.DRIVE, ready = false), offers.offer.value)
        parked = true
        advanceTimeBy(ResumeOffers.POLL_MS + 1)
        assertEquals(Offer(Reason.DRIVE, ready = true), offers.offer.value)
    }

    @Test
    fun anOfferNotReadyWithinHalfAnHourIsDropped() = runTest {
        val offers = offers()
        parked = false
        offers.stop(Reason.DRIVE)
        advanceTimeBy(29 * minute)
        assertEquals(Offer(Reason.DRIVE, ready = false), offers.offer.value)
        advanceTimeBy(minute + ResumeOffers.POLL_MS)
        assertNull(offers.offer.value)
        val reads = parkedReads
        advanceTimeBy(10 * minute)
        assertEquals("nothing is read once it is dropped", reads, parkedReads)
    }

    @Test
    fun aReadyOfferIsDroppedAfterTenMinutesOrOnceDrivenOff() = runTest {
        val offers = offers()
        offers.stop(Reason.CALL)
        runCurrent()
        assertEquals(Offer(Reason.CALL, ready = true), offers.offer.value)
        advanceTimeBy(9 * minute)
        assertEquals("unanswered for 9 min", Offer(Reason.CALL, ready = true), offers.offer.value)
        advanceTimeBy(minute + ResumeOffers.POLL_MS)
        assertNull("unanswered for 10 min", offers.offer.value)

        offers.stop(Reason.DRIVE)
        runCurrent()
        assertEquals(Offer(Reason.DRIVE, ready = true), offers.offer.value)
        parked = false
        advanceTimeBy(ResumeOffers.POLL_MS + 1)
        assertNull("driven off: no means no", offers.offer.value)
    }

    @Test
    fun theReadyOfferTakesThePlaceOfThePlainStopNotice() = runTest {
        val offers = offers()
        inCall.value = true
        offers.stop(Reason.CALL, "전화가 와서 무대를 멈췄어요.")
        inCall.value = false
        runCurrent()
        assertNull(notice.value)
    }

    @Test
    fun aNoticeThatAlsoReportsWhatWasNotPutBackStays() = runTest {
        val offers = offers()
        inCall.value = true
        offers.stoppedFor(Reason.CALL, "전화가 와서 무대를 멈췄어요.")
        notice.value = "전화가 와서 무대를 멈췄어요. 일부 차량 설정을 원래대로 되돌리지 못했어요."
        inCall.value = false
        runCurrent()
        assertEquals("전화가 와서 무대를 멈췄어요. 일부 차량 설정을 원래대로 되돌리지 못했어요.", notice.value)
    }

    @Test
    fun anOfferClearedBeforeItIsReadyLeavesTheNoticeAndStopsReadingTheGear() = runTest {
        val offers = offers()
        parked = false
        offers.stop(Reason.DRIVE, "주행을 시작해서 무대를 멈췄어요.")
        advanceTimeBy(ResumeOffers.POLL_MS * 2)
        offers.clear()
        runCurrent()
        assertNull(offers.offer.value)
        val reads = parkedReads
        parked = true
        advanceTimeBy(ResumeOffers.POLL_MS * 5)
        assertEquals("no gear reads once the offer is cleared", reads, parkedReads)
        assertNull("the cleared watch does not come back", offers.offer.value)
        assertEquals("주행을 시작해서 무대를 멈췄어요.", notice.value)
    }

    @Test
    fun aNewStopReplacesTheLastOfferAndItsWatch() = runTest {
        val offers = offers()
        parked = false
        offers.stop(Reason.DRIVE, "drive")
        advanceTimeBy(ResumeOffers.POLL_MS * 2)
        inCall.value = true
        offers.stop(Reason.CALL, "call")
        runCurrent()
        val reads = parkedReads
        parked = true
        advanceTimeBy(ResumeOffers.POLL_MS * 5)
        assertEquals("the drive's watch is gone; the call's waits for the call", reads, parkedReads)
        assertEquals(Offer(Reason.CALL, ready = false), offers.offer.value)
        inCall.value = false
        runCurrent()
        assertEquals(Offer(Reason.CALL, ready = true), offers.offer.value)
        assertNull("only the latest stop's notice gives way", notice.value)
    }

    @Test
    fun comingBackSoonAfterLeavingWithAStageOpenOffersToSingOn() = runTest {
        val offers = offers()
        notice.value = "something else"
        offers.leftWhileOpen()
        advanceTimeBy(20 * minute)
        assertNull("nothing while away", offers.offer.value)
        offers.returned()
        runCurrent()
        assertEquals(Offer(Reason.BACKGROUND, ready = true), offers.offer.value)
        assertEquals("no stop notice of its own to replace", "something else", notice.value)
    }

    @Test
    fun comingBackWhileDrivingOffersOnceParked() = runTest {
        val offers = offers()
        offers.leftWhileOpen()
        parked = false
        offers.returned()
        runCurrent()
        assertEquals(Offer(Reason.BACKGROUND, ready = false), offers.offer.value)
        parked = true
        advanceTimeBy(ResumeOffers.POLL_MS + 1)
        assertEquals(Offer(Reason.BACKGROUND, ready = true), offers.offer.value)
    }

    @Test
    fun comingBackWithinThreeHoursStillOffersButNotAfter() = runTest {
        val offers = offers()
        offers.leftWhileOpen()
        advanceTimeBy(ResumeOffers.RETURN_MS)
        offers.returned()
        runCurrent()
        assertEquals("at the limit", Offer(Reason.BACKGROUND, ready = true), offers.offer.value)
        offers.clear()
        offers.leftWhileOpen()
        advanceTimeBy(ResumeOffers.RETURN_MS + 1)
        offers.returned()
        runCurrent()
        assertNull("away longer, the user starts afresh", offers.offer.value)
    }

    @Test
    fun comingBackDuringACallOffersOnlyOnceTheCallIsOver() = runTest {
        val offers = offers()
        offers.leftWhileOpen()
        inCall.value = true
        offers.returned()
        runCurrent()
        assertEquals("worded as after a call", Offer(Reason.CALL, ready = false), offers.offer.value)
        inCall.value = false
        runCurrent()
        assertEquals(Offer(Reason.CALL, ready = true), offers.offer.value)
    }

    @Test
    fun comingBackKeepsTheOfferACallOrADriveMade() = runTest {
        val offers = offers()
        offers.leftWhileOpen()
        parked = false
        offers.stop(Reason.DRIVE, "drove off")
        offers.returned()
        runCurrent()
        assertEquals(Offer(Reason.DRIVE, ready = false), offers.offer.value)
        parked = true
        advanceTimeBy(ResumeOffers.POLL_MS + 1)
        assertEquals(Offer(Reason.DRIVE, ready = true), offers.offer.value)
        assertNull("the drive's notice gave way to its question", notice.value)
    }

    @Test
    fun aSecondReturnAfterTheOfferWasDroppedOffersNothing() = runTest {
        val offers = offers()
        offers.leftWhileOpen()
        offers.returned()
        runCurrent()
        advanceTimeBy(ResumeOffers.READY_MS + ResumeOffers.POLL_MS)
        assertNull("unanswered, dropped", offers.offer.value)
        offers.returned()
        runCurrent()
        assertNull("no stage was open when it left this time", offers.offer.value)
    }

    @Test
    fun anAnswerForgetsThatTheStageWasLeftOpen() = runTest {
        val offers = offers()
        offers.leftWhileOpen()
        offers.clear()
        offers.returned()
        runCurrent()
        assertNull(offers.offer.value)
    }

    @Test
    fun comingBackWithoutHavingLeftAStageOpenOffersNothing() = runTest {
        val offers = offers()
        offers.returned()
        runCurrent()
        assertNull(offers.offer.value)
    }
}
