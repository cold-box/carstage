package com.carstage.app.lab

import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import com.carstage.app.audio.AudioAnalyzer
import com.carstage.app.audio.AudioSourceOption
import com.carstage.app.audio.AudioSources
import com.carstage.app.audio.MicCapture
import com.carstage.app.stage.AudioEvent
import com.carstage.app.stage.VoiceMode
import com.carstage.app.vehicle.describe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.log10

/**
 * Whether the car's own echo cancelling keeps another app's music out of the microphone, source by
 * source (docs/05 "마이크 입력 종류 비교"). Hands-free calls and Gleo need the car's music taken out of
 * the microphone too, so a car's audio system may do it for the sources made for voice
 * (VOICE_COMMUNICATION, VOICE_RECOGNITION) and not for MIC; the stage could then simply listen
 * through such a source.
 *
 * While another app plays music, records each source in turn for [seconds], [rounds] times (the
 * order repeats, so a louder part of the song does not favour one source), through the stage's own
 * analysis, and reports per source: the input level, the accompaniment the analysis heard, the
 * frames in voice mode, and whether an echo canceller sits on the recording. Run it once with
 * nobody singing (a source that cancels the music shows a much lower level and no voice mode) and
 * once singing along (the voices must still be heard). Audio stays in memory and is dropped after
 * each recording.
 */
class MicSourceProbe(
    private val musicPlaying: () -> Boolean,
    private val seconds: Int,
    private val rounds: Int,
    private val report: (String) -> Unit,
) {
    suspend fun run() = withContext(Dispatchers.IO) {
        if (!musicPlaying()) report("no other app plays music: play a song first, or the comparison says nothing about echo cancelling")
        report("AEC available to apps: ${AcousticEchoCanceler.isAvailable()}; $rounds rounds of ${SOURCES.size} sources × $seconds s")
        val totals = SOURCES.associate { it.name to Totals() }
        repeat(rounds) { round ->
            for (source in SOURCES) {
                val result = runCatching { record(source) }
                result.onSuccess { heard ->
                    // Zeros (another app holds the microphone, or it is muted) or a stopped capture
                    // would read as music taken out; they are shown but left out of the summary.
                    if (heard.trustworthy) totals.getValue(source.name).add(heard)
                    report("round ${round + 1} ${source.name}: ${heard.describe()}")
                }.onFailure { report("round ${round + 1} ${source.name}: failed: ${it.describe()}") }
                delay(GAP_MS)
            }
        }
        if (!musicPlaying()) report("the music stopped during the run; the later rounds heard no music")
        report("summary (mean of rounds; lower input and accompaniment with music only means the source took the music out):")
        totals.forEach { (name, total) -> report("  $name: ${total.describe()}") }
    }

    /** One recording of [source] through the stage's analysis. */
    private suspend fun record(source: AudioSourceOption): Heard {
        val heard = Heard()
        val modes = VoiceMode()
        val analyzer = AudioAnalyzer(MicCapture.DEFAULT_SAMPLE_RATE, emit = { event ->
            if (event is AudioEvent.Frame) {
                heard.frames++
                heard.voiceSum += event.level
                heard.musicSum += event.musicLevel
                if (modes.hear(event)) heard.voiceModeFrames++
            }
        })
        val mic = MicCapture(source.id)
        try {
            val record = mic.start(onFailure = { why, _ ->
                heard.stoppedEarly = true
                report("${source.name}: capture stopped by itself: $why")
            }) { frame ->
                var sum = 0.0
                for (s in frame) sum += s.toDouble() * s
                if (sum == 0.0) heard.silentFrames++
                heard.energy += sum
                heard.samples += frame.size
                analyzer.process(frame)
            }
            heard.effects = effectsOn(record)
            delay(seconds * 1_000L)
        } finally {
            // Joins the capture thread, so what it counted is settled when read below.
            mic.stop()
        }
        heard.accompanimentDb = analyzer.backgroundDb
        return heard
    }

    /** Whether an echo canceller is on the recording's session as the platform set it up, before the app touches it. */
    private fun effectsOn(record: AudioRecord): String {
        if (!AcousticEchoCanceler.isAvailable()) return "AEC n/a"
        val effect = AcousticEchoCanceler.create(record.audioSessionId) ?: return "AEC not attachable"
        return try {
            "AEC ${if (effect.enabled) "on" else "off"}"
        } finally {
            effect.release()
        }
    }

    private class Heard {
        var samples = 0L
        var energy = 0.0
        var frames = 0
        var voiceSum = 0.0
        var musicSum = 0.0
        var voiceModeFrames = 0
        var accompanimentDb = Float.NaN
        var effects = ""
        var silentFrames = 0

        @Volatile
        var stoppedEarly = false

        /** Not all zeros and not stopped: a recording the comparison can rest on. */
        val trustworthy: Boolean get() = !stoppedEarly && frames > 0 && silentFrames * 2 < frames

        val inputDb: Double get() = if (samples == 0L) Double.NaN else 10 * log10(energy / samples + 1e-12)
        val voice: Double get() = if (frames == 0) 0.0 else voiceSum / frames
        val music: Double get() = if (frames == 0) 0.0 else musicSum / frames
        val voiceModeShare: Double get() = if (frames == 0) 0.0 else 100.0 * voiceModeFrames / frames

        fun describe() =
            "input ${inputDb.fmt()} dBFS, accompaniment ${accompanimentDb.toDouble().fmt()} dBFS, " +
                "voice ${voice.fmt2()}, music ${music.fmt2()}, voice mode ${voiceModeShare.fmt()}% of $frames frames, $effects" +
                (if (silentFrames > 0) ", $silentFrames all-zero frames" else "") +
                (if (stoppedEarly) ", stopped early" else "") +
                (if (trustworthy) "" else " (left out of the summary)")
    }

    private class Totals {
        private val heard = mutableListOf<Heard>()

        fun add(one: Heard) {
            heard += one
        }

        fun describe(): String {
            if (heard.isEmpty()) return "no usable recording"
            fun mean(of: (Heard) -> Double) = heard.map(of).filter { !it.isNaN() }.average()
            return "input ${mean { it.inputDb }.fmt()} dBFS, accompaniment ${mean { it.accompanimentDb.toDouble() }.fmt()} dBFS, " +
                "voice ${mean { it.voice }.fmt2()}, music ${mean { it.music }.fmt2()}, voice mode ${mean { it.voiceModeShare }.fmt()}%, " +
                heard.map { it.effects }.distinct().joinToString("/")
        }
    }

    companion object {
        /** The sources worth comparing: the stage's default and those a car may run its voice processing on. */
        val SOURCES: List<AudioSourceOption> = AudioSources.all.filter {
            it.id == MediaRecorder.AudioSource.MIC ||
                it.id == MediaRecorder.AudioSource.VOICE_RECOGNITION ||
                it.id == MediaRecorder.AudioSource.VOICE_COMMUNICATION ||
                it.id == MediaRecorder.AudioSource.UNPROCESSED ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && it.id == MediaRecorder.AudioSource.VOICE_PERFORMANCE)
        }

        const val DEFAULT_SECONDS = 8
        const val DEFAULT_ROUNDS = 2

        /** Between recordings, so one source's processing settles before the next opens. */
        private const val GAP_MS = 300L

        private fun Double.fmt() = if (isNaN()) "-" else "%.1f".format(this)
        private fun Double.fmt2() = "%.2f".format(this)
    }
}
