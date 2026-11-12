package com.carstage.app.audio.echo

import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CabinEchoCancellerTest {
    private val rate = 48_000

    private class Scene(val mic: FloatArray, val reference: FloatArray, val voice: FloatArray)

    /**
     * 20 s of a song through the cabin at -20 dBFS, a passenger singing as loud as the echo from
     * 8 to 14 s, and noise 40 dB under the echo.
     */
    private fun scene(delayMs: Double, seconds: Double = 20.0, voiceFrom: Double = 8.0, voiceTo: Double = 14.0, changeAt: Double? = null, changedMs: Double = 0.0): Scene {
        val music = EchoScene.music(rate, seconds)
        val echo = EchoScene.convolve(music, EchoScene.cabin(rate, delayMs))
        if (changeAt != null) {
            val later = EchoScene.convolve(music, EchoScene.cabin(rate, changedMs))
            for (i in (changeAt * rate).toInt() until echo.size) echo[i] = later[i]
        }
        val echoRms = sqrt(echo.sumOf { it * it } / echo.size)
        val voice = EchoScene.voice(rate, seconds, seed = 42)
        val voiceInMic = FloatArray(music.size) { if (it >= voiceFrom * rate && it < voiceTo * rate) voice[it] else 0f }
        val noise = EchoScene.noise(music.size, 0.001)
        val mic = FloatArray(music.size) { (echo[it] * 0.1 / echoRms + voiceInMic[it] + noise[it]).toFloat() }
        return Scene(mic, music, voiceInMic)
    }

    private fun cancel(scene: Scene, block: Int = 2_048): FloatArray {
        val canceller = CabinEchoCanceller(rate)
        return EchoScene.run(canceller, scene.mic, scene.reference, block, canceller.latency)
    }

    @Test
    fun withoutReferenceTheMicrophonePassesThroughLate() {
        val canceller = CabinEchoCanceller(rate)
        val mic = EchoScene.voice(rate, 1.0, seed = 1)
        val out = FloatArray(mic.size)
        canceller.process(mic, FloatArray(mic.size), out)
        for (i in canceller.latency until mic.size) assertEquals(mic[i - canceller.latency], out[i], 0f)
        for (i in 0 until canceller.latency) assertEquals(0f, out[i], 0f)
    }

    @Test
    fun notANumberAndSilenceStaySafe() {
        val canceller = CabinEchoCanceller(rate)
        val music = EchoScene.music(rate, 2.0)
        val mic = FloatArray(music.size) { if (it % 997 == 0) Float.NaN else music[it] * 0.5f }
        val reference = music.copyOf().also { it[1_000] = Float.POSITIVE_INFINITY; it.fill(0f, 40_000, 60_000) }
        val out = FloatArray(mic.size)
        canceller.process(mic, reference, out)
        canceller.process(FloatArray(mic.size), FloatArray(mic.size), out)
        assertTrue(out.all { it.isFinite() && it in -1f..1f })
        assertTrue("silence in, silence out", out.drop(canceller.latency * 2).all { it == 0f })
    }

    @Test
    fun theOutputIsNeverLouderThanTheMicrophone() {
        // A reference that has nothing to do with the microphone: whatever the filter does, the
        // output must not grow.
        val canceller = CabinEchoCanceller(rate)
        val mic = EchoScene.voice(rate, 6.0, seed = 5)
        val reference = EchoScene.music(rate, 6.0)
        val out = EchoScene.run(canceller, mic, reference, 2_048, canceller.latency)
        val block = canceller.blockSize
        var start = 0
        while (start + block <= mic.size) {
            assertTrue(EchoScene.energy(out, start, start + block) <= EchoScene.energy(mic, start, start + block) * 1.0001 + 1e-12)
            start += block
        }
    }

    @Test
    fun theBlockLengthDoesNotChangeTheOutput() {
        val scene = scene(120.0, seconds = 4.0)
        assertArrayEquals(cancel(scene, 2_048), cancel(scene, 333), 0f)
    }

    @Test
    fun takesTheSongOutAndKeepsTheVoice() {
        for (delayMs in listOf(50.0, 150.0, 300.0)) {
            val scene = scene(delayMs)
            val out = cancel(scene)
            val converged = EchoScene.convergence(scene.mic, out, rate, 0, 8 * rate, TARGET_ERLE_DB)
            val erleBefore = EchoScene.erle(scene.mic, out, 4 * rate, 8 * rate)
            val erleAfter = EchoScene.erle(scene.mic, out, 15 * rate, 20 * rate)
            val (voiceGain, voiceOverRest) = EchoScene.voiceKept(scene.voice, out, 9 * rate, 14 * rate)
            val (_, voiceOverEchoIn) = EchoScene.voiceKept(scene.voice, scene.mic, 9 * rate, 14 * rate)
            println(
                "echo synthetic delay=${delayMs.toInt()}ms converged=${converged?.let { "%.2f".format(it) }}s " +
                    "erle=%.1f/%.1f dB voice gain=%.2f dB voice/rest=%.1f dB (in %.1f dB)".format(erleBefore, erleAfter, voiceGain, voiceOverRest, voiceOverEchoIn),
            )
            assertNotNull("converged at $delayMs ms", converged)
            assertTrue("converged in ${converged}s at $delayMs ms", converged!! <= 3.0)
            assertTrue("ERLE $erleBefore dB at $delayMs ms", erleBefore >= TARGET_ERLE_DB)
            assertTrue("ERLE after the voice $erleAfter dB at $delayMs ms", erleAfter >= TARGET_ERLE_DB)
            assertTrue("voice lost ${-voiceGain} dB at $delayMs ms", voiceGain >= -3.0)
        }
    }

    @Test
    fun followsADelayThatMoves() {
        val scene = scene(120.0, changeAt = 10.0, changedMs = 160.0, voiceFrom = 20.0, voiceTo = 20.0)
        val canceller = CabinEchoCanceller(rate)
        val out = EchoScene.run(canceller, scene.mic, scene.reference, 2_048, canceller.latency)
        val converged = EchoScene.convergence(scene.mic, out, rate, 10 * rate, 20 * rate, TARGET_ERLE_DB)
        println("echo delay 120 -> 160 ms: reconverged in ${converged}s, realignments=${canceller.realignments}")
        assertNotNull(converged)
        assertTrue("reconverged in ${converged}s", converged!! <= 3.0)
    }

    @Test
    fun costsLittleOfOneCore() {
        val scene = scene(150.0, seconds = 10.0)
        cancel(scene) // warm up the JIT
        val started = System.nanoTime()
        cancel(scene)
        val share = (System.nanoTime() - started) / 1e9 / 10.0
        println("echo cpu: %.1f%% of one core (JVM)".format(share * 100))
        assertTrue("uses ${share * 100}% of one core", share < 0.25)
    }

    private companion object {
        const val TARGET_ERLE_DB = 20.0
    }
}
