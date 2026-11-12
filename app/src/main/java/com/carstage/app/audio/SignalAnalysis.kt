package com.carstage.app.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

object SignalAnalysis {
    const val SILENCE_DB = -100f

    fun rmsDbfs(samples: FloatArray, from: Int = 0, to: Int = samples.size): Float {
        if (to <= from) return SILENCE_DB
        var sum = 0.0
        for (i in from until to) {
            val s = samples[i]
            sum += s * s
        }
        return toDbfs(sqrt(sum / (to - from)))
    }

    fun peakDbfs(samples: FloatArray): Float {
        var peak = 0f
        for (s in samples) peak = maxOf(peak, abs(s))
        return toDbfs(peak.toDouble())
    }

    private fun toDbfs(amplitude: Double): Float =
        if (amplitude <= 1e-9) SILENCE_DB else (20 * log10(amplitude)).toFloat().coerceAtLeast(SILENCE_DB)

    private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    fun noteName(hz: Float): String {
        val midi = (69 + 12 * log2(hz / 440.0)).roundToInt()
        return NOTE_NAMES[Math.floorMod(midi, 12)] + (Math.floorDiv(midi, 12) - 1)
    }
}

/** YIN fundamental-frequency estimator for monophonic voice, tuned for the singing range. */
class YinPitchDetector(
    private val sampleRate: Int,
    minHz: Float = 70f,
    maxHz: Float = 1_100f,
    private val threshold: Float = 0.15f,
) {
    private val tauMin = (sampleRate / maxHz).toInt().coerceAtLeast(2)
    private val tauMax = (sampleRate / minHz).toInt()

    /** Returns the fundamental frequency in Hz, or null when the frame is unvoiced. */
    fun detect(frame: FloatArray): Float? {
        val half = frame.size / 2
        val maxTau = minOf(tauMax, half - 1)
        if (maxTau <= tauMin) return null

        val normalized = FloatArray(maxTau + 1)
        normalized[0] = 1f
        var runningSum = 0f
        for (tau in 1..maxTau) {
            var diff = 0f
            for (i in 0 until half) {
                val d = frame[i] - frame[i + tau]
                diff += d * d
            }
            runningSum += diff
            normalized[tau] = if (runningSum == 0f) 1f else diff * tau / runningSum
        }

        var tau = tauMin
        while (tau <= maxTau) {
            if (normalized[tau] < threshold) {
                while (tau + 1 <= maxTau && normalized[tau + 1] < normalized[tau]) tau++
                return sampleRate / interpolate(normalized, tau, maxTau)
            }
            tau++
        }
        return null
    }

    private fun interpolate(values: FloatArray, tau: Int, maxTau: Int): Float {
        if (tau <= 1 || tau >= maxTau) return tau.toFloat()
        val left = values[tau - 1]
        val center = values[tau]
        val right = values[tau + 1]
        val denominator = left - 2 * center + right
        return if (denominator == 0f) tau.toFloat() else tau + (left - right) / (2 * denominator)
    }
}

/** Energy-rise onset detector used to count claps and other sharp transients. */
class OnsetDetector(
    private val sampleRate: Int,
    private val hopSize: Int = 256,
    private val riseDb: Float = 12f,
    private val floorDb: Float = -40f,
    private val refractoryMs: Int = 150,
) {
    private var averageDb = SignalAnalysis.SILENCE_DB
    private var lastOnsetSample = -sampleRate.toLong()
    private var position = 0L

    /** Returns how many onsets start inside [frame]. */
    fun process(frame: FloatArray): Int {
        var onsets = 0
        var start = 0
        while (start + hopSize <= frame.size) {
            val db = SignalAnalysis.rmsDbfs(frame, start, start + hopSize)
            val sample = position + start
            val inRefractory = (sample - lastOnsetSample) * 1000 / sampleRate < refractoryMs
            if (!inRefractory && db > floorDb && db - averageDb > riseDb) {
                onsets++
                lastOnsetSample = sample
            }
            averageDb = if (averageDb == SignalAnalysis.SILENCE_DB) db else averageDb * 0.9f + db * 0.1f
            start += hopSize
        }
        position += frame.size
        return onsets
    }
}
