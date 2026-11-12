package com.carstage.app.audio.echo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DelayEstimatorTest {
    private val rate = 48_000

    private fun run(delayMs: Int, voice: Boolean): DelayEstimator {
        val music = EchoScene.music(rate, 4.0)
        val singer = if (voice) EchoScene.voice(rate, 4.0, seed = 9) else FloatArray(music.size)
        val estimator = DelayEstimator(rate, EchoCanceller.MAX_DELAY_MS)
        val delay = delayMs * rate / 1_000
        for (i in music.indices) {
            val echo = if (i >= delay) 0.3 * music[i - delay] else 0.0
            estimator.push(echo + singer[i], music[i].toDouble())
        }
        return estimator
    }

    @Test
    fun findsTheDelayOfTheMusic() {
        for (ms in listOf(0, 50, 137, 300, 480)) {
            val estimator = run(ms, voice = false)
            assertEquals("delay $ms ms", ms * rate / 1_000.0, estimator.delaySamples.toDouble(), 12.0)
        }
    }

    @Test
    fun aVoiceAsLoudAsTheEchoDoesNotMoveIt() {
        val estimator = run(212, voice = true)
        assertEquals(212 * rate / 1_000.0, estimator.delaySamples.toDouble(), 12.0)
        assertTrue("confidence ${estimator.confidence}", estimator.confidence > 6)
    }

    @Test
    fun noReferenceNoDelay() {
        val estimator = DelayEstimator(rate, EchoCanceller.MAX_DELAY_MS)
        val voice = EchoScene.voice(rate, 3.0, seed = 3)
        for (v in voice) estimator.push(v.toDouble(), 0.0)
        assertEquals(-1, estimator.delaySamples)
    }
}
