package com.carstage.app.score

import com.carstage.app.audio.AudioAnalyzer
import com.carstage.app.stage.AudioEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreKeeperTest {
    // Whole frames a second, so the test's seconds are the keeper's (the stage's 23.4 drift by one a minute).
    private val fps = 23f
    private val keeper = ScoreKeeper(fps)

    /** A frame whose voices stand [db] over the accompaniment. */
    private fun frame(db: Float) = AudioEvent.Frame(loudness = db / AudioAnalyzer.VOICE_RANGE_DB, voiced = true)

    /** [seconds] of frames at [db] for each second (from its start). */
    private fun sing(seconds: Int, db: (second: Int) -> Float) {
        repeat((seconds * fps).toInt()) { frame ->
            assertNull(keeper.hear(frame(db((frame / fps).toInt()))))
        }
    }

    /** [seconds] of frames at [db] for each frame. */
    private fun frames(seconds: Int, db: (frame: Int) -> Float) {
        repeat((seconds * fps).toInt()) { assertNull(keeper.hear(frame(db(it)))) }
    }

    private fun end(): SongResult? = keeper.hear(AudioEvent.SongEnded)

    private fun SongResult.parts() = participation + heat + response + completion

    /** The song's singer 3 dB over the band, and the cabin joining 11 dB over that in [joined] of each ten seconds. */
    private fun joinIn(seconds: Int, joined: Int) = sing(seconds) { if (it % 10 < joined) 14f else 3f }

    @Test
    fun aSongNobodySangToScoresNothing() {
        sing(60) { 0f }
        assertEquals(SongResult(participation = 0, heat = 0, response = 0, completion = 0, seconds = 60), end())
    }

    @Test
    fun theSongsOwnSingerIsNotParticipationHoweverLoud() {
        // A fixed level would count every second of this as singing.
        sing(60) { 10f }
        assertEquals(0, end()!!.participation)
        sing(60) { if (it / 20 % 2 == 0) 12f else 4f }
        assertEquals("a recording's chorus 8 dB over its verse", 0, end()!!.participation)
        sing(60) { if (it % 20 < 3) -5f else 7f }
        assertEquals("a song's short breaks are not its quiet side", 0, end()!!.participation)
    }

    @Test
    fun voicesOverTheSongsOwnLevelTakePart() {
        joinIn(60, joined = 3)
        val full = end()!!
        assertEquals("joining in for 30% of the song is full marks", ScoreKeeper.PARTICIPATION_POINTS, full.participation)
        assertEquals("and the last third too", ScoreKeeper.COMPLETION_POINTS, full.completion)
        joinIn(60, joined = 1)
        assertEquals("a third of that is a third of the marks", 10, end()!!.participation)
        joinIn(60, joined = 7)
        assertEquals("joining in for most of the song", ScoreKeeper.PARTICIPATION_POINTS, end()!!.participation)
    }

    @Test
    fun aSongSungOverNearlyAllTheWayIsItsOwnQuietSide() {
        // The known limit (docs/09 "흥 점수 v2"): over three quarters of the seconds, the singing is the song's quiet side.
        joinIn(60, joined = 8)
        assertEquals(0, end()!!.participation)
    }

    @Test
    fun singingOverAnInstrumentalTakesPart() {
        // The band alone far under the voices' floor, the cabin just over it: the floor is the bar.
        sing(60) { if (it % 10 < 3) 6f else -10f }
        assertEquals(ScoreKeeper.PARTICIPATION_POINTS, end()!!.participation)
    }

    @Test
    fun aFrameFarUnderTheFloorDoesNotSinkItsSecond() {
        // The analyzer reads near -150 dB when nothing stands out of the band; one such frame a second.
        frames(60) { if (it % 23 == 0) -150f else if ((it / fps).toInt() % 10 < 3) 10f else -2f }
        assertEquals(ScoreKeeper.PARTICIPATION_POINTS, end()!!.participation)
        // A level that is not a number counts as the floor too, not as a second that never takes part.
        frames(60) { if (it % 23 == 0) Float.NaN else if ((it / fps).toInt() % 10 < 3) 10f else -2f }
        assertEquals(ScoreKeeper.PARTICIPATION_POINTS, end()!!.participation)
    }

    @Test
    fun joiningInMustAlsoStandOutOfTheMusic() {
        // 14 dB over the song's quiet side but under the voices' floor: the band's own swells.
        sing(60) { if (it % 10 < 3) 4f else -10f }
        assertEquals(0, end()!!.participation)
    }

    @Test
    fun stoppingBeforeTheLastThirdLosesCompletion() {
        sing(60) { if (it < 40 && it % 10 < 3) 14f else 3f }
        val result = end()!!
        assertTrue("${result.participation}", result.participation > 0)
        assertEquals(0, result.completion)
    }

    @Test
    fun peaksAreFramesFarOverTheSongsMiddle() {
        frames(60) { if (it % 100 < 12) 20f else 3f }
        assertEquals("12% of the frames is full marks", ScoreKeeper.HEAT_POINTS, end()!!.heat)
        frames(60) { if (it % 100 < 5) 20f else 3f }
        assertEquals("5% is what a song's own accents give", 0, end()!!.heat)
        frames(60) { if (it % 200 < 17) 20f else 3f }
        assertEquals("8.5% is half", 13, end()!!.heat)
    }

    @Test
    fun peaksAreJudgedAgainstTheSongNotAFixedLevel() {
        // The same 12% of frames 15 dB over the rest, in a quiet song and in a loud one.
        frames(60) { if (it % 100 < 12) 16f else 0f }
        val quiet = end()!!.heat
        frames(60) { if (it % 100 < 12) 31f else 15f }
        assertEquals(quiet, end()!!.heat)
        assertEquals(ScoreKeeper.HEAT_POINTS, quiet)
        frames(60) { if (it % 100 < 12) 13f else 0f }
        assertEquals("13 dB over is not a peak", 0, end()!!.heat)
    }

    @Test
    fun cheersCountUpToOneAMinutePlusOne() {
        // 3 minutes: four cheers are full marks, two are half.
        repeat(2) { keeper.hear(AudioEvent.Cheer) }
        sing(180) { 0f }
        assertEquals(18, end()!!.response)
        repeat(8) { keeper.hear(AudioEvent.Cheer) }
        sing(180) { 0f }
        assertEquals("more than enough is still full", ScoreKeeper.RESPONSE_POINTS, end()!!.response)
    }

    /** Joining in over the song's level in three of every ten seconds, belting in half of their frames. */
    private fun belt(seconds: Int) = frames(seconds) { if ((it / fps).toInt() % 10 >= 3) 3f else if (it % 2 == 0) 30f else 12f }

    @Test
    fun theScoreIsItsPartsAndTheGradeFollowsIt() {
        // Joining in, peaks and two cheers in a minute: every part full.
        belt(60)
        repeat(2) { keeper.hear(AudioEvent.Cheer) }
        val best = end()!!
        assertEquals(best.parts(), best.score)
        assertEquals(100, best.score)
        assertEquals(Grade.BEST, best.grade)

        joinIn(60, joined = 3)
        repeat(1) { keeper.hear(AudioEvent.Cheer) }
        val good = end()!!
        assertEquals(good.parts(), good.score)
        assertTrue("${good.score}", good.score in Grade.GOOD_FROM until Grade.BEST_FROM)

        joinIn(60, joined = 3)
        val basic = end()!!
        assertTrue("joining in without peaks or cheers: ${basic.score}", basic.score < Grade.GOOD_FROM)
    }

    @Test
    fun theBestGradeTakesACheer() {
        // Every other part full: 65 points, the grade below.
        belt(60)
        val noCheer = end()!!
        assertEquals(ScoreKeeper.PARTICIPATION_POINTS + ScoreKeeper.HEAT_POINTS + ScoreKeeper.COMPLETION_POINTS, noCheer.score)
        assertEquals(Grade.GOOD, noCheer.grade)
    }

    @Test
    fun aSongEndedWithin10sIsNotScoredAndDoesNotCarryOver() {
        // 15% of the next song's frames: full heat if they carried over.
        frames(9) { 30f }
        assertNull(end())
        sing(60) { 0f }
        assertEquals("the short song's peaks are gone", SongResult(0, 0, 0, 0, 60), end())
    }

    @Test
    fun eachSongStartsFromNothing() {
        repeat(3) { keeper.hear(AudioEvent.Cheer) }
        belt(60)
        assertNotNull(end())
        sing(60) { 0f }
        assertEquals(SongResult(participation = 0, heat = 0, response = 0, completion = 0, seconds = 60), end())
    }

    @Test
    fun aSongIsScorableOnceItIsTenSecondsLong() {
        assertFalse(keeper.scorable)
        sing(9) { 6f }
        assertFalse(keeper.scorable)
        sing(2) { 6f }
        assertTrue(keeper.scorable)
        keeper.restart()
        assertFalse("a restart starts a new song", keeper.scorable)
    }

    @Test
    fun aRestartForgetsTheSongSoFar() {
        repeat(3) { keeper.hear(AudioEvent.Cheer) }
        belt(60)
        keeper.restart()
        sing(60) { 0f }
        assertEquals(SongResult(participation = 0, heat = 0, response = 0, completion = 0, seconds = 60), end())
    }

    @Test
    fun theMoodDoesNotChangeTheScore() {
        joinIn(30, joined = 3)
        assertNull(keeper.hear(AudioEvent.Mood(AudioEvent.Mood.Feel.LIVELY, 1f)))
        joinIn(30, joined = 3)
        assertEquals(ScoreKeeper.PARTICIPATION_POINTS, end()!!.participation)
    }

    @Test
    fun aVeryLongSongIsJudgedOnItsLastFifteenMinutes() {
        // Joined in for the first five minutes only, then fifteen of the recording alone.
        sing(20 * 60) { if (it < 5 * 60 && it % 10 < 3) 14f else 3f }
        val result = end()!!
        assertEquals(20 * 60, result.seconds)
        assertEquals("the joining fell out of the last fifteen minutes", 0, result.participation)
        // Joined in the last five minutes only: a third of the window, all of its last third.
        sing(20 * 60) { if (it >= 15 * 60 && it % 10 < 3) 14f else 3f }
        val late = end()!!
        assertEquals(10, late.participation)
        assertEquals(ScoreKeeper.COMPLETION_POINTS, late.completion)
    }

    @Test
    fun aSongEndedByHandJustOver10sIsScored() {
        sing(11) { 6f }
        assertNotNull(end())
    }

    @Test
    fun aLevelThatIsNotANumberCountsAsQuiet() {
        repeat((60 * fps).toInt()) { assertNull(keeper.hear(AudioEvent.Frame(loudness = Float.NaN, voiced = false))) }
        assertEquals(SongResult(participation = 0, heat = 0, response = 0, completion = 0, seconds = 60), end())
    }

    @Test
    fun gradesStartAt50And80() {
        assertEquals(Grade.BASIC, Grade.of(49))
        assertEquals(Grade.GOOD, Grade.of(50))
        assertEquals(Grade.GOOD, Grade.of(79))
        assertEquals(Grade.BEST, Grade.of(80))
        assertEquals(Grade.BEST, SongResult(participation = 30, heat = 25, response = 25, completion = 0, seconds = 60).grade)
    }
}
