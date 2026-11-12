package com.carstage.app.audio

import com.carstage.app.audio.echo.CabinEchoCanceller
import com.carstage.app.audio.echo.EchoCanceller
import kotlin.math.log10

/** Why a stage hears the microphone without the music taken out, for the log and the notice. */
enum class EchoOff(val reason: String) {
    /** A development build turned it off (`--es echo off`) to compare; the music is still captured and measured. */
    SWITCHED_OFF("switched off for a test"),

    /** The Lab plays a recording through the stage: there is nothing playing to take out. */
    RECORDING("a recording plays"),

    /** Opened without a tap on the screen (Gleo, a resumed stage), so nobody was asked. */
    NOT_ASKED("not asked: opened without a tap"),

    /** The user did not allow capturing what plays. */
    DENIED("not allowed by the user"),

    /** The car has no way to ask (no consent dialog, or Android before 10). */
    UNAVAILABLE("the car cannot capture playback"),

    /** The capture would not start, or stopped. */
    FAILED("the capture failed"),

    /** Music plays but the capture hears only silence: the music app does not allow capture. */
    BLOCKED("the music app does not allow capture"),
    ;

    /**
     * What the first screen says about it, once an app run; null for what the user need not hear.
     * That the music is not taken out from the start is not said: it is how every stage listens
     * now, and the user can do nothing about it (2026-11-01).
     */
    val notice: String?
        get() = when (this) {
            FAILED -> NOTICE_LOST
            DENIED, UNAVAILABLE, BLOCKED, SWITCHED_OFF, RECORDING, NOT_ASKED -> null
        }

    companion object {
        /** Said when the capture failed or stopped. */
        const val NOTICE_LOST = "음악 소리를 듣지 못하게 되어서, 지금은 음악을 빼지 않고 들어요."
    }
}

/**
 * One stage's way from the microphone to the analyzer: each frame goes through the echo
 * canceller with the reference the tap captured for the same moment, and comes out with the
 * music taken out (docs/09 "에코 제거"). Measures how loud the reference was, how much the
 * canceller took out where it played, and how much of it was missing, for the microphone's
 * "off" line. Watches for a music app that plays but cannot be captured ([onBlocked]). Runs on
 * the microphone thread; [reference] is null when there is nothing to take out ([off] says why).
 */
class EchoPath(
    private val canceller: EchoCanceller,
    private val reference: ReferenceBuffer?,
    /** Whether the canceller runs, or the frame only gets measured (`--es echo off`). */
    private val cancels: Boolean,
    @Volatile var off: EchoOff?,
    /** What the reference comes from, for the log. */
    @Volatile var sourceLabel: String? = null,
    private val musicPlaying: () -> Boolean = { false },
    private val onBlocked: () -> Unit = {},
    private val now: () -> Long = System::nanoTime,
) {
    private var ref = FloatArray(0)
    private var out = FloatArray(0)

    private var frames = 0
    private var referenceFrames = 0
    private var referenceDbSum = 0.0
    private var micEnergy = 0.0
    private var outEnergy = 0.0
    private var silentWhilePlaying = 0
    private var blocked = false

    /** The frame as the analyzer should hear it; the same array when nothing is taken out. */
    fun process(mic: FloatArray): FloatArray {
        frames++
        val buffer = reference ?: return mic
        val ended = now()
        if (ref.size < mic.size) {
            ref = FloatArray(mic.size)
            out = FloatArray(mic.size)
        }
        buffer.read(ref, mic.size, ended)
        if (cancels) canceller.process(mic, ref, out, mic.size) else mic.copyInto(out)

        var refEnergy = 0.0
        var peak = 0f
        for (i in mic.indices) {
            refEnergy += ref[i].toDouble() * ref[i]
            peak = maxOf(peak, kotlin.math.abs(ref[i]))
        }
        val refDb = 10 * log10(refEnergy / mic.size + 1e-20)
        if (refDb > REFERENCE_FLOOR_DB) {
            referenceFrames++
            referenceDbSum += refDb
            for (i in mic.indices) {
                micEnergy += mic[i].toDouble() * mic[i]
                outEnergy += out[i].toDouble() * out[i]
            }
        }
        // Exact zeros while the car says music plays: the capture is not given that app's sound.
        silentWhilePlaying = if (peak == 0f && musicPlaying()) silentWhilePlaying + 1 else 0
        if (silentWhilePlaying == BLOCKED_FRAMES && !blocked) {
            blocked = true
            onBlocked()
        }
        return if (out.size == mic.size) out else out.copyOf(mic.size)
    }

    /** For the microphone's "off" line: whether the music was taken out, from what, and how much. */
    fun summary(): String {
        val why = off
        val measured = if (reference == null) {
            ""
        } else {
            val mean = if (referenceFrames == 0) "-" else "%.1f".format(referenceDbSum / referenceFrames)
            val removed = if (referenceFrames == 0 || outEnergy <= 0) "-" else "%.1f".format(10 * log10(micEnergy / outEnergy))
            val missing = reference.let { if (it.read == 0L) 0.0 else 100.0 * it.missing / it.read }
            " (reference $mean dBFS in $referenceFrames of $frames frames, removed $removed dB, " +
                "${"%.1f".format(missing)}% missing, ${reference.gaps} gaps, ${reference.resyncs} resyncs" +
                (if (blocked) ", silent while music played" else "") + engineState() + ")"
        }
        return when {
            why == null -> "echo on via ${sourceLabel ?: "-"} with ${canceller::class.java.simpleName}$measured"
            why == EchoOff.SWITCHED_OFF -> "echo off: ${why.reason}, via ${sourceLabel ?: "-"}$measured"
            else -> "echo off: ${why.reason}$measured"
        }
    }

    /** The cabin canceller's own view, for telling "found no delay" from "found it and took little out". */
    private fun engineState(): String {
        if (!cancels) return ""
        val c = canceller as? CabinEchoCanceller ?: return ""
        val ms = c.alignment * 1_000 / c.sampleRate
        return ", ${if (c.locked) "locked at $ms ms" else "no delay found"} (confidence ${"%.2f".format(c.delayConfidence)}), " +
            "${c.activeBlocks} active blocks, ${c.realignments} realignments, ${c.restarts} restarts"
    }

    companion object {
        /** Quieter reference frames are not music: they say nothing about the canceller. */
        const val REFERENCE_FLOOR_DB = -60.0

        /** About 3 s of microphone frames, longer than the pause between two songs' playback states. */
        const val BLOCKED_FRAMES = 69
    }
}
