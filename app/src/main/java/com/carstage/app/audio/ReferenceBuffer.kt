package com.carstage.app.audio

import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Lines up what the other apps play (the reference, from a [PlaybackSource]) with the
 * microphone's frames for the echo canceller. The capture thread [write]s blocks as they arrive,
 * at the capture's rate and channels; the microphone thread [read]s, for each frame, a block of
 * the same length, mono at the microphone's [sampleRate]. Synchronized, as the two threads differ.
 *
 * The alignment is rough on purpose, since the canceller follows the delay itself
 * (EchoCanceller.MAX_DELAY_MS). Each write says when its last sample arrived, which puts the
 * reference on the clock; a read takes the samples that had arrived [HOLD_MS] before the
 * microphone frame did, so a block that is a little late is still there. The read cursor then
 * moves on by exactly what was read, so the canceller hears one unbroken signal, and jumps only
 * when it falls more than [RESYNC_MS] behind the clock or runs more than [AHEAD_MS] ahead of it. A gap in the capture longer than
 * [GAP_MS] is filled with silence, so what follows stays on the clock; samples not there yet or
 * already overwritten read as silence. All of it is counted for the log. Nothing is kept beyond
 * the last [capacityMs].
 */
class ReferenceBuffer(
    val sampleRate: Int,
    capacityMs: Int = CAPACITY_MS,
    /** How long before a microphone frame its reference must have arrived ([HOLD_MS] by default). */
    private val holdMs: Int = HOLD_MS,
) {
    private val ring = FloatArray(sampleRate * capacityMs / 1_000)
    private val nanosPerSample = 1e9 / sampleRate

    /** Mono samples at [sampleRate] written so far, gaps included. */
    private var written = 0L

    /** The clock time of sample 0, NaN before the first write. */
    private var originNanos = Double.NaN
    private var cursor = NO_CURSOR

    private var inputRate = 0
    private var previous = 0f
    private var phase = 0.0
    private var mono = FloatArray(0)

    /** Samples a read wanted that were not there: not yet arrived, or overwritten. */
    var missing = 0L
        private set

    /** Samples read in all. */
    var read = 0L
        private set

    /** Gaps in the capture filled with silence. */
    var gaps = 0
        private set

    /** Times the read cursor jumped back onto the clock. */
    var resyncs = 0
        private set

    /**
     * Adds [count] interleaved 16-bit samples of [channels] channels at [rate], the last of which
     * arrived at [arrivedNanos] (System.nanoTime). Mixed down to mono and resampled to [sampleRate].
     */
    @Synchronized
    fun write(pcm: ShortArray, count: Int, channels: Int, rate: Int, arrivedNanos: Long) {
        require(channels >= 1 && rate > 0) { "bad format: $channels channels at $rate Hz" }
        val frames = count / channels
        if (frames == 0) return
        if (rate != inputRate) {
            inputRate = rate
            phase = 0.0
        }
        if (mono.size < frames) mono = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += pcm[i * channels + c]
            mono[i] = sum / (channels * 32_768f)
        }
        val step = rate.toDouble() / sampleRate
        // Output count for this block, to place it on the clock before it is written.
        val outputs = if (phase >= frames) 0 else floor((frames - phase - 1e-9) / step).toInt() + 1

        val candidate = arrivedNanos - (written + outputs) * nanosPerSample
        if (originNanos.isNaN()) {
            originNanos = candidate
        } else {
            val late = candidate - originNanos
            when {
                // Nothing arrived for a while (the capture stalled): silence keeps what follows on the clock.
                late > GAP_MS * 1e6 -> {
                    val silence = (late / nanosPerSample).roundToLong()
                    repeat(minOf(silence, ring.size.toLong()).toInt()) { put(0f) }
                    written += silence - minOf(silence, ring.size.toLong())
                    gaps++
                }
                // A block that came early shows the delay is shorter than thought: take it at once.
                late < 0 -> originNanos = candidate
                // A late one is mostly scheduling, so the clock only creeps after it (and after a drift).
                else -> originNanos += late * CREEP
            }
        }

        // Linear interpolation from the last sample of the block before (position 0) through this one (1..frames).
        var t = phase
        while (t < frames) {
            val i = floor(t).toInt()
            val frac = (t - i).toFloat()
            val a = if (i == 0) previous else mono[i - 1]
            val b = mono[i]
            put(a + (b - a) * frac)
            t += step
        }
        phase = t - frames
        previous = mono[frames - 1]
    }

    /**
     * Fills [out] with [length] samples of the reference that played while the microphone frame
     * ending at [endNanos] (System.nanoTime) was heard, as the read cursor goes.
     */
    @Synchronized
    fun read(out: FloatArray, length: Int, endNanos: Long) {
        if (originNanos.isNaN()) {
            out.fill(0f, 0, length)
            missing += length
            read += length
            return
        }
        val target = ((endNanos - holdMs * 1e6 - originNanos) / nanosPerSample).roundToLong() - length
        if (cursor == NO_CURSOR) {
            cursor = target
        } else if (cursor - target > AHEAD_MS * sampleRate / 1_000 || target - cursor > RESYNC_MS * sampleRate / 1_000) {
            cursor = target
            resyncs++
        }
        val oldest = written - ring.size
        for (k in 0 until length) {
            val index = cursor + k
            out[k] = if (index in oldest until written && index >= 0) {
                ring[(index % ring.size).toInt()]
            } else {
                missing++
                0f
            }
        }
        cursor += length
        read += length
    }

    private fun put(value: Float) {
        ring[(written % ring.size).toInt()] = value
        written++
    }

    companion object {
        const val CAPACITY_MS = 2_000

        /** How long before a microphone frame its reference must have arrived: a capture block or two. */
        const val HOLD_MS = 60

        /**
         * How far the read cursor may drift off the clock before it jumps back. Wide, as every jump
         * is a break the canceller must learn across, and it follows a slow drift itself: on the
         * stage the microphone thread also analyzes and directs, so its frames come late in bursts,
         * and 40 ms made 35 to 74 jumps in 35 s where the Lab's bare reader made none (docs/05).
         */
        const val RESYNC_MS = 250

        /**
         * How far the cursor may run ahead of the clock: a read time is only ever late, so a cursor
         * ahead was set during a burst and would read samples not there yet, as silence; it jumps back at once.
         */
        const val AHEAD_MS = 40

        /** A pause in the capture this long is a gap, not scheduling. */
        const val GAP_MS = 150

        /** How much of a late block's lateness moves the clock: follows a drift over seconds, not one late block. */
        private const val CREEP = 0.002
        private const val NO_CURSOR = Long.MIN_VALUE
    }
}
