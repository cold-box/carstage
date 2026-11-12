package com.carstage.app.audio.echo

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The app's [EchoCanceller]: takes the music the car plays out of the microphone in four steps.
 *
 * 1. [DelayEstimator] finds the bulk delay between the reference and its echo (0 to
 *    [EchoCanceller.MAX_DELAY_MS]) by cross-correlation, and the reference is read that much
 *    later, less [MARGIN_MS] so the filter also sees the echo's onset. When the estimate moves
 *    (the output or capture buffers changed), the filter is shifted with it instead of starting
 *    again, so a slow drift is followed.
 * 2. [MdfFilter] learns the cabin's response over [TAIL_MS] after that (the speaker, the
 *    reflections) and subtracts the echo it predicts, slowing its learning during double-talk.
 * 3. [ResidualEchoSuppressor] takes down what the filter missed, frequency by frequency, without
 *    thinning a voice that stands above the echo estimate.
 * 4. The output's energy is never above the microphone's, block by block, and samples stay in
 *    -1..1; anything that is not a finite number counts as 0.
 *
 * The whole band is processed at [sampleRate] (48 kHz in the app): the analyzer's cheer and drum
 * bands reach 12 kHz, the cost is a few percent of one core (docs/09 "에코 제거"), and no resampler
 * adds its own delay and aliasing. Blocks are [blockSize] samples (about 11 ms); the output is
 * [LATENCY_BLOCKS] blocks ([latency] samples, about 21 ms at 48 kHz) behind the input, the same
 * for every call however long. While the reference has been silent for the whole span the filter
 * looks at, the microphone passes through unchanged (only that much later).
 *
 * Use: one instance per microphone stream, on the audio thread, the reference captured from the
 * other app's playback mixed to mono at the same rate. Feed each microphone block with the
 * reference received over the same time:
 * ```
 * val canceller = CabinEchoCanceller(48_000)
 * canceller.process(micFrame, referenceFrame, cleaned)   // all three the same length
 * analyzer.onFrame(cleaned)
 * ```
 * If no reference arrived for a block, pass zeros of the same length (the canceller then passes
 * the microphone through). Call [reset] when either stream restarts. The reference must not come
 * later than the microphone: a reference that lags its echo is not found.
 */
class CabinEchoCanceller(override val sampleRate: Int = 48_000) : EchoCanceller {
    init {
        require(sampleRate in 8_000..96_000) { "unsupported sample rate $sampleRate" }
    }

    /** Samples per block: a power of two near [sampleRate] / 80 (512 at 48 kHz). */
    val blockSize = Integer.highestOneBit(sampleRate / 80)

    /** How far the output is behind the input, in samples. */
    val latency = LATENCY_BLOCKS * blockSize

    private val n = blockSize
    private val partitions = ceil(TAIL_MS * sampleRate / 1_000.0 / n).toInt()
    private val margin = MARGIN_MS * sampleRate / 1_000
    private val maxDelay = EchoCanceller.MAX_DELAY_MS * sampleRate / 1_000

    private val filter = MdfFilter(n, partitions, sampleRate)
    private val suppressor = ResidualEchoSuppressor(n)
    private val delay = DelayEstimator(sampleRate, EchoCanceller.MAX_DELAY_MS)

    // Pre-emphasized reference in 16-bit scale, far enough back for the longest delay and the
    // filter's whole history.
    private val refRing = DoubleArray(Integer.highestOneBit(maxDelay + (partitions + 3) * n) * 2)
    private var refWritten = 0L
    private var refPrevious = 0.0

    private val micBlock = FloatArray(n)
    private var micFill = 0
    private val micPrevious = FloatArray(n)
    private var notchIn = 0.0
    private var notchOut = 0.0
    private var micEmphasis = 0.0

    private val x = DoubleArray(n)
    private val d = DoubleArray(n)
    private val error = DoubleArray(n)
    private val echo = DoubleArray(n)
    private val reference = DoubleArray(n)
    private var refDeemphasis = 0.0
    private val suppressed = DoubleArray(n)
    private var errorDeemphasis = 0.0
    private var echoDeemphasis = 0.0

    private val output = FloatArray(n * (LATENCY_BLOCKS + 1))
    private var outRead = 0
    private var outWrite = 0

    private var refPeak = 0.0
    private var silentBlocks = Int.MAX_VALUE / 2
    private var lastEstimates = 0
    private var pending = -1
    private var pendingAgreed = 0

    /** Where the reference is read from: this many samples before the microphone. */
    var alignment = 0
        private set

    /** Whether a delay was found since the start or the last [reset]. */
    var locked = false
        private set

    /** Blocks processed with the filter (not passed through), for the log. */
    var activeBlocks = 0L
        private set

    /** Times the filter moved with a new delay estimate, for the log. */
    var realignments = 0
        private set

    /** Times the filter broke (numbers out of range) and started again, for the log. */
    val restarts: Int get() = filter.restarts

    /** The delay estimator's last confidence, for the log. */
    val delayConfidence: Double get() = delay.confidence

    init {
        reset()
    }

    override fun reset() {
        filter.reset()
        suppressor.reset()
        delay.reset()
        refRing.fill(0.0)
        refWritten = 0
        refPrevious = 0.0
        micFill = 0
        micPrevious.fill(0f)
        notchIn = 0.0
        notchOut = 0.0
        micEmphasis = 0.0
        errorDeemphasis = 0.0
        echoDeemphasis = 0.0
        refDeemphasis = 0.0
        output.fill(0f)
        outRead = 0
        outWrite = n
        refPeak = 0.0
        silentBlocks = Int.MAX_VALUE / 2
        lastEstimates = 0
        pending = -1
        pendingAgreed = 0
        alignment = 0
        locked = false
        activeBlocks = 0
        realignments = 0
    }

    override fun process(mic: FloatArray, reference: FloatArray, out: FloatArray, length: Int) {
        require(length in 0..mic.size && length <= reference.size && length <= out.size) {
            "length $length does not fit mic ${mic.size}, reference ${reference.size}, out ${out.size}"
        }
        for (i in 0 until length) {
            val m = clean(mic[i])
            val r = clean(reference[i])
            delay.push(m.toDouble(), r.toDouble())
            val scaled = r * SCALE
            refRing[(refWritten % refRing.size).toInt()] = scaled - EMPHASIS * refPrevious
            refPrevious = scaled
            refWritten++
            refPeak = max(refPeak, abs(r.toDouble()))
            micBlock[micFill++] = m
            if (micFill == n) {
                processBlock()
                micFill = 0
            }
            out[i] = output[outRead]
            outRead = (outRead + 1) % output.size
        }
    }

    private fun processBlock() {
        followDelay()
        silentBlocks = if (refPeak < SILENT_PEAK) silentBlocks + 1 else 0
        refPeak = 0.0
        val passThrough = silentBlocks > (alignment + (partitions + 1) * n) / n + 1

        var clipped = false
        for (i in 0 until n) {
            val m = micBlock[i].toDouble()
            if (abs(m) >= CLIPPED) clipped = true
            // DC notch, then pre-emphasis, as the reference got.
            val scaled = m * SCALE
            notchOut = scaled - notchIn + NOTCH * notchOut
            notchIn = scaled
            d[i] = notchOut - EMPHASIS * micEmphasis
            micEmphasis = notchOut
        }
        val start = refWritten - n - alignment
        for (i in 0 until n) x[i] = refAt(start + i)

        filter.process(x, d, adapt = !clipped && !passThrough, out = error, echo = echo)
        for (i in 0 until n) {
            refDeemphasis = x[i] + EMPHASIS * refDeemphasis
            reference[i] = refDeemphasis / SCALE
            errorDeemphasis = error[i] + EMPHASIS * errorDeemphasis
            echoDeemphasis = echo[i] + EMPHASIS * echoDeemphasis
            error[i] = errorDeemphasis / SCALE
            echo[i] = echoDeemphasis / SCALE
        }
        suppressor.process(error, echo, reference, leak = minOf(1.0, 2 * filter.leak), out = suppressed)

        // The suppressor's block is the previous microphone block.
        var outEnergy = 0.0
        var micEnergy = 0.0
        var finite = true
        for (i in 0 until n) {
            val v = suppressed[i]
            if (!v.isFinite()) finite = false
            outEnergy += v * v
            micEnergy += micPrevious[i].toDouble() * micPrevious[i]
        }
        if (!finite) {
            filter.reset()
            suppressor.reset()
            errorDeemphasis = 0.0
            echoDeemphasis = 0.0
        }
        if (passThrough || !finite) {
            for (i in 0 until n) push(micPrevious[i])
        } else {
            activeBlocks++
            val scale = if (outEnergy > micEnergy) sqrt(micEnergy / outEnergy) else 1.0
            for (i in 0 until n) push((suppressed[i] * scale).toFloat().coerceIn(-1f, 1f))
        }
        micBlock.copyInto(micPrevious)
    }

    /** Moves the alignment when the delay estimator has a new, repeated answer. */
    private fun followDelay() {
        if (delay.estimates == lastEstimates) return
        lastEstimates = delay.estimates
        val found = delay.delaySamples
        if (found < 0) return
        val target = (found - margin).coerceIn(0, maxDelay)
        if (!locked) {
            // What the filter learnt before the first estimate is kept if it is still in its span.
            locked = true
            if (abs(target - alignment) > partitions * n / 2) filter.reset()
            realign(target)
            return
        }
        if (abs(target - alignment) <= TOLERANCE * sampleRate / 1_000) {
            pending = -1
            return
        }
        if (pending >= 0 && abs(target - pending) <= TOLERANCE * sampleRate / 1_000) {
            pendingAgreed++
        } else {
            pending = target
            pendingAgreed = 1
        }
        if (pendingAgreed >= AGREEMENTS) {
            realign(target)
            pending = -1
        }
    }

    private fun realign(target: Int) {
        val delta = target - alignment
        alignment = target
        realignments++
        // The block being collected is the newest; the filter's newest frame is the last one done.
        val newestStart = refWritten - micFill - n - alignment
        filter.realign(delta) { age, into ->
            val blockStart = newestStart - age * n
            for (i in 0 until 2 * n) into[i] = refAt(blockStart - n + i)
        }
    }

    private fun refAt(index: Long): Double =
        if (index < 0 || index < refWritten - refRing.size) 0.0 else refRing[(index % refRing.size).toInt()]

    private fun push(v: Float) {
        output[outWrite] = v
        outWrite = (outWrite + 1) % output.size
    }

    private fun clean(v: Float): Float = if (v.isFinite()) v.coerceIn(-1f, 1f) else 0f

    companion object {
        /** The output is this many blocks behind: one to fill a block, one for the suppressor's overlap. */
        const val LATENCY_BLOCKS = 2

        /** How much of the cabin's response after the bulk delay the filter learns. */
        const val TAIL_MS = 100

        /** The reference is read this much earlier than the delay found, for the echo's onset. */
        const val MARGIN_MS = 8

        /** A new delay this close to the alignment (ms) is not worth moving for. */
        private const val TOLERANCE = 1

        /** Estimates (0.25 s apart) that must agree on a new delay before moving. */
        private const val AGREEMENTS = 2

        private const val SCALE = 32_768.0
        private const val EMPHASIS = 0.9
        private const val NOTCH = 0.995
        private const val CLIPPED = 0.999
        private const val SILENT_PEAK = 1e-5
    }
}
