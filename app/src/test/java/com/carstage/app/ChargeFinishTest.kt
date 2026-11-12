package com.carstage.app

import com.carstage.app.ChargeFinish.Step
import com.carstage.app.stage.FinaleLayer
import com.carstage.app.stage.GlowDirector
import com.carstage.app.vehicle.BatteryState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChargeFinishTest {
    private val open = MutableStateFlow(false)
    private val battery = MutableStateFlow<BatteryState?>(null)
    private val announcing = MutableStateFlow(false)
    private var closes = 0
    private var closeFails = false
    private var mcBusy = false
    private val logged = mutableListOf<String>()

    private fun TestScope.finish() = ChargeFinish(
        backgroundScope,
        open,
        battery,
        announcing,
        close = { closes++; if (closeFails) error("engine stop failed") },
        log = { logged += it },
        mcBusy = { mcBusy },
    ).also { runCurrent() }

    /** Charging seen on, then off, on an open stage, and the last song chosen. */
    private fun TestScope.lastSongChosen(): ChargeFinish {
        val finish = finish()
        openStage()
        charging(true)
        charging(false)
        finish.lastSong()
        return finish
    }

    private fun TestScope.charging(on: Boolean?, percent: Int? = 80) {
        battery.value = BatteryState(percent, on)
        runCurrent()
    }

    private fun TestScope.openStage() {
        open.value = true
        runCurrent()
    }

    @Test
    fun theLastSongsScoreIsSaidBeforeTheStageCloses() = runTest {
        val finish = lastSongChosen()
        mcBusy = true
        finish.songEnded()
        advanceTimeBy(ChargeFinish.FINALE_MS + 3_000)
        runCurrent()
        assertEquals("the MC is still saying the score", 0, closes)
        mcBusy = false
        advanceTimeBy(300)
        runCurrent()
        assertEquals(1, closes)
    }

    @Test
    fun anMcThatNeverStopsDoesNotKeepTheStageOpen() = runTest {
        val finish = lastSongChosen()
        mcBusy = true
        finish.songEnded()
        advanceTimeBy(ChargeFinish.FINALE_MS + ChargeFinish.MC_WAIT_MS + 300)
        runCurrent()
        assertEquals(1, closes)
    }

    @Test
    fun chargingStoppingOnAnOpenStageAsksAboutTheLastSong() = runTest {
        val finish = finish()
        charging(true)
        openStage()
        assertEquals(Step.NONE, finish.step.value)
        charging(false)
        assertEquals(Step.ASKING, finish.step.value)
    }

    @Test
    fun onlyChargingSeenOnThenOffWhileOpenCounts() = runTest {
        val finish = finish()
        charging(true)
        charging(false)
        openStage()
        assertEquals("it stopped before the stage opened", Step.NONE, finish.step.value)
        charging(null)
        charging(false)
        assertEquals("an unknown switch is not a stop", Step.NONE, finish.step.value)
        battery.value = null
        runCurrent()
        assertEquals(Step.NONE, finish.step.value)
        charging(true)
        charging(null)
        assertEquals("an unreadable switch while charging is not a stop", Step.NONE, finish.step.value)
        charging(false)
        assertEquals("unknown in between still remembers it was on", Step.ASKING, finish.step.value)
    }

    @Test
    fun theLastSongClosesTheStageAfterItsFinale() = runTest {
        val finish = finish()
        openStage()
        charging(true)
        charging(false)
        finish.lastSong()
        assertEquals(Step.LAST_SONG, finish.step.value)
        finish.songEnded()
        advanceTimeBy(ChargeFinish.FINALE_MS - 1)
        assertEquals("the finale plays first", 0, closes)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, closes)
    }

    @Test
    fun aSongEndBeforeTheAnswerIsNotTheLastSong() = runTest {
        val finish = finish()
        openStage()
        charging(true)
        charging(false)
        finish.songEnded()
        runCurrent()
        finish.lastSong()
        advanceTimeBy(10 * ChargeFinish.FINALE_MS)
        assertEquals("the song sung after the answer is the last", 0, closes)
        finish.songEnded()
        advanceTimeBy(ChargeFinish.FINALE_MS)
        runCurrent()
        assertEquals(1, closes)
    }

    @Test
    fun keepSingingSaysNoEvenAfterAYes() = runTest {
        val finish = finish()
        openStage()
        charging(true)
        charging(false)
        finish.keepSinging()
        assertEquals(Step.NONE, finish.step.value)
        finish.lastSong()
        assertEquals("nothing is asked now", Step.NONE, finish.step.value)
        charging(true)
        charging(false)
        finish.lastSong()
        finish.songEnded()
        runCurrent()
        finish.keepSinging()
        advanceTimeBy(10 * ChargeFinish.FINALE_MS)
        assertEquals("changed their mind during the finale", 0, closes)
    }

    @Test
    fun closingTheStageForgetsTheQuestionAndAWaitingClose() = runTest {
        val finish = finish()
        openStage()
        charging(true)
        charging(false)
        finish.lastSong()
        finish.songEnded()
        runCurrent()
        open.value = false
        runCurrent()
        assertEquals(Step.NONE, finish.step.value)
        openStage()
        advanceTimeBy(10 * ChargeFinish.FINALE_MS)
        assertEquals("the new stage is not closed", 0, closes)
    }

    @Test
    fun chargingSeenOnAnEarlierStageDoesNotCount() = runTest {
        val finish = finish()
        openStage()
        charging(true)
        open.value = false
        runCurrent()
        charging(false)
        openStage()
        assertEquals("charging ended while no stage was open", Step.NONE, finish.step.value)
    }

    @Test
    fun chargingAgainDoesNotTakeBackAYes() = runTest {
        val finish = lastSongChosen()
        charging(true)
        charging(false)
        assertEquals(Step.LAST_SONG, finish.step.value)
    }

    @Test
    fun theLastSongWaitsForADuelBeingAnnounced() = runTest {
        val finish = lastSongChosen()
        announcing.value = true
        finish.songEnded()
        advanceTimeBy(10 * ChargeFinish.FINALE_MS)
        assertEquals("the winner is announced first", 0, closes)
        announcing.value = false
        runCurrent()
        assertEquals(1, closes)
    }

    @Test
    fun keepSingingWhileADuelIsAnnouncedKeepsTheStage() = runTest {
        val finish = lastSongChosen()
        announcing.value = true
        finish.songEnded()
        advanceTimeBy(ChargeFinish.FINALE_MS + 1)
        finish.keepSinging()
        announcing.value = false
        runCurrent()
        assertEquals("they chose to sing on", 0, closes)
    }

    @Test
    fun aCloseThatFailsIsLoggedAndTheQuestionIsDropped() = runTest {
        closeFails = true
        val finish = lastSongChosen()
        finish.songEnded()
        advanceTimeBy(ChargeFinish.FINALE_MS)
        runCurrent()
        assertEquals(1, closes)
        assertEquals(Step.NONE, finish.step.value)
        assertEquals("closing the stage after the last song failed: IllegalStateException: engine stop failed", logged.last())
        closeFails = false
        charging(true)
        charging(false)
        assertEquals("it still asks on the same stage", Step.ASKING, finish.step.value)
    }

    @Test
    fun theCloseWaitsLongerThanTheFinale() {
        val finaleMs = FinaleLayer.FINALE_FRAMES * 1000 / GlowDirector.FRAMES_PER_SECOND
        assertTrue("finale $finaleMs ms", ChargeFinish.FINALE_MS > finaleMs)
    }
}
