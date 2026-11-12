package com.carstage.app.audio

/**
 * Estimates the accompaniment in each frame's spectrum, the way noise suppressors estimate noise
 * (minimum statistics): per frequency bin, the [quantile] of the last [history] frames, capped by
 * the frame itself. Sustained music stays in that estimate; a voice, which comes and goes with
 * syllables and breaths, mostly stays above it. A note held for longer than about 80 % of the
 * history (about 1.2 s at the defaults) sinks into the estimate.
 *
 * An offline version of this estimate, on the team's test recordings, followed the
 * accompaniment that Demucs separated: the voice-to-accompaniment ratio correlated 0.77 and 0.82
 * per frame, 0.82 and 0.88 over 1 s means (docs/09).
 */
class Accompaniment(private val bins: Int, private val history: Int = HISTORY_FRAMES, private val quantile: Float = QUANTILE) {
    private val past = FloatArray(history * bins)
    private val column = FloatArray(history)
    private var stored = 0
    private var next = 0

    /** Adds [magnitudes] (one frame, [bins] long) to the history and writes the accompaniment estimate into [out]. */
    fun estimate(magnitudes: FloatArray, out: FloatArray) {
        require(magnitudes.size == bins && out.size == bins) { "need $bins bins" }
        System.arraycopy(magnitudes, 0, past, next * bins, bins)
        next = (next + 1) % history
        if (stored < history) stored++
        // Linear interpolation between order statistics, as numpy's percentile does.
        val position = quantile * (stored - 1)
        val low = position.toInt()
        val fraction = position - low
        for (k in 0 until bins) {
            for (i in 0 until stored) column[i] = past[i * bins + k]
            column.sort(0, stored)
            val value = if (low + 1 < stored) column[low] + (column[low + 1] - column[low]) * fraction else column[low]
            out[k] = minOf(value, magnitudes[k])
        }
    }

    companion object {
        /** About 1.5 s at 23 frames a second. */
        const val HISTORY_FRAMES = 36
        const val QUANTILE = 0.2f
    }
}
