package com.carstage.app.stage

import ai.pleos.playground.vehicle.constant.area.SeatValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The car output's value mapping; debug-only like [VehicleCabin], which release builds leave out while they do not drive the cabin. */
class VehicleCabinValuesTest {
    @Test
    fun onlySingleFrontSeatAreasMapToSides() {
        assertEquals(Side.DRIVER, VehicleCabin.sideOf(listOf(SeatValue.ROW_1_LEFT)))
        assertEquals(Side.PASSENGER, VehicleCabin.sideOf(listOf(SeatValue.ROW_1_RIGHT)))
        assertNull(VehicleCabin.sideOf(listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT)))
        assertNull(VehicleCabin.sideOf(listOf(SeatValue.ROW_2_LEFT)))
    }

    @Test
    fun aTemperatureOffsetIsFromTheStagesStartWithinTheCarsRange() {
        assertEquals(20f, VehicleCabin.temperatureFor(22f, -2f, 15f, 30f), 0f)
        assertEquals("never below what the car allows", 15f, VehicleCabin.temperatureFor(16f, -2f, 15f, 30f), 0f)
        assertEquals("0 is the start", 22.5f, VehicleCabin.temperatureFor(22.5f, 0f, 15f, 30f), 0f)
        assertEquals("0 is the start even outside the range the car reports", 31f, VehicleCabin.temperatureFor(31f, 0f, 15f, 30f), 0f)
        assertEquals("cooler never ends up warmer", 14f, VehicleCabin.temperatureFor(14f, -2f, 15f, 30f), 0f)
        assertEquals("warmer never ends up cooler", 31f, VehicleCabin.temperatureFor(31f, 2f, 15f, 30f), 0f)
    }

    @Test
    fun aFanStepIsFromTheStagesStartWithinTheCarsRange() {
        assertEquals(4, VehicleCabin.fanFor(3, 1, 1, 8))
        assertEquals("never above the top step", 8, VehicleCabin.fanFor(8, 1, 1, 8))
        assertEquals("0 is the start as the car had it", 0, VehicleCabin.fanFor(0, 0, 1, 8))
        assertEquals("up never ends up lower", 9, VehicleCabin.fanFor(9, 1, 1, 8))
    }

    @Test
    fun percentMapsOntoTheCarsRange() {
        assertEquals(1, VehicleCabin.fromPercent(0, 1, 10))
        assertEquals(10, VehicleCabin.fromPercent(100, 1, 10))
        assertEquals(6, VehicleCabin.fromPercent(55, 1, 10))
        assertEquals(128, VehicleCabin.fromPercent(50, 0, 255))
        assertEquals(0, VehicleCabin.toPercent(1, 1, 10))
        assertEquals(100, VehicleCabin.toPercent(10, 1, 10))
        assertEquals(70, VehicleCabin.toPercent(70, 0, 100))
        assertEquals(100, VehicleCabin.toPercent(3, 3, 3))
    }
}
