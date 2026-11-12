package com.carstage.app.audio.echo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Synthetic cabin for the echo canceller's tests: a song (pads, bass, drums and a singer), a
 * passenger's voice, a cabin response (bulk delay, early reflections, a reverb tail) and the
 * numbers that say how well the echo went: ERLE, what is left of the voice, convergence time.
 */
object EchoScene {
    /** Vowels as their first three formants (Hz). */
    private val VOWELS = listOf(
        doubleArrayOf(800.0, 1_200.0, 2_500.0),
        doubleArrayOf(500.0, 1_900.0, 2_600.0),
        doubleArrayOf(300.0, 2_300.0, 3_000.0),
        doubleArrayOf(500.0, 900.0, 2_400.0),
        doubleArrayOf(350.0, 800.0, 2_300.0),
    )

    /**
     * A sung line: a harmonic source with vibrato through vowel formants, in syllables of about
     * 0.25 s with short breaks between phrases. [lowHz]..[highHz] is the melody's range. RMS 0.1.
     */
    fun voice(rate: Int, seconds: Double, seed: Int, lowHz: Double = 140.0, highHz: Double = 280.0): FloatArray {
        val random = Random(seed)
        val total = (seconds * rate).toInt()
        val out = DoubleArray(total)
        val harmonics = 30
        val phase = DoubleArray(harmonics)
        val amplitudes = DoubleArray(harmonics)
        var t = 0
        var vibrato = 0.0
        while (t < total) {
            val syllable = (rate * random.nextDouble(0.18, 0.35)).toInt()
            val rest = random.nextInt(8) == 0
            val f0 = lowHz * (highHz / lowHz).pow(random.nextDouble())
            val vowel = VOWELS[random.nextInt(VOWELS.size)]
            for (h in 0 until harmonics) {
                val f = f0 * (h + 1)
                var a = 0.0
                for ((i, formant) in vowel.withIndex()) {
                    val width = 80.0 + 40 * i
                    a += exp(-0.5 * ((f - formant) / width).pow(2)) / (i + 1)
                }
                amplitudes[h] = if (f < rate / 2.2) (a + 0.02) / (h + 1).toDouble().pow(0.5) else 0.0
            }
            for (i in 0 until syllable) {
                if (t >= total) break
                vibrato += 2 * PI * 5.5 / rate
                val f = f0 * (1 + 0.012 * sin(vibrato))
                val envelope = if (rest) 0.0 else sin(PI * i / syllable).pow(0.4)
                var v = 0.0
                for (h in 0 until harmonics) {
                    phase[h] += 2 * PI * f * (h + 1) / rate
                    if (phase[h] > 2 * PI) phase[h] -= 2 * PI
                    v += amplitudes[h] * sin(phase[h])
                }
                out[t++] = envelope * (v + 0.05 * random.nextDouble(-1.0, 1.0))
            }
        }
        return normalized(out, 0.1)
    }

    /** A pop-like song at 120 BPM: pads, bass, kick, hats and a singer [singerDb] against the band. RMS 0.1. */
    fun music(rate: Int, seconds: Double, seed: Int = 1, singerDb: Double = -3.0): FloatArray {
        val random = Random(seed)
        val total = (seconds * rate).toInt()
        val band = DoubleArray(total)
        val beat = rate / 2
        val chords = listOf(
            doubleArrayOf(220.0, 277.2, 329.6),
            doubleArrayOf(196.0, 246.9, 293.7),
            doubleArrayOf(174.6, 220.0, 261.6),
            doubleArrayOf(164.8, 207.7, 246.9),
        )
        for (t in 0 until total) {
            val chord = chords[(t / (4 * beat)) % chords.size]
            val inBeat = t % beat
            var v = 0.0
            for (note in chord) {
                for (h in 1..8) v += 0.03 / h * sin(2 * PI * note * h * t / rate + h)
            }
            val bassHz = chord[0] / 2
            val pluck = exp(-inBeat / (0.25 * rate))
            for (h in 1..4) v += 0.12 / h * pluck * sin(2 * PI * bassHz * h * t / rate)
            val kickTime = inBeat.toDouble() / rate
            v += 0.5 * exp(-kickTime / 0.12) * sin(2 * PI * (50 + 70 * exp(-kickTime / 0.03)) * kickTime)
            band[t] = v
        }
        // Hats on the off-beats: high noise bursts.
        var last = 0.0
        for (t in 0 until total) {
            val sinceOff = (t + beat / 2) % beat
            val noise = random.nextDouble(-1.0, 1.0)
            val high = noise - last
            last = noise
            band[t] += 0.08 * exp(-sinceOff / (0.04 * rate)) * high
        }
        val bandNorm = normalized(band, 0.1)
        val singer = voice(rate, seconds, seed + 100, lowHz = 180.0, highHz = 420.0)
        val gain = 10.0.pow(singerDb / 20)
        val mixed = DoubleArray(total) { bandNorm[it] + gain * singer[it] }
        return normalized(mixed, 0.1)
    }

    /**
     * A cabin's response: a bulk delay of [delayMs], the direct sound through a small speaker
     * (slightly low-passed), reflections over the next 20 ms and a reverb tail of [t60Ms].
     */
    fun cabin(rate: Int, delayMs: Double, seed: Int = 7, t60Ms: Double = 80.0): DoubleArray {
        val random = Random(seed)
        val delay = (delayMs * rate / 1_000).toInt()
        val length = delay + (t60Ms * rate / 1_000 * 1.5).toInt()
        val h = DoubleArray(length)
        h[delay] += 0.6
        h[delay + 1] += 0.3
        h[delay + 2] += 0.1
        repeat(8) {
            val at = delay + (rate * random.nextDouble(0.002, 0.02)).toInt()
            h[at] += random.nextDouble(0.15, 0.4) * (if (random.nextBoolean()) 1 else -1)
        }
        val start = delay + rate * 2 / 1_000
        for (i in start until length) {
            val time = (i - start).toDouble() / rate
            h[i] += 0.04 * gaussian(random) * exp(-6.9 * time / (t60Ms / 1_000))
        }
        return h
    }

    /** [signal] through [response], the same length as [signal] (FFT overlap-add). */
    fun convolve(signal: FloatArray, response: DoubleArray): DoubleArray {
        val block = 4_096
        var size = 1
        while (size < block + response.size) size *= 2
        val fft = RealFft(size)
        val hRe = DoubleArray(fft.bins)
        val hIm = DoubleArray(fft.bins)
        val frame = DoubleArray(size)
        response.copyInto(frame)
        fft.forward(frame, hRe, hIm)
        val re = DoubleArray(fft.bins)
        val im = DoubleArray(fft.bins)
        val out = DoubleArray(signal.size)
        var start = 0
        while (start < signal.size) {
            frame.fill(0.0)
            for (i in 0 until minOf(block, signal.size - start)) frame[i] = signal[start + i].toDouble()
            fft.forward(frame, re, im)
            for (k in 0 until fft.bins) {
                val r = re[k] * hRe[k] - im[k] * hIm[k]
                val i = re[k] * hIm[k] + im[k] * hRe[k]
                re[k] = r
                im[k] = i
            }
            fft.inverse(re, im, frame)
            for (i in 0 until size) if (start + i < out.size) out[start + i] += frame[i]
            start += block
        }
        return out
    }

    /** Pinkish noise (white through a one-pole low-pass) at [rms]. */
    fun noise(samples: Int, rms: Double, seed: Int = 5): DoubleArray {
        val random = Random(seed)
        var state = 0.0
        val out = DoubleArray(samples) {
            state = 0.95 * state + 0.05 * random.nextDouble(-1.0, 1.0)
            state + 0.1 * random.nextDouble(-1.0, 1.0)
        }
        return normalized(out, rms).map { it.toDouble() }.toDoubleArray()
    }

    /** Runs [canceller] over the streams in [block]-sample calls and returns the output shifted back by [latency]. */
    fun run(canceller: EchoCanceller, mic: FloatArray, reference: FloatArray, block: Int, latency: Int): FloatArray {
        val out = FloatArray(mic.size + latency)
        val micPadded = mic.copyOf(mic.size + latency)
        val refPadded = reference.copyOf(mic.size + latency)
        var start = 0
        val m = FloatArray(block)
        val r = FloatArray(block)
        val o = FloatArray(block)
        while (start < micPadded.size) {
            val length = minOf(block, micPadded.size - start)
            micPadded.copyInto(m, 0, start, start + length)
            refPadded.copyInto(r, 0, start, start + length)
            canceller.process(m, r, o, length)
            o.copyInto(out, start, 0, length)
            start += length
        }
        return out.copyOfRange(latency, latency + mic.size)
    }

    fun energy(signal: FloatArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from until to) sum += signal[i].toDouble() * signal[i]
        return sum
    }

    fun db(ratio: Double) = 10 * log10(ratio)

    /** Echo return loss enhancement over [from]..[to]: how much quieter the output is than the microphone. */
    fun erle(mic: FloatArray, out: FloatArray, from: Int, to: Int) = db(energy(mic, from, to) / (energy(out, from, to) + 1e-20))

    /**
     * How the voice came through over [from]..[to]: its gain in the output (projection of the
     * output on the voice), in dB (0 = untouched), and the voice over everything else left, in dB.
     */
    fun voiceKept(voice: FloatArray, out: FloatArray, from: Int, to: Int): Pair<Double, Double> {
        var dot = 0.0
        var vv = 0.0
        for (i in from until to) {
            dot += out[i].toDouble() * voice[i]
            vv += voice[i].toDouble() * voice[i]
        }
        val g = dot / vv
        var rest = 0.0
        for (i in from until to) {
            val r = out[i] - g * voice[i]
            rest += r * r
        }
        return 20 * log10(abs(g)) to db(g * g * vv / (rest + 1e-20))
    }

    /**
     * Seconds from [from] until the ERLE over every following [windowS] window, up to [to], is at
     * least [targetDb]; null if it never settles.
     */
    fun convergence(mic: FloatArray, out: FloatArray, rate: Int, from: Int, to: Int, targetDb: Double, windowS: Double = 0.25): Double? {
        val window = (windowS * rate).toInt()
        var settled: Int? = null
        var start = from
        while (start + window <= to) {
            if (erle(mic, out, start, start + window) >= targetDb) {
                if (settled == null) settled = start
            } else {
                settled = null
            }
            start += window
        }
        return settled?.let { (it - from).toDouble() / rate + windowS }
    }

    private fun gaussian(random: Random): Double {
        var sum = 0.0
        repeat(6) { sum += random.nextDouble() }
        return (sum - 3) * sqrt(2.0)
    }

    private fun normalized(signal: DoubleArray, rms: Double): FloatArray {
        val now = sqrt(signal.sumOf { it * it } / signal.size)
        val gain = if (now > 0) rms / now else 0.0
        return FloatArray(signal.size) { (signal[it] * gain).toFloat() }
    }
}
