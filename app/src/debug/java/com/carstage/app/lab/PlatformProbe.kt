package com.carstage.app.lab

import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.carstage.app.audio.MicCapture
import com.carstage.app.audio.SignalAnalysis
import com.carstage.app.vehicle.VehicleSession
import com.carstage.app.vehicle.awaitVehicle
import com.carstage.app.vehicle.describe
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Checks the platform signals the launch suggestion and convenience features depend on
 * (docs/07-convenience-features.md): whether other apps' media playback is visible, whether the
 * audio mode reports calls, whether EV battery state can be read, and how much CPU analysis costs.
 */
class PlatformProbe(
    private val context: Context,
    private val session: VehicleSession,
    private val report: (String) -> Unit,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    suspend fun run() {
        val steps = listOf(
            step("ev battery") { probeEvBattery() },
            step("playback and audio mode") { watchPlaybackAndMode() },
            step("mic analysis cpu") { measureMicAnalysisLoad() },
            step("worst-case analysis cpu") { measureWorstCaseAnalysis() },
        )
        report("platform: ${steps.count { it }}/${steps.size} steps completed")
    }

    private suspend fun step(name: String, block: suspend () -> Unit): Boolean {
        report("-- $name")
        return try {
            block()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LabViewModel.TAG, "$name failed", e)
            report("$name failed: ${e.describe()}")
            false
        }
    }

    /** Runs one SDK call and reports its value or error, so one failure does not skip the others. */
    private suspend fun <T> reportCall(label: String, call: suspend () -> T) {
        val text = try {
            call().toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.describe()
        }
        report("$label=$text")
    }

    private suspend fun probeEvBattery() {
        session.awaitReady().onFailure { report("vehicle service readiness unknown: ${it.describe()}") }
        val ev = session.evBattery
        reportCall("state of charge access") {
            awaitVehicle<CapabilityConfig?> { ok, fail -> ev.checkEvBatteryStateOfChargeCapabilities(ok, fail) }?.accessType
        }
        reportCall("state of charge %") { awaitVehicle<Int?> { ok, fail -> ev.getEvBatteryStateOfCharge(ok, fail) } }
        reportCall("charge switch access") {
            awaitVehicle<CapabilityConfig?> { ok, fail -> ev.checkEvChargeSwitchCapabilities(ok, fail) }?.accessType
        }
        reportCall("charging") { awaitVehicle<Boolean?> { ok, fail -> ev.getEvChargeSwitchState(ok, fail) } }
    }

    /**
     * Logs playback and audio mode changes for [WATCH_MS]. Meanwhile start or stop music in another
     * app (tools/mediatester) or place a call (`adb emu gsm call <number>`). Keep CarStage in the
     * foreground so the next step still hears the microphone.
     */
    private suspend fun watchPlaybackAndMode() {
        report("mode=${modeName(audioManager.mode)} active playback: ${describe(audioManager.activePlaybackConfigurations)}")
        report("watching ${WATCH_MS / 1_000}s: play/stop music in another app or place a call now")
        val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                report("playback changed: ${describe(configs)}")
            }
        }
        audioManager.registerAudioPlaybackCallback(playbackCallback, Handler(Looper.getMainLooper()))
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val modeListener = AudioManager.OnModeChangedListener { report("audio mode changed: ${modeName(it)}") }
                audioManager.addOnModeChangedListener(context.mainExecutor, modeListener)
                try {
                    delay(WATCH_MS)
                } finally {
                    audioManager.removeOnModeChangedListener(modeListener)
                }
            } else {
                report("audio mode listener needs API 31; only the final mode is reported on API ${Build.VERSION.SDK_INT}")
                delay(WATCH_MS)
            }
        } finally {
            audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        }
        report("after watch: mode=${modeName(audioManager.mode)} active playback: ${describe(audioManager.activePlaybackConfigurations)}")
    }

    /**
     * Captures MIC for [LOAD_MS] with the same per-frame analysis as Lab's live meter (without its UI
     * updates) and reports this process's CPU share. A quiet room skips pitch detection, so the
     * worst case is measured separately.
     */
    private suspend fun measureMicAnalysisLoad() = withContext(Dispatchers.IO) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        check(granted) { "RECORD_AUDIO not granted" }
        val capture = MicCapture(MediaRecorder.AudioSource.MIC)
        val stats = SingingStats(capture.sampleRate)
        val cpuStart = Process.getElapsedCpuTime()
        val wallStart = SystemClock.elapsedRealtime()
        capture.start(onFailure = { why, _ -> report("capture stopped by itself: $why") }) { stats.add(it) }
        try {
            delay(LOAD_MS)
        } finally {
            capture.stop()
        }
        val cpuMs = Process.getElapsedCpuTime() - cpuStart
        val wallMs = SystemClock.elapsedRealtime() - wallStart
        val cores = Runtime.getRuntime().availableProcessors()
        val oneCore = 100f * cpuMs / wallMs
        report(
            "cpu ${cpuMs}ms over ${wallMs}ms: ${"%.1f".format(oneCore)}% of one core, " +
                "${"%.1f".format(oneCore / cores)}% of $cores cores (whole process)"
        )
        report("analysis: ${stats.summary()}")
        if (stats.loudestDb <= SignalAnalysis.SILENCE_DB) {
            report("warning: silent input; CarStage may have been in the background or host audio is off")
        }
    }

    /** Feeds a loud voiced tone so pitch detection runs on every frame, the costliest case. */
    private fun measureWorstCaseAnalysis() {
        val sampleRate = MicCapture.DEFAULT_SAMPLE_RATE
        val frameSize = MicCapture.DEFAULT_FRAME_SIZE
        val frames = WORST_CASE_SECONDS * sampleRate / frameSize
        val stats = SingingStats(sampleRate)
        var phase = 0.0
        val step = 2 * Math.PI * VOICE_HZ / sampleRate
        val frame = FloatArray(frameSize)
        val cpuStart = SystemClock.currentThreadTimeMillis()
        repeat(frames) {
            for (i in frame.indices) {
                frame[i] = (0.3 * sin(phase)).toFloat()
                phase += step
            }
            stats.add(frame)
        }
        val cpuMs = SystemClock.currentThreadTimeMillis() - cpuStart
        val audioMs = frames * frameSize * 1_000L / sampleRate
        report(
            "worst case: $frames voiced frames (${audioMs}ms of audio) took ${cpuMs}ms cpu = " +
                "${"%.1f".format(100f * cpuMs / audioMs)}% of one core while singing; ${stats.summary()}"
        )
    }

    private fun describe(configs: List<AudioPlaybackConfiguration>): String =
        if (configs.isEmpty()) "none" else configs.joinToString(prefix = "[", postfix = "]") {
            val attributes = it.audioAttributes
            "${usageName(attributes.usage)}/${contentName(attributes.contentType)}"
        }

    private companion object {
        const val WATCH_MS = 20_000L
        const val LOAD_MS = 15_000L
        const val WORST_CASE_SECONDS = 15
        const val VOICE_HZ = 220.0

        fun usageName(usage: Int): String = when (usage) {
            AudioAttributes.USAGE_MEDIA -> "MEDIA"
            AudioAttributes.USAGE_GAME -> "GAME"
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "NAVIGATION_GUIDANCE"
            AudioAttributes.USAGE_ASSISTANT -> "ASSISTANT"
            AudioAttributes.USAGE_VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            AudioAttributes.USAGE_NOTIFICATION -> "NOTIFICATION"
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> "RINGTONE"
            AudioAttributes.USAGE_ALARM -> "ALARM"
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> "SONIFICATION"
            else -> "USAGE_$usage"
        }

        fun contentName(type: Int): String = when (type) {
            AudioAttributes.CONTENT_TYPE_MUSIC -> "MUSIC"
            AudioAttributes.CONTENT_TYPE_SPEECH -> "SPEECH"
            AudioAttributes.CONTENT_TYPE_MOVIE -> "MOVIE"
            AudioAttributes.CONTENT_TYPE_SONIFICATION -> "SONIFICATION"
            else -> "UNKNOWN"
        }

        fun modeName(mode: Int): String = when (mode) {
            AudioManager.MODE_NORMAL -> "NORMAL"
            AudioManager.MODE_RINGTONE -> "RINGTONE"
            AudioManager.MODE_IN_CALL -> "IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
            AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"
            else -> "MODE_$mode"
        }
    }
}
