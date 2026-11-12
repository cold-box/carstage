package com.carstage.app.stage

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreJournalTest {
    private val dir: File = Files.createTempDirectory("journal").toFile().apply { deleteOnExit() }
    private val file = File(dir, "stage/restore-journal.properties")
    private val journal = RestoreJournal(file)

    private val allTargets = listOf(
        CabinTarget.AmbientSwitch,
        CabinTarget.AmbientColor,
        CabinTarget.AmbientBrightness,
        CabinTarget.ReadingLight(Side.DRIVER),
        CabinTarget.ReadingLight(Side.PASSENGER),
        CabinTarget.Fan,
        CabinTarget.SeatVentilation(Side.DRIVER),
        CabinTarget.SeatVentilation(Side.PASSENGER),
        CabinTarget.Temperature(Side.DRIVER),
        CabinTarget.Temperature(Side.PASSENGER),
    )

    @Test
    fun anEmptyJournalHasNothingToRestore() {
        assertTrue(journal.entries().isEmpty())
        journal.clear()
        assertFalse(file.exists())
    }

    @Test
    fun originalsSurviveANewJournalObjectLikeANewProcess() {
        journal.record(CabinTarget.AmbientSwitch, "AUTOMATIC")
        journal.record(CabinTarget.AmbientColor, "16777215")
        journal.record(CabinTarget.SeatVentilation(Side.PASSENGER), "2")
        assertEquals(
            mapOf(
                CabinTarget.AmbientSwitch to "AUTOMATIC",
                CabinTarget.AmbientColor to "16777215",
                CabinTarget.SeatVentilation(Side.PASSENGER) to "2",
            ),
            RestoreJournal(file).entries(),
        )
        assertFalse("no temporary file is left behind", File(file.parentFile, "${file.name}.tmp").exists())
    }

    @Test
    fun theFirstOriginalWins() {
        journal.record(CabinTarget.AmbientSwitch, "OFF")
        journal.record(CabinTarget.AmbientSwitch, "ON")
        assertEquals("OFF", journal.entries()[CabinTarget.AmbientSwitch])
    }

    @Test
    fun clearForgetsEverything() {
        journal.record(CabinTarget.Fan, "3")
        journal.clear()
        assertTrue(journal.entries().isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun everyTargetHasAKeyThatReadsBack() {
        allTargets.forEach { assertEquals(it, RestoreJournal.targetOf(RestoreJournal.keyOf(it))) }
        assertEquals(allTargets.size, allTargets.map(RestoreJournal::keyOf).toSet().size)
    }

    @Test
    fun keysFromAnotherVersionAreSkipped() {
        file.parentFile!!.mkdirs()
        file.writeText("AmbientSwitch=OFF\nSunroof=OPEN\nReadingLight.MIDDLE=ON\n")
        assertEquals(mapOf(CabinTarget.AmbientSwitch to "OFF"), journal.entries())
        assertNull(RestoreJournal.targetOf("ReadingLight"))
    }

    @Test
    fun forgetDropsOnlyTheGivenTargets() {
        journal.record(CabinTarget.AmbientSwitch, "OFF")
        journal.record(CabinTarget.AmbientColor, "255")
        journal.forget(setOf(CabinTarget.AmbientSwitch, CabinTarget.Fan))
        assertEquals(mapOf(CabinTarget.AmbientColor to "255"), journal.entries())
        journal.forget(setOf(CabinTarget.AmbientColor))
        assertFalse(file.exists())
    }
}
