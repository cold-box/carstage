package com.carstage.app.lab

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WavReplayTest {
    private fun wav(channels: Int, bits: Int, samples: ShortArray, rate: Int = 48_000): File {
        val data = samples.size * 2
        val bytes = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        bytes.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        bytes.put("data".toByteArray()).putInt(data)
        samples.forEach { bytes.putShort(it) }
        return File.createTempFile("replay", ".wav").apply { writeBytes(bytes.array()); deleteOnExit() }
    }

    private fun framesOf(replay: WavReplay): List<FloatArray> {
        val frames = mutableListOf<FloatArray>()
        replay.start({ frames += it }, { why, _ -> error(why) })
        Thread.sleep(replay.durationMs + 200)
        replay.stop()
        return frames
    }

    @Test
    fun monoIsPlayedInFramesOfTheMicrophoneSize() {
        val replay = WavReplay.open(wav(1, 16, ShortArray(4_096 + 100) { 16_384 }, rate = 96_000))
        assertEquals(96_000, replay.sampleRate)
        assertEquals("replay ${replay.label.removePrefix("replay ")}", replay.label)
        val frames = framesOf(replay)
        assertEquals(listOf(2_048, 2_048, 100), frames.map { it.size })
        assertTrue(frames.all { frame -> frame.all { it == 0.5f } })
    }

    @Test
    fun stereoIsMixedToMono() {
        val replay = WavReplay.open(wav(2, 16, shortArrayOf(16_384, 0, -16_384, -16_384)))
        assertEquals(listOf(0.25f, -0.5f), framesOf(replay).single().toList())
    }

    @Test
    fun otherFormatsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { WavReplay.open(wav(1, 8, ShortArray(10))) }
        val notWav = File.createTempFile("replay", ".wav").apply { writeText("hello"); deleteOnExit() }
        assertThrows(IllegalArgumentException::class.java) { WavReplay.open(notWav) }
    }
}
