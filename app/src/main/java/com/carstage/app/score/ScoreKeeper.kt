package com.carstage.app.score

import com.carstage.app.audio.AudioAnalyzer
import com.carstage.app.stage.AudioEvent
import kotlin.math.roundToInt

/** How excited a song left the cabin: from the lowest to the highest. */
enum class Grade {
    BASIC,
    GOOD,
    BEST,
    ;

    companion object {
        const val GOOD_FROM = 50
        const val BEST_FROM = 80

        fun of(score: Int): Grade = when {
            score >= BEST_FROM -> BEST
            score >= GOOD_FROM -> GOOD
            else -> BASIC
        }
    }
}

/**
 * One song's 흥 점수 (docs/09 section 5 "흥 점수"): the four parts, [participation] of
 * [ScoreKeeper.PARTICIPATION_POINTS], [heat] of [ScoreKeeper.HEAT_POINTS], [response] of
 * [ScoreKeeper.RESPONSE_POINTS] and [completion] of [ScoreKeeper.COMPLETION_POINTS], and the song's
 * length in [seconds], digital silence included, with the song's [title] when the music app told
 * it ([com.carstage.app.SongTitles]). The score and its grade follow from the parts.
 */
data class SongResult(
    val participation: Int,
    val heat: Int,
    val response: Int,
    val completion: Int,
    val seconds: Int,
    val title: String? = null,
) {
    val score: Int get() = participation + heat + response + completion
    val grade: Grade get() = Grade.of(score)
}

/**
 * Keeps one song's 흥 점수 from the audio events and hands it over when the song ends (docs/09
 * section 5 "흥 점수", v2 from 2026-11-02). Hears every event on the audio thread; nothing here
 * allocates per frame, and only levels are kept, never sound. Pure Kotlin.
 *
 * The microphone hears the song's own singer as well as the cabin (docs/09), and how loud that
 * singer stands out of the band differs by tens of dB from song to song, so the parts are measured
 * against the song's own level rather than a fixed one:
 * - participation: the share of seconds whose voices stand out of the music ([PARTICIPATING_DB])
 *   and [OVER_SONG_DB] over the song's quiet side, the [SONG_LEVEL_QUANTILE] of its seconds (its
 *   breaks and soft verses); [FULL_PARTICIPATION] of the song is full marks. A song sung over that
 *   in three quarters of its seconds or more makes the singing its own quiet side and scores
 *   none: this rewards rising over the song, not the share sung.
 * - heat: the peaks, frames [PEAK_DB] over the song's middle frame (belting, shouts), from
 *   [PEAKS_FROM] of the song (a song's own drums and accents reach that far) to [PEAKS_FULL].
 * - response: cheers; full marks at one a minute plus one (four for a 3-minute song).
 * - completion: participation in the last third of the song, full at the same share.
 *
 * The numbers were chosen on scenes with known answers made from five songs (docs/09 "흥 점수
 * v2"), where songs nobody sang stay in the lowest grade and loud singing rises well above them.
 * Singing along as loud as the recording's singer stays near nobody singing: on one microphone the
 * two differ by about 2 dB a second, less than a song's own verse and chorus do; only the car's
 * echo cancelling can tell them apart.
 */
class ScoreKeeper(private val framesPerSecond: Float) {
    private val framesPerSecondWhole = framesPerSecond.roundToInt()
    private var frames = 0
    private var cheers = 0

    /** How many of the song's frames stood at each level, in [LEVEL_STEP_DB] steps from [FLOOR_DB]. */
    private val frameLevels = IntArray(LEVEL_STEPS)

    /** Per second of the song, the mean level of its frames in dB over the accompaniment; the last [MAX_SECONDS] are kept. */
    private val secondLevels = FloatArray(MAX_SECONDS)
    private var seconds = 0
    private var levelThisSecond = 0f
    private var framesThisSecond = 0

    /** Takes one event; the result when it ends a song long enough to score, null otherwise. */
    fun hear(event: AudioEvent): SongResult? {
        when (event) {
            is AudioEvent.Frame -> frame(event.loudness)
            AudioEvent.Cheer -> cheers++
            is AudioEvent.Mood -> Unit
            AudioEvent.SongEnded -> return finish()
        }
        return null
    }

    private fun frame(loudness: Float) {
        frames++
        val db = if (loudness.isNaN()) FLOOR_DB else (loudness * AudioAnalyzer.VOICE_RANGE_DB).coerceIn(FLOOR_DB, CEILING_DB)
        frameLevels[stepOf(db)]++
        levelThisSecond += db
        if (++framesThisSecond < framesPerSecondWhole) return
        secondLevels[seconds % MAX_SECONDS] = levelThisSecond / framesThisSecond
        seconds++
        levelThisSecond = 0f
        framesThisSecond = 0
    }

    /** Whether the song heard since the last end is long enough to be scored if it ended now. */
    val scorable: Boolean get() = frames >= MIN_SECONDS * framesPerSecond

    /** Forgets the song so far and starts a new one, as when a duel's turn starts mid-song. */
    fun restart() {
        finish()
    }

    /** Scores the song heard since the last end and starts the next one; null for a song under [MIN_SECONDS]. */
    private fun finish(): SongResult? {
        val result = if (scorable) score() else null
        frames = 0
        cheers = 0
        frameLevels.fill(0)
        seconds = 0
        levelThisSecond = 0f
        framesThisSecond = 0
        return result
    }

    private fun score(): SongResult {
        val kept = minOf(seconds, MAX_SECONDS)
        // Once per song, so copying and sorting here is fine.
        val levels = FloatArray(kept) { secondLevels[(seconds - kept + it) % MAX_SECONDS] }
        val quietSide = if (kept == 0) FLOOR_DB else levels.sorted().let { it[((it.size - 1) * SONG_LEVEL_QUANTILE).roundToInt()] }
        val takingPart = maxOf(PARTICIPATING_DB, quietSide + OVER_SONG_DB)
        val participation = points(PARTICIPATION_POINTS, levels.count { it >= takingPart }.toFloat() / maxOf(kept, 1) / FULL_PARTICIPATION)
        val peaks = framesFrom(middleFrameLevel() + PEAK_DB).toFloat() / frames
        val heat = points(HEAT_POINTS, (peaks - PEAKS_FROM) / (PEAKS_FULL - PEAKS_FROM))
        val minutes = frames / framesPerSecond / 60
        val response = points(RESPONSE_POINTS, cheers / (1 + minutes))
        val lastThird = kept / 3
        val takingPartAtTheEnd = (kept - lastThird until kept).count { levels[it] >= takingPart }
        val completion = if (lastThird == 0) 0 else points(COMPLETION_POINTS, takingPartAtTheEnd.toFloat() / lastThird / FULL_PARTICIPATION)
        return SongResult(
            participation = participation,
            heat = heat,
            response = response,
            completion = completion,
            seconds = (frames / framesPerSecond).roundToInt(),
        )
    }

    /** The level of the song's middle frame, at the middle of its step. */
    private fun middleFrameLevel(): Float {
        var below = 0
        for (step in frameLevels.indices) {
            below += frameLevels[step]
            if (below * 2 >= frames) return FLOOR_DB + (step + 0.5f) * LEVEL_STEP_DB
        }
        return CEILING_DB
    }

    /** How many frames stood in the steps whose middle is at [db] or above. */
    private fun framesFrom(db: Float): Int {
        var count = 0
        for (step in frameLevels.indices) {
            if (FLOOR_DB + (step + 0.5f) * LEVEL_STEP_DB >= db) count += frameLevels[step]
        }
        return count
    }

    private fun stepOf(db: Float): Int = ((db - FLOOR_DB) / LEVEL_STEP_DB).toInt().coerceIn(0, LEVEL_STEPS - 1)

    private fun points(of: Int, fraction: Float): Int = (of * fraction.coerceIn(0f, 1f)).roundToInt()

    companion object {
        /** The voices 5 dB over the accompaniment (the light 40 %): someone may be singing, the song's singer included. */
        const val PARTICIPATING_DB = 5f

        /** How far over the song's quiet side a second must be to count as the cabin joining in; a recording's own chorus rises less. */
        const val OVER_SONG_DB = 10f

        /** The song's quiet side: this share of its seconds are at or below it. */
        const val SONG_LEVEL_QUANTILE = 0.25f

        /** A share of the song taking part (for completion, of its last third) that is full marks. */
        const val FULL_PARTICIPATION = 0.3f

        /** How far over the song's middle frame a frame must be to be a peak. */
        const val PEAK_DB = 15f

        /** The share of peak frames a song gives by itself, and the share that is full marks. */
        const val PEAKS_FROM = 0.05f
        const val PEAKS_FULL = 0.12f

        const val PARTICIPATION_POINTS = 30
        const val HEAT_POINTS = 25
        const val RESPONSE_POINTS = 35
        const val COMPLETION_POINTS = 10

        /** A song shorter than this is not scored, such as one ended by hand right after the stage opened. */
        const val MIN_SECONDS = 10

        /**
         * About fifteen minutes of seconds (of 23 frames); a longer song is scored on its last
         * fifteen for participation and completion, and on all of it for peaks.
         */
        const val MAX_SECONDS = 900

        /** The levels kept, in dB over the accompaniment, and their steps. */
        private const val FLOOR_DB = -30f
        private const val CEILING_DB = 40f
        private const val LEVEL_STEP_DB = 0.5f
        private val LEVEL_STEPS = ((CEILING_DB - FLOOR_DB) / LEVEL_STEP_DB).toInt() + 1
    }
}
