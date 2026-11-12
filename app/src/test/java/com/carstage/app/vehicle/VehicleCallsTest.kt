package com.carstage.app.vehicle

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleCallsTest {
    @Test
    fun theEmulatorsReadingLightRefusalInDriveIsADrivingRestriction() {
        // What the emulator sent for reading light writes in gear D (2026-10-23): an Exception
        // whose message is "java.lang.IllegalArgumentException: 50001".
        assertTrue(Exception(IllegalArgumentException("50001")).isDrivingRestricted())
        assertTrue(Exception("java.lang.IllegalArgumentException: 50001").isDrivingRestricted())
        assertTrue(IllegalArgumentException("50001").isDrivingRestricted())
    }

    @Test
    fun theDocumentedCodeNameCountsWhereverItAppears() {
        // Assumed form, not seen for this code: the emulator's other VehicleExceptions print as
        // just their code name ("a: ERROR_NOT_SUPPORTED_PROPERTY" in the vehicle probe, 2026-10-23).
        assertTrue(Exception("ERROR_DRIVING_RESTRICTED").isDrivingRestricted())
        assertTrue(Exception("ERROR_DRIVING_RESTRICTED: reading light").isDrivingRestricted())
        assertTrue(Exception("ErrorCode.ERROR_DRIVING_RESTRICTED").isDrivingRestricted())
    }

    @Test
    fun otherFailuresAreNotDrivingRestrictions() {
        assertFalse(Exception("ERROR_NOT_SUPPORTED_PROPERTY").isDrivingRestricted())
        assertFalse(Exception("ERROR_DRIVING_RESTRICTED_ALWAYS").isDrivingRestricted())
        assertFalse(VehicleCallTimeoutException(5_000).isDrivingRestricted())
        assertFalse(IllegalStateException().isDrivingRestricted())
        assertFalse(
            Exception("PropertyAsyncError{request ID: 50001, property: HVAC_FAN_SPEED, areaId: 5, error code: 2, vendor error code: 0}")
                .isDrivingRestricted(),
        )
        assertFalse(Exception("vendor error code: 50001").isDrivingRestricted())
        assertFalse(IllegalStateException("50001").isDrivingRestricted())
        assertFalse(IllegalArgumentException("150001").isDrivingRestricted())
    }

    @Test
    fun theEmulatorsRefusalWithoutPermissionIsADenialWhereverItSits() {
        // What the emulator throws for a cabin write without the CONTROL_* permissions (2026-10-24).
        assertTrue(SecurityException("setAmbientLightSwitch need Permission").isControlDenied())
        assertTrue(Exception(SecurityException("setHvacFanSpeed need Permission")).isControlDenied())
        assertTrue(Exception("java.lang.SecurityException: setReadingLightSwitch need Permission").isControlDenied())
    }

    @Test
    fun otherRefusalsAreNotDenials() {
        assertFalse(Exception("java.lang.IllegalArgumentException: 50001").isControlDenied())
        assertFalse(Exception("ERROR_NOT_SUPPORTED_PROPERTY").isControlDenied())
        assertFalse(VehicleCallTimeoutException(5_000).isControlDenied())
        assertFalse(Exception("needs a SecurityException review").isControlDenied())
    }

    @Test
    fun aPropertyTheCarLacksIsNotSupportedWhereverItIsNamed() {
        // What the emulator answers for the Odometer's trip reads (2026-11-01): "a: ERROR_NOT_SUPPORTED_PROPERTY".
        assertTrue(Exception("ERROR_NOT_SUPPORTED_PROPERTY").isNotSupported())
        assertTrue(Exception(Exception("ErrorCode.ERROR_NOT_SUPPORTED_PROPERTY")).isNotSupported())
        assertFalse(Exception("ERROR_DRIVING_RESTRICTED").isNotSupported())
        assertFalse(VehicleCallTimeoutException(5_000).isNotSupported())
        assertFalse(Exception("ERROR_NOT_SUPPORTED_PROPERTY_YET").isNotSupported())
    }

    @Test
    fun aCheckAnsweringAConfigurationIsSupportedAndOneAnsweringNoneOrNotSupportedIsNot() = runTest {
        assertTrue(awaitSupported<String> { ok, _ -> ok("config") })
        assertFalse(awaitSupported<String> { ok, _ -> ok(null) })
        assertFalse(awaitSupported<String> { _, fail -> fail(Exception("ERROR_NOT_SUPPORTED_PROPERTY")) })
    }

    @Test
    fun aCheckFailingOtherwiseIsThrownSoItIsAskedAgain() = runTest {
        val thrown = runCatching { awaitSupported<String> { _, fail -> fail(IllegalStateException("service error")) } }
        assertEquals("service error", thrown.exceptionOrNull()?.message)
        val timedOut = runCatching { awaitSupported<String> { _, _ -> } }
        assertTrue(timedOut.exceptionOrNull() is VehicleCallTimeoutException)
    }
}
