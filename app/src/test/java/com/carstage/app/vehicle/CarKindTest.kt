package com.carstage.app.vehicle

import android.car.FuelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarKindTest {
    @Test
    fun theFuelTypesSayWhatDrivesTheCar() {
        assertEquals(CarKind.ELECTRIC, CarKind.of(listOf(FuelType.ELECTRIC)))
        assertEquals(CarKind.PLUG_IN_HYBRID, CarKind.of(listOf(FuelType.UNLEADED, FuelType.ELECTRIC)))
        assertEquals("a Grandeur or an Avante", CarKind.COMBUSTION, CarKind.of(listOf(FuelType.UNLEADED)))
        assertEquals(CarKind.COMBUSTION, CarKind.of(listOf(FuelType.DIESEL_1)))
        assertEquals(CarKind.COMBUSTION, CarKind.of(listOf(FuelType.LPG)))
    }

    @Test
    fun fuelTypesThatSayNeitherAreUnknown() {
        assertNull(CarKind.of(emptyList()))
        assertNull(CarKind.of(listOf(FuelType.UNKNOWN)))
        assertNull("a fuel cell car: no engine, but its battery is for the SDK to say", CarKind.of(listOf(FuelType.HYDROGEN)))
        assertNull(CarKind.of(listOf(FuelType.OTHER)))
    }

    @Test
    fun onlyACarWithAnEngineIsWarnedAboutIdlingAndOnlyACarWithABatteryIsAskedForIt() {
        assertFalse(CarKind.ELECTRIC.mayIdle)
        assertTrue(CarKind.PLUG_IN_HYBRID.mayIdle)
        assertTrue(CarKind.COMBUSTION.mayIdle)
        assertTrue(CarKind.ELECTRIC.hasEvBattery)
        assertTrue(CarKind.PLUG_IN_HYBRID.hasEvBattery)
        assertFalse(CarKind.COMBUSTION.hasEvBattery)
    }

    @Test
    fun theKindDecidesIdlingAndTheBatteryChecksOnlyWhenItIsUnknown() {
        assertTrue("a plug-in hybrid reports a battery but may idle", CarKind.mayIdle(CarKind.PLUG_IN_HYBRID, evBattery = true))
        assertFalse("an EV whose battery checks fail is still an EV", CarKind.mayIdle(CarKind.ELECTRIC, evBattery = null))
        assertTrue(CarKind.mayIdle(null, evBattery = false))
        assertTrue("checks that keep failing may be a car without a battery", CarKind.mayIdle(null, evBattery = null))
        assertFalse(CarKind.mayIdle(null, evBattery = true))
    }

    @Test
    fun aCarKnownToRunOnFuelOnlyIsNotAskedForAnEvBattery() {
        assertFalse(CarKind.readsEvBattery(CarKind.COMBUSTION))
        assertTrue(CarKind.readsEvBattery(CarKind.ELECTRIC))
        assertTrue(CarKind.readsEvBattery(CarKind.PLUG_IN_HYBRID))
        assertTrue("unknown: the SDK's checks decide", CarKind.readsEvBattery(null))
    }

    @Test
    fun anAnswerIsFinalAndAThrowIsTriedAgain() {
        assertEquals(CarKindReader.Attempt.Answered(listOf(FuelType.UNLEADED), CarKind.COMBUSTION), CarKindReader.attempt { listOf(FuelType.UNLEADED) })
        assertEquals("an answer that says neither is still an answer", CarKindReader.Attempt.Answered(emptyList(), null), CarKindReader.attempt { emptyList() })
        val failed = CarKindReader.attempt { throw IllegalArgumentException("property not supported") }
        assertEquals(CarKindReader.Attempt.Failed("reading the fuel type threw IllegalArgumentException: property not supported"), failed)
    }
}
