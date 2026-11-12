package com.carstage.app.stage

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ParkedRestoreTest {
    private var parked: Boolean? = false
    private val parkedReads = mutableListOf<Boolean?>()
    private var results = ArrayDeque<StageRecovery.Result?>()
    private var recoveries = 0
    private var gaveUp = 0
    private val logs = mutableListOf<String>()
    private lateinit var restore: ParkedRestore

    private fun TestScope.start(onRecover: () -> Unit = {}): ParkedRestore {
        restore = ParkedRestore(
            scope = backgroundScope,
            parked = { parked.also { parkedReads += it } },
            recover = {
                recoveries++
                onRecover()
                results.removeFirstOrNull() ?: StageRecovery.Result.Restored(emptySet(), emptyList())
            },
            log = { logs += it },
            onGaveUp = { gaveUp++ },
        )
        restore.request()
        return restore
    }

    @Test
    fun waitsWhileDrivingWithoutTouchingTheCarThenRestoresOnceParked() = runTest {
        start()
        advanceTimeBy(10 * 60_000L)
        assertEquals(0, recoveries)
        parked = true
        advanceTimeBy(ParkedRestore.POLL_MS + 1)
        assertEquals(1, recoveries)
        val reads = parkedReads.size
        advanceTimeBy(10 * ParkedRestore.POLL_MS)
        assertEquals("the wait ended after the run", reads, parkedReads.size)
    }

    @Test
    fun anUnknownGearKeepsWaiting() = runTest {
        parked = null
        start()
        advanceTimeBy(60_000L)
        assertEquals(0, recoveries)
        assertTrue(parkedReads.size >= 29)
    }

    @Test
    fun anUnreachableCarIsTriedThreeTimesWhileParkedThenTheUserIsTold() = runTest {
        parked = true
        results = ArrayDeque(List(5) { StageRecovery.Result.Unreachable })
        start()
        advanceTimeBy(10 * ParkedRestore.POLL_MS)
        assertEquals(ParkedRestore.ATTEMPTS, recoveries)
        assertEquals(1, gaveUp)
    }

    @Test
    fun drivingTicksDoNotCountAsAttempts() = runTest {
        results = ArrayDeque(List(5) { StageRecovery.Result.Unreachable })
        val gears = ArrayDeque(listOf(true, false, false, true, false, true, true))
        restore = ParkedRestore(
            scope = backgroundScope,
            parked = { gears.removeFirstOrNull() ?: false },
            recover = { recoveries++; results.removeFirst() },
            log = { logs += it },
            onGaveUp = { gaveUp++ },
        )
        restore.request()
        advanceTimeBy(7 * ParkedRestore.POLL_MS + 1)
        assertEquals(3, recoveries)
        assertEquals(1, gaveUp)
    }

    @Test
    fun anOpenStageEndsTheWait() = runTest {
        parked = true
        results = ArrayDeque(listOf(null))
        start()
        advanceTimeBy(10 * ParkedRestore.POLL_MS)
        assertEquals(1, recoveries)
        assertEquals(0, gaveUp)
    }

    @Test
    fun aRequestWhileWaitingStartsNoSecondWait() = runTest {
        start(onRecover = { restore.request() })
        runCurrent()
        restore.request()
        advanceTimeBy(5 * ParkedRestore.POLL_MS + 1)
        assertEquals("one read per poll", 5, parkedReads.size)
        parked = true
        advanceTimeBy(10 * ParkedRestore.POLL_MS)
        assertEquals(1, recoveries)
    }
}
