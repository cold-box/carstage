package com.carstage.app

import com.carstage.app.stage.AudioEvent
import com.carstage.app.stage.CabinCommand
import com.carstage.app.stage.DuelAnnouncement
import com.carstage.app.stage.DuelState
import com.carstage.app.stage.GlowDirector
import com.carstage.app.stage.StageController
import com.carstage.app.stage.StageController.Phase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SongSessionTest {
    private val phase = MutableStateFlow(Phase.CLOSED)
    private val music = MutableStateFlow(false)
    private val notices = NoticeBoard()
    private var opens = 0
    private var openStage: suspend () -> OpenOutcome = {
        opens++
        phase.value = Phase.OPEN
        OpenOutcome.Opened(StageController.OpenResult(emptyList(), alreadyOpen = false))
    }
    private val failures = mutableListOf<String>()
    private val logged = mutableListOf<String>()
    private val sung = AudioEvent.Frame(loudness = 0.6f, voiced = true)
    private val quiet = AudioEvent.Frame(loudness = 0f, voiced = false)

    private fun TestScope.session(titleOf: (Int) -> String? = { null }) = SongSession(
        backgroundScope,
        phase,
        music,
        openStage = { openStage() },
        notices = notices,
        log = { logged += it },
        warn = {},
        fail = { line, e -> failures += "$line: ${e.message}" },
        titleOf = titleOf,
    ).also { runCurrent() }

    private fun GlowDirector.sing(seconds: Int) = repeat((seconds * GlowDirector.FRAMES_PER_SECOND).toInt()) { direct(sung) }

    private fun SongSession.director(night: Boolean = false) =
        newDirector(onSongEnded = {}, onSound = {}, onSoundStop = {}, onSoundChange = {}, onSpeak = {}, night = { night })

    @Test
    fun aDirectorMadeAtNightKeepsItsLightUnderTheNightCeiling() = runTest {
        val director = session().director(night = true)
        val levels = (1..200).flatMap { director.direct(if (it == 100) AudioEvent.Cheer else sung) }
            .filterIsInstance<CabinCommand.AmbientBrightness>().map { it.level.value }
        assertEquals("a cheer reaches the night's top and no further", GlowDirector.NIGHT_CEILING, levels.max())
    }

    @Test
    fun aScoredSongCarriesTheTitleThatPlayedOverIt() = runTest {
        val lengths = mutableListOf<Int>()
        val session = session { seconds -> lengths += seconds; "Vibe Ace" }
        val director = session.director()
        director.sing(20)
        director.direct(AudioEvent.SongEnded)
        assertEquals("Vibe Ace", session.result.value?.title)
        assertEquals("asked over the song's length", listOf(session.result.value?.seconds), lengths)
        assertTrue(logged.any { it.endsWith("title \"Vibe Ace\"") })
    }

    @Test
    fun aScoredSongIsShownAndANewStageStartsClear() = runTest {
        val session = session()
        val director = session.director()
        director.sing(20)
        director.direct(AudioEvent.SongEnded)
        assertTrue(session.result.value != null)
        session.opened(alreadyOpen = true)
        assertTrue("the same stage keeps it", session.result.value != null)
        session.opened(alreadyOpen = false)
        assertNull(session.result.value)
    }

    @Test
    fun aStageSaysAScoreWillComeUntilItsFirstSongIsScored() = runTest {
        val session = session()
        val director = session.director()
        assertEquals(false, session.scored.value)
        director.sing(5)
        director.direct(AudioEvent.SongEnded)
        assertEquals("a song too short to score keeps the hint", false, session.scored.value)
        director.sing(20)
        director.direct(AudioEvent.SongEnded)
        assertEquals(true, session.scored.value)
        session.dismissResult()
        assertEquals("dismissing the score does not bring the hint back", true, session.scored.value)
        session.opened(alreadyOpen = true)
        assertEquals("the same stage keeps it", true, session.scored.value)
        session.opened(alreadyOpen = false)
        assertEquals("a new stage starts again", false, session.scored.value)
    }

    @Test
    fun aDuelAskedForOnAClosedStageOpensItFirstAndSaysHowItStarted() = runTest {
        val session = session()
        val director = session.director()
        val outcome = async { session.startDuel() }
        runCurrent()
        assertEquals("opened first", 1, opens)
        director.direct(quiet)
        assertEquals(DuelOutcome.Asked(afterThisSong = false), outcome.await())
        assertEquals(DuelState.start(), session.duel.value)
        assertNull("a duel takes the score's place", session.result.value)
    }

    @Test
    fun aDuelWithoutADirectorIsRefusedOnTheScreen() = runTest {
        phase.value = Phase.OPEN
        val outcome = session().startDuel()
        assertEquals(DuelOutcome.Refused(NoticeBoard.DUEL_FAILED), outcome)
        assertEquals(NoticeBoard.DUEL_FAILED, notices.notice.value)
    }

    @Test
    fun aTapThatFailsToStartADuelBecomesANotice() = runTest {
        openStage = { error("vehicle service died") }
        session().requestDuel(now = false)
        runCurrent()
        assertEquals(NoticeBoard.DUEL_FAILED, notices.notice.value)
        assertEquals(listOf("duel start failed: vehicle service died"), failures)
    }

    @Test
    fun aDuelAskedAgainOnTheDriversTurnCallsNoNewTurn() = runTest {
        phase.value = Phase.OPEN
        val session = session()
        val director = session.director()
        val first = async { session.startDuel(now = true) }
        runCurrent()
        director.direct(quiet)
        assertEquals(DuelOutcome.Asked(afterThisSong = false, turnIsNew = true), first.await())
        val again = async { session.startDuel(now = true) }
        runCurrent()
        director.direct(quiet)
        assertEquals("still the driver's turn, nothing new to call", DuelOutcome.Asked(afterThisSong = false, turnIsNew = false), again.await())
    }

    @Test
    fun closingTheStageEndsADuelBeingSung() = runTest {
        phase.value = Phase.OPEN
        val session = session()
        val director = session.director()
        val outcome = async { session.startDuel(now = true) }
        runCurrent()
        director.direct(quiet)
        outcome.await()
        assertEquals(DuelState.Step.TURN, session.duel.value?.step)
        phase.value = Phase.CLOSED
        runCurrent()
        assertNull(session.duel.value)
    }

    @Test
    fun closingTheStageDuringTheAnnouncementTellsTheWinnerAtOnce() = runTest {
        phase.value = Phase.OPEN
        val session = session()
        val director = session.director()
        val outcome = async { session.startDuel(now = true) }
        runCurrent()
        director.direct(quiet)
        outcome.await()
        repeat(2) {
            director.sing(20)
            director.direct(AudioEvent.SongEnded)
        }
        assertEquals(DuelState.Step.ANNOUNCING to false, session.duel.value?.let { it.step to it.winnerShown })
        phase.value = Phase.CLOSED
        runCurrent()
        assertEquals("the card shows the scores and its buttons", DuelState.Step.DONE to true, session.duel.value?.let { it.step to it.winnerShown })
    }

    @Test
    fun aDuelsScoredTurnEndsTheHintThatAScoreWillCome() = runTest {
        phase.value = Phase.OPEN
        val session = session()
        val director = session.director()
        val outcome = async { session.startDuel(now = true) }
        runCurrent()
        director.direct(quiet)
        outcome.await()
        assertEquals(false, session.scored.value)
        director.sing(20)
        director.direct(AudioEvent.SongEnded)
        assertEquals("the driver's turn was a scored song", true, session.scored.value)
        session.dismissDuel()
        assertEquals("closing the duel's card does not bring the hint back", true, session.scored.value)
    }

    @Test
    fun aFinishedDuelGivesWayToTheNextSongsScore() = runTest {
        phase.value = Phase.OPEN
        val session = session()
        val director = session.director()
        val outcome = async { session.startDuel(now = true) }
        runCurrent()
        director.direct(quiet)
        outcome.await()
        repeat(2) {
            director.sing(20)
            director.direct(AudioEvent.SongEnded)
        }
        assertEquals(DuelState.Step.ANNOUNCING, session.duel.value?.step)
        repeat(DuelAnnouncement.TOTAL_FRAMES) { director.direct(quiet) }
        assertEquals(DuelState.Step.DONE, session.duel.value?.step)
        val announcement = logged.mapNotNull { Regex("^duel (announcing|crowned|done):").find(it)?.groupValues?.get(1) }
        assertEquals("each part of the announcement is logged by name (docs/05)", listOf("announcing", "crowned", "done"), announcement)
        assertNull("the duel's songs are not solo scores", session.result.value)
        director.sing(20)
        director.direct(AudioEvent.SongEnded)
        assertNull(session.duel.value)
        assertTrue(session.result.value != null)
    }
}
