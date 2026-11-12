package com.carstage.app.lab

import android.app.Activity
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Process
import com.carstage.app.ShellTap
import com.carstage.app.audio.EffectPlayer
import com.carstage.app.audio.MicCapture
import com.carstage.app.audio.MicInput
import com.carstage.app.audio.PlaybackSource
import com.carstage.app.audio.ReferenceBuffer
import com.carstage.app.audio.TestTonePlayer
import com.carstage.app.stage.SoundCue
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Checks whether the stage can hear what the other apps play, to take it out of the microphone
 * (docs/05 "재생 소리 캡처 확인"):
 * 1. Android's way: whether the MediaProjection consent dialog exists, and the PROJECT_MEDIA app op.
 * 2. The development helper (scripts/playback-tap.sh): how loud each capturable usage is right now.
 * 3. Whether the app's own sound stays out: a media tone with and without excluding the app, and a
 *    sound effect (only while no other music plays).
 * 4. While music plays: how far the microphone lags the reference as the stage lines them up
 *    (ReferenceBuffer), by cross-correlation, every [WINDOW_GAP_MS] for [minutes], and how it drifts.
 * Audio stays in memory and is dropped after each window.
 */
class EchoProbe(
    private val context: Context,
    private val effects: EffectPlayer,
    private val musicPlaying: () -> Boolean,
    private val minutes: Int,
    private val report: (String) -> Unit,
) {
    suspend fun run() {
        consent()
        val levels = levels()
        if (!levels) {
            report("helper unreachable: start it with scripts/playback-tap.sh start")
            return
        }
        if (musicPlaying()) {
            report("self sound: skipped while music plays (run again with the music stopped)")
            delays()
        } else {
            selfSound()
            report("delay: skipped, no music plays (start the test music app and run again)")
        }
    }

    private fun consent() {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val intents = buildList {
            add("default" to manager.createScreenCaptureIntent())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add("default display" to manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()))
            }
        }
        for ((name, intent) in intents) {
            val resolved = intent.resolveActivity(context.packageManager)
            report("consent ($name): ${intent.component?.flattenToShortString()} resolves to ${resolved?.flattenToShortString() ?: "nothing"}")
        }
        try {
            context.startActivity(intents.first().second.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            report("consent: dialog started (take a screenshot)")
        } catch (e: ActivityNotFoundException) {
            report("consent: no dialog: ActivityNotFoundException: ${e.message}")
        }
        // unsafeCheckOpNoThrow is API 29; playback capture itself needs 29, so older cars skip it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val mode = ops.unsafeCheckOpNoThrow(PROJECT_MEDIA, Process.myUid(), context.packageName)
            report("app op PROJECT_MEDIA: ${modeName(mode)} (adb shell appops set --user 10 ${context.packageName} PROJECT_MEDIA allow)")
        }
        // Without the dialog's answer there is no token; an empty one shows what Android says.
        val projection = runCatching { manager.getMediaProjection(Activity.RESULT_OK, Intent()) }
        report("getMediaProjection without a consent: ${projection.fold({ it?.let { "a projection" } ?: "null" }, { "${it::class.java.simpleName}: ${it.message}" })}")
    }

    /** Each capturable usage alone, then all; false when the helper cannot be reached. */
    private suspend fun levels(): Boolean {
        val sets = PlaybackSource.USAGES.map { listOf(it) } + listOf(PlaybackSource.USAGES)
        for (usages in sets) {
            val name = usages.joinToString("+") { ShellTap.usageName(it) }
            val level = measure(ShellTap(usages), LEVEL_MS) ?: return false
            report("level $name: $level")
        }
        return true
    }

    private suspend fun selfSound() {
        val tone = TestTonePlayer()
        tone.start()
        try {
            delay(SETTLE_MS)
            report("self sound: media tone, app excluded: ${measure(ShellTap(), LEVEL_MS)}")
            report("self sound: media tone, app not excluded: ${measure(ShellTap(exclude = null), LEVEL_MS)}")
        } finally {
            tone.stop()
        }
        delay(SETTLE_MS)
        val wasMuted = effects.muted
        effects.muted = false
        try {
            effects.play(SoundCue.APPLAUSE)
            report("self sound: applause effect (sonification), app not excluded: ${measure(ShellTap(exclude = null), LEVEL_MS)}")
        } finally {
            effects.muted = wasMuted
        }
    }

    /** Reads [source] for [ms]: mean and loudest 100 ms in dBFS, and samples received against expected. */
    private suspend fun measure(source: PlaybackSource, ms: Long): String? = withContext(Dispatchers.IO) {
        val format = try {
            source.open()
        } catch (e: Exception) {
            report("${source.label}: ${e::class.java.simpleName}: ${e.message}")
            return@withContext null
        }
        try {
            val pcm = ShortArray(format.sampleRate / 10 * format.channels)
            val levels = mutableListOf<Double>()
            var frames = 0L
            val start = System.nanoTime()
            while (System.nanoTime() - start < ms * 1_000_000) {
                val count = source.read(pcm)
                if (count < 0) break
                var energy = 0.0
                for (i in 0 until count) energy += pcm[i].toDouble() * pcm[i]
                if (count > 0) levels += 10 * log10(energy / count / (32_768.0 * 32_768.0) + 1e-20)
                frames += count / format.channels
            }
            val expected = format.sampleRate * ms / 1_000
            val mean = levels.map { Math.pow(10.0, it / 10) }.average().let { 10 * log10(it + 1e-20) }
            "mean ${"%.1f".format(mean)} dBFS, loudest ${"%.1f".format(levels.maxOrNull() ?: -200.0)} dBFS, $frames of $expected samples"
        } finally {
            source.close()
        }
    }

    /** Microphone and reference side by side as the stage lines them up; one lag per window. */
    private suspend fun delays() {
        val windows = (minutes * 60_000L / WINDOW_GAP_MS).toInt().coerceAtLeast(1)
        val buffer = ReferenceBuffer(MicCapture.DEFAULT_SAMPLE_RATE)
        val tap = ShellTap()
        // The helper is a socket: open it off the main thread.
        val format = try {
            withContext(Dispatchers.IO) { tap.open() }
        } catch (e: Exception) {
            report("delay: helper: ${e::class.java.simpleName}: ${e.message}")
            return
        }
        val reader = Thread({
            val pcm = ShortArray(format.sampleRate / 50 * format.channels)
            while (true) {
                val count = try {
                    tap.read(pcm)
                } catch (e: Exception) {
                    -1
                }
                if (count < 0) break
                buffer.write(pcm, count, format.channels, format.sampleRate, System.nanoTime())
            }
        }, "echo-probe-tap").apply { start() }
        val window = Window(WINDOW_MS * MicCapture.DEFAULT_SAMPLE_RATE / 1_000 / DECIMATE)
        val capture = MicCapture(MicInput.SOURCE)
        val ref = FloatArray(MicCapture.DEFAULT_FRAME_SIZE)
        capture.start(onFailure = { why, _ -> report("delay: microphone stopped by itself: $why") }) { frame ->
            if (ref.size < frame.size) return@start
            buffer.read(ref, frame.size, System.nanoTime())
            window.add(frame, ref, frame.size)
        }
        val lags = mutableListOf<Pair<Long, Double>>()
        val started = System.currentTimeMillis()
        try {
            repeat(windows) { index ->
                window.reset()
                while (!window.full()) delay(100)
                val (mic, reference) = window.take()
                val at = (System.currentTimeMillis() - started) / 1_000
                val (lag, r) = withContext(Dispatchers.Default) { bestLag(mic, reference) }
                val ms = lag * 1_000.0 * DECIMATE / MicCapture.DEFAULT_SAMPLE_RATE
                report("delay window ${index + 1}/$windows at $at s: microphone lags reference by ${"%.1f".format(ms)} ms (r=${"%.2f".format(r)})")
                if (r >= MIN_CORRELATION) lags += at to ms
                if (index < windows - 1) delay(WINDOW_GAP_MS - WINDOW_MS)
            }
        } finally {
            capture.stop()
            // NonCancellable: a cancelled probe must still close the helper's socket and its reader.
            withContext(NonCancellable + Dispatchers.IO) {
                tap.close()
                reader.join(500)
            }
        }
        report(
            "delay: ${lags.size} of $windows windows correlated; " +
                if (lags.isEmpty()) {
                    "none"
                } else {
                    "min ${"%.1f".format(lags.minOf { it.second })} ms, max ${"%.1f".format(lags.maxOf { it.second })} ms, " +
                        "drift ${"%+.1f".format(lags.last().second - lags.first().second)} ms over ${lags.last().first - lags.first().first} s; " +
                        "buffer ${buffer.missing * 100 / maxOf(1, buffer.read)}% missing, ${buffer.gaps} gaps, ${buffer.resyncs} resyncs"
                },
        )
    }

    /** The lag (in decimated samples, the microphone behind the reference) with the strongest normalized correlation. */
    private fun bestLag(mic: FloatArray, reference: FloatArray): Pair<Int, Double> {
        var best = 0
        var bestR = -1.0
        val micEnergy = mic.sumOf { it.toDouble() * it }
        for (lag in MIN_LAG..MAX_LAG) {
            var dot = 0.0
            var refEnergy = 0.0
            val from = maxOf(0, lag)
            val to = minOf(mic.size, reference.size + lag)
            for (n in from until to) {
                val r = reference[n - lag]
                dot += mic[n] * r
                refEnergy += r.toDouble() * r
            }
            val norm = sqrt(micEnergy * refEnergy)
            if (norm > 0 && dot / norm > bestR) {
                bestR = dot / norm
                best = lag
            }
        }
        return best to bestR
    }

    /** One window of microphone and reference, averaged down by [DECIMATE]; filled on the microphone thread. */
    private class Window(private val size: Int) {
        private var mic = FloatArray(size)
        private var reference = FloatArray(size)
        private var filled = 0
        private var sumMic = 0f
        private var sumRef = 0f
        private var count = 0

        @Synchronized
        fun add(frame: FloatArray, ref: FloatArray, length: Int) {
            for (i in 0 until length) {
                if (filled >= size) return
                sumMic += frame[i]
                sumRef += ref[i]
                if (++count == DECIMATE) {
                    mic[filled] = sumMic / DECIMATE
                    reference[filled] = sumRef / DECIMATE
                    filled++
                    sumMic = 0f
                    sumRef = 0f
                    count = 0
                }
            }
        }

        @Synchronized
        fun full() = filled >= size

        @Synchronized
        fun reset() {
            filled = 0
            count = 0
            sumMic = 0f
            sumRef = 0f
        }

        @Synchronized
        fun take(): Pair<FloatArray, FloatArray> = mic.copyOf() to reference.copyOf()
    }

    private fun modeName(mode: Int) = when (mode) {
        AppOpsManager.MODE_ALLOWED -> "allow"
        AppOpsManager.MODE_IGNORED -> "ignore"
        AppOpsManager.MODE_ERRORED -> "deny"
        AppOpsManager.MODE_DEFAULT -> "default"
        else -> "mode $mode"
    }

    companion object {
        private const val PROJECT_MEDIA = "android:project_media"
        private const val LEVEL_MS = 3_000L
        private const val SETTLE_MS = 500L
        private const val WINDOW_MS = 8_000
        private const val WINDOW_GAP_MS = 20_000L

        /** To 2 kHz: half a millisecond is plenty for a canceller that follows the lag, and keeps a 3 s search quick. */
        private const val DECIMATE = 24

        /**
         * -125 ms (the reference behind the microphone, which a canceller could not follow) to 3 s: the
         * emulator's virtual cabin delays its echo by about 1.6 s, far past the canceller's 500 ms (docs/05).
         */
        private const val MIN_LAG = -250
        private const val MAX_LAG = 6_000

        /** Weaker peaks are not the music: the window is left out of the drift. */
        private const val MIN_CORRELATION = 0.2
    }
}
