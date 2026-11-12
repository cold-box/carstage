package com.carstage.app.vehicle

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripReaderTest {
    /** A car with both parts that answers 95 minutes and 123.4 km, until a test says otherwise. */
    private class FakeCar : TripReader.Calls {
        var hasTime: () -> Boolean = { true }
        var hasDistance: () -> Boolean = { true }
        var driven: () -> Int? = { 95 }
        var covered: () -> Float? = { 123.4f }
        var checks = 0

        override suspend fun timeSupported() = hasTime().also { checks++ }
        override suspend fun distanceSupported() = hasDistance().also { checks++ }
        override suspend fun minutes() = driven()
        override suspend fun kilometers() = covered()
    }

    private val car = FakeCar()
    private val logged = mutableListOf<String>()
    private val reader = TripReader(car, log = { logged += it })

    @Test
    fun bothPartsAreReadAndTheirSupportIsCheckedOnce() = runTest {
        assertEquals(Trip(95, 123.4f), reader.read())
        assertEquals(Trip(95, 123.4f), reader.read())
        assertEquals("trip: driving time supported, distance supported", logged.single())
        assertEquals("both checks once, then remembered", 2, car.checks)
    }

    @Test
    fun aCarSupportingNeitherPartHasNoTripAndIsNotAskedAgain() = runTest {
        // As the emulator v2.1.0 answers every trip read (2026-11-01).
        car.hasTime = { false }
        car.hasDistance = { false }
        assertNull(reader.read())
        assertNull(reader.read())
        assertEquals("both checks once, then remembered", 2, car.checks)
    }

    @Test
    fun aCheckThatFailsIsAskedAgainNotTakenAsUnsupported() = runTest {
        car.hasDistance = { throw VehicleCallTimeoutException(VEHICLE_CALL_TIMEOUT_MS) }
        assertEquals("unknown, not a car without a trip", Trip(null, null), reader.read())
        car.hasDistance = { true }
        assertEquals(Trip(95, 123.4f), reader.read())
    }

    @Test
    fun onlyTheSupportedPartIsRead() = runTest {
        car.hasDistance = { false }
        car.covered = { error("a part the car does not support is not read") }
        assertEquals(Trip(95, null), reader.read())
    }

    @Test
    fun aPartThatCannotBeReadIsUnknownAndTheOtherIsKept() = runTest {
        car.driven = { throw IllegalStateException("service error") }
        assertEquals(Trip(null, 123.4f), reader.read())
        car.driven = { 96 }
        assertEquals(Trip(96, 123.4f), reader.read())
    }

    @Test
    fun aTripIsBelievedAtADrivingPace() {
        assertEquals("78 km/h", 95, Trip(95, 123.4f).believableMinutes())
        assertEquals("a jam's crawl", 60, Trip(60, 4f).believableMinutes())
        assertEquals("a motorway", 60, Trip(60, 180f).believableMinutes())
    }

    @Test
    fun aTripIsNotBelievedWithoutBothPartsOrAtAnUnlikelyPace() {
        assertNull("no distance to check the time by", Trip(95, null).believableMinutes())
        assertNull(Trip(null, 123.4f).believableMinutes())
        assertNull("no time driven", Trip(0, 0f).believableMinutes())
        // 30 minutes and 25 km if the car counts seconds: 1800 "minutes" make under 1 km/h.
        assertNull("seconds taken for minutes", Trip(1_800, 25f).believableMinutes())
        assertNull("standing still", Trip(45, 0f).believableMinutes())
        assertNull("faster than a car goes", Trip(10, 50f).believableMinutes())
        // Two hours at 120 km/h counted in seconds: 7200 "minutes" make 2 km/h, a crawl.
        assertNull("seconds taken for minutes on a fast trip", Trip(7_200, 240f).believableMinutes())
        // Ten minutes at 180 km/h in seconds: 600 "minutes" and 30 km make exactly 3 km/h.
        assertNull("seconds at the slowest edge", Trip(600, 30f).believableMinutes())
        assertNull("longer than half a day", Trip(Trip.MAX_MINUTES + 1, 900f).believableMinutes())
        assertEquals("half a day", Trip.MAX_MINUTES, Trip(Trip.MAX_MINUTES, 900f).believableMinutes())
    }
}
