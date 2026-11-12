package com.carstage.app

import com.carstage.app.vehicle.Trip
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripWatchTest {
    /** Whether a stage is opening or open. */
    private val active = MutableStateFlow(false)
    private var trip: suspend () -> Trip? = { Trip(95, 123.4f) }
    private var reads = 0
    private val logged = mutableListOf<String>()

    private fun TestScope.watch() = TripWatch(backgroundScope, active, read = { reads++; trip() }, log = { logged += it }).also { runCurrent() }

    private fun TestScope.stage(open: Boolean) {
        active.value = open
        runCurrent()
    }

    @Test
    fun theTripIsReadOnceForEachStage() = runTest {
        val watch = watch()
        assertEquals("not read before a stage opens", 0, reads)
        assertNull(watch.minutes)
        stage(open = true)
        assertEquals(95, watch.minutes)
        assertEquals("trip: 95 min, 123.4 km", logged.single())
        stage(open = false)
        assertNull("a closed stage drops the trip", watch.minutes)
        trip = { Trip(140, 190f) }
        stage(open = true)
        assertEquals(2, reads)
        assertEquals(140, watch.minutes)
    }

    @Test
    fun aTripNotBelievedOrNotReadIsNotKept() = runTest {
        val watch = watch()
        stage(open = true)
        stage(open = false)
        trip = { Trip(1_800, 25f) }
        stage(open = true)
        assertNull(watch.minutes)
        assertEquals("trip: 1800 min, 25.0 km, not believed", logged.last())
        stage(open = false)
        trip = { null }
        stage(open = true)
        assertEquals("trip: the car reports none", logged.last())
        stage(open = false)
        trip = { Trip(null, null) }
        stage(open = true)
        assertEquals("trip: not read", logged.last())
    }

    @Test
    fun aStageThatClosesWhileItsTripIsReadCancelsTheRead() = runTest {
        val watch = watch()
        val answer = CompletableDeferred<Trip?>()
        trip = { answer.await() }
        stage(open = true)
        assertNull("still reading", watch.minutes)
        assertTrue(logged.isEmpty())
        stage(open = false)
        trip = { Trip(40, 30f) }
        stage(open = true)
        answer.complete(Trip(95, 123.4f))
        runCurrent()
        assertEquals("the new stage's trip, not the cancelled read's", 40, watch.minutes)
        assertEquals("trip: 40 min, 30.0 km", logged.single())
    }

    @Test
    fun aCloseAndReopenTheWatchNeverSawKeepsTheLastReadUntilTheNextClose() = runTest {
        // A known limit: closing a stage takes the car's restore calls, so the watch sees it in practice.
        val watch = watch()
        stage(open = true)
        assertEquals(95, watch.minutes)
        trip = { Trip(40, 30f) }
        active.value = false
        active.value = true
        runCurrent()
        assertEquals("the watch saw no new stage, so it keeps the last read rather than reading 40", 95, watch.minutes)
        assertEquals(1, reads)
        stage(open = false)
        assertNull(watch.minutes)
    }

    @Test
    fun aGreetingBeforeTheReadFinishedIsLogged() = runTest {
        val watch = watch()
        val answer = CompletableDeferred<Trip?>()
        trip = { answer.await() }
        stage(open = true)
        assertNull(watch.minutesNow())
        assertEquals("trip: greeted before the read finished", logged.single())
        answer.complete(Trip(95, 123.4f))
        runCurrent()
        assertEquals(95, watch.minutesNow())
        assertEquals("nothing more once read", 2, logged.size)
    }

    @Test
    fun aReadThatFailsIsLoggedAndTheNextStageReadsAgain() = runTest {
        val watch = watch()
        trip = { throw IllegalStateException("service error") }
        stage(open = true)
        assertNull(watch.minutes)
        assertEquals("trip: reading failed: IllegalStateException: service error", logged.single())
        stage(open = false)
        trip = { Trip(95, 123.4f) }
        stage(open = true)
        assertEquals(95, watch.minutes)
    }

    @Test
    fun aTestSetsTheTripWhateverTheCarSays() = runTest {
        trip = { Trip(40, 30f) }
        val watch = watch()
        watch.force(95)
        stage(open = true)
        assertEquals("the car's read does not undo it", 95, watch.minutes)
        assertEquals("trip: 40 min, 30.0 km, forced to 95 min by a test", logged.single())
        watch.force(null)
        assertEquals("follows the car again", 40, watch.minutes)
    }
}
