// The adaptation (the learning rate from the estimated echo leak, the proportional step per
// partition, the foreground/background filter pair and their thresholds) follows the multidelay
// block frequency-domain filter of SpeexDSP's libspeexdsp/mdf.c, Copyright (C) 2003-2008
// Jean-Marc Valin, BSD 3-clause licence (see README "출처"). It was rewritten for this app rather
// than copied: float doubles only, no fixed point, one microphone and one reference, and a
// realign step for when the delay estimate moves.
package com.carstage.app.audio.echo

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Multidelay block frequency-domain adaptive filter (MDF, J.-M. Valin, "On adjusting the learning
 * rate in frequency domain echo cancellation with double-talk", IEEE TASLP 2007): learns how the
 * reference block reaches the microphone over [partitions] blocks of [blockSize] samples and
 * subtracts it.
 *
 * Two filters run side by side. The background one adapts every block, with a step that shrinks
 * when the error holds more than echo (a voice over the music, double-talk) because the step is
 * the estimated share of the error that is still echo. The foreground one makes the output and
 * takes the background's weights only when they have been doing better for a while; when the
 * background does clearly worse (it adapted on a voice) it is put back to the foreground. So a
 * voice can slow the learning but not undo it.
 *
 * Samples are in 16-bit scale (±32768) so that the constants keep Speex's meaning. Not thread-safe.
 */
internal class MdfFilter(val blockSize: Int, val partitions: Int, sampleRate: Int) {
    private val n = blockSize
    private val fft = RealFft(2 * n)
    private val bins = fft.bins
    private val frameScale = 1.0 / fft.size

    private val xRe = Array(partitions) { DoubleArray(bins) }
    private val xIm = Array(partitions) { DoubleArray(bins) }
    private var newest = 0

    private val bgRe = Array(partitions) { DoubleArray(bins) }
    private val bgIm = Array(partitions) { DoubleArray(bins) }
    private val fgRe = Array(partitions) { DoubleArray(bins) }
    private val fgIm = Array(partitions) { DoubleArray(bins) }

    private val previousX = DoubleArray(n)
    private val frame = DoubleArray(2 * n)
    private val time = DoubleArray(2 * n)
    private val specRe = DoubleArray(bins)
    private val specIm = DoubleArray(bins)
    private val errRe = DoubleArray(bins)
    private val errIm = DoubleArray(bins)

    private val yFg = DoubleArray(n)
    private val yBg = DoubleArray(n)
    private val eFg = DoubleArray(n)
    private val eBg = DoubleArray(n)

    private val power = DoubleArray(bins)
    private val step = DoubleArray(bins)
    private val echoSmooth = DoubleArray(bins)
    private val errorSmooth = DoubleArray(bins)
    private val echoPower = DoubleArray(bins)
    private val errorPower = DoubleArray(bins)
    private val prop = DoubleArray(partitions)

    private val specAverage = n.toDouble() / sampleRate
    private val beta0 = 2.0 * n / sampleRate
    private val betaMax = 0.5 * n / sampleRate
    private val smoothing = 0.35 / partitions

    private var pey = 1.0
    private var pyy = 1.0
    private var davg1 = 0.0
    private var davg2 = 0.0
    private var dvar1 = 0.0
    private var dvar2 = 0.0
    private var sumAdapt = 0.0
    private var adapted = false
    private var blocks = 0L
    private var screwedUp = 0

    /** Share of the echo the filter is estimated to miss, 0.005..1. */
    var leak = 1.0
        private set

    /** Times the filter was found broken (non-finite or growing the signal) and started again. */
    var restarts = 0
        private set

    init {
        reset()
    }

    fun reset() {
        for (m in 0 until partitions) {
            xRe[m].fill(0.0); xIm[m].fill(0.0)
            bgRe[m].fill(0.0); bgIm[m].fill(0.0)
            fgRe[m].fill(0.0); fgIm[m].fill(0.0)
        }
        previousX.fill(0.0)
        power.fill(0.0)
        echoSmooth.fill(0.0)
        errorSmooth.fill(0.0)
        pey = 1.0
        pyy = 1.0
        davg1 = 0.0; davg2 = 0.0; dvar1 = 0.0; dvar2 = 0.0
        sumAdapt = 0.0
        adapted = false
        leak = 1.0
        screwedUp = 0
        blocks = 0
        val decay = exp(-2.4 / partitions)
        var p = 0.7
        var sum = 0.0
        for (m in 0 until partitions) {
            prop[m] = p
            sum += p
            p *= decay
        }
        for (m in 0 until partitions) prop[m] = 0.8 * prop[m] / sum
    }

    /**
     * Takes the next block of the aligned reference [x] and the microphone [d] (both [blockSize]
     * samples, pre-emphasized), writes the microphone with the echo removed into [out] and the
     * echo that was removed into [echo]. [adapt] false (a clipped microphone) keeps the weights.
     */
    fun process(x: DoubleArray, d: DoubleArray, adapt: Boolean, out: DoubleArray, echo: DoubleArray) {
        // The newest partition holds the last two reference blocks.
        newest = (newest + partitions - 1) % partitions
        for (i in 0 until n) {
            frame[i] = previousX[i]
            frame[n + i] = x[i]
        }
        x.copyInto(previousX)
        forward(frame, xRe[newest], xIm[newest])

        var sxx = 0.0
        for (i in 0 until n) sxx += x[i] * x[i]

        filter(fgRe, fgIm, yFg)
        filter(bgRe, bgIm, yBg)
        var sff = 0.0
        var see = 0.0
        var sdd = 0.0
        var dbf = 10.0
        for (i in 0 until n) {
            eFg[i] = d[i] - yFg[i]
            eBg[i] = d[i] - yBg[i]
            sff += eFg[i] * eFg[i]
            see += eBg[i] * eBg[i]
            sdd += d[i] * d[i]
            val diff = eFg[i] - eBg[i]
            dbf += diff * diff
        }

        // Foreground/background: copy the background when it has been better, put it back when
        // it went wrong (Speex's VAR1_UPDATE, VAR2_UPDATE, VAR_BACKTRACK).
        val gap = sff - see
        davg1 = 0.6 * davg1 + 0.4 * gap
        davg2 = 0.85 * davg2 + 0.15 * gap
        dvar1 = 0.36 * dvar1 + 0.16 * sff * dbf
        dvar2 = 0.7225 * dvar2 + 0.0225 * sff * dbf
        val promote = gap * abs(gap) > sff * dbf || davg1 * abs(davg1) > 0.5 * dvar1 || davg2 * abs(davg2) > 0.25 * dvar2
        if (promote) {
            davg1 = 0.0; davg2 = 0.0; dvar1 = 0.0; dvar2 = 0.0
            copy(bgRe, bgIm, fgRe, fgIm)
            // Fade from the old foreground's output to the new one over the block.
            for (i in 0 until n) {
                val w = (i + 0.5) / n
                out[i] = (1 - w) * eFg[i] + w * eBg[i]
                echo[i] = (1 - w) * yFg[i] + w * yBg[i]
            }
        } else {
            val backtrack = -gap * abs(gap) > 4 * sff * dbf || -davg1 * abs(davg1) > 4 * dvar1 || -davg2 * abs(davg2) > 4 * dvar2
            if (backtrack) {
                davg1 = 0.0; davg2 = 0.0; dvar1 = 0.0; dvar2 = 0.0
                copy(fgRe, fgIm, bgRe, bgIm)
                eFg.copyInto(eBg)
                yFg.copyInto(yBg)
                see = sff
            }
            eFg.copyInto(out)
            yFg.copyInto(echo)
        }

        var syy = 0.0
        var sey = 0.0
        for (i in 0 until n) {
            syy += yBg[i] * yBg[i]
            sey += eBg[i] * yBg[i]
        }

        val broken = !(sxx.isFinite() && see.isFinite() && syy.isFinite() && sff.isFinite()) ||
            sff > n * 1e9 || syy > n * 1e9 || sxx > n * 1e9
        if (broken) {
            screwedUp += 50
        } else if (sff / 4 > sdd + n * 10_000.0 / 64) {
            screwedUp++
        } else {
            screwedUp = 0
        }
        if (screwedUp >= 50) {
            restarts++
            reset()
            d.copyInto(out)
            echo.fill(0.0)
            return
        }
        blocks++
        if (!adapt) return

        // Spectra of the error, of the echo estimate and of the reference.
        frame.fill(0.0, 0, n)
        eBg.copyInto(frame, n)
        forward(frame, errRe, errIm)
        for (k in 0 until bins) errorPower[k] = errRe[k] * errRe[k] + errIm[k] * errIm[k]
        yBg.copyInto(frame, n)
        forward(frame, specRe, specIm)
        for (k in 0 until bins) echoPower[k] = specRe[k] * specRe[k] + specIm[k] * specIm[k]
        val xr = xRe[newest]
        val xi = xIm[newest]
        for (k in 0 until bins) {
            power[k] = (1 - smoothing) * power[k] + 1 + smoothing * (xr[k] * xr[k] + xi[k] * xi[k])
        }

        // How much of the echo estimate's movement shows up in the error: the leak.
        var peyNow = 0.0
        var pyyNow = 0.0
        for (k in 0 until bins) {
            val e = errorPower[k] - errorSmooth[k]
            val y = echoPower[k] - echoSmooth[k]
            peyNow += e * y
            pyyNow += y * y
            errorSmooth[k] = (1 - specAverage) * errorSmooth[k] + specAverage * errorPower[k]
            echoSmooth[k] = (1 - specAverage) * echoSmooth[k] + specAverage * echoPower[k]
        }
        pyyNow = sqrt(pyyNow)
        if (pyyNow > 0) peyNow /= pyyNow
        val alpha = if (see > 0) min(beta0 * syy, betaMax * see) / see else 0.0
        pey = (1 - alpha) * pey + alpha * peyNow
        pyy = (1 - alpha) * pyy + alpha * pyyNow
        if (pyy < 1) pyy = 1.0
        pey = pey.coerceIn(MIN_LEAK * pyy, pyy)
        leak = pey / pyy

        // Residual-to-error ratio: how much of the error is echo the filter still misses.
        var rer = (0.0001 * sxx + 3 * leak * syy) / (see + 1)
        val floor = sey * sey / (1 + see * syy)
        if (rer < floor) rer = floor
        if (rer > 0.5) rer = 0.5

        if (!adapted && sumAdapt > partitions && leak > 0.03) adapted = true
        if (adapted) {
            for (k in 0 until bins) {
                val e = errorPower[k] + 1
                var r = leak * echoPower[k]
                if (r > 0.5 * e) r = 0.5 * e
                r = 0.7 * r + 0.3 * rer * e
                step[k] = r / (e * (power[k] + 10))
            }
        } else {
            var rate = 0.0
            if (sxx > n * 1_000.0 && see > 0) rate = min(0.25 * sxx, 0.25 * see) / see
            for (k in 0 until bins) step[k] = rate / (power[k] + 10)
            sumAdapt += rate
        }

        adjustProp()
        for (m in 0 until partitions) {
            val p = (newest + m) % partitions
            val ar = xRe[p]
            val ai = xIm[p]
            val wr = bgRe[m]
            val wi = bgIm[m]
            val scale = prop[m]
            for (k in 0 until bins) {
                // conj(X) * E, scaled by the step.
                val g = scale * step[k]
                wr[k] += g * (ar[k] * errRe[k] + ai[k] * errIm[k])
                wi[k] += g * (ar[k] * errIm[k] - ai[k] * errRe[k])
            }
        }
        // Keep the weights a linear (not circular) convolution: the first partition every block,
        // one more in turn (Speex's AUMDF).
        constrain(0)
        if (partitions > 1) constrain(1 + (blocks % (partitions - 1)).toInt())
    }

    /**
     * Moves the filter by [delta] samples after the reference's alignment moved by as much (a
     * later reference needs the taps earlier), so a small change of the delay keeps what was
     * learnt. [referenceFrame] gives the two-block reference frame that is [age] blocks old under
     * the new alignment (age 0 is the newest), to rebuild the reference history.
     */
    fun realign(delta: Int, referenceFrame: (age: Int, into: DoubleArray) -> Unit) {
        val taps = DoubleArray(partitions * n)
        for ((re, im) in listOf(fgRe to fgIm, bgRe to bgIm)) {
            for (m in 0 until partitions) {
                inverse(re[m], im[m], time)
                time.copyInto(taps, m * n, 0, n)
            }
            for (m in 0 until partitions) {
                frame.fill(0.0)
                for (i in 0 until n) {
                    val src = m * n + i + delta
                    frame[i] = if (src in taps.indices) taps[src] else 0.0
                }
                forward(frame, re[m], im[m])
            }
        }
        for (age in 0 until partitions) {
            referenceFrame(age, frame)
            forward(frame, xRe[(newest + age) % partitions], xIm[(newest + age) % partitions])
            if (age == 0) frame.copyInto(previousX, 0, n, 2 * n)
        }
    }

    private fun filter(re: Array<DoubleArray>, im: Array<DoubleArray>, y: DoubleArray) {
        specRe.fill(0.0)
        specIm.fill(0.0)
        for (m in 0 until partitions) {
            val p = (newest + m) % partitions
            val ar = xRe[p]
            val ai = xIm[p]
            val wr = re[m]
            val wi = im[m]
            for (k in 0 until bins) {
                specRe[k] += wr[k] * ar[k] - wi[k] * ai[k]
                specIm[k] += wr[k] * ai[k] + wi[k] * ar[k]
            }
        }
        inverse(specRe, specIm, time)
        time.copyInto(y, 0, n, 2 * n)
    }

    private fun constrain(m: Int) {
        inverse(bgRe[m], bgIm[m], time)
        time.fill(0.0, n, 2 * n)
        forward(time, bgRe[m], bgIm[m])
    }

    private fun adjustProp() {
        var maxSum = 1.0
        for (m in 0 until partitions) {
            var sum = 0.0
            val wr = bgRe[m]
            val wi = bgIm[m]
            for (k in 0 until bins) sum += wr[k] * wr[k] + wi[k] * wi[k]
            prop[m] = sqrt(sum)
            maxSum = max(maxSum, prop[m])
        }
        var total = 1.0
        for (m in 0 until partitions) {
            prop[m] += 0.1 * maxSum
            total += prop[m]
        }
        for (m in 0 until partitions) prop[m] = 0.99 * prop[m] / total
    }

    private fun copy(fromRe: Array<DoubleArray>, fromIm: Array<DoubleArray>, toRe: Array<DoubleArray>, toIm: Array<DoubleArray>) {
        for (m in 0 until partitions) {
            fromRe[m].copyInto(toRe[m])
            fromIm[m].copyInto(toIm[m])
        }
    }

    // Speex's convention: the forward transform is scaled by 1 / size, the inverse is not.
    private fun forward(t: DoubleArray, re: DoubleArray, im: DoubleArray) {
        fft.forward(t, re, im)
        for (k in 0 until bins) {
            re[k] *= frameScale
            im[k] *= frameScale
        }
    }

    private fun inverse(re: DoubleArray, im: DoubleArray, t: DoubleArray) {
        fft.inverse(re, im, t)
        val size = fft.size.toDouble()
        for (i in t.indices) t[i] *= size
    }

    private companion object {
        const val MIN_LEAK = 0.005
    }
}
