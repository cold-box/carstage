package com.carstage.app.stage

import com.carstage.app.vehicle.VehicleCallTimeoutException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The write logic of [VehicleCabin] that needs no car: [VehicleCabin.applyOnce]. */
class VehicleCabinApplyTest {
    private var told = 0
    private val denials = ControlDenials { told++ }.apply { stageStarted() }
    private var saves = 0
    private var writes = 0
    private var forgets = 0
    private val target = CabinTarget.AmbientColor

    /** What the emulator throws for a cabin write without the CONTROL_* permissions. */
    private val permission = SecurityException("setAmbientLightBrightnessColor need Permission")

    private suspend fun send(firstSave: Boolean = true, write: () -> ApplyResult): ApplyResult =
        VehicleCabin.applyOnce(
            target,
            denials,
            save = { saves++; firstSave },
            write = { writes++; write() },
            forget = { forgets++ },
        )

    private suspend inline fun <reified T : Throwable> refused(noinline block: suspend () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        fail("expected ${T::class.java.simpleName}")
        error("unreachable")
    }

    @Test
    fun anAllowedWriteSavesItsOriginalAndIsSent() = runTest {
        assertEquals(ApplyResult.APPLIED, send { ApplyResult.APPLIED })
        assertEquals(1, saves)
        assertEquals(0, forgets)
    }

    @Test
    fun aDeniedFirstChangeDropsTheOriginalItSavedAndIsNotSentAgainThisStage() = runTest {
        refused<CabinControlDenied> { send { throw permission } }
        assertTrue(denials.isDenied(target))
        assertEquals("nothing changed, so nothing to restore after a crash", 1, forgets)
        assertEquals(1, told)

        refused<CabinControlDenied> { send { ApplyResult.APPLIED } }
        assertEquals("not saved or sent again", 1, saves)
        assertEquals(1, writes)
        assertEquals(1, told)
    }

    @Test
    fun aWriteThatLandsAfterADenialEndsTheRefusal() = runTest {
        refused<CabinControlDenied> { send { throw permission } }
        assertTrue(denials.refused.value)
        denials.stageStarted()
        assertEquals(ApplyResult.UNSUPPORTED, send { ApplyResult.UNSUPPORTED })
        assertTrue("an unsupported target says nothing of control", denials.refused.value)
        assertEquals(ApplyResult.UNCHANGED, send { ApplyResult.UNCHANGED })
        assertTrue("nothing written says nothing of control", denials.refused.value)
        assertEquals(ApplyResult.APPLIED, send { ApplyResult.APPLIED })
        assertFalse(denials.refused.value)
    }

    @Test
    fun onlyAWriteTheCarTookEndsTheRefusal() = runTest {
        refused<CabinControlDenied> { send { throw permission } }
        denials.stageStarted()
        refused<VehicleCallTimeoutException> { send { throw VehicleCallTimeoutException(5_000) } }
        assertTrue("a timeout may not have landed", denials.refused.value)
        refused<CabinWriteRejected> { send { throw Exception("PropertyAsyncError error code 2") } }
        assertTrue("a refusal for another reason does not show the car allows it", denials.refused.value)
    }

    @Test
    fun aDenialAfterAnEarlierChangeKeepsTheOriginalForTheRestore() = runTest {
        refused<CabinControlDenied> { send(firstSave = false) { throw Exception(permission) } }
        assertEquals("an earlier write of this stage may have changed it", 0, forgets)
    }

    @Test
    fun otherRefusalsAreTriedAgainAndDropTheOriginalTheySaved() = runTest {
        refused<CabinWriteRejected> { send { throw Exception("PropertyAsyncError error code 2") } }
            .also { assertFalse(it is CabinControlDenied) }
        assertFalse("tried again next time", denials.isDenied(target))
        assertEquals("nothing changed, so nothing to restore after a crash", 1, forgets)
        assertEquals(0, told)
        refused<CabinWriteRejected> { send(firstSave = false) { throw Exception("PropertyAsyncError error code 2") } }
        assertEquals("an earlier change keeps its original", 1, forgets)
    }

    @Test
    fun aTimeoutPassesThroughAsTheWriteMayHaveLanded() = runTest {
        refused<VehicleCallTimeoutException> { send { throw VehicleCallTimeoutException(5_000) } }
        assertFalse(denials.isDenied(target))
        assertEquals(0, forgets)
    }
}
