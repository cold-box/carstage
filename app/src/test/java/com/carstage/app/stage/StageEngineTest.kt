package com.carstage.app.stage

import com.carstage.app.stage.CabinCommand.AmbientBrightness
import com.carstage.app.stage.CabinCommand.AmbientColor
import com.carstage.app.stage.CabinCommand.AmbientSwitch
import com.carstage.app.stage.CabinCommand.FanStep
import com.carstage.app.stage.CabinCommand.ReadingLight
import com.carstage.app.stage.CabinCommand.SeatVentilation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StageEngineTest {
    private val red = AmbientColor(Rgb(0xFF0000))
    private val blue = AmbientColor(Rgb(0x0000FF))

    private fun TestScope.engine(vararg outputs: CabinOutput, reports: MutableList<String> = mutableListOf(), minIntervalMs: Long = 100) =
        StageEngine(outputs.toList(), { reports += it }, minIntervalMs)

    @Test
    fun sendsEachCommandToEveryOutput() = runTest {
        val car = FakeOutput("car")
        val screen = FakeOutput("screen")
        val engine = engine(car, screen)
        engine.start(this)
        assertTrue(engine.send(red))
        runCurrent()
        assertEquals(listOf<CabinCommand>(red), car.applied)
        assertEquals(listOf<CabinCommand>(red), screen.applied)
        engine.stop()
    }

    @Test
    fun keepsOnlyTheNewestQueuedCommandPerTarget() = runTest {
        val car = FakeOutput("car")
        val engine = engine(car)
        engine.start(this)
        engine.send(blue)
        engine.send(AmbientBrightness(Percent(30)))
        engine.send(AmbientColor(Rgb(0x00FF00)))
        engine.send(red)
        runCurrent()
        assertEquals(listOf(AmbientBrightness(Percent(30)), red), car.applied)
        engine.stop()
    }

    @Test
    fun waitsTheMinimumIntervalBetweenBatches() = runTest {
        val car = FakeOutput("car")
        val engine = engine(car, minIntervalMs = 100)
        engine.start(this)
        engine.send(blue)
        runCurrent()
        engine.send(red)
        advanceTimeBy(99)
        runCurrent()
        assertEquals(1, car.applied.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(red, car.applied.last())
        engine.stop()
    }

    @Test
    fun stopDropsQueuedCommandsThenRestoresWhatChangedWithTheSwitchLast() = runTest {
        val start = SimulatedCabin.DEFAULT.with(AmbientSwitch(false))
        val car = FakeOutput("car", start)
        val engine = engine(car)
        engine.start(this)
        engine.send(AmbientSwitch(true))
        engine.send(ReadingLight(Side.DRIVER, true))
        runCurrent()
        engine.send(SeatVentilation(Side.PASSENGER, 3))
        val result = engine.stop()
        assertTrue(SeatVentilation(Side.PASSENGER, 3) !in car.applied)
        assertEquals(start, car.state)
        assertEquals(CabinTarget.AmbientSwitch, car.restored.last())
        assertTrue(result.restoredAll)
        assertFalse(engine.isRunning)
        assertFalse(engine.send(red))
    }

    @Test
    fun aRejectedWriteIsNotRestored() = runTest {
        val car = FakeOutput("car", rejectApply = { it is FanStep })
        val reports = mutableListOf<String>()
        val engine = engine(car, reports = reports)
        engine.start(this)
        engine.send(FanStep(5))
        runCurrent()
        val result = engine.stop()
        assertTrue(car.restored.isEmpty())
        assertTrue(result.restoredAll)
        assertTrue(reports.any { it.startsWith("car: Fan rejected") })
    }

    @Test
    fun startReturnsTheDrivenOutputsAndSaysWhenThereAreNone() = runTest {
        val reports = mutableListOf<String>()
        val both = engine(FakeOutput("car"), FakeOutput("screen"))
        assertEquals(listOf("car", "screen"), both.start(this))
        both.stop()
        val none = engine(FakeOutput("car", captureFails = true), reports = reports)
        assertTrue(none.start(this).isEmpty())
        assertTrue("no output can be driven this stage" in reports)
        none.stop()
    }

    @Test
    fun restoresOnlyTheTargetsTheStageChanged() = runTest {
        val car = FakeOutput("car")
        val engine = engine(car)
        engine.start(this)
        engine.send(SeatVentilation(Side.DRIVER, 3))
        runCurrent()
        engine.stop()
        assertEquals(listOf<CabinTarget>(CabinTarget.SeatVentilation(Side.DRIVER)), car.restored)
    }

    @Test
    fun neverChangesATargetWhoseValueCouldNotBeCaptured() = runTest {
        val car = FakeOutput("car", unreadable = setOf(CabinTarget.AmbientColor))
        val reports = mutableListOf<String>()
        val engine = engine(car, reports = reports)
        engine.start(this)
        engine.send(red)
        runCurrent()
        engine.stop()
        assertTrue(car.applied.isEmpty())
        assertTrue(reports.any { it.startsWith("car: AmbientColor skipped") })
    }

    @Test
    fun unsupportedTargetsAreNotRestored() = runTest {
        val car = FakeOutput("car", unsupported = setOf(CabinTarget.Fan))
        val engine = engine(car)
        engine.start(this)
        engine.send(FanStep(5))
        runCurrent()
        engine.stop()
        assertTrue(car.restored.isEmpty())
    }

    @Test
    fun anOutputThatCannotBeCapturedIsLeftOutAndTheOthersRun() = runTest {
        val car = FakeOutput("car", captureFails = true)
        val screen = FakeOutput("screen")
        val reports = mutableListOf<String>()
        val engine = engine(car, screen, reports = reports)
        engine.start(this)
        engine.send(red)
        runCurrent()
        assertTrue(car.applied.isEmpty())
        assertEquals(listOf<CabinCommand>(red), screen.applied)
        assertTrue(reports.any { it.startsWith("car: not driven") })
        engine.stop()
    }

    @Test
    fun aFailingOutputIsReportedOnceAndCountedAndTheOthersKeepGoing() = runTest {
        val car = FakeOutput("car", failApply = { true })
        val screen = FakeOutput("screen")
        val reports = mutableListOf<String>()
        val engine = engine(car, screen, reports = reports)
        engine.start(this)
        repeat(3) {
            engine.send(red)
            advanceTimeBy(100)
            runCurrent()
        }
        engine.stop()
        assertEquals(3, screen.applied.size)
        assertEquals(1, reports.count { it.startsWith("car: AmbientColor failed") && "write rejected" in it && "times" !in it })
        assertTrue(reports.any { it.startsWith("car: AmbientColor failed") && it.endsWith("(3 times)") })
    }

    @Test
    fun aSlowOutputDoesNotDelayTheOthers() = runTest {
        val car = FakeOutput("car", applyDelayMs = 5_000)
        val screen = FakeOutput("screen")
        val engine = engine(car, screen)
        engine.start(this)
        engine.send(red)
        runCurrent()
        assertEquals(listOf<CabinCommand>(red), screen.applied)
        assertTrue(car.applied.isEmpty())
        engine.stop()
        assertEquals(listOf<CabinCommand>(red), car.applied)
    }

    @Test
    fun cancellingTheStageScopeStillLetsStopRestore() = runTest {
        val start = SimulatedCabin.DEFAULT
        val car = FakeOutput("car", start)
        val reports = mutableListOf<String>()
        val engine = engine(car, reports = reports)
        val stageScope = CoroutineScope(coroutineContext + Job())
        engine.start(stageScope)
        engine.send(SeatVentilation(Side.DRIVER, 3))
        runCurrent()
        stageScope.cancel()
        runCurrent()
        assertTrue(reports.any { "sender ended before stop()" in it })
        assertTrue(engine.stop().restoredAll)
        assertEquals(start, car.state)
    }

    @Test
    fun stopInTheFinallyBlockOfACancelledCoroutineStillRestores() = runTest {
        val start = SimulatedCabin.DEFAULT
        val car = FakeOutput("car", start, applyDelayMs = 10)
        val engine = engine(car)
        val stage = launch {
            engine.start(this)
            try {
                engine.send(SeatVentilation(Side.DRIVER, 3))
                delay(60_000)
            } finally {
                engine.stop()
            }
        }
        advanceTimeBy(50)
        stage.cancel()
        advanceUntilIdle()
        assertEquals(start, car.state)
        assertFalse(engine.isRunning)
    }

    @Test
    fun stopCalledWhileStartIsCapturingWaitsAndThenEndsTheStage() = runTest {
        val car = FakeOutput("car", captureDelayMs = 1_000)
        val engine = engine(car)
        launch { engine.start(this@runTest) }
        runCurrent()
        val stop = async { engine.stop() }
        advanceUntilIdle()
        assertTrue(stop.await().restoredAll)
        assertFalse(engine.isRunning)
    }

    @Test
    fun stopReportsRestoreFailures() = runTest {
        val car = FakeOutput("car", failRestore = { true })
        val engine = engine(car)
        engine.start(this)
        engine.send(red)
        runCurrent()
        val result = engine.stop()
        assertFalse(result.restoredAll)
        assertTrue(result.failures.single().startsWith("car: restore AmbientColor failed"))
    }

    @Test
    fun restoresKeptForParkingAreReportedApartFromFailures() = runTest {
        val left = ReadingLight(Side.DRIVER, true)
        val car = FakeOutput("car", deferRestore = { it == left.target }, failRestore = { it == CabinTarget.Fan })
        val engine = engine(car)
        engine.start(this)
        engine.send(left)
        engine.send(FanStep(3))
        engine.send(red)
        runCurrent()
        val result = engine.stop()
        assertFalse(result.restoredAll)
        assertEquals(listOf("car: restore ReadingLight(side=DRIVER) deferred: refused while driving"), result.deferred)
        assertTrue(result.failures.single().startsWith("car: restore Fan failed"))
        assertFalse("a real failure means the parking promise is not enough", result.onlyDeferred)
        assertEquals(listOf(CabinTarget.AmbientColor), car.restored)
    }

    @Test
    fun aStopWithOnlyDeferredRestoresIsOnlyDeferred() = runTest {
        val car = FakeOutput("car", deferRestore = { it is CabinTarget.ReadingLight })
        val engine = engine(car)
        engine.start(this)
        engine.send(ReadingLight(Side.DRIVER, true))
        runCurrent()
        val result = engine.stop()
        assertTrue(result.onlyDeferred)
        assertFalse(result.restoredAll)
    }

    @Test
    fun bothSidesOfTheSameKindAreSentInOneBatch() = runTest {
        val car = FakeOutput("car")
        val engine = engine(car)
        engine.start(this)
        val commands = listOf(
            ReadingLight(Side.DRIVER, true),
            ReadingLight(Side.PASSENGER, true),
            SeatVentilation(Side.DRIVER, 3),
            SeatVentilation(Side.PASSENGER, 2),
        )
        commands.forEach { engine.send(it) }
        runCurrent()
        assertEquals(commands, car.applied)
        engine.stop()
    }

    @Test
    fun eachOutputIsRestoredToItsOwnCapturedState() = runTest {
        val carStart = SimulatedCabin.DEFAULT.with(SeatVentilation(Side.DRIVER, 1))
        val screenStart = SimulatedCabin.DEFAULT.with(SeatVentilation(Side.DRIVER, 2))
        val car = FakeOutput("car", carStart)
        val screen = FakeOutput("screen", screenStart)
        val engine = engine(car, screen)
        engine.start(this)
        engine.send(SeatVentilation(Side.DRIVER, 3))
        runCurrent()
        engine.stop()
        assertEquals(carStart, car.state)
        assertEquals(screenStart, screen.state)
    }

    @Test
    fun oneFailedRestoreDoesNotSkipTheRest() = runTest {
        val start = SimulatedCabin.DEFAULT.with(AmbientSwitch(false))
        val car = FakeOutput("car", start, failRestore = { it == CabinTarget.Fan })
        val engine = engine(car)
        engine.start(this)
        engine.send(FanStep(6))
        engine.send(AmbientSwitch(true))
        runCurrent()
        val result = engine.stop()
        assertEquals(false, car.state.ambientOn)
        assertTrue(result.failures.single().startsWith("car: restore Fan failed"))
    }

    @Test
    fun aWriteThatFailedIsStillRestoredBecauseItMayHaveLanded() = runTest {
        val car = FakeOutput("car", failApply = { it is SeatVentilation })
        val engine = engine(car)
        engine.start(this)
        engine.send(SeatVentilation(Side.DRIVER, 3))
        runCurrent()
        engine.stop()
        assertEquals(listOf<CabinTarget>(CabinTarget.SeatVentilation(Side.DRIVER)), car.restored)
    }

    @Test
    fun aSecondStageRestoresOnlyWhatItChanged() = runTest {
        val car = FakeOutput("car")
        val engine = engine(car)
        engine.start(this)
        engine.send(red)
        runCurrent()
        engine.stop()
        car.restored.clear()
        engine.start(this)
        engine.send(SeatVentilation(Side.PASSENGER, 2))
        runCurrent()
        engine.stop()
        assertEquals(listOf<CabinTarget>(CabinTarget.SeatVentilation(Side.PASSENGER)), car.restored)
    }

    @Test
    fun cancellingMidWriteIsNotReportedAsAFailure() = runTest {
        val car = FakeOutput("car", applyDelayMs = 1_000)
        val reports = mutableListOf<String>()
        val engine = engine(car, reports = reports)
        val stage = launch {
            engine.start(this)
            try {
                engine.send(red)
                delay(60_000)
            } finally {
                engine.stop()
            }
        }
        advanceTimeBy(500)
        stage.cancel()
        advanceUntilIdle()
        assertTrue(reports.none { "failed" in it })
    }

    @Test
    fun cueStepTimesCountFromTheStartOfPlay() = runTest {
        val car = FakeOutput("car")
        val engine = engine(car, minIntervalMs = 10)
        engine.start(this)
        val sent = mutableListOf<Long>()
        val cue = Cue.build {
            at(0, red)
            at(300, blue)
            at(600, ReadingLight(Side.DRIVER, true))
        }
        val start = testScheduler.currentTime
        launch {
            engine.play(cue)
            sent += testScheduler.currentTime - start
        }
        advanceTimeBy(599)
        runCurrent()
        assertEquals(2, car.applied.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(600L), sent)
        assertEquals(3, car.applied.size)
        engine.stop()
    }

    @Test
    fun cueRejectsNegativeTimesAndAnEmptyCueLastsZero() {
        assertTrue(runCatching { Cue.Step(-1, red) }.isFailure)
        assertEquals(0, Cue(emptyList()).durationMs)
    }

    @Test
    fun lifecycleMisuseIsRejected() = runTest {
        val engine = engine(FakeOutput("car"))
        assertFalse(engine.send(red))
        assertTrue(runCatching { engine.play(Cue.build { at(0, red) }) }.isFailure)
        assertTrue(engine.stop().restoredAll)
        engine.start(this)
        assertTrue(runCatching { engine.start(this) }.isFailure)
        engine.stop()
    }

    @Test
    fun playSendsCueStepsAtTheirTimes() = runTest {
        val car = FakeOutput("car")
        val engine = engine(car, minIntervalMs = 10)
        engine.start(this)
        val cue = Cue.build {
            at(500, AmbientColor(Rgb(0x00FF00)))
            at(0, red, ReadingLight(Side.DRIVER, true))
        }
        launch { engine.play(cue) }
        runCurrent()
        assertEquals(listOf(red, ReadingLight(Side.DRIVER, true)), car.applied)
        advanceTimeBy(499)
        runCurrent()
        assertEquals(2, car.applied.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(AmbientColor(Rgb(0x00FF00)), car.applied.last())
        assertEquals(500, cue.durationMs)
        engine.stop()
    }

    @Test
    fun everyOutputHearsThatTheStageEndedAfterItsRestores() = runTest {
        val car = FakeOutput("car")
        val screen = FakeOutput("screen", failRestore = { true })
        val engine = engine(car, screen)
        engine.start(this)
        engine.send(red)
        runCurrent()
        val stop = engine.stop()
        assertEquals(listOf(listOf<CabinTarget>(CabinTarget.AmbientColor)), car.endedAfter)
        assertEquals("ended even when restoring failed", 1, screen.endedAfter.size)
        assertEquals(1, stop.failures.size)
    }

    @Test
    fun anOutputThatCannotEndIsReported() = runTest {
        val car = FakeOutput("car", failEnd = true)
        val engine = engine(car)
        engine.start(this)
        val stop = engine.stop()
        assertEquals(listOf("car: ending the stage failed: IllegalStateException: journal not cleared"), stop.failures)
        assertFalse(engine.isRunning)
    }
}
