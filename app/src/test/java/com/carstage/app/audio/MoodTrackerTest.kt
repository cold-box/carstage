package com.carstage.app.audio

import com.carstage.app.stage.AudioEvent
import com.carstage.app.stage.AudioEvent.Mood.Feel
import kotlin.math.floor
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodTrackerTest {
    private val fps = 48_000f / 2_048
    private val tracker = MoodTracker(fps)
    private val random = Random(1)
    private var frame = 0
    private val moods = mutableListOf<AudioEvent.Mood>()

    /**
     * Feeds [seconds] of drum-band levels: a hit of [hitDb] on every beat at [bpm] (or at random
     * times, as often, when not [regular]) that dies away over 4 frames, over a level that wobbles by
     * [wobbleDb]; the accompaniment at [accompanimentDb].
     */
    private fun feed(
        seconds: Float,
        hitDb: Float = 0f,
        bpm: Float = 120f,
        regular: Boolean = true,
        wobbleDb: Float = 0.5f,
        accompanimentDb: Float = -30f,
    ) {
        val framesPerBeat = fps * 60 / bpm
        var sinceHit = 99
        repeat((seconds * fps).toInt()) {
            val hit = if (regular) floor(frame / framesPerBeat) != floor((frame - 1) / framesPerBeat) else random.nextFloat() < 1 / framesPerBeat
            if (hit) sinceHit = 0 else sinceHit++
            val swell = if (sinceHit < 4) hitDb * (4 - sinceHit) / 4 else 0f
            val bands = FloatArray(5) { -40f + swell + (random.nextFloat() - 0.5f) * 2 * wobbleDb }
            tracker.process(bands, accompanimentDb)?.let { moods += it }
            frame++
        }
    }

    @Test
    fun theFirstMoodComesAfter6sAndThenEvery2s() {
        repeat(140) { assertNull(tracker.process(FloatArray(5) { -40f }, -30f)) }
        assertTrue("at 6 s", tracker.process(FloatArray(5) { -40f }, -30f) != null)
        val next = (1..47).map { tracker.process(FloatArray(5) { -40f }, -30f) }
        assertTrue("2 s later", next.dropLast(1).all { it == null } && next.last() != null)
    }

    @Test
    fun aSteadyBeatWithDrumsIsLively() {
        feed(20f, hitDb = 30f)
        assertEquals(Feel.LIVELY, moods.last().feel)
        assertEquals(120.0, tracker.beatsPerMinute, 6.0)
    }

    @Test
    fun theBeatIsMeasuredAtTheSongsTempoNotTwiceOrHalf() {
        feed(20f, hitDb = 30f, bpm = 90f)
        assertEquals(90.0, tracker.beatsPerMinute, 5.0)
    }

    @Test
    fun musicWithoutDrumsIsCalm() {
        feed(20f, wobbleDb = 1f)
        assertTrue(moods.all { it.feel == Feel.CALM })
    }

    @Test
    fun loudRisesThatDoNotRepeatAreNotLively() {
        // Talk: as many big rises as a lively song, but at random times.
        feed(30f, hitDb = 30f, regular = false)
        assertTrue(moods.joinToString { it.feel.name }, moods.none { it.feel == Feel.LIVELY })
    }

    @Test
    fun aLivelySongStaysLivelyWhenTheDrumsSoftenALittle() {
        // Without the wobble, the 12 or 13 hits in 6 s drive about 12 × hit ÷ 141 dB a frame.
        feed(20f, hitDb = 30f, wobbleDb = 0f)
        // 22 dB hits drive 1.87 dB a frame: below the 2 dB to become lively, within the margin to stay.
        feed(30f, hitDb = 22f, wobbleDb = 0f)
        assertEquals(Feel.LIVELY, moods.last().feel)
        feed(30f, hitDb = 17f, wobbleDb = 0f)
        assertEquals("well below, it leaves", Feel.MODERATE, moods.last().feel)
        feed(30f, wobbleDb = 0f)
        assertEquals(Feel.CALM, moods.last().feel)
        assertEquals("lively, moderate, calm", 2, tracker.changes)
    }

    @Test
    fun aCalmSongStaysCalmWhenTheDrumsComeInSoftly() {
        feed(20f, wobbleDb = 0f)
        // 17 dB hits drive 1.45 dB a frame: above the 1.4 dB of calm, within the margin to stay.
        feed(30f, hitDb = 17f, wobbleDb = 0f)
        assertEquals(Feel.CALM, moods.last().feel)
        assertEquals(0, tracker.changes)
    }

    @Test
    fun theFeelIsKeptUntilTheValuesMovePastTheMargin() {
        fun decide(current: Feel, drive: Double, beat: Double) = MoodTracker.decide(current, drive, beat)
        assertEquals("both at the thresholds", Feel.LIVELY, decide(Feel.MODERATE, 2.0, 0.35))
        assertEquals("a clear beat with soft drums", Feel.MODERATE, decide(Feel.MODERATE, 1.99, 0.8))
        assertEquals("strong drums without a clear beat", Feel.MODERATE, decide(Feel.MODERATE, 2.5, 0.34))
        assertEquals("lively stays within the beat margin", Feel.LIVELY, decide(Feel.LIVELY, 2.5, 0.31))
        assertEquals("and leaves past it", Feel.MODERATE, decide(Feel.LIVELY, 2.5, 0.29))
        assertEquals("lively stays within the drive margin", Feel.LIVELY, decide(Feel.LIVELY, 1.81, 0.5))
        assertEquals("and leaves past it", Feel.MODERATE, decide(Feel.LIVELY, 1.79, 0.5))
        assertEquals("well below, straight to calm", Feel.CALM, decide(Feel.LIVELY, 1.0, 0.5))
        assertEquals(Feel.CALM, decide(Feel.MODERATE, 1.39, 0.0))
        assertEquals(Feel.MODERATE, decide(Feel.MODERATE, 1.41, 0.0))
        assertEquals("calm stays within the margin", Feel.CALM, decide(Feel.CALM, 1.59, 0.9))
        assertEquals("and leaves past it", Feel.MODERATE, decide(Feel.CALM, 1.61, 0.0))
        assertEquals("from calm straight to lively", Feel.LIVELY, decide(Feel.CALM, 2.5, 0.5))
    }

    @Test
    fun aLivelySongTurnsModerateWhenTheBeatGoesAway() {
        feed(20f, hitDb = 30f)
        assertEquals(Feel.LIVELY, moods.last().feel)
        // Talk: louder rises than the drums, at random times.
        feed(30f, hitDb = 40f, regular = false)
        assertEquals(Feel.MODERATE, moods.last().feel)
    }

    @Test
    fun aShortBreakInTheDrumsKeepsTheSongLively() {
        feed(20f, hitDb = 30f, wobbleDb = 0f)
        // Unsmoothed, the window over the break reads about 1.7 dB a frame, below the margin.
        feed(2f, wobbleDb = 0f)
        feed(12f, hitDb = 30f, wobbleDb = 0f)
        assertTrue(moods.all { it.feel == Feel.LIVELY })
        assertEquals(0, tracker.changes)
    }

    @Test
    fun aBriefPeakDoesNotStretchTheRange() {
        // 30 s at -30 dB with one 0.4 s peak at -10 dB, then 2 s at -25 dB: 5 dB up a 6 dB range.
        repeat((30 * fps).toInt()) { i -> tracker.process(FloatArray(5) { -40f }, if (i in 300..309) -10f else -30f) }
        repeat(49) { tracker.process(FloatArray(5) { -40f }, -25f)?.let { moods += it } }
        assertTrue("${moods.last().energy}", moods.last().energy > 0.7f)
    }

    @Test
    fun theFirstFrameAfterDigitalSilenceIsNotAHit() {
        // Every quarter of a second the microphone is lost and comes back 60 dB louder or quieter,
        // regularly like a beat; without forgetting the last frame those returns would read as
        // lively drums.
        repeat((20 * fps).toInt()) {
            if (it % 6 == 0) tracker.interrupt()
            val level = if (it / 6 % 2 == 0) -70f else -10f
            tracker.process(FloatArray(5) { level }, -30f)?.let { mood -> moods += mood }
        }
        assertTrue(moods.all { it.feel == Feel.CALM })
    }

    @Test
    fun energySaysWhereTheAccompanimentIsWithinTheLast30s() {
        feed(20f, accompanimentDb = -30f)
        assertEquals("a song that does not change sits at its quiet end", 0f, moods.last().energy, 0.01f)
        feed(4f, accompanimentDb = -20f)
        assertTrue("a louder chorus: ${moods.last().energy}", moods.last().energy > 0.9f)
        feed(40f, accompanimentDb = -20f)
        assertEquals("30 s later the chorus is the new quiet end", 0f, moods.last().energy, 0.01f)
    }

    @Test
    fun aSmallWobbleOfTheAccompanimentStaysSmall() {
        feed(20f, accompanimentDb = -30f)
        feed(4f, accompanimentDb = -29f)
        assertTrue("1 dB of a 6 dB range at most: ${moods.last().energy}", moods.last().energy <= 1f / 6 + 0.01f)
    }

    @Test
    fun eachReportIsCounted() {
        feed(20f, hitDb = 30f)
        assertEquals(moods.size, Feel.entries.sumOf { tracker.timesReported(it) })
        assertEquals(moods.count { it.feel == Feel.LIVELY }, tracker.timesReported(Feel.LIVELY))
    }
}
