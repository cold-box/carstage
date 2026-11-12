package com.carstage.app

import com.carstage.app.audio.Announcer
import com.carstage.app.score.SongResult
import com.carstage.app.stage.DuelState
import com.carstage.app.stage.Side
import com.carstage.app.stage.StageController.Phase
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
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class StageMcTest {
    private val phase = MutableStateFlow(Phase.CLOSED)
    private val result = MutableStateFlow<SongResult?>(null)
    private val duel = MutableStateFlow<DuelState?>(null)
    private val charge = MutableStateFlow(ChargeFinish.Step.NONE)

    /** What the voice said and when; it speaks for as long as [Announcer.estimateMs] says. */
    private val heard = mutableListOf<Pair<Long, String>>()
    private val said get() = heard.map { it.second }
    private var effects = false
    private var voiceUntil = 0L
    private var mcOn = true
    private var moment = McLines.Moment(night = false, charging = false, batteryPercent = null, twoInFront = false, hour = 7, drivenMinutes = null)
    private val greeting get() = McLines.opening(moment)
    private val song = SongResult(participation = 25, heat = 15, response = 35, completion = 10, seconds = 180, title = "A")

    private fun TestScope.mc() = StageMc(
        backgroundScope, phase, result, duel, charge,
        moment = { moment },
        soundPlaying = { effects || voiceUntil > testScheduler.currentTime },
        on = { mcOn },
        speak = {
            heard += testScheduler.currentTime to it
            voiceUntil = testScheduler.currentTime + Announcer.estimateMs(it)
        },
        log = {},
        now = { testScheduler.currentTime },
        random = Random(0),
    ).also { runCurrent() }

    private fun TestScope.open() {
        phase.value = Phase.OPEN
        runCurrent()
    }

    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    /** Opens the stage and lets its greeting be said and end. */
    private fun TestScope.openAndGreet() {
        open()
        wait(StageMc.OPENING_DELAY_MS + Announcer.MAX_LINE_MS)
        heard.clear()
    }

    private fun assertNoLineCutAnother() = heard.zipWithNext().forEach { (a, b) ->
        assertTrue("\"${b.second}\" began before \"${a.second}\" ended", b.first - a.first >= Announcer.estimateMs(a.second))
    }

    @Test
    fun aStageIsGreetedAMomentAfterItOpens() = runTest {
        mc()
        open()
        wait(StageMc.OPENING_DELAY_MS - 1)
        assertTrue(said.isEmpty())
        wait(1)
        assertEquals(listOf(greeting), said)
    }

    @Test
    fun theGreetingTellsTheMomentAsItIsSaid() = runTest {
        mc()
        open()
        wait(300)
        // The seats are read only once the stage is open.
        moment = moment.copy(twoInFront = true, hour = 14)
        wait(StageMc.OPENING_DELAY_MS)
        assertEquals(listOf(McLines.opening(moment, Random(0))), said)
    }

    @Test
    fun aScoreIsSaidOnceTheFinalesSoundIsOver() = runTest {
        mc()
        openAndGreet()
        effects = true
        result.value = song
        wait(3_000)
        assertTrue("not over the applause", said.isEmpty())
        effects = false
        wait(300)
        assertEquals(listOf(McLines.scored(song)), said)
    }

    @Test
    fun aScoreWaitsForTheApplauseThatStartsJustAfterIt() = runTest {
        mc()
        openAndGreet()
        result.value = song
        wait(100)
        effects = true
        wait(StageMc.SETTLE_MS)
        assertTrue("the applause began after the score", said.isEmpty())
        effects = false
        wait(300)
        assertEquals(listOf(McLines.scored(song)), said)
    }

    @Test
    fun soundThatGoesOnTooLongIsNotWaitedForForever() = runTest {
        mc()
        openAndGreet()
        effects = true
        result.value = song
        wait(StageMc.SETTLE_MS + StageMc.SOUND_WAIT_MS + 300)
        assertEquals(listOf(McLines.scored(song)), said)
    }

    @Test
    fun aScoreNoLongerOnTheScreenIsNotSaid() = runTest {
        mc()
        openAndGreet()
        effects = true
        result.value = song
        wait(100)
        val next = song.copy(title = "B")
        result.value = next
        wait(100)
        effects = false
        wait(StageMc.SETTLE_MS + Announcer.MAX_LINE_MS)
        assertEquals("only the score shown", listOf(McLines.scored(next)), said)
    }

    @Test
    fun afterGleoOpensTheStageItsGreetingIsLeftOutAndOtherLinesWait() = runTest {
        val mc = mc()
        open()
        wait(100)
        // Gleo answers once the stage is open.
        mc.gleoSpoke(setOf(StageMc.Said.GREETING))
        wait(StageMc.OPENING_DELAY_MS + StageMc.GLEO_QUIET_MS)
        assertTrue("Gleo greeted already", said.isEmpty())
        mc.gleoSpoke(emptySet())
        result.value = song
        wait(1_000)
        assertTrue("Gleo is still speaking", said.isEmpty())
        wait(StageMc.GLEO_QUIET_MS)
        assertEquals(listOf(McLines.scored(song)), said)
    }

    @Test
    fun afterGleoOnlyBeepsTheMcGreetsOnceTheBeepIsOver() = runTest {
        val mc = mc()
        open()
        // A late answer, so the beep is still on when the greeting is due.
        wait(StageMc.OPENING_DELAY_MS - 100)
        mc.gleoBeeped()
        wait(100)
        assertTrue("the beep is still on", said.isEmpty())
        wait(StageMc.BEEP_QUIET_MS - 100)
        assertEquals("greeted with what the car knows, not left out", listOf(greeting), said)
    }

    @Test
    fun afterGleoOnlyBeepsForADuelTheMcCallsTheDriversTurn() = runTest {
        val mc = mc()
        openAndGreet()
        mc.gleoBeeped()
        duel.value = DuelState.start()
        wait(StageMc.SETTLE_MS + 1)
        assertTrue("waits for the beep", said.isEmpty())
        wait(StageMc.BEEP_QUIET_MS)
        assertEquals(listOf(McLines.turn(Side.DRIVER)), said)
    }

    @Test
    fun aLineAskedOnAStageThatClosedIsNotSaidOnTheNext() = runTest {
        mc()
        openAndGreet()
        // The turn waits for the voice of a long line, and the stage closes and opens meanwhile,
        // starting a duel again as the duel mode's stage does.
        voiceUntil = testScheduler.currentTime + 5_000
        duel.value = DuelState.start()
        wait(StageMc.SETTLE_MS + 100)
        phase.value = Phase.CLOSED
        duel.value = null
        runCurrent()
        open()
        duel.value = DuelState.start()
        wait(StageMc.SOUND_WAIT_MS + StageMc.OPENING_DELAY_MS + 2 * Announcer.MAX_LINE_MS)
        assertEquals("the new stage's greeting, then its own turn", listOf(greeting, McLines.turn(Side.DRIVER)), said)
    }

    @Test
    fun aGreetingOnlyWaitsForAnswersThatDidNotGreet() = runTest {
        val mc = mc()
        mc.gleoSpoke(emptySet())
        open()
        wait(StageMc.OPENING_DELAY_MS + 1)
        assertTrue("waits while Gleo says it could not", said.isEmpty())
        wait(StageMc.GLEO_QUIET_MS)
        assertEquals(listOf(greeting), said)
    }

    @Test
    fun aStageOpenedLongAfterGleoAnsweredIsGreeted() = runTest {
        val mc = mc()
        mc.gleoSpoke(setOf(StageMc.Said.GREETING))
        wait(StageMc.GLEO_QUIET_MS + 1)
        open()
        wait(StageMc.OPENING_DELAY_MS + 1)
        assertEquals(listOf(greeting), said)
    }

    @Test
    fun afterGleoStartsADuelTheDriversTurnIsLeftOutAndThePassengersWaits() = runTest {
        val mc = mc()
        openAndGreet()
        mc.gleoSpoke(setOf(StageMc.Said.GREETING, StageMc.Said.DRIVER_TURN))
        duel.value = DuelState.start()
        wait(StageMc.SETTLE_MS + StageMc.GLEO_QUIET_MS + 1)
        assertTrue("Gleo said who starts", said.isEmpty())
        mc.gleoSpoke(emptySet())
        duel.value = DuelState(DuelState.Step.TURN, Side.PASSENGER, mapOf(Side.DRIVER to song))
        wait(StageMc.SETTLE_MS + 1)
        assertTrue(said.isEmpty())
        wait(StageMc.GLEO_QUIET_MS)
        assertEquals(listOf(McLines.turn(Side.PASSENGER)), said)
    }

    @Test
    fun aDuelsTurnsAreCalledInOrder() = runTest {
        mc()
        openAndGreet()
        duel.value = DuelState.start()
        wait(StageMc.SETTLE_MS + 1)
        duel.value = DuelState(DuelState.Step.TURN, Side.PASSENGER, mapOf(Side.DRIVER to song))
        wait(StageMc.SETTLE_MS + Announcer.MAX_LINE_MS)
        assertEquals(listOf(McLines.turn(Side.DRIVER), McLines.turn(Side.PASSENGER)), said)
        assertNoLineCutAnother()
    }

    @Test
    fun nothingIsSaidOverADuelsAnnouncement() = runTest {
        mc()
        openAndGreet()
        charge.value = ChargeFinish.Step.ASKING
        wait(100)
        duel.value = DuelState(DuelState.Step.ANNOUNCING, Side.PASSENGER, mapOf(Side.DRIVER to song, Side.PASSENGER to song))
        wait(StageMc.SETTLE_MS)
        assertTrue(said.isEmpty())
    }

    @Test
    fun aDuelStartedAsTheStageOpensDoesNotCutTheGreeting() = runTest {
        mc()
        open()
        wait(300)
        duel.value = DuelState.start()
        wait(StageMc.OPENING_DELAY_MS + 2 * Announcer.MAX_LINE_MS)
        assertEquals(listOf(greeting, McLines.turn(Side.DRIVER)), said)
        assertNoLineCutAnother()
    }

    @Test
    fun eachStageIsGreetedAndEachDuelCallsTheDriver() = runTest {
        mc()
        open()
        duel.value = DuelState.start()
        wait(StageMc.OPENING_DELAY_MS + 2 * Announcer.MAX_LINE_MS)
        // Closing forgets a duel still in its turns.
        phase.value = Phase.CLOSED
        duel.value = null
        wait(1)
        open()
        duel.value = DuelState.start()
        wait(StageMc.OPENING_DELAY_MS + 2 * Announcer.MAX_LINE_MS)
        assertEquals(2, said.count { it == greeting })
        assertEquals(2, said.count { it == McLines.turn(Side.DRIVER) })
    }

    @Test
    fun aStageClosedAndOpenedAgainIsGreetedOnce() = runTest {
        mc()
        open()
        wait(300)
        phase.value = Phase.CLOSED
        wait(1)
        open()
        wait(StageMc.OPENING_DELAY_MS + Announcer.MAX_LINE_MS)
        assertEquals(listOf(greeting), said)
    }

    @Test
    fun chargingThatStopsAsksForALastSongWhileItIsAsked() = runTest {
        mc()
        openAndGreet()
        charge.value = ChargeFinish.Step.ASKING
        wait(StageMc.SETTLE_MS + 1)
        assertEquals(listOf(McLines.CHARGE_DONE), said)
        heard.clear()
        wait(Announcer.MAX_LINE_MS)
        charge.value = ChargeFinish.Step.NONE
        effects = true
        charge.value = ChargeFinish.Step.ASKING
        wait(100)
        charge.value = ChargeFinish.Step.NONE
        effects = false
        wait(StageMc.SETTLE_MS)
        assertTrue("answered before it was said", said.isEmpty())
    }

    @Test
    fun aClosedStageHearsNothing() = runTest {
        mc()
        open()
        phase.value = Phase.CLOSED
        wait(StageMc.OPENING_DELAY_MS + 1)
        result.value = song
        wait(StageMc.SETTLE_MS + 1)
        assertTrue(said.isEmpty())
    }

    @Test
    fun theMcIsBusyUntilItsLineIsSaidAndSpoken() = runTest {
        val mc = mc()
        openAndGreet()
        wait(Announcer.MAX_LINE_MS)
        assertFalse(mc.busy)
        result.value = song
        runCurrent()
        assertTrue("the score waits to be said", mc.busy)
        wait(StageMc.SETTLE_MS + 1)
        assertTrue("and is being said", mc.busy)
        wait(Announcer.MAX_LINE_MS)
        assertFalse(mc.busy)
    }

    @Test
    fun nothingIsSaidWhileTheMcIsOff() = runTest {
        mcOn = false
        val mc = mc()
        open()
        wait(StageMc.OPENING_DELAY_MS + 1)
        result.value = song
        duel.value = DuelState.start()
        charge.value = ChargeFinish.Step.ASKING
        runCurrent()
        assertFalse("no line waits to be said", mc.busy)
        wait(StageMc.SETTLE_MS + Announcer.MAX_LINE_MS)
        assertTrue(said.isEmpty())
    }

    @Test
    fun aLineAskedBeforeTheMcWasTurnedOffIsNotSaid() = runTest {
        val mc = mc()
        openAndGreet()
        effects = true
        result.value = song
        wait(100)
        mcOn = false
        effects = false
        wait(StageMc.SETTLE_MS + Announcer.MAX_LINE_MS)
        assertTrue(said.isEmpty())
        assertFalse(mc.busy)
    }

    @Test
    fun turnedOnAgainTheMcSpeaksFromTheNextLine() = runTest {
        mcOn = false
        mc()
        open()
        // On again before the greeting's time: it was not asked for, so it is not said.
        wait(300)
        mcOn = true
        wait(StageMc.OPENING_DELAY_MS + Announcer.MAX_LINE_MS)
        assertTrue("the greeting was not asked for", said.isEmpty())
        result.value = song
        wait(StageMc.SETTLE_MS + 1)
        assertEquals(listOf(McLines.scored(song)), said)
    }
}
