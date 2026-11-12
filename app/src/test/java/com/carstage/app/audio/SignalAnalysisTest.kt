package com.carstage.app.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalAnalysisTest {
    private val sampleRate = 48_000

    private fun sine(hz: Double, size: Int = 2_048, amplitude: Double = 0.5) =
        FloatArray(size) { (amplitude * sin(2 * PI * hz * it / sampleRate)).toFloat() }

    @Test
    fun rmsOfFullScaleSineIsMinusThreeDb() {
        assertEquals(-3.0f, SignalAnalysis.rmsDbfs(sine(440.0, amplitude = 1.0)), 0.1f)
    }

    @Test
    fun rmsOfSilenceIsFloor() {
        assertEquals(SignalAnalysis.SILENCE_DB, SignalAnalysis.rmsDbfs(FloatArray(1_024)))
    }

    @Test
    fun yinFindsSungPitches() {
        val detector = YinPitchDetector(sampleRate)
        for (hz in listOf(110.0, 220.0, 440.0, 660.0)) {
            val detected = detector.detect(sine(hz))
            assertNotNull("no pitch for $hz Hz", detected)
            assertTrue("detected $detected for $hz Hz", abs(detected!! - hz) / hz < 0.01)
        }
    }

    @Test
    fun yinReturnsNullForSilence() {
        assertNull(YinPitchDetector(sampleRate).detect(FloatArray(2_048)))
    }

    @Test
    fun noteNamesFollowScientificPitch() {
        assertEquals("A4", SignalAnalysis.noteName(440f))
        assertEquals("C4", SignalAnalysis.noteName(261.63f))
        assertEquals("A2", SignalAnalysis.noteName(110f))
    }

    @Test
    fun onsetDetectorCountsSeparatedClaps() {
        val detector = OnsetDetector(sampleRate)
        val quiet = FloatArray(sampleRate / 2)
        val clap = FloatArray(1_024) { if (it % 2 == 0) 0.8f else -0.8f }
        val signal = quiet + clap + quiet + clap + quiet
        val onsets = signal.toList().chunked(2_048).sumOf { detector.process(it.toFloatArray()) }
        assertEquals(2, onsets)
    }
}
