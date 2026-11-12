package com.carstage.app.stage

import ai.pleos.playground.vehicle.constant.control.LightSwitch
import com.carstage.app.vehicle.VehicleCallTimeoutException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The restore logic of [VehicleCabin] that needs no car: [VehicleCabin.restoreOne] and [VehicleCabin.restoreEach]. */
class VehicleCabinRestoreTest {
    /** What the emulator sends for a reading light write in gear D. */
    private val drivingRefusal = Exception(IllegalArgumentException("50001"))

    @Test
    fun theOriginalIsAlwaysWrittenFirst() = runTest {
        var reads = 0
        val outcome = VehicleCabin.restoreOne("OFF", write = {}, read = { reads++; LightSwitch.ON })
        assertEquals(VehicleCabin.Restore.WRITTEN, outcome)
        assertEquals("an accepted write needs no read", 0, reads)
    }

    @Test
    fun aRefusalForDrivingIsFineWhenTheTargetHoldsItsOriginal() = runTest {
        val outcome = VehicleCabin.restoreOne("OFF", write = { throw drivingRefusal }, read = { LightSwitch.OFF })
        assertEquals(VehicleCabin.Restore.ALREADY_ORIGINAL, outcome)
    }

    @Test
    fun aRefusalForDrivingIsDeferredWhenTheTargetHoldsSomethingElseOrCannotBeRead() = runTest {
        val on = runCatching { VehicleCabin.restoreOne("OFF", write = { throw drivingRefusal }, read = { LightSwitch.ON }) }
        assertTrue(on.exceptionOrNull() is CabinRestoreDeferred)
        val unknown = runCatching { VehicleCabin.restoreOne("OFF", write = { throw drivingRefusal }, read = { error("read failed") }) }
        val deferred = unknown.exceptionOrNull() as CabinRestoreDeferred
        assertTrue("the read failure is kept", "read failed" in deferred.message.orEmpty())
        assertEquals(1, deferred.suppressed.size)
        val missing = runCatching { VehicleCabin.restoreOne("OFF", write = { throw drivingRefusal }, read = { null }) }
        assertTrue(missing.exceptionOrNull() is CabinRestoreDeferred)
    }

    @Test
    fun otherFailuresAreThrownAsTheyAre() {
        val rejected = Exception("PropertyAsyncError{error code: 2}")
        val thrown = assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking { VehicleCabin.restoreOne("3", write = { throw rejected }, read = { 3 }) }
        }
        assertEquals(rejected, thrown)
    }

    @Test
    fun numbersCompareInTheirSavedForm() = runTest {
        val outcome = VehicleCabin.restoreOne("16777215", write = { throw drivingRefusal }, read = { 0x00FFFFFF })
        assertEquals(VehicleCabin.Restore.ALREADY_ORIGINAL, outcome)
    }

    @Test
    fun leftoversAreClassifiedAndTheAmbientSwitchGoesLast() = runTest {
        val left = CabinTarget.ReadingLight(Side.DRIVER)
        val right = CabinTarget.ReadingLight(Side.PASSENGER)
        val written = mutableListOf<CabinTarget>()
        val saved = linkedMapOf(
            CabinTarget.AmbientSwitch to "AUTOMATIC",
            left to "OFF",
            right to "OFF",
            CabinTarget.Fan to "2",
            CabinTarget.SeatVentilation(Side.DRIVER) to "0",
        )
        val failures = VehicleCabin.restoreEach(
            saved,
            write = { target, _ ->
                written += target
                when (target) {
                    is CabinTarget.ReadingLight -> throw drivingRefusal
                    CabinTarget.Fan -> throw VehicleCallTimeoutException(5_000)
                    is CabinTarget.SeatVentilation -> false
                    else -> true
                }
            },
            // The driver's light still shows the stage's flash; the passenger's is back off.
            read = { target -> if (target == left) LightSwitch.ON else LightSwitch.OFF },
        )
        assertEquals(CabinTarget.AmbientSwitch, written.last())
        assertEquals(listOf(left, CabinTarget.Fan, CabinTarget.SeatVentilation(Side.DRIVER)), failures.map { it.target })
        assertEquals(listOf(true, false, false), failures.map { it.retryWhenParked })
        assertEquals("not supported by this car", failures.last().reason)
    }

    @Test
    fun refusalsForDrivingAloneDoNotMeanTheCarIsUnreachable() = runTest {
        val light = CabinTarget.ReadingLight(Side.DRIVER)
        val failures = VehicleCabin.restoreEach(mapOf(light to "OFF"), write = { _, _ -> throw drivingRefusal }, read = { LightSwitch.ON })
        assertEquals(listOf(true), failures.map { it.retryWhenParked })
    }

    @Test
    fun onlyTimeoutsMeanTheCarIsUnreachable() {
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                VehicleCabin.restoreEach(
                    mapOf(CabinTarget.Fan to "2", CabinTarget.AmbientBrightness to "48"),
                    write = { _, _ -> throw VehicleCallTimeoutException(5_000) },
                    read = { null },
                )
            }
        }
    }
}
