package com.carstage.app.vehicle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FailureLogTest {
    private val logs = mutableListOf<String>()
    private val failures = FailureLog { line, _ -> logs += line }

    @Test
    fun failuresAreLoggedWhenTheyStartChangeRepeatAndEnd() {
        failures.failed("gear", "timeout")
        failures.failed("gear", "timeout")
        assertEquals(1, logs.size)
        assertEquals("timeout", failures.current("gear"))
        failures.failed("gear", "SecurityException")
        assertEquals(2, logs.size)
        repeat(FailureLog.EVERY) { failures.failed("gear", "SecurityException") }
        assertEquals(3, logs.size)
        failures.succeeded("gear")
        failures.succeeded("gear")
        assertEquals(4, logs.size)
        assertNull(failures.current("gear"))
        assertTrue(logs.last().contains("works again"))
    }

    @Test
    fun anAttemptTurnsAMissingValueOrAFailureIntoNullAndSaysWhenItWorksAgain() = runTest {
        assertNull(failures.attempt("ROW_1_LEFT", missing = "no area for each front seat") { null })
        assertEquals("no area for each front seat", failures.current("ROW_1_LEFT"))
        assertNull(failures.attempt<Boolean>("ROW_1_LEFT") { throw IllegalStateException("binder died") })
        assertEquals("IllegalStateException: binder died", failures.current("ROW_1_LEFT"))
        assertEquals(true, failures.attempt("ROW_1_LEFT") { true })
        assertNull(failures.current("ROW_1_LEFT"))
        assertEquals(
            listOf(
                "ROW_1_LEFT failed: no area for each front seat",
                "ROW_1_LEFT failed differently (2 in a row): IllegalStateException: binder died",
                "ROW_1_LEFT works again after 2 failures",
            ),
            logs,
        )
    }

    @Test
    fun aTimeoutInsideTheAttemptIsAFailureNotACancellation() = runTest {
        // VehicleDriveReader reads the gear with withTimeout inside attempt; its TimeoutCancellationException must become "unknown".
        assertNull(failures.attempt("gear") { withTimeout(10) { awaitCancellation() } })
        assertTrue(logs.single(), logs.single().startsWith("gear failed: TimeoutCancellationException"))
    }

    @Test
    fun cancellingTheCallerIsPassedOnAndNotLoggedAsAFailure() = runTest {
        var carriedOn = false
        val reading = launch {
            failures.attempt<Boolean>("ROW_1_RIGHT") { awaitCancellation() }
            carriedOn = true
        }
        runCurrent()
        reading.cancel()
        runCurrent()
        assertFalse("the caller stops at the cancelled read", carriedOn)
        assertTrue(logs.isEmpty())
        assertNull(failures.current("ROW_1_RIGHT"))
    }
}
