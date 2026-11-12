package com.carstage.app.stage

import com.carstage.app.stage.StageController.CloseResult
import com.carstage.app.stage.StageController.Phase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StageControllerTest {
    private val loud = AudioEvent.Frame(loudness = 0.9f, voiced = true)

    /** Lights the ambient light in proportion to loudness. */
    private val brightnessDirector = Director { event ->
        if (event is AudioEvent.Frame) listOf(CabinCommand.AmbientBrightness(Percent.ofFraction(event.level))) else emptyList()
    }

    @Test
    fun openAndCloseMoveThroughThePhasesAndRestoreTheCabin() = runTest {
        val car = FakeOutput("car")
        val stage = StageController(listOf(car), this, {}, brightnessDirector)
        assertEquals(Phase.CLOSED, stage.phase.value)
        assertEquals(StageController.OpenResult(listOf("car"), alreadyOpen = false), stage.open())
        assertEquals(Phase.OPEN, stage.phase.value)
        stage.onAudio(loud)
        runCurrent()
        assertEquals(listOf<CabinCommand>(CabinCommand.AmbientBrightness(Percent(90))), car.applied)
        val closed = stage.close()
        assertTrue(closed is CloseResult.Closed && closed.stop.restoredAll)
        assertEquals(Phase.CLOSED, stage.phase.value)
        assertEquals(SimulatedCabin.DEFAULT, car.state)
    }

    @Test
    fun theOpenResultNamesOnlyTheOutputsThatCouldBeCaptured() = runTest {
        val stage = StageController(listOf(FakeOutput("car", captureFails = true), FakeOutput("screen")), this, {})
        assertEquals(listOf("screen"), stage.open().driven)
        assertEquals(StageController.OpenResult(listOf("screen"), alreadyOpen = true), stage.open())
        stage.close()
    }

    @Test
    fun endingASongByHandReachesTheDirectorBeforeItsNextEvent() = runTest {
        val heard = mutableListOf<AudioEvent>()
        val stage = StageController(listOf(FakeOutput("car")), this, {}, Director { heard += it; emptyList() })
        assertFalse("no stage, nothing to end", stage.endSong())
        stage.open()
        assertTrue(stage.endSong())
        assertTrue("nothing yet: the audio thread delivers it", heard.isEmpty())
        stage.onAudio(loud)
        stage.onAudio(loud)
        assertEquals(listOf(AudioEvent.SongEnded, loud, loud), heard)
        stage.close()
    }

    @Test
    fun aGapHeardRightAfterASongEndedByHandIsTheSameSong() = runTest {
        val heard = mutableListOf<AudioEvent>()
        val stage = StageController(listOf(FakeOutput("car")), this, {}, Director { heard += it; emptyList() })
        stage.open()
        stage.endSong()
        repeat(StageController.HAND_END_HOLD_FRAMES - 1) { stage.onAudio(loud) }
        stage.onAudio(AudioEvent.SongEnded)
        assertEquals("the outro's gap is not another song", 1, heard.count { it == AudioEvent.SongEnded })
        stage.onAudio(loud)
        stage.onAudio(AudioEvent.SongEnded)
        assertEquals("30 s later it is", 2, heard.count { it == AudioEvent.SongEnded })
        stage.close()
    }

    @Test
    fun theMusicAppMovingOnEndsTheSongUnlessTheGapJustDid() = runTest {
        val heard = mutableListOf<AudioEvent>()
        val stage = StageController(listOf(FakeOutput("car")), this, {}, Director { heard += it; emptyList() })
        stage.open()
        assertTrue(stage.endSong(StageController.EndCause.TRACK))
        stage.onAudio(loud)
        assertEquals("the app moved on", 1, heard.count { it == AudioEvent.SongEnded })

        repeat(StageController.HAND_END_HOLD_FRAMES) { stage.onAudio(loud) }
        stage.onAudio(AudioEvent.SongEnded)
        assertEquals("a gap heard later is the next end", 2, heard.count { it == AudioEvent.SongEnded })
        repeat(StageController.TRACK_END_HOLD_FRAMES - 1) { stage.onAudio(loud) }
        stage.endSong(StageController.EndCause.TRACK)
        stage.onAudio(loud)
        assertEquals("the app moving on just after that gap is the same end", 2, heard.count { it == AudioEvent.SongEnded })

        repeat(StageController.TRACK_END_HOLD_FRAMES) { stage.onAudio(loud) }
        stage.endSong(StageController.EndCause.TRACK)
        stage.onAudio(loud)
        assertEquals("later it is another song", 3, heard.count { it == AudioEvent.SongEnded })
        stage.close()
    }

    @Test
    fun theMusicAppMovingOnRightAfterAnEndByHandIsTheSameSong() = runTest {
        val heard = mutableListOf<AudioEvent>()
        val stage = StageController(listOf(FakeOutput("car")), this, {}, Director { heard += it; emptyList() })
        stage.open()
        stage.endSong()
        repeat(StageController.HAND_END_HOLD_FRAMES - 1) { stage.onAudio(loud) }
        stage.endSong(StageController.EndCause.TRACK)
        stage.onAudio(loud)
        assertEquals("the outro the singers stopped before is not a song of its own", 1, heard.count { it == AudioEvent.SongEnded })
        repeat(StageController.HAND_END_HOLD_FRAMES) { stage.onAudio(loud) }
        stage.endSong(StageController.EndCause.TRACK)
        stage.onAudio(loud)
        assertEquals("later it is the next song", 2, heard.count { it == AudioEvent.SongEnded })
        stage.close()
    }

    @Test
    fun aGapHeardRightAfterTheMusicAppMovedOnIsTheSameSong() = runTest {
        val heard = mutableListOf<AudioEvent>()
        val stage = StageController(listOf(FakeOutput("car")), this, {}, Director { heard += it; emptyList() })
        stage.open()
        stage.endSong(StageController.EndCause.TRACK)
        repeat(StageController.HAND_END_HOLD_FRAMES - 1) { stage.onAudio(loud) }
        stage.onAudio(AudioEvent.SongEnded)
        assertEquals("the gap after the app moved on is the same end", 1, heard.count { it == AudioEvent.SongEnded })
        stage.endSong(StageController.EndCause.TRACK)
        stage.onAudio(loud)
        assertEquals("a skip right after a skip is another song", 2, heard.count { it == AudioEvent.SongEnded })
        stage.close()
    }

    @Test
    fun anEndAskedForIsNotDeliveredOnceTheStageClosed() = runTest {
        val heard = mutableListOf<AudioEvent>()
        val stage = StageController(listOf(FakeOutput("car")), this, {}, Director { heard += it; emptyList() })
        stage.open()
        stage.endSong()
        stage.close()
        stage.onAudio(loud)
        assertTrue("$heard", heard.isEmpty())
    }

    @Test
    fun anEndAskedForBeforeAStageCloseDoesNotCarryIntoTheNextStage() = runTest {
        val heard = mutableListOf<AudioEvent>()
        val stage = StageController(listOf(FakeOutput("car")), this, {}, Director { heard += it; emptyList() })
        stage.open()
        stage.endSong()
        stage.close()
        stage.open()
        stage.onAudio(loud)
        assertEquals(listOf<AudioEvent>(loud), heard)
        stage.close()
    }

    @Test
    fun audioIsIgnoredWhileTheStageIsClosed() = runTest {
        val car = FakeOutput("car")
        val stage = StageController(listOf(car), this, {}, brightnessDirector)
        stage.onAudio(loud)
        runCurrent()
        assertTrue(car.applied.isEmpty())
    }

    @Test
    fun closingWhenClosedSaysSo() = runTest {
        val stage = StageController(listOf(FakeOutput("car")), this, {})
        assertEquals(CloseResult.NotOpen, stage.close())
    }

    @Test
    fun aCloseRequestedWhileOpeningWaitsAndThenCloses() = runTest {
        val car = FakeOutput("car", captureDelayMs = 1_000)
        val stage = StageController(listOf(car), this, {})
        val opening = async { stage.open() }
        runCurrent()
        assertEquals(Phase.OPENING, stage.phase.value)
        val closing = async { stage.close() }
        advanceUntilIdle()
        opening.await()
        assertTrue(closing.await() is CloseResult.Closed)
        assertEquals(Phase.CLOSED, stage.phase.value)
    }

    @Test
    fun anOpenRequestedWhileClosingWaitsAndThenOpensAgain() = runTest {
        val car = FakeOutput("car", applyDelayMs = 1_000)
        val stage = StageController(listOf(car), this, {}, brightnessDirector)
        stage.open()
        stage.onAudio(loud)
        runCurrent()
        val closing = async { stage.close() }
        runCurrent()
        assertEquals(Phase.CLOSING, stage.phase.value)
        val reopening = async { stage.open() }
        advanceUntilIdle()
        assertTrue(closing.await() is CloseResult.Closed)
        assertFalse(reopening.await().alreadyOpen)
        assertEquals(Phase.OPEN, stage.phase.value)
        stage.close()
    }

    @Test
    fun aFailedOpenGoesBackToClosed() = runTest {
        val stage = StageController(listOf(FakeOutput("car", captureError = true)), this, {})
        assertTrue(runCatching { stage.open() }.exceptionOrNull() is AssertionError)
        assertEquals(Phase.CLOSED, stage.phase.value)
    }

    @Test
    fun aThrowingDirectorLosesTheEventNotTheAudioThread() = runTest {
        val logs = mutableListOf<String>()
        val stage = StageController(listOf(FakeOutput("car")), this, { logs += it }, Director { error("bad mapping") })
        stage.open()
        stage.onAudio(loud)
        stage.onAudio(loud)
        assertEquals(1, logs.count { it.startsWith("director failed") })
        stage.close()
    }

    @Test
    fun theGateDropsCommandsItRefuses() = runTest {
        val car = FakeOutput("car")
        val director = Director { listOf(CabinCommand.FanStep(5), CabinCommand.AmbientBrightness(Percent(70))) }
        val stage = StageController(listOf(car), this, {}, director, gate = { it !is CabinCommand.FanStep })
        stage.open()
        stage.onAudio(loud)
        runCurrent()
        assertEquals(listOf<CabinCommand>(CabinCommand.AmbientBrightness(Percent(70))), car.applied)
        stage.close()
    }

    @Test
    fun loudnessAndEnergyOutsideTheRangeAreClampedNotRejected() {
        assertEquals(1f, AudioEvent.Frame(1.2f, voiced = true).level)
        assertEquals(0f, AudioEvent.Frame(Float.NaN, voiced = false).level)
        assertEquals(0f, AudioEvent.Frame(-0.1f, voiced = false).level)
        assertEquals(0f, AudioEvent.Mood(AudioEvent.Mood.Feel.CALM, Float.NaN).level)
        assertEquals(1f, AudioEvent.Mood(AudioEvent.Mood.Feel.CALM, 5f).level)
        assertEquals(0f, AudioEvent.Mood(AudioEvent.Mood.Feel.CALM, -3f).level)
    }

    @Test
    fun eachStageThatReallyOpensGetsAFreshDirector() = runTest {
        val stage = StageController(listOf(FakeOutput("car")), this, {})
        var made = 0
        val factory = { made++; brightnessDirector }
        stage.open(factory)
        assertEquals(1, made)
        assertTrue(stage.open(factory).alreadyOpen)
        assertEquals("an open stage keeps its director", 1, made)
        stage.close()
        stage.open(factory)
        assertEquals(2, made)
        stage.close()
    }

    @Test
    fun theDirectorHearsWhatTheGateHeldBack() = runTest {
        val dropped = mutableListOf<CabinCommand>()
        val director = object : Director {
            override fun direct(event: AudioEvent) = listOf(CabinCommand.FanStep(2), CabinCommand.AmbientSwitch(true))
            override fun dropped(command: CabinCommand) {
                dropped += command
            }
        }
        val logs = mutableListOf<String>()
        val stage = StageController(listOf(FakeOutput("car")), this, { logs += it }, director, gate = { it !is CabinCommand.FanStep })
        stage.open()
        stage.onAudio(loud)
        stage.onAudio(loud)
        assertEquals(listOf<CabinCommand>(CabinCommand.FanStep(2), CabinCommand.FanStep(2)), dropped)
        stage.close()
        assertTrue(logs.contains("the command rules held back 2 commands in this stage"))
    }

    @Test
    fun theDirectorCanBeReplacedWhileOpen() = runTest {
        val car = FakeOutput("car")
        val stage = StageController(listOf(car), this, {})
        stage.open()
        stage.onAudio(loud)
        runCurrent()
        assertTrue("the default director sends nothing", car.applied.isEmpty())
        stage.director = brightnessDirector
        stage.onAudio(loud)
        runCurrent()
        assertEquals(1, car.applied.size)
        stage.close()
    }

    @Test
    fun whileClosedRunsOnlyWhenNoStageIsOpen() = runTest {
        val stage = StageController(listOf(FakeOutput("car")), this, {})
        assertEquals(1, stage.whileClosed { 1 })
        stage.open()
        assertEquals(null, stage.whileClosed { 1 })
        stage.close()
        assertEquals(2, stage.whileClosed { 2 })
    }
}
