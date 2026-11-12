package com.carstage.app

import com.carstage.app.vehicle.BatteryState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BatteryWatchTest {
    private var battery: BatteryState? = BatteryState(percent = 42, charging = true)
    private var reads = 0
    private val logged = mutableListOf<String>()

    @Test
    fun theBatteryIsReadOnlyWhileSomeoneWatches() = runTest {
        val watch = BatteryWatch(backgroundScope, read = { reads++; battery }, log = { logged += it })
        advanceTimeBy(60_000)
        assertEquals("nobody watches", 0, reads)
        assertNull(watch.state.value)
        val screen = backgroundScope.launch { watch.state.collect {} }
        runCurrent()
        assertEquals(BatteryState(42, true), watch.state.value)
        advanceTimeBy(BatteryWatch.POLL_MS * 3)
        runCurrent()
        assertEquals(4, reads)
        screen.cancel()
        advanceTimeBy(BatteryWatch.STOP_AFTER_MS + BatteryWatch.POLL_MS)
        val whenStopped = reads
        advanceTimeBy(BatteryWatch.POLL_MS * 10)
        assertEquals("stopped a moment after the last watcher left", whenStopped, reads)
        assertNull("and forgotten, so an old value is not shown later", watch.state.value)
    }

    @Test
    fun aScreenTurningDoesNotRestartTheReads() = runTest {
        val watch = BatteryWatch(backgroundScope, read = { reads++; battery }, log = {})
        val before = backgroundScope.launch { watch.state.collect {} }
        runCurrent()
        before.cancel()
        advanceTimeBy(1_000)
        backgroundScope.launch { watch.state.collect {} }
        runCurrent()
        assertEquals("the same reads go on", 1, reads)
        assertEquals(BatteryState(42, true), watch.state.value)
    }

    @Test
    fun aReadThatThrowsIsUnknownAndTheWatchGoesOn() = runTest {
        var throwing = true
        val watch = BatteryWatch(backgroundScope, read = { if (throwing) error("service not bound") else battery }, log = { logged += it })
        backgroundScope.launch { watch.state.collect {} }
        runCurrent()
        assertEquals(BatteryState(null, null), watch.state.value)
        assertEquals(listOf("reading the ev battery failed: IllegalStateException: service not bound"), logged)
        throwing = false
        advanceTimeBy(BatteryWatch.POLL_MS)
        runCurrent()
        assertEquals(BatteryState(42, true), watch.state.value)
    }

    @Test
    fun chargingStartingAndStoppingIsLogged() = runTest {
        val watch = BatteryWatch(backgroundScope, read = { battery }, log = { logged += it })
        backgroundScope.launch { watch.state.collect {} }
        runCurrent()
        assertEquals("the first read is not a change", emptyList<String>(), logged)
        battery = BatteryState(percent = 80, charging = false)
        advanceTimeBy(BatteryWatch.POLL_MS)
        runCurrent()
        battery = BatteryState(percent = null, charging = null)
        advanceTimeBy(BatteryWatch.POLL_MS)
        runCurrent()
        battery = BatteryState(percent = 79, charging = true)
        advanceTimeBy(BatteryWatch.POLL_MS)
        runCurrent()
        assertEquals(listOf("charging stopped at 80%", "charging started at 79%"), logged)
    }
}
