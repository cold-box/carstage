package com.carstage.app.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Magnitude spectrum of one frame: a Hann window and an in-place radix-2 FFT. Buffers are kept
 * between frames, so the audio thread allocates nothing; one instance per thread.
 */
class Spectrum(val size: Int) {
    init {
        require(size >= 2 && size and (size - 1) == 0) { "size must be a power of two: $size" }
    }

    /** Bins from 0 Hz to half the sample rate, each sampleRate / [size] Hz wide. */
    val bins = size / 2 + 1

    private val window = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / size) }

    /** Sum of the squared window, to turn bin energy back into the frame's mean power. */
    val windowEnergy = window.sumOf { it * it }

    private val re = DoubleArray(size)
    private val im = DoubleArray(size)
    private val reversed = IntArray(size).also { table ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) table[i] = Integer.reverse(i) ushr (32 - bits)
    }
    private val cosines = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sines = DoubleArray(size / 2) { -sin(2 * PI * it / size) }

    /** Writes the magnitude of each bin of [frame] (exactly [size] samples) into [out] ([bins] long). */
    fun magnitudes(frame: FloatArray, out: FloatArray) {
        require(frame.size == size && out.size == bins) { "need $size samples and $bins bins" }
        for (i in 0 until size) {
            re[reversed[i]] = frame[i] * window[i]
            im[reversed[i]] = 0.0
        }
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var start = 0
            while (start < size) {
                for (k in 0 until half) {
                    val wr = cosines[k * step]
                    val wi = sines[k * step]
                    val a = start + k
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                start += half * 2
            }
            half *= 2
        }
        for (k in 0 until bins) out[k] = sqrt(re[k] * re[k] + im[k] * im[k]).toFloat()
    }
}
