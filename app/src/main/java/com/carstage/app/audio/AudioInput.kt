package com.carstage.app.audio

import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import java.util.concurrent.Executor

/** A running source of audio frames for the stage: the microphone, or a recording the Lab plays back. */
interface AudioInput {
    val sampleRate: Int

    /** What the log calls this input. */
    val label: String

    /**
     * Starts handing frames (floats in [-1, 1]) to [onFrame] on its own thread; throws when it
     * cannot start. If it stops on its own later, it calls [onFailure] with the reason and the
     * exception, if one was thrown.
     */
    fun start(onFrame: (FloatArray) -> Unit, onFailure: (String, Throwable?) -> Unit)

    /** What is known about the running input, for the log; empty when there is nothing to add. */
    fun details(): String = ""

    fun stop()
}

/**
 * The cabin microphone through [MicCapture]. A capture the system silences (another app such as
 * Gleo holds the microphone, or it is muted) gets zeros without any error, so [log] hears every
 * change of that state.
 */
class MicInput(private val log: (String) -> Unit, private val source: Int = SOURCE) : AudioInput {
    private val capture = MicCapture(source)
    private var record: AudioRecord? = null
    private var silencedWatch: AudioManager.AudioRecordingCallback? = null

    private val sourceName = AudioSources.nameOf(source)

    override val sampleRate: Int get() = capture.sampleRate
    override val label = "microphone"

    override fun start(onFrame: (FloatArray) -> Unit, onFailure: (String, Throwable?) -> Unit) {
        val started = capture.start(onFailure = onFailure, onFrame = onFrame)
        record = started
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var silenced = started.activeRecordingConfiguration?.isClientSilenced
            val watch = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                    val now = started.activeRecordingConfiguration?.isClientSilenced ?: return
                    // Right after starting the state may not be known yet; that counts as not silenced.
                    val was = silenced ?: false
                    silenced = now
                    if (now == was) return
                    log(if (now) "microphone silenced by the system (another app has it, or it is muted)" else "microphone no longer silenced")
                }
            }
            started.registerAudioRecordingCallback(Executor(Runnable::run), watch)
            silencedWatch = watch
        }
    }

    /** The device the platform routed the capture to and whether it is silenced right now. */
    override fun details(): String {
        val running = record ?: return ""
        val device = running.routedDevice?.let { "${it.productName} (type ${it.type})" } ?: "unknown"
        var used = ""
        var silenced: Boolean? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            running.activeRecordingConfiguration?.let { config ->
                // A platform may open another source than the one asked for (UNPROCESSED falls back
                // to DEFAULT where it is not supported) without an error; only this tells.
                if (config.audioSource != source) used = " (the platform uses ${AudioSources.nameOf(config.audioSource)})"
                silenced = config.isClientSilenced
            }
        }
        return "source=$sourceName$used, device=$device, silenced=${silenced ?: "unknown"}"
    }

    override fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) silencedWatch?.let { record?.unregisterAudioRecordingCallback(it) }
        silencedWatch = null
        capture.stop()
        record = null
    }

    companion object {
        /**
         * All sources worked on the emulator, and none cancels the car's music there (docs/05
         * "목소리 모드 확인"); a real car may cancel it for VOICE_RECOGNITION, which recordings in the car
         * decide (docs/05 "남은 검증"). Development builds try another with `--es micsource`.
         */
        const val SOURCE = MediaRecorder.AudioSource.MIC
    }
}
