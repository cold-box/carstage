package com.carstage.app.audio.echo

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Takes down the echo the adaptive filter leaves (the part it has not learnt yet, the speaker's
 * distortion, the reverb past its length) with a gain per frequency, like a noise suppressor whose
 * "noise" is the echo estimate scaled by the filter's leak. Voices that stand above that estimate
 * keep a gain near 1 (Wiener gain with a decision-directed a priori SNR, Ephraim and Malah 1984),
 * so they are not thinned, and where the echo dominates the gain stops at [GAIN_FLOOR].
 *
 * The echo estimate only covers what the filter has learnt. When the music moves to notes it has
 * not played yet, their echo is not in it, so a second estimate predicts the echo from the
 * reference itself: the cabin's coupling (echo estimate over reference, averaged over time and
 * over neighbouring frequencies, about a quarter octave) times the reference, less what the
 * filter already predicts. A cabin's response is smooth across frequency, so the learnt notes
 * tell how loud the new ones will come back.
 *
 * Works on half-overlapping frames of two blocks with a square-root Hann window on both sides, so
 * its output is one block late. Not thread-safe.
 */
internal class ResidualEchoSuppressor(private val blockSize: Int) {
    private val n = blockSize
    private val fft = RealFft(2 * n)
    private val bins = fft.bins
    private val window = DoubleArray(2 * n) { sin(PI * it / (2 * n)) }

    private val errorFrame = DoubleArray(2 * n)
    private val echoFrame = DoubleArray(2 * n)
    private val refFrame = DoubleArray(2 * n)
    private val time = DoubleArray(2 * n)
    private val errRe = DoubleArray(bins)
    private val errIm = DoubleArray(bins)
    private val echoRe = DoubleArray(bins)
    private val echoIm = DoubleArray(bins)
    private val refRe = DoubleArray(bins)
    private val refIm = DoubleArray(bins)
    private val echoPower = DoubleArray(bins)
    private val refPower = DoubleArray(bins)
    private val echoAverage = DoubleArray(bins)
    private val refAverage = DoubleArray(bins)
    private val echoCumulative = DoubleArray(bins + 1)
    private val refCumulative = DoubleArray(bins + 1)
    private val residual = DoubleArray(bins)
    private val gain = DoubleArray(bins) { 1.0 }
    private val lastPosterior = DoubleArray(bins)
    private val overlap = DoubleArray(n)

    fun reset() {
        errorFrame.fill(0.0)
        echoFrame.fill(0.0)
        refFrame.fill(0.0)
        echoAverage.fill(0.0)
        refAverage.fill(0.0)
        residual.fill(0.0)
        gain.fill(1.0)
        lastPosterior.fill(0.0)
        overlap.fill(0.0)
    }

    /**
     * Takes the next block of the filter's output [error], of the echo it removed [echo] and of
     * the aligned [reference] (all [blockSize] samples), with [leak] the share of the echo the
     * filter misses, and writes the previous block, suppressed, into [out].
     */
    fun process(error: DoubleArray, echo: DoubleArray, reference: DoubleArray, leak: Double, out: DoubleArray) {
        spectrum(errorFrame, error, errRe, errIm)
        spectrum(echoFrame, echo, echoRe, echoIm)
        spectrum(refFrame, reference, refRe, refIm)

        for (k in 0 until bins) {
            echoPower[k] = echoRe[k] * echoRe[k] + echoIm[k] * echoIm[k]
            refPower[k] = refRe[k] * refRe[k] + refIm[k] * refIm[k]
            echoAverage[k] += AVERAGE * (echoPower[k] - echoAverage[k])
            refAverage[k] += AVERAGE * (refPower[k] - refAverage[k])
            echoCumulative[k + 1] = echoCumulative[k] + echoAverage[k]
            refCumulative[k + 1] = refCumulative[k] + refAverage[k]
        }

        val share = OVERESTIMATE * leak
        for (k in 0 until bins) {
            val errorPower = errRe[k] * errRe[k] + errIm[k] * errIm[k]
            // Echo of notes the filter has not learnt: coupling around k times the reference.
            val width = max(MIN_HALF_BAND, k / 8)
            val low = max(0, k - width)
            val high = minOf(bins, k + width + 1)
            val coupling = (echoCumulative[high] - echoCumulative[low]) / (refCumulative[high] - refCumulative[low] + TINY)
            val unlearnt = max(coupling * refPower[k] - echoPower[k], 0.0)
            val missed = max(share * echoPower[k], unlearnt)
            // The room keeps ringing after the echo estimate drops: let the estimate fall slowly.
            residual[k] = max(missed, RESIDUAL_DECAY * residual[k])
            val posterior = errorPower / (residual[k] + TINY)
            val prior = DIRECTED * gain[k] * gain[k] * lastPosterior[k] + (1 - DIRECTED) * max(posterior - 1, 0.0)
            gain[k] = (prior / (1 + prior)).coerceIn(GAIN_FLOOR, 1.0)
            lastPosterior[k] = posterior
            errRe[k] *= gain[k]
            errIm[k] *= gain[k]
        }
        fft.inverse(errRe, errIm, time)
        for (i in 0 until n) {
            out[i] = overlap[i] + time[i] * window[i]
            overlap[i] = time[n + i] * window[n + i]
        }
    }

    private fun spectrum(frame: DoubleArray, block: DoubleArray, re: DoubleArray, im: DoubleArray) {
        frame.copyInto(frame, 0, n, 2 * n)
        block.copyInto(frame, n)
        for (i in 0 until 2 * n) time[i] = frame[i] * window[i]
        fft.forward(time, re, im)
    }

    private companion object {
        /** Per block: the coupling is averaged over about half a second. */
        const val AVERAGE = 0.02

        /** Bins on each side for the coupling at low frequencies (about ±190 Hz at 48 kHz). */
        const val MIN_HALF_BAND = 4

        /** The leak is a mean over all frequencies; per frequency the residual can be well above it. */
        const val OVERESTIMATE = 2.0

        /** Per block (about 11 ms at 48 kHz): -20 dB in about 0.12 s, a car's reverb. */
        const val RESIDUAL_DECAY = 0.65

        /** Weight of the last frame's clean estimate in the a priori SNR. */
        const val DIRECTED = 0.9

        /** -30 dB: deep enough for the analyzer, shallow enough not to gate a voice's onset. */
        const val GAIN_FLOOR = 0.0316

        const val TINY = 1e-20
    }
}
