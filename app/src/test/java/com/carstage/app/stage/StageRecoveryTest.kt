package com.carstage.app.stage

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StageRecoveryTest {
    private val file = File(Files.createTempDirectory("recovery").toFile().apply { deleteOnExit() }, "journal.properties")
    private val journal = RestoreJournal(file)
    private val logs = mutableListOf<String>()
    private val written = mutableListOf<Map<CabinTarget, String>>()

    private fun recovery(restore: suspend (Map<CabinTarget, String>) -> List<StageRecovery.Failure> = { written += it; emptyList() }) =
        StageRecovery(journal, restore) { line, _ -> logs += line }

    @Test
    fun nothingLeftMeansNothingToDo() = runTest {
        assertEquals(StageRecovery.Result.NothingToDo, recovery().run())
        assertTrue(written.isEmpty())
    }

    @Test
    fun leftoversAreWrittenBackAndForgotten() = runTest {
        journal.record(CabinTarget.AmbientSwitch, "AUTOMATIC")
        journal.record(CabinTarget.AmbientBrightness, "48")
        val result = recovery().run()
        assertEquals(StageRecovery.Result.Restored(setOf(CabinTarget.AmbientSwitch, CabinTarget.AmbientBrightness), emptyList()), result)
        assertEquals(listOf(mapOf(CabinTarget.AmbientSwitch to "AUTOMATIC", CabinTarget.AmbientBrightness to "48")), written)
        assertTrue(journal.entries().isEmpty())
        assertTrue("the log shows the values", logs.any { "AUTOMATIC" in it })
    }

    @Test
    fun anUnreachableCarKeepsTheJournalForTheNextTry() = runTest {
        journal.record(CabinTarget.Fan, "2")
        assertEquals(StageRecovery.Result.Unreachable, recovery { error("vehicle service not ready") }.run())
        assertEquals(mapOf(CabinTarget.Fan to "2"), journal.entries())
    }

    @Test
    fun failedTargetsAreReportedAndNotRetriedForever() = runTest {
        journal.record(CabinTarget.Fan, "2")
        val result = recovery { listOf(StageRecovery.Failure(CabinTarget.Fan, "rejected")) }.run()
        assertEquals(StageRecovery.Result.Restored(setOf(CabinTarget.Fan), listOf("Fan: rejected")), result)
        assertTrue(journal.entries().isEmpty())
    }

    @Test
    fun targetsRefusedOnlyForDrivingStayForARunWhileParked() = runTest {
        val light = CabinTarget.ReadingLight(Side.DRIVER)
        journal.record(light, "OFF")
        journal.record(CabinTarget.AmbientBrightness, "48")
        journal.record(CabinTarget.Fan, "2")
        val driving = recovery {
            written += it
            listOf(
                StageRecovery.Failure(light, "Exception: java.lang.IllegalArgumentException: 50001", retryWhenParked = true),
                StageRecovery.Failure(CabinTarget.Fan, "rejected"),
            )
        }.run()
        assertEquals(
            StageRecovery.Result.Restored(setOf(light, CabinTarget.AmbientBrightness, CabinTarget.Fan), listOf("Fan: rejected"), pending = setOf(light)),
            driving,
        )
        assertEquals("only the refused-for-driving target is kept", mapOf(light to "OFF"), journal.entries())

        val parked = recovery().run()
        assertEquals(StageRecovery.Result.Restored(setOf(light), emptyList()), parked)
        assertEquals(mapOf(light to "OFF"), written.last())
        assertTrue(journal.entries().isEmpty())
    }

    @Test
    fun runsWhileStillDrivingKeepEveryRefusedTarget() = runTest {
        val left = CabinTarget.ReadingLight(Side.DRIVER)
        val right = CabinTarget.ReadingLight(Side.PASSENGER)
        journal.record(left, "OFF")
        journal.record(right, "OFF")
        val driving = recovery { saved -> saved.keys.map { StageRecovery.Failure(it, "50001", retryWhenParked = true) } }
        repeat(2) {
            assertEquals(StageRecovery.Result.Restored(setOf(left, right), emptyList(), pending = setOf(left, right)), driving.run())
            assertEquals(mapOf(left to "OFF", right to "OFF"), journal.entries())
            assertTrue(logs.last().startsWith("restoring"))
        }
        assertEquals(StageRecovery.Result.Restored(setOf(left, right), emptyList()), recovery().run())
        assertTrue(journal.entries().isEmpty())
    }

    @Test
    fun aRunThatKnowsTheCarIsParkedForgetsRefusalsForDriving() = runTest {
        val light = CabinTarget.ReadingLight(Side.DRIVER)
        journal.record(light, "OFF")
        val result = recovery { listOf(StageRecovery.Failure(light, "50001", retryWhenParked = true)) }.run(parked = { true })
        assertEquals(
            StageRecovery.Result.Restored(setOf(light), listOf("ReadingLight(side=DRIVER): 50001 (refused although parked)")),
            result,
        )
        assertTrue("nothing is left for a stage to capture as its original", journal.entries().isEmpty())
    }

    @Test
    fun theGearIsReadAfterTheWritesAndOnlyForARefusalForDriving() = runTest {
        val light = CabinTarget.ReadingLight(Side.DRIVER)
        journal.record(light, "OFF")
        val events = mutableListOf<String>()
        // The car was parked when the open checked, but drove off while the recovery wrote.
        val result = recovery {
            events += "write"
            listOf(StageRecovery.Failure(light, "50001", retryWhenParked = true))
        }.run(parked = { events += "gear"; false })
        assertEquals(listOf("write", "gear"), events)
        assertEquals(setOf(light), (result as StageRecovery.Result.Restored).pending)
        assertEquals("kept for a run once parked", mapOf(light to "OFF"), journal.entries())

        journal.record(CabinTarget.Fan, "2")
        events.clear()
        recovery { events += "write"; listOf(StageRecovery.Failure(CabinTarget.Fan, "rejected")) }.run(parked = { events += "gear"; true })
        assertEquals("no refusal for driving, no gear read", listOf("write"), events)
    }

    @Test
    fun aDamagedJournalIsDiscardedAndReported() = runTest {
        file.writeText("AmbientSwitch=\\uZZZZ\n")
        assertEquals(StageRecovery.Result.Unreadable, recovery().run())
        assertTrue(written.isEmpty())
        assertTrue(journal.entries().isEmpty())
    }
}
