package com.carstage.app.audio.echo

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds how long the reference takes to come back in the microphone, 0 to [maxDelayMs], by
 * cross-correlating the two (GCC-PHAT). Both are first box-averaged down to about 4 kHz, which is
 * plenty for a bulk delay that the adaptive filter then refines to the sample. The cross-spectrum
 * is averaged over the last few windows, so a voice over the music or a quiet bar does not move
 * the peak; PHAT whitening keeps the peak sharp for music whose spectrum is dominated by the bass.
 *
 * [push] takes one sample of each at the full rate; every [HOP_MS] a new estimate is made and
 * [delaySamples] (at the full rate) changes when it is confident. Not thread-safe.
 */
internal class DelayEstimator(sampleRate: Int, maxDelayMs: Int) {
    private val decimation = max(1, (sampleRate / TARGET_RATE.toDouble()).roundToInt())
    private val rate = sampleRate.toDouble() / decimation
    private val maxLag = (maxDelayMs * rate / 1_000).roundToInt()
    private val window = Integer.highestOneBit((WINDOW_MS * rate / 1_000).roundToInt() - 1) shl 1
    private val hop = (HOP_MS * rate / 1_000).roundToInt()
    private val fft = RealFft(Integer.highestOneBit(window + maxLag) * 2)

    // Decimated history, long enough for one window of the microphone and the reference before it.
    private val history = Integer.highestOneBit(window + maxLag) * 2
    private val micRing = DoubleArray(history)
    private val refRing = DoubleArray(history)
    private var written = 0L

    private var micSum = 0.0
    private var refSum = 0.0
    private var summed = 0
    private var sinceEstimate = 0

    private val frame = DoubleArray(fft.size)
    private val micRe = DoubleArray(fft.bins)
    private val micIm = DoubleArray(fft.bins)
    private val refRe = DoubleArray(fft.bins)
    private val refIm = DoubleArray(fft.bins)
    private val crossRe = DoubleArray(fft.bins)
    private val crossIm = DoubleArray(fft.bins)
    private val correlation = DoubleArray(fft.size)
    private var averaged = 0

    /** The last confident delay at the full rate, or -1 before the first. */
    var delaySamples = -1
        private set

    /** How far the last peak stood out of the other lags (peak over their RMS), for the log. */
    var confidence = 0.0
        private set

    /** Estimates made so far, confident or not. */
    var estimates = 0
        private set

    fun push(mic: Double, reference: Double) {
        micSum += mic
        refSum += reference
        if (++summed < decimation) return
        val at = (written % history).toInt()
        micRing[at] = micSum / decimation
        refRing[at] = refSum / decimation
        micSum = 0.0
        refSum = 0.0
        summed = 0
        written++
        if (++sinceEstimate >= hop && written >= window) {
            sinceEstimate = 0
            estimate()
        }
    }

    fun reset() {
        micRing.fill(0.0)
        refRing.fill(0.0)
        crossRe.fill(0.0)
        crossIm.fill(0.0)
        written = 0
        micSum = 0.0
        refSum = 0.0
        summed = 0
        sinceEstimate = 0
        averaged = 0
        delaySamples = -1
        confidence = 0.0
        estimates = 0
    }

    private fun estimate() {
        estimates++
        // The microphone's last window at the start of the frame, the reference from maxLag
        // earlier: correlation[k] then lines the microphone up with the reference delayed by
        // maxLag - k.
        val end = written
        var micEnergy = 0.0
        var refEnergy = 0.0
        frame.fill(0.0)
        for (i in 0 until window) {
            val v = micRing[((end - window + i) % history).toInt()]
            frame[i] = v
            micEnergy += v * v
        }
        fft.forward(frame, micRe, micIm)
        frame.fill(0.0)
        val refSpan = window + maxLag
        for (i in 0 until refSpan) {
            val at = end - refSpan + i
            val v = if (at < 0) 0.0 else refRing[(at % history).toInt()]
            frame[i] = v
            refEnergy += v * v
        }
        if (micEnergy < SILENT_ENERGY * window || refEnergy < SILENT_ENERGY * refSpan) return
        fft.forward(frame, refRe, refIm)

        // conj(MIC) * REF, each window whitened so a loud bar does not outweigh the others.
        var norm = 0.0
        for (k in 0 until fft.bins) {
            val re = micRe[k] * refRe[k] + micIm[k] * refIm[k]
            val im = micRe[k] * refIm[k] - micIm[k] * refRe[k]
            micRe[k] = re
            micIm[k] = im
            norm += hypot(re, im)
        }
        if (norm <= 0.0 || !norm.isFinite()) return
        val keep = if (averaged == 0) 0.0 else AVERAGE_KEEP
        val scale = fft.bins / norm
        for (k in 0 until fft.bins) {
            crossRe[k] = keep * crossRe[k] + (1 - keep) * micRe[k] * scale
            crossIm[k] = keep * crossIm[k] + (1 - keep) * micIm[k] * scale
        }
        averaged++
        for (k in 0 until fft.bins) {
            val magnitude = hypot(crossRe[k], crossIm[k]) + 1e-12
            refRe[k] = crossRe[k] / magnitude
            refIm[k] = crossIm[k] / magnitude
        }
        fft.inverse(refRe, refIm, correlation)

        var best = 0
        var sumSquares = 0.0
        for (k in 0..maxLag) {
            val c = correlation[k]
            sumSquares += c * c
            if (c > correlation[best]) best = k
        }
        val peak = correlation[best]
        // RMS of the lags away from the peak.
        var near = 0.0
        for (k in max(0, best - 2)..minOf(maxLag, best + 2)) near += correlation[k] * correlation[k]
        val others = (maxLag + 1 - minOf(5, maxLag + 1)).coerceAtLeast(1)
        val rms = sqrt(max(sumSquares - near, 0.0) / others)
        confidence = if (rms > 0) peak / rms else 0.0
        if (peak > 0 && confidence >= CONFIDENT) {
            val lag = maxLag - best
            delaySamples = lag * decimation
        }
    }

    companion object {
        private const val TARGET_RATE = 4_000
        private const val WINDOW_MS = 500
        const val HOP_MS = 250

        /** Share of the earlier windows in the averaged cross-spectrum (about four windows). */
        private const val AVERAGE_KEEP = 0.75

        /** Peak over the other lags' RMS needed to believe a delay. */
        private const val CONFIDENT = 6.0

        /** Mean square below about -80 dBFS: nothing to correlate. */
        private const val SILENT_ENERGY = 1e-8
    }
}
