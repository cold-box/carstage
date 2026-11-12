package com.carstage.app.audio

import kotlin.math.log10

/**
 * Decides when the cabin cheers, from values [AudioAnalyzer] measures per frame: a cheer, a shout
 * or loud applause is sound in the voice band standing well out of the music and above the
 * voices' energy over the last 5 s, for [FRAMES] frames in a row, most of them neither pitched nor
 * tonal. Someone singing loudly is pitched; a piano chord is tonal, its energy in a few peaks,
 * though too many notes at once for a pitch; a crowd cheering, applause or a scream is neither,
 * with breath and noise between the peaks. After a cheer it waits [COOLDOWN_FRAMES], so one burst
 * of applause is one cheer.
 *
 * Known limits (docs/09): a cheer about as loud as the singing just before it is not one; one
 * person shouting a clear note can be missed; a sudden jump of the music by about 20 dB in an
 * unpitched, noisy passage, a loud noisy burst in the music itself (a snare roll that stands out
 * of the last 1.5 s), or now and then a piano chord near the flatness threshold can read as one
 * cheer.
 * Pure Kotlin, so each condition can be tested on its own.
 */
class CheerDetector {
    var cheers = 0
        private set

    /**
     * Energy standing out of the accompaniment in the voice band: the plain mean of the frames
     * heard for the first 5 s, then a moving average over about 5 s, so it is not low just
     * because the stage has only been open briefly. NaN before the first frame.
     */
    private var recentEnergy = Double.NaN
    private var heard = 0
    private var run = 0
    /** Whether each of the run's last [FRAMES] frames was pitched or tonal, like music rather than a crowd. */
    private val runMusical = BooleanArray(FRAMES)
    private var cooldown = 0

    /**
     * Takes one frame: [voiceEnergy] standing out of the accompaniment in the voice band,
     * [ratioDb] that energy over the whole accompaniment, whether the frame is [pitched], and
     * whether it is [tonal] (spectral flatness of the voice band under [MIN_FLATNESS]). Call it
     * only once the accompaniment estimate has a full history. True when this frame completes a
     * cheer.
     */
    fun process(voiceEnergy: Double, ratioDb: Float, pitched: Boolean, tonal: Boolean): Boolean {
        val recent = recentEnergy
        // Averaged as energy, not dB, so frames where nothing stands out do not drag it down.
        val excited = !recent.isNaN() && ratioDb >= OVER_ACCOMPANIMENT_DB && decibels(voiceEnergy) >= decibels(recent) + RISE_DB
        heard++
        recentEnergy = if (recent.isNaN()) voiceEnergy else recent + (voiceEnergy - recent) * maxOf(RECENT_WEIGHT, 1.0 / heard)
        if (cooldown > 0) cooldown--
        if (!excited) {
            run = 0
            return false
        }
        run++
        runMusical[run % FRAMES] = pitched || tonal
        // The last FRAMES frames were all excited, and few of them sounded like music.
        if (run < FRAMES || cooldown > 0 || runMusical.count { it } > MAX_MUSICAL) return false
        cheers++
        run = 0
        cooldown = COOLDOWN_FRAMES
        return true
    }

    /** Ends a run in progress, for example at digital silence, which is not a sound at all. */
    fun interrupt() {
        run = 0
    }

    private fun decibels(energy: Double): Double = 10 * log10(energy + 1e-12)

    companion object {
        /** The voice band this far above the accompaniment, as loud as clearly singing over the music... */
        const val OVER_ACCOMPANIMENT_DB = 12f

        /** ...and this far above the voices' recent energy... */
        const val RISE_DB = 3.0

        /** ...for about 0.3 s at 23 frames a second... */
        const val FRAMES = 7

        /** ...with at most this many of those frames pitched or tonal. */
        const val MAX_MUSICAL = 3

        /**
         * A frame whose voice band is flatter than this has energy between its peaks: breath,
         * noise, many voices. Under it, nearly all of it sits in a few peaks, as in a chord. White
         * noise is about 0.56. Placed between measured clips (docs/09), as medians over the seven
         * frames at each detection: nearly every piano chord that read as a cheer measured 0.009
         * or less, also with noise 20 dB under it, though one nocturne chord at 0.010 still reads
         * as one; real cheers and applause mixed into a car recording measured 0.012 to 0.33, the
         * lowest one person's whooped note. It gets checked with the scripted cheer recordings.
         */
        const val MIN_FLATNESS = 0.01

        /** About 4 s between cheers. */
        const val COOLDOWN_FRAMES = 92

        /** Share of the gap the recent energy closes per frame, up or down: about 5 s. */
        const val RECENT_WEIGHT = 1.0 / 115
    }
}
