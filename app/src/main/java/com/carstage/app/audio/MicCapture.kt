package com.carstage.app.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import kotlin.concurrent.thread

data class AudioSourceOption(val name: String, val id: Int)

object AudioSources {
    val all: List<AudioSourceOption> = buildList {
        add(AudioSourceOption("MIC", MediaRecorder.AudioSource.MIC))
        add(AudioSourceOption("VOICE_RECOGNITION", MediaRecorder.AudioSource.VOICE_RECOGNITION))
        add(AudioSourceOption("VOICE_COMMUNICATION", MediaRecorder.AudioSource.VOICE_COMMUNICATION))
        add(AudioSourceOption("UNPROCESSED", MediaRecorder.AudioSource.UNPROCESSED))
        add(AudioSourceOption("CAMCORDER", MediaRecorder.AudioSource.CAMCORDER))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(AudioSourceOption("VOICE_PERFORMANCE", MediaRecorder.AudioSource.VOICE_PERFORMANCE))
        }
    }

    /** The name of the source [id], or its number when it is not one of [all]. */
    fun nameOf(id: Int): String = all.firstOrNull { it.id == id }?.name ?: "source $id"
}

/**
 * Reads mono 16-bit PCM from [source] on a dedicated audio thread and hands each frame to the
 * callback as floats in [-1, 1]. Audio never leaves the device and is not stored by this class.
 */
class MicCapture(
    private val source: Int,
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val frameSize: Int = DEFAULT_FRAME_SIZE,
    private val preferredDevice: AudioDeviceInfo? = null,
) {
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    /**
     * Starts capturing; throws [IllegalStateException] when the source cannot be opened. If
     * reading keeps failing (the audio server died, for example) or [onFrame] throws, the capture
     * ends and [onFailure] says why (with the exception, if one was thrown), on the capture thread.
     */
    @SuppressLint("MissingPermission")
    fun start(onFailure: (String, Throwable?) -> Unit, onFrame: (FloatArray) -> Unit): AudioRecord {
        check(record == null) { "capture already started" }
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, CHANNEL, ENCODING)
        check(minBuffer > 0) { "unsupported format $sampleRate Hz (minBuffer=$minBuffer)" }

        val recorder = AudioRecord(source, sampleRate, CHANNEL, ENCODING, maxOf(minBuffer, frameSize * 2 * 4))
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            error("AudioRecord not initialized for source $source at $sampleRate Hz")
        }
        preferredDevice?.let(recorder::setPreferredDevice)
        recorder.startRecording()
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            recorder.release()
            error("startRecording failed for source $source")
        }

        record = recorder
        running = true
        worker = thread(name = "mic-capture") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val pcm = ShortArray(frameSize)
            var failuresInRow = 0
            while (running) {
                val read = recorder.read(pcm, 0, frameSize)
                if (read <= 0) {
                    // A dead record never recovers; other errors get half a second to clear.
                    if (read == AudioRecord.ERROR_DEAD_OBJECT || ++failuresInRow >= MAX_FAILED_READS) {
                        if (running) onFailure("AudioRecord.read returned $read", null)
                        break
                    }
                    Thread.sleep(10)
                    continue
                }
                failuresInRow = 0
                try {
                    onFrame(FloatArray(read) { pcm[it] / 32_768f })
                } catch (e: Exception) {
                    onFailure("frame handling threw ${e::class.java.simpleName}: ${e.message}", e)
                    break
                }
            }
        }
        return recorder
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val DEFAULT_FRAME_SIZE = 2_048
        private const val MAX_FAILED_READS = 50
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
