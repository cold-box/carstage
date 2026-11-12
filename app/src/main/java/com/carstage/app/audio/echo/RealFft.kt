package com.carstage.app.audio.echo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * FFT of a real signal of [size] samples into its [bins] = size / 2 + 1 non-negative frequencies,
 * and back. A complex FFT of half the size does the work (even samples as the real part, odd as
 * the imaginary), so it costs about half of a complex FFT of [size]. Buffers are kept between
 * calls, so the audio thread allocates nothing; one instance per thread.
 */
internal class RealFft(val size: Int) {
    init {
        require(size >= 4 && size and (size - 1) == 0) { "size must be a power of two of at least 4: $size" }
    }

    val bins = size / 2 + 1
    private val half = size / 2

    private val zr = DoubleArray(half)
    private val zi = DoubleArray(half)
    private val reversed = IntArray(half).also { table ->
        val bits = Integer.numberOfTrailingZeros(half)
        for (i in 0 until half) table[i] = if (bits == 0) 0 else Integer.reverse(i) ushr (32 - bits)
    }

    // Twiddles of the half-size complex FFT, and of the split between even and odd samples.
    private val cosHalf = DoubleArray(half / 2) { cos(2 * PI * it / half) }
    private val sinHalf = DoubleArray(half / 2) { -sin(2 * PI * it / half) }
    private val cosSplit = DoubleArray(half + 1) { cos(2 * PI * it / size) }
    private val sinSplit = DoubleArray(half + 1) { -sin(2 * PI * it / size) }

    /** Writes the spectrum of [time] ([size] samples) into [re] and [im] ([bins] long). */
    fun forward(time: DoubleArray, re: DoubleArray, im: DoubleArray) {
        for (n in 0 until half) {
            val j = reversed[n]
            zr[j] = time[2 * n]
            zi[j] = time[2 * n + 1]
        }
        butterflies(inverse = false)
        for (k in 0..half) {
            val a = k % half
            val b = (half - k) % half
            // Z[k] and conj(Z[half - k]) give the spectra of the even and the odd samples.
            val evenRe = 0.5 * (zr[a] + zr[b])
            val evenIm = 0.5 * (zi[a] - zi[b])
            val oddRe = 0.5 * (zi[a] + zi[b])
            val oddIm = -0.5 * (zr[a] - zr[b])
            val wr = cosSplit[k]
            val wi = sinSplit[k]
            re[k] = evenRe + wr * oddRe - wi * oddIm
            im[k] = evenIm + wr * oddIm + wi * oddRe
        }
    }

    /** Writes the signal of the spectrum [re], [im] ([bins] long) into [time] ([size] samples). */
    fun inverse(re: DoubleArray, im: DoubleArray, time: DoubleArray) {
        for (k in 0 until half) {
            val b = half - k
            val evenRe = 0.5 * (re[k] + re[b])
            val evenIm = 0.5 * (im[k] - im[b])
            val diffRe = 0.5 * (re[k] - re[b])
            val diffIm = 0.5 * (im[k] + im[b])
            // Undo the split twiddle: multiply by its conjugate.
            val wr = cosSplit[k]
            val wi = -sinSplit[k]
            val oddRe = diffRe * wr - diffIm * wi
            val oddIm = diffRe * wi + diffIm * wr
            val j = reversed[k]
            zr[j] = evenRe - oddIm
            zi[j] = evenIm + oddRe
        }
        butterflies(inverse = true)
        val scale = 1.0 / half
        for (n in 0 until half) {
            time[2 * n] = zr[n] * scale
            time[2 * n + 1] = zi[n] * scale
        }
    }

    private fun butterflies(inverse: Boolean) {
        val sign = if (inverse) -1.0 else 1.0
        var span = 1
        while (span < half) {
            val step = half / (span * 2)
            var start = 0
            while (start < half) {
                for (k in 0 until span) {
                    val wr = cosHalf[k * step]
                    val wi = sign * sinHalf[k * step]
                    val a = start + k
                    val b = a + span
                    val tr = zr[b] * wr - zi[b] * wi
                    val ti = zr[b] * wi + zi[b] * wr
                    zr[b] = zr[a] - tr
                    zi[b] = zi[a] - ti
                    zr[a] += tr
                    zi[a] += ti
                }
                start += span * 2
            }
            span *= 2
        }
    }
}
