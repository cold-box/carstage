package com.carstage.app.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumTest {
    private val spectrum = Spectrum(2_048)
    private val out = FloatArray(spectrum.bins)

    private fun tone(bin: Double, amplitude: Double = 0.5) = FloatArray(2_048) { (amplitude * sin(2 * PI * bin * it / 2_048)).toFloat() }

    @Test
    fun aToneLandsInItsBin() {
        spectrum.magnitudes(tone(100.0), out)
        assertEquals(100, out.indices.maxBy { out[it] })
        assertTrue("far bins stay near zero", out[300] < out[100] * 1e-3f)
    }

    @Test
    fun binEnergyGivesBackTheFramesPower() {
        val frame = tone(37.0, 0.3)
        spectrum.magnitudes(frame, out)
        val binEnergy = out.sumOf { it.toDouble() * it }
        val power = 2 * binEnergy / (2_048 * spectrum.windowEnergy)
        val meanSquare = frame.sumOf { it.toDouble() * it } / 2_048
        assertEquals(meanSquare, power, meanSquare * 0.02)
    }

    @Test
    fun onlyPowersOfTwo() {
        assertThrows(IllegalArgumentException::class.java) { Spectrum(1_000) }
    }
}
