package com.carstage.app.audio.echo

import kotlin.math.sqrt
import org.junit.Test

class DebugTraceTest {
    @Test
    fun trace() {
        val rate = 48_000
        for (delayMs in listOf(150.0, 300.0)) {
            val music = EchoScene.music(rate, 8.0)
            val echo = EchoScene.convolve(music, EchoScene.cabin(rate, delayMs))
            val rms = sqrt(echo.sumOf { it * it } / echo.size)
            val noise = EchoScene.noise(music.size, 0.001)
            val mic = FloatArray(music.size) { (echo[it] * 0.1 / rms + noise[it]).toFloat() }
            val c = CabinEchoCanceller(rate)
            val out = FloatArray(mic.size)
            val step = 12_000
            val o = FloatArray(step)
            val line = StringBuilder("delay $delayMs: ")
            var s = 0
            while (s + step <= mic.size) {
                c.process(mic.copyOfRange(s, s + step), music.copyOfRange(s, s + step), o)
                o.copyInto(out, s)
                val lat = c.latency
                val from = maxOf(s - lat, 0)
                line.append("[%.2f a=%d c=%.1f erle=%.1f] ".format((s + step) / 48000.0, c.alignment, c.delayConfidence,
                    EchoScene.db(EchoScene.energy(mic, from, s + step - lat) / (EchoScene.energy(out, from + lat, s + step) + 1e-20))))
                s += step
            }
            println(line)
        }
    }
}
