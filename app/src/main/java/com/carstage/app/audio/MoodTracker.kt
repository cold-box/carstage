package com.carstage.app.audio

import com.carstage.app.stage.AudioEvent
import com.carstage.app.stage.AudioEvent.Mood.Feel
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Tells what the music is like, for the stage's background, from values [AudioAnalyzer] measures
 * per frame (docs/09 section 5):
 * - drive: the mean rise per frame, in dB, of the bands outside the voice band where drums sound
 *   ([AudioAnalyzer.DRUM_BANDS_HZ]). Music without drums moves about 1 dB a frame on its own; a beat
 *   adds a rise on every hit. Levels are compared with each other, so the volume does not change it.
 *   Talk raises it too, as much as drums or more.
 * - beat: how regularly those rises repeat, the autocorrelation of the last 6 s at the strongest
 *   period between 60 and 180 BPM, weighted toward 120 BPM as librosa's tempo estimate is. Talk
 *   and singing add rises that do not repeat, so they lower it; this is what keeps talk from
 *   reading as lively.
 * - energy: the accompaniment's level over the last 2 s within its range over the last 30 s of sound.
 *
 * Every 2 s, once 6 s were heard, drive and beat are smoothed (a third of the way to each new
 * measurement) and the feel decided: lively with drums and a clear beat, calm without drums,
 * moderate otherwise. A new song's feel shows about 6 to 10 s after it starts. A feel is kept until
 * the values move clearly past its threshold, so the light does not flip at a border. The
 * thresholds come from ten freely licensed tracks with and without drums; the car recordings and
 * talk were only checks, and read as moderate (docs/09). Pure Kotlin.
 */
class MoodTracker(framesPerSecond: Float) {
    private val windowFrames = (WINDOW_SECONDS * framesPerSecond).roundToInt()
    private val everyFrames = (EVERY_SECONDS * framesPerSecond).roundToInt()
    private val rangeFrames = (RANGE_SECONDS * framesPerSecond).roundToInt()
    /** Whole frames between beats, within [SLOWEST_BPM] and [FASTEST_BPM]: 8 to 23 at 48 kHz. */
    private val shortestLag = ceil(60 * framesPerSecond / FASTEST_BPM).toInt()
    private val longestLag = floor(60 * framesPerSecond / SLOWEST_BPM).toInt()
    private val bpmOfLag = DoubleArray(longestLag + 1) { if (it == 0) 0.0 else 60.0 * framesPerSecond / it }
    private val prior = DoubleArray(longestLag + 1) {
        if (it == 0) 0.0 else exp(-0.5 * (ln(bpmOfLag[it] / PRIOR_BPM) / ln(2.0)).let { octaves -> octaves * octaves })
    }

    private val rises = FloatArray(windowFrames)
    private val levels = FloatArray(rangeFrames)
    private var heard = 0
    private var previous = FloatArray(0)
    private var hasPrevious = false

    /** Scratch space for the analysis every 2 s, so the audio thread does not allocate. */
    private val window = DoubleArray(windowFrames)
    private val sorted = FloatArray(rangeFrames)

    private var drive = Double.NaN
    private var beat = Double.NaN
    private var feel = Feel.MODERATE

    /**
     * The tempo of the strongest period in the last 6 s, for the log: not smoothed, in whole-frame
     * steps (… 108, 117, 128, 141 … BPM), and there even when the music has no beat. NaN before the
     * first mood.
     */
    var beatsPerMinute = Double.NaN
        private set

    /** Times the reported feel changed, for the log; the first report is not a change. */
    var changes = 0
        private set
    private val reported = IntArray(Feel.entries.size)

    /** How many moods were reported with [feel], for the log. */
    fun timesReported(feel: Feel): Int = reported[feel.ordinal]

    /**
     * Takes one frame: the level of each drum band in dB ([bandsDb], the same bands every frame) and
     * the accompaniment's level in dB. Returns a mood every 2 s once 6 s were heard, null otherwise.
     */
    fun process(bandsDb: FloatArray, accompanimentDb: Float): AudioEvent.Mood? {
        var rise = 0f
        if (hasPrevious && previous.size == bandsDb.size) {
            for (i in bandsDb.indices) rise += maxOf(0f, bandsDb[i] - previous[i])
            rise /= bandsDb.size
        }
        if (previous.size != bandsDb.size) previous = FloatArray(bandsDb.size)
        bandsDb.copyInto(previous)
        hasPrevious = true
        rises[heard % windowFrames] = rise
        levels[heard % rangeFrames] = accompanimentDb
        heard++
        if (heard < windowFrames || heard % everyFrames != 0) return null
        return measure()
    }

    /** Forgets the last frame, so the first frame after digital silence is not read as a hit. */
    fun interrupt() {
        hasPrevious = false
    }

    private fun measure(): AudioEvent.Mood {
        var sum = 0.0
        for (i in 0 until windowFrames) {
            window[i] = rises[(heard - windowFrames + i) % windowFrames].toDouble()
            sum += window[i]
        }
        val meanRise = sum / windowFrames
        var zeroLag = 1e-12
        for (i in 0 until windowFrames) {
            window[i] -= meanRise
            zeroLag += window[i] * window[i]
        }
        var best = Double.NEGATIVE_INFINITY
        var regularity = 0.0
        for (lag in shortestLag..longestLag) {
            var product = 0.0
            for (i in 0 until windowFrames - lag) product += window[i] * window[i + lag]
            // Unbiased, so longer periods are not penalised for overlapping less.
            val correlation = product / zeroLag * windowFrames / (windowFrames - lag)
            if (correlation * prior[lag] > best) {
                best = correlation * prior[lag]
                regularity = correlation
                beatsPerMinute = bpmOfLag[lag]
            }
        }
        drive = if (drive.isNaN()) meanRise else drive + (meanRise - drive) * SMOOTHING
        beat = if (beat.isNaN()) regularity else beat + (regularity - beat) * SMOOTHING
        val next = decide(feel, drive, beat)
        if (next != feel && reported.any { it > 0 }) changes++
        feel = next
        reported[feel.ordinal]++
        return AudioEvent.Mood(feel, energy(), beatsPerMinute.toFloat())
    }

    /** Where the last 2 s sit between the quiet and loud ends (5th and 95th percentiles) of the last 30 s. */
    private fun energy(): Float {
        val stored = minOf(heard, rangeFrames)
        levels.copyInto(sorted, endIndex = stored)
        sorted.sort(0, stored)
        val low = percentile(stored, 0.05f)
        val high = maxOf(percentile(stored, 0.95f), low + MIN_RANGE_DB)
        var recent = 0f
        for (i in 1..everyFrames) recent += levels[(heard - i) % rangeFrames]
        return ((recent / everyFrames - low) / (high - low)).coerceIn(0f, 1f)
    }

    /** Linear interpolation between order statistics of the first [count] sorted values, as numpy's percentile does. */
    private fun percentile(count: Int, quantile: Float): Float {
        val position = quantile * (count - 1)
        val low = position.toInt()
        return if (low + 1 < count) sorted[low] + (sorted[low + 1] - sorted[low]) * (position - low) else sorted[low]
    }

    companion object {
        /**
         * The feel for the smoothed [drive] and [beat], given the [current] one: lively with drums
         * and a clear beat, calm without drums, moderate otherwise; a feel is kept until the values
         * move past its threshold by the margin.
         */
        fun decide(current: Feel, drive: Double, beat: Double): Feel {
            val staysLively = drive >= LIVELY_DRIVE_DB - DRIVE_MARGIN_DB && beat >= LIVELY_BEAT - BEAT_MARGIN
            return when {
                current == Feel.LIVELY && staysLively -> Feel.LIVELY
                current == Feel.CALM && drive < CALM_DRIVE_DB + DRIVE_MARGIN_DB -> Feel.CALM
                drive >= LIVELY_DRIVE_DB && beat >= LIVELY_BEAT -> Feel.LIVELY
                drive < CALM_DRIVE_DB -> Feel.CALM
                else -> Feel.MODERATE
            }
        }

        const val WINDOW_SECONDS = 6f
        const val EVERY_SECONDS = 2f
        const val RANGE_SECONDS = 30f
        const val SLOWEST_BPM = 60f
        const val FASTEST_BPM = 180f
        const val PRIOR_BPM = 120.0

        /** Share of the gap each new measurement closes: a time constant of about 5 s. */
        const val SMOOTHING = 1.0 / 3

        /**
         * Drums drive the song: their bands rise this much a frame on average... (five of the six
         * drum tracks tried passed it; between [CALM_DRIVE_DB] and this a song is moderate)
         */
        const val LIVELY_DRIVE_DB = 2.0

        /**
         * ...and the rises repeat at least this regularly. 6 s of noise reach about 0.16 by chance
         * (0.31 in one window in a hundred), talk 0.19 and the car recordings 0.22; drumless piano
         * can pass it, and its low drive keeps it calm.
         */
        const val LIVELY_BEAT = 0.35

        /** Below this the song has no drums to speak of: it sits between the tracks without drums (up to 1.17) and with them (from 1.84). */
        const val CALM_DRIVE_DB = 1.4

        /** How far past its threshold the values must move before a feel is left. */
        const val DRIVE_MARGIN_DB = 0.2
        const val BEAT_MARGIN = 0.05

        /** A song that hardly changes still reads its level against this range, so small wobbles stay small. */
        const val MIN_RANGE_DB = 6f
    }
}
