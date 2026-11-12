package com.carstage.app.lab

import com.carstage.app.audio.AudioInput
import com.carstage.app.audio.MicCapture
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Plays a 16-bit PCM WAV file (mono, or stereo mixed down) as if it came from the microphone, in
 * real time, so stage effects can be checked and tuned without singing in the car. Lab only.
 */
class WavReplay private constructor(
    private val name: String,
    private val samples: FloatArray,
    override val sampleRate: Int,
) : AudioInput {
    override val label get() = "replay $name"

    val durationMs: Long get() = samples.size * 1000L / sampleRate

    @Volatile
    private var running = false
    private var worker: Thread? = null

    override fun start(onFrame: (FloatArray) -> Unit, onFailure: (String, Throwable?) -> Unit) {
        check(worker == null) { "replay already started" }
        running = true
        worker = thread(name = "wav-replay") {
            val frameNanos = FRAME_SIZE * 1_000_000_000L / sampleRate
            val startNanos = System.nanoTime()
            var index = 0
            var offset = 0
            while (running && offset < samples.size) {
                val end = minOf(offset + FRAME_SIZE, samples.size)
                try {
                    onFrame(samples.copyOfRange(offset, end))
                } catch (e: Exception) {
                    onFailure("frame handling threw ${e::class.java.simpleName}: ${e.message}", e)
                    break
                }
                offset = end
                index++
                val wait = startNanos + index * frameNanos - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            }
        }
    }

    override fun stop() {
        running = false
        worker?.join(500)
        worker = null
    }

    companion object {
        private const val FRAME_SIZE = MicCapture.DEFAULT_FRAME_SIZE

        /** Reads [file]; throws [IllegalArgumentException] when it is not 16-bit PCM WAV. */
        fun open(file: File): WavReplay {
            val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.remaining() >= 12 && bytes.int == RIFF && bytes.int >= 0 && bytes.int == WAVE) { "${file.name} is not a WAV file" }
            var channels = 0
            var sampleRate = 0
            var bits = 0
            while (bytes.remaining() >= 8) {
                val id = bytes.int
                val size = bytes.int
                require(size >= 0 && size <= bytes.remaining()) { "${file.name}: broken chunk" }
                val chunk = bytes.slice().order(ByteOrder.LITTLE_ENDIAN).limit(size) as ByteBuffer
                bytes.position(bytes.position() + size + size % 2)
                when (id) {
                    FMT -> {
                        require(size >= 16) { "${file.name}: fmt chunk too short ($size bytes)" }
                        require(chunk.short.toInt() == PCM) { "${file.name}: only PCM is supported" }
                        channels = chunk.short.toInt()
                        sampleRate = chunk.int
                        chunk.position(14)
                        bits = chunk.short.toInt()
                    }
                    DATA -> {
                        require(bits == 16 && channels in 1..2 && sampleRate > 0) {
                            "${file.name}: need 16-bit mono or stereo, got $bits-bit, $channels channels"
                        }
                        val frames = size / (2 * channels)
                        val samples = FloatArray(frames) {
                            var sum = 0
                            repeat(channels) { sum += chunk.short }
                            sum / channels / 32_768f
                        }
                        return WavReplay(file.name, samples, sampleRate)
                    }
                }
            }
            throw IllegalArgumentException("${file.name}: no audio data")
        }

        private const val RIFF = 0x46464952
        private const val WAVE = 0x45564157
        private const val FMT = 0x20746d66
        private const val DATA = 0x61746164
        private const val PCM = 1
    }
}
