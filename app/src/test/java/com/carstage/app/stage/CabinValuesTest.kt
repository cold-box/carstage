package com.carstage.app.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CabinValuesTest {
    @Test
    fun percentRejectsOutOfRangeButFactoriesClamp() {
        assertThrows(IllegalArgumentException::class.java) { Percent(101) }
        assertEquals(Percent(100), Percent.coerced(140))
        assertEquals(Percent(100), Percent.ofFraction(1.004f))
        assertEquals(Percent(0), Percent.ofFraction(-0.2f))
        assertEquals(Percent(43), Percent.ofFraction(0.426f))
    }

    @Test
    fun rgbConvertsToAndFromArgb() {
        assertEquals(Rgb(0x2979FF), Rgb.fromArgb(0xFF2979FF.toInt()))
        assertEquals(0xFF2979FF.toInt(), Rgb(0x2979FF).toArgb())
        assertThrows(IllegalArgumentException::class.java) { Rgb(0xFF2979FF.toInt()) }
    }

    @Test
    fun aTemperatureOffsetMustBeANumber() {
        assertThrows(IllegalArgumentException::class.java) { CabinCommand.Temperature(Side.DRIVER, Float.NaN) }
    }
}
