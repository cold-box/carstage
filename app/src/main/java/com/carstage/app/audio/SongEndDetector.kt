package com.carstage.app.audio

import kotlin.math.roundToInt

/**
 * Guesses when a song ended, from values [AudioAnalyzer] measures per frame: once [MIN_SONG_SECONDS]
 * of sound were heard, the cabin goes quiet, [QUIET_BELOW_DB] under the song's level, for
 * [END_SECONDS]. That is the gap between tracks, or the end of a fade-out (docs/09 section 5
 * "곡 종료 감지").
 *
 * The song's level is near the loud end of the accompaniment (the steady part of the sound, see
 * [Accompaniment]) over the last 30 s of sound. In talk without music the accompaniment is about
 * the cabin's noise between the words, so the pauses are not 15 dB under it and do not count.
 *
 * Known limits: it misses a gapless album, a gap shorter than a second, a gap someone talks or
 * cheers in, a cabin whose own noise is within 15 dB of the music's accompaniment (an idling
 * engine under quiet music), a song that ends within 30 s of sound after the stage opened, and a
 * real end within 30 s of a wrong one. The cabin before the first song (or after a pause of over a
 * minute) is its own level, so it is not quiet and counts towards the 30 s. A quiet passage of a
 * second in ambient music, pausing the music, or turning it down by 25 dB mid-song reads as an end.
 * The screen's "곡 끝내기" button and Gleo end a song by hand ([com.carstage.app.stage.StageController.endSong]).
 * Pure Kotlin.
 */
class SongEndDetector(framesPerSecond: Float) {
    private val songFrames = (MIN_SONG_SECONDS * framesPerSecond).roundToInt()
    private val endFrames = (END_SECONDS * framesPerSecond).roundToInt()
    private val everyFrames = (EVERY_SECONDS * framesPerSecond).roundToInt()
    private val levels = FloatArray((RANGE_SECONDS * framesPerSecond).roundToInt())

    /** Scratch space for the level every 2 s, so the audio thread does not allocate. */
    private val sorted = FloatArray(levels.size)
    private var heard = 0
    private var songLevel = Float.NaN
    private var sinceEnd = 0
    private var quietRun = 0

    /** Songs that ended, for the log. */
    var songsEnded = 0
        private set

    /**
     * Takes one frame of sound: its level and the accompaniment's level, both in dBFS. True when
     * this frame ends a song.
     */
    fun process(frameDb: Float, accompanimentDb: Float): Boolean {
        levels[heard % levels.size] = accompanimentDb
        heard++
        if (heard % everyFrames == 0) songLevel = loudEnd()
        val quiet = !songLevel.isNaN() && frameDb <= songLevel - QUIET_BELOW_DB
        quietRun = if (quiet) quietRun + 1 else 0
        // A pause between songs is not part of the next one, until the level has settled on it.
        if (!quiet) sinceEnd++
        if (sinceEnd < songFrames || quietRun < endFrames) return false
        songsEnded++
        sinceEnd = 0
        quietRun = 0
        return true
    }

    /** Breaks a quiet run, for example at digital silence, which is the microphone gone rather than a gap. */
    fun interrupt() {
        quietRun = 0
    }

    /** The [LOUD_END] percentile of the accompaniment's level over the last 30 s of sound. */
    private fun loudEnd(): Float {
        val stored = minOf(heard, levels.size)
        levels.copyInto(sorted, endIndex = stored)
        sorted.sort(0, stored)
        return sorted[((stored - 1) * LOUD_END).roundToInt()]
    }

    companion object {
        /**
         * Frames of sound since the stage opened or the last end, not counting quiet ones, before
         * an end can count: so a long quiet outro does not end the song twice ("Calmant" did at 20 s).
         */
        const val MIN_SONG_SECONDS = 30f

        /** Quiet this long ends a song; gaps between tracks usually last at least this long (docs/04), so shorter ones are missed. */
        const val END_SECONDS = 1f

        /**
         * How far under the song's level a frame must be to count as quiet: the cabin's own noise,
         * not a soft passage. 20 dB missed a gap 30 dB under the sound; 12 and 10 dB also ended an
         * ambient track at 69 s; 15 dB still ends the 16 min "Tranquility" twice midway (docs/09).
         */
        const val QUIET_BELOW_DB = 15f

        /** The song's level comes from this much sound... */
        const val RANGE_SECONDS = 30f

        /**
         * ...worked out this often rather than every frame, so the audio thread sorts 703 values
         * every 2 s, not 23 times a second. The level follows up to 2 s late, and the first 2 s
         * have none, so nothing counts as quiet then.
         */
        const val EVERY_SECONDS = 2f

        /** Near the loud end, so the quieter verses and the gaps themselves do not pull the level down. */
        const val LOUD_END = 0.8f
    }
}
