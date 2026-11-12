package com.carstage.app.audio

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongEndDetectorTest {
    private val fps = 48_000f / 2_048

    /** One stage's detector, fed in seconds or frames; [ends] holds the frames that ended a song. */
    private class Run(val fps: Float) {
        val detector = SongEndDetector(fps)
        var frame = 0
        val ends = mutableListOf<Int>()

        fun frames(count: Int, frameDb: Float, accompanimentDb: Float = frameDb) = repeat(count) {
            if (detector.process(frameDb, accompanimentDb)) ends += frame
            frame++
        }

        fun feed(seconds: Float, frameDb: Float, accompanimentDb: Float = frameDb) = frames((seconds * fps).toInt(), frameDb, accompanimentDb)

        /** A song whose accompaniment sits 10 dB under its sound. */
        fun song(seconds: Float) = feed(seconds, frameDb = -20f, accompanimentDb = -30f)

        /** The cabin between songs, 20 dB under the song's accompaniment. */
        fun gap(seconds: Float) = feed(seconds, frameDb = -50f)
    }

    private val run = Run(fps)

    /** The frame of a gap that ends the song: the last of a second of quiet frames. */
    private val lastOfASecond = (SongEndDetector.END_SECONDS * fps).roundToInt() - 1

    @Test
    fun aSecondOfQuietAfterASongEndsItOnce() {
        run.song(40f)
        val gapStart = run.frame
        run.gap(3f)
        assertEquals("one end, a second into the gap", listOf(gapStart + lastOfASecond), run.ends)
        assertEquals(1, run.detector.songsEnded)
    }

    @Test
    fun theEndComesASecondIntoTheGapWhereverTheGapStarts() {
        // Three seconds of start points, so some gaps straddle the level's update every 2 s.
        repeat(70) { offset ->
            val other = Run(fps)
            other.song(40f)
            other.frames(offset, frameDb = -20f, accompanimentDb = -30f)
            val gapStart = other.frame
            other.gap(3f)
            assertEquals("gap starting $offset frames later", listOf(gapStart + lastOfASecond), other.ends)
        }
    }

    @Test
    fun anEndNeeds30sOfSound() {
        run.song(29.5f)
        run.gap(2f)
        assertTrue("half a second short", run.ends.isEmpty())
        run.song(1f)
        run.gap(2f)
        assertEquals("half a second over: the gap in between did not count, the song before it did", 1, run.ends.size)
    }

    @Test
    fun aPauseBetweenSongsIsNotPartOfTheNextOne() {
        run.song(40f)
        run.gap(2f)
        assertEquals(1, run.ends.size)
        // A 20 s pause, then a song with a dip 8 s in: the pause does not count towards its 30 s.
        run.gap(20f)
        run.song(8f)
        run.gap(2f)
        assertEquals(1, run.ends.size)
    }

    @Test
    fun aGapShorterThanASecondIsNotAnEnd() {
        run.song(40f)
        run.gap(0.9f)
        run.song(5f)
        assertTrue(run.ends.isEmpty())
    }

    @Test
    fun theGapMustBe15dBUnderTheAccompaniment() {
        run.song(40f)
        run.feed(2f, frameDb = -44f)
        assertTrue("14 dB under is a soft passage", run.ends.isEmpty())
        run.song(5f)
        run.feed(2f, frameDb = -45f)
        assertEquals("15 dB under is a gap", 1, run.ends.size)
    }

    @Test
    fun theSongsLevelIsItsLoudPartsNotItsQuietVerses() {
        // A quiet verse and a loud chorus: 17 dB under the chorus's accompaniment is a gap, though only 7 dB under the verse's.
        run.feed(20f, frameDb = -28f, accompanimentDb = -35f)
        run.feed(15f, frameDb = -18f, accompanimentDb = -25f)
        run.feed(2f, frameDb = -42f)
        assertEquals(1, run.ends.size)
    }

    @Test
    fun aFewLoudSecondsDoNotSetTheLevel() {
        run.song(35f)
        run.feed(4.5f, frameDb = 0f, accompanimentDb = -10f)
        run.song(3f)
        run.feed(2f, frameDb = -42f)
        assertTrue("12 dB under the song is a soft passage", run.ends.isEmpty())
    }

    @Test
    fun aSoftOutroThenAGapStillEnds() {
        run.song(40f)
        run.feed(9f, frameDb = -40f, accompanimentDb = -50f)
        assertTrue("an outro 10 dB under the song is not a gap", run.ends.isEmpty())
        run.gap(2f)
        assertEquals(1, run.ends.size)
    }

    @Test
    fun aQuieterNextSongIsMeasuredOnItsOwn() {
        run.feed(40f, frameDb = -10f, accompanimentDb = -20f)
        run.feed(2f, frameDb = -60f)
        assertEquals(1, run.ends.size)
        // The music turned 20 dB down: its soft passages are not gaps once the last 30 s are its own.
        run.feed(30f, frameDb = -30f, accompanimentDb = -40f)
        run.feed(2f, frameDb = -48f)
        assertEquals("8 dB under the quieter song's accompaniment", 1, run.ends.size)
        run.feed(2f, frameDb = -60f)
        assertEquals(2, run.ends.size)
    }

    @Test
    fun digitalSilenceBreaksAQuietRun() {
        run.song(40f)
        run.gap(0.6f)
        run.detector.interrupt()
        run.gap(0.6f)
        assertTrue("the microphone was gone in between", run.ends.isEmpty())
    }

    @Test
    fun eachSongEndsOnce() {
        run.song(40f)
        run.gap(2f)
        run.song(40f)
        run.gap(2f)
        assertEquals(2, run.ends.size)
        assertEquals(2, run.detector.songsEnded)
    }

    @Test
    fun secondsNotFramesAt44kHz() {
        val other = Run(44_100f / 2_048)
        other.song(29.5f)
        other.gap(2f)
        assertTrue(other.ends.isEmpty())
        other.song(1f)
        val gapStart = other.frame
        other.gap(2f)
        assertEquals(listOf(gapStart + (44_100f / 2_048).roundToInt() - 1), other.ends)
    }
}
