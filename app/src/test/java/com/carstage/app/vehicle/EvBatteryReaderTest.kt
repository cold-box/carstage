package com.carstage.app.vehicle

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EvBatteryReaderTest {
    /** A car with both parts that answers 42 % and charging, until a test says otherwise. */
    private class FakeCar : EvBatteryReader.Calls {
        var percentSupported: () -> Boolean = { true }
        var chargingSupported: () -> Boolean = { true }
        var percent: () -> Int? = { 42 }
        var charging: () -> Boolean? = { true }
        var checks = 0

        override suspend fun stateOfChargeSupported() = percentSupported().also { checks++ }
        override suspend fun chargeSwitchSupported() = chargingSupported().also { checks++ }
        override suspend fun stateOfCharge() = percent()
        override suspend fun chargeSwitch() = charging()
    }

    private val car = FakeCar()
    private val reader = EvBatteryReader(car, log = {})

    @Test
    fun aCheckThatFailsIsAskedAgainNotTakenAsNoBattery() = runTest {
        car.chargingSupported = { throw VehicleCallTimeoutException(VEHICLE_CALL_TIMEOUT_MS) }
        assertEquals("unknown, not a car without an EV battery", BatteryState(null, null), reader.read())
        car.chargingSupported = { true }
        assertEquals(BatteryState(42, true), reader.read())
    }

    @Test
    fun aCarReportingNeitherPartHasNoBatteryAndIsNotAskedAgain() = runTest {
        car.percentSupported = { false }
        car.chargingSupported = { false }
        assertNull(reader.read())
        assertNull(reader.read())
        assertEquals("both checks once, then remembered", 2, car.checks)
    }

    @Test
    fun onlyTheReportedPartIsRead() = runTest {
        car.percentSupported = { false }
        car.percent = { error("a part the car does not report is not read") }
        assertEquals(BatteryState(null, true), reader.read())
    }

    @Test
    fun aPartThatCannotBeReadIsUnknownAndTheOtherIsKept() = runTest {
        car.charging = { throw IllegalStateException("service error") }
        assertEquals(BatteryState(42, null), reader.read())
        car.charging = { true }
        assertEquals(BatteryState(42, true), reader.read())
    }

    @Test
    fun theChargeIsAPercentage() = runTest {
        car.percent = { 104 }
        assertEquals(100, reader.read()?.percent)
        car.percent = { -1 }
        assertEquals(0, reader.read()?.percent)
    }

    @Test
    fun whetherTheCarHasABatteryIsKnownOnceBothChecksAnswered() = runTest {
        assertNull(reader.hasBattery)
        car.chargingSupported = { throw IllegalStateException("service error") }
        reader.read()
        assertNull("one check failed", reader.hasBattery)
        car.chargingSupported = { false }
        reader.read()
        assertEquals("the state of charge alone is a battery", true, reader.hasBattery)
    }

    @Test
    fun aCarReportingNeitherPartHasNoBattery() = runTest {
        car.percentSupported = { false }
        car.chargingSupported = { false }
        reader.read()
        assertEquals(false, reader.hasBattery)
    }

    @Test
    fun theChargeSwitchAloneIsABattery() = runTest {
        car.percentSupported = { false }
        reader.read()
        assertEquals(true, reader.hasBattery)
    }
}
