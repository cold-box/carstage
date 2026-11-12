package com.carstage.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DuelOfferTest {
    private val open = MutableStateFlow(false)
    private var seated: Boolean? = true
    private var reads = 0
    private val logged = mutableListOf<String>()

    private fun TestScope.offer() = DuelOffer(backgroundScope, open, bothSeated = { reads++; seated }, log = { logged += it }).also { runCurrent() }

    @Test
    fun twoInFrontOfAnOpenStageAreOfferedADuel() = runTest {
        val offer = offer()
        assertFalse("closed", offer.offered.value)
        assertEquals("nothing is read while the stage is closed", 0, reads)
        open.value = true
        runCurrent()
        assertTrue(offer.offered.value)
        assertEquals(listOf("offering a duel: both front seats are taken"), logged)
    }

    @Test
    fun theSeatsAreReadEveryFewSecondsWhileOpen() = runTest {
        val offer = offer()
        open.value = true
        runCurrent()
        seated = false
        advanceTimeBy(DuelOffer.POLL_MS - 1)
        assertTrue("not read again yet", offer.offered.value)
        advanceTimeBy(1)
        runCurrent()
        assertFalse("the passenger got out", offer.offered.value)
        assertEquals(2, reads)
        seated = null
        advanceTimeBy(DuelOffer.POLL_MS)
        runCurrent()
        assertFalse("unreadable seats offer nothing", offer.offered.value)
        assertEquals("one line per change", 2, logged.size)
    }

    @Test
    fun closingTheStageWithdrawsTheOfferAndStopsReading() = runTest {
        val offer = offer()
        open.value = true
        runCurrent()
        open.value = false
        runCurrent()
        assertFalse(offer.offered.value)
        val readsAtClose = reads
        advanceTimeBy(10 * DuelOffer.POLL_MS)
        assertEquals(readsAtClose, reads)
    }

    @Test
    fun aNewStageReadsTheSeatsAtOnceAndOffersAgain() = runTest {
        val offer = offer()
        open.value = true
        runCurrent()
        open.value = false
        runCurrent()
        open.value = true
        runCurrent()
        assertTrue(offer.offered.value)
        assertEquals(2, reads)
        assertEquals(List(2) { "offering a duel: both front seats are taken" }, logged)
    }

    @Test
    fun anOfferWithdrawnBecauseTheSeatsCannotBeReadSaysSo() = runTest {
        val offer = offer()
        open.value = true
        runCurrent()
        seated = null
        advanceTimeBy(DuelOffer.POLL_MS)
        runCurrent()
        assertFalse(offer.offered.value)
        assertEquals("no longer offering a duel: the front seats cannot be read", logged.last())
    }

    @Test
    fun aReaderThatThrowsCountsAsUnknownAndTheOfferKeepsWatching() = runTest {
        var throwing = true
        val offer = DuelOffer(backgroundScope, open, bothSeated = { reads++; if (throwing) error("binder died") else true }, log = { logged += it })
        open.value = true
        runCurrent()
        assertFalse(offer.offered.value)
        assertEquals("reading the front seats failed: IllegalStateException: binder died", logged.single())
        throwing = false
        advanceTimeBy(DuelOffer.POLL_MS)
        runCurrent()
        assertTrue("still reading", offer.offered.value)
    }

    @Test
    fun unreadableSeatsAreNotAnOffer() = runTest {
        seated = null
        val offer = offer()
        open.value = true
        runCurrent()
        assertFalse(offer.offered.value)
        assertTrue("nothing changed, nothing logged", logged.isEmpty())
    }
}
