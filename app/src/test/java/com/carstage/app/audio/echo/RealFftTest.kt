package com.carstage.app.audio.echo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class RealFftTest {
    @Test
    fun matchesTheDefinition() {
        val fft = RealFft(64)
        val random = Random(1)
        val signal = DoubleArray(64) { random.nextDouble(-1.0, 1.0) }
        val re = DoubleArray(fft.bins)
        val im = DoubleArray(fft.bins)
        fft.forward(signal, re, im)
        for (k in 0 until fft.bins) {
            var r = 0.0
            var i = 0.0
            for (t in 0 until 64) {
                r += signal[t] * cos(2 * PI * k * t / 64)
                i -= signal[t] * sin(2 * PI * k * t / 64)
            }
            assertEquals("re of bin $k", r, re[k], 1e-9)
            assertEquals("im of bin $k", i, im[k], 1e-9)
        }
    }

    @Test
    fun inverseGivesTheSignalBack() {
        val fft = RealFft(1_024)
        val random = Random(2)
        val signal = DoubleArray(1_024) { random.nextDouble(-1.0, 1.0) }
        val re = DoubleArray(fft.bins)
        val im = DoubleArray(fft.bins)
        val back = DoubleArray(1_024)
        fft.forward(signal, re, im)
        fft.inverse(re, im, back)
        for (t in signal.indices) assertEquals(signal[t], back[t], 1e-12)
    }
}
