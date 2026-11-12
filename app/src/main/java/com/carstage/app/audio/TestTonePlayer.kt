package com.carstage.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Plays a looping C-major arpeggio as media audio, standing in for a music app during echo tests. */
class TestTonePlayer(private val sampleRate: Int = 48_000) {
    private var track: AudioTrack? = null
    private var worker: Thread? = null

    @Volatile
    private var playing = false

    fun start() {
        check(track == null) { "player already started" }
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack.play()
        track = audioTrack
        playing = true
        worker = thread(name = "test-tone") {
            val noteSamples = sampleRate / 4
            val buffer = ShortArray(noteSamples)
            val fade = sampleRate / 100.0
            var note = 0
            while (playing) {
                val hz = ARPEGGIO_HZ[note % ARPEGGIO_HZ.size]
                for (i in 0 until noteSamples) {
                    val t = i.toDouble() / sampleRate
                    val envelope = min(1.0, i / fade) * min(1.0, (noteSamples - i) / fade)
                    val value = (0.6 * sin(2 * PI * hz * t) + 0.25 * sin(4 * PI * hz * t)) * envelope * 0.5
                    buffer[i] = (value * Short.MAX_VALUE).toInt().toShort()
                }
                audioTrack.write(buffer, 0, buffer.size)
                note++
            }
        }
    }

    fun stop() {
        playing = false
        worker?.join(1_000)
        worker = null
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
    }

    private companion object {
        val ARPEGGIO_HZ = doubleArrayOf(261.63, 329.63, 392.00, 523.25)
    }
}
