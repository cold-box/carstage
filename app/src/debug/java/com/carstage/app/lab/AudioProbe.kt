package com.carstage.app.lab

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import com.carstage.app.audio.AudioSourceOption
import com.carstage.app.audio.AudioSources
import com.carstage.app.audio.MicCapture
import com.carstage.app.audio.SignalAnalysis
import com.carstage.app.audio.TestTonePlayer
import com.carstage.app.vehicle.describe
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Answers the questions the karaoke concept depends on: can a third-party app open the cabin
 * microphone, through which sources, with which pre-processing, and does echo cancellation
 * remove music the car itself is playing.
 */
class AudioProbe(
    private val context: Context,
    private val report: (String) -> Unit,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    suspend fun run() = withContext(Dispatchers.IO) {
        if (!hasMicPermission()) {
            report("RECORD_AUDIO not granted")
            return@withContext
        }
        reportInputDevices()
        reportMicrophones()
        reportEffects()
        AudioSources.all.forEach { probeSource(it) }
        probeEchoSuppression()
        probeEchoReference()
    }

    /** Saves a short MIC capture to app cache for offline listening; for development only. */
    suspend fun saveSample(durationMs: Long = 5_000) = withContext(Dispatchers.IO) {
        if (!hasMicPermission()) {
            report("RECORD_AUDIO not granted")
            return@withContext
        }
        report("recording ${durationMs / 1_000}s from MIC: sing a phrase and clap twice")
        val samples = capture(MediaRecorder.AudioSource.MIC, durationMs)
        val file = File(context.cacheDir, "lab/mic-sample.wav").apply { parentFile?.mkdirs() }
        WavWriter.write(file, samples, MicCapture.DEFAULT_SAMPLE_RATE)
        // Pleos Connect runs apps as a secondary user (10), so run-as needs --user.
        val userId = Process.myUid() / PER_USER_UID_RANGE
        report(
            "saved ${samples.size} samples rms=${SignalAnalysis.rmsDbfs(samples).fmt()} " +
                "peak=${SignalAnalysis.peakDbfs(samples).fmt()} dBFS -> ${file.absolutePath} " +
                "(pull: adb exec-out run-as ${context.packageName} --user $userId cat cache/lab/mic-sample.wav > mic-sample.wav)"
        )
        val stats = SingingStats(MicCapture.DEFAULT_SAMPLE_RATE)
        val frameSize = MicCapture.DEFAULT_FRAME_SIZE
        for (start in 0..samples.size - frameSize step frameSize) {
            stats.add(samples.copyOfRange(start, start + frameSize))
        }
        report("singing stats: ${stats.summary()}")
    }

    /** Health check for `scripts/doctor.sh`: true when MIC delivers a non-silent signal. */
    suspend fun smoke(): Boolean = withContext(Dispatchers.IO) {
        if (!hasMicPermission()) {
            report("RECORD_AUDIO not granted")
            return@withContext false
        }
        val samples = capture(MediaRecorder.AudioSource.MIC, PROBE_CAPTURE_MS)
        val peak = SignalAnalysis.peakDbfs(samples)
        val ok = samples.isNotEmpty() && peak > SILENT_PEAK_DB
        val verdict = if (ok) "signal" else "no host audio (host mic off, or macOS denied the emulator microphone access)"
        report("mic ${samples.size} samples rms=${SignalAnalysis.rmsDbfs(samples).fmt()} peak=${peak.fmt()} dBFS: $verdict")
        ok
    }

    /**
     * Captures the platform echo-reference input (what the car is playing) while music plays.
     * If readable, the app can separate the user's voice from the music itself.
     */
    private suspend fun probeEchoReference() {
        val device = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == TYPE_ECHO_REFERENCE }
            ?: run {
                report("echo reference input not present")
                return
            }
        val outcome = runCatching {
            var routed = ""
            val baseline = SignalAnalysis.rmsDbfs(
                capture(MediaRecorder.AudioSource.MIC, ECHO_BASELINE_MS, preferredDevice = device) {
                    routed = it.routedDevice?.let { d -> "${deviceTypeName(d.type)}/${d.address}" }.toString()
                }
            )
            val player = TestTonePlayer()
            val withPlayback = try {
                player.start()
                delay(ECHO_PLAYER_WARMUP_MS)
                SignalAnalysis.rmsDbfs(capture(MediaRecorder.AudioSource.MIC, ECHO_PLAYBACK_MS, preferredDevice = device))
            } finally {
                player.stop()
            }
            "routed=$routed baseline=${baseline.fmt()} playback=${withPlayback.fmt()} rise=${(withPlayback - baseline).fmt()} dB"
        }
        report("echo reference ${outcome.getOrElse { "failed: ${it.describe()}" }}")
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun reportInputDevices() {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        report("input devices: ${inputs.size}")
        inputs.forEach { device ->
            report(
                "  input type=${deviceTypeName(device.type)} name=${device.productName} " +
                    "address=${device.address} rates=${device.sampleRates.joinToString()} " +
                    "channels=${device.channelCounts.joinToString()}"
            )
        }
    }

    private fun reportMicrophones() {
        runCatching { audioManager.microphones }
            .onSuccess { mics ->
                report("microphones: ${mics.size}")
                mics.forEach { mic ->
                    report("  mic id=${mic.id} desc=${mic.description} location=${mic.location} directionality=${mic.directionality}")
                }
            }
            .onFailure { report("microphones query failed: ${it.describe()}") }
    }

    private fun reportEffects() {
        report(
            "effects available: AEC=${AcousticEchoCanceler.isAvailable()} " +
                "NS=${NoiseSuppressor.isAvailable()} AGC=${AutomaticGainControl.isAvailable()}"
        )
        val descriptors = runCatching { AudioEffect.queryEffects() }.getOrNull().orEmpty()
        report("effect descriptors: ${descriptors.joinToString { it.name }}")
    }

    private suspend fun probeSource(source: AudioSourceOption) {
        for (sampleRate in listOf(MicCapture.DEFAULT_SAMPLE_RATE, FALLBACK_SAMPLE_RATE)) {
            val result = runCatching {
                var details = ""
                val samples = capture(source.id, PROBE_CAPTURE_MS, sampleRate) { record ->
                    details = describeRecording(record)
                }
                "rms=${SignalAnalysis.rmsDbfs(samples).fmt()} peak=${SignalAnalysis.peakDbfs(samples).fmt()} dBFS " +
                    "samples=${samples.size} $details"
            }
            result.onSuccess {
                report("source ${source.name} @${sampleRate}Hz OK $it")
                return
            }.onFailure {
                report("source ${source.name} @${sampleRate}Hz failed: ${it.describe()}")
            }
        }
    }

    private fun describeRecording(record: AudioRecord): String {
        val routed = record.routedDevice?.let { "${deviceTypeName(it.type)}/${it.productName}" }
        val silenced = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            record.activeRecordingConfiguration?.isClientSilenced
        } else {
            null
        }
        val aec = if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(record.audioSessionId)?.let { effect ->
                try {
                    effect.enabled = true
                    effect.enabled
                } finally {
                    effect.release()
                }
            }
        } else {
            null
        }
        return "routed=$routed silenced=$silenced aecAttach=$aec"
    }

    /**
     * Records with each voice-oriented source while the app plays music through the media stream.
     * A smaller rise over the quiet baseline for a source means its pre-processing removed playback.
     */
    private suspend fun probeEchoSuppression() {
        report("echo test: plays a test arpeggio through USAGE_MEDIA; keep speakers on and the room quiet")
        for (source in ECHO_TEST_SOURCES) {
            val outcome = runCatching {
                val baseline = SignalAnalysis.rmsDbfs(capture(source.id, ECHO_BASELINE_MS))
                val player = TestTonePlayer()
                val withPlayback = try {
                    player.start()
                    delay(ECHO_PLAYER_WARMUP_MS)
                    SignalAnalysis.rmsDbfs(capture(source.id, ECHO_PLAYBACK_MS))
                } finally {
                    player.stop()
                }
                "baseline=${baseline.fmt()} playback=${withPlayback.fmt()} rise=${(withPlayback - baseline).fmt()} dB"
            }
            report("echo ${source.name} ${outcome.getOrElse { "failed: ${it.describe()}" }}")
        }
    }

    private suspend fun capture(
        source: Int,
        durationMs: Long,
        sampleRate: Int = MicCapture.DEFAULT_SAMPLE_RATE,
        preferredDevice: AudioDeviceInfo? = null,
        onStarted: (AudioRecord) -> Unit = {},
    ): FloatArray {
        val frames = ConcurrentLinkedQueue<FloatArray>()
        val mic = MicCapture(source, sampleRate, preferredDevice = preferredDevice)
        try {
            val record = mic.start(onFailure = { why, _ -> report("capture stopped by itself: $why") }) { frames.add(it) }
            onStarted(record)
            delay(durationMs)
        } finally {
            mic.stop()
        }
        val out = FloatArray(frames.sumOf { it.size })
        var offset = 0
        frames.forEach { frame ->
            frame.copyInto(out, offset)
            offset += frame.size
        }
        return out
    }

    private fun Float.fmt() = "%.1f".format(this)

    private companion object {
        // AudioDeviceInfo.TYPE_ECHO_REFERENCE is hidden from the public SDK.
        const val TYPE_ECHO_REFERENCE = 28
        // With host audio off the emulator feeds synthetic noise peaking at 7 LSB (-73 dBFS);
        // a real room through the host mic peaks far above -60 dBFS.
        const val SILENT_PEAK_DB = -60f
        const val PER_USER_UID_RANGE = 100_000
        const val FALLBACK_SAMPLE_RATE = 16_000
        const val PROBE_CAPTURE_MS = 1_000L
        const val ECHO_BASELINE_MS = 800L
        const val ECHO_PLAYER_WARMUP_MS = 300L
        const val ECHO_PLAYBACK_MS = 1_500L

        val ECHO_TEST_SOURCES = AudioSources.all.filter {
            it.id == MediaRecorder.AudioSource.MIC ||
                it.id == MediaRecorder.AudioSource.VOICE_RECOGNITION ||
                it.id == MediaRecorder.AudioSource.VOICE_COMMUNICATION
        }

        fun deviceTypeName(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
            AudioDeviceInfo.TYPE_BUS -> "BUS"
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
            AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
            AudioDeviceInfo.TYPE_FM_TUNER -> "FM_TUNER"
            AudioDeviceInfo.TYPE_TV_TUNER -> "TV_TUNER"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
            TYPE_ECHO_REFERENCE -> "ECHO_REFERENCE"
            else -> "TYPE_$type"
        }
    }
}
