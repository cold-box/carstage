package com.carstage.app

import com.carstage.app.audio.Track
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackEndsTest {
    private val ends = TrackEnds()

    private fun song(
        title: String?,
        artist: String? = "Artist",
        playing: Boolean = true,
        positionMs: Long = 10_000,
        atMs: Long = 0,
        durationMs: Long? = 200_000,
        mediaId: String? = null,
    ) = Track("music.app", title, artist, playing, positionMs, atMs, 1f, durationMs, mediaId)

    @Test
    fun aStageThatOpensMidSongStartsFromIt() {
        assertFalse(ends.next(song("A"), open = false, nowMs = 0))
        assertFalse("the song playing as the stage opens is not an end", ends.next(song("A"), open = true, nowMs = 1_000))
    }

    @Test
    fun anotherSongEndsTheOneBeingSung() {
        ends.next(song("A"), open = true, nowMs = 0)
        assertTrue(ends.next(song("B", positionMs = 0, atMs = 60_000), open = true, nowMs = 60_000))
        assertFalse("the same song again says nothing", ends.next(song("B", positionMs = 1_000, atMs = 61_000), open = true, nowMs = 61_000))
    }

    @Test
    fun theAppsOwnIdTellsSongsApartBeforeTheTitle() {
        ends.next(song("Same", mediaId = "1"), open = true, nowMs = 0)
        assertTrue("two songs of one title", ends.next(song("Same", positionMs = 0, atMs = 1, mediaId = "2"), open = true, nowMs = 1))
    }

    @Test
    fun aPauseASeekOrALostSessionIsNotAnEnd() {
        ends.next(song("A"), open = true, nowMs = 0)
        assertFalse("paused mid-song", ends.next(song("A", playing = false, positionMs = 50_000, atMs = 40_000), open = true, nowMs = 40_000))
        assertFalse("played again", ends.next(song("A", positionMs = 50_000, atMs = 50_000), open = true, nowMs = 50_000))
        assertFalse("sought back to the top mid-song", ends.next(song("A", positionMs = 0, atMs = 60_000), open = true, nowMs = 60_000))
        assertFalse("the session went away", ends.next(null, open = true, nowMs = 70_000))
        assertFalse("and came back with it", ends.next(song("A", positionMs = 20_000, atMs = 80_000), open = true, nowMs = 80_000))
    }

    @Test
    fun aSongPlayedToItsEndEndsOnceAndMovingOnFromItIsTheSameEnd() {
        ends.next(song("A", positionMs = 0, atMs = 0), open = true, nowMs = 0)
        assertTrue(ends.next(song("A", playing = false, positionMs = 199_000, atMs = 199_000), open = true, nowMs = 199_000))
        assertFalse("still stopped there", ends.next(song("A", playing = false, positionMs = 199_000, atMs = 199_000), open = true, nowMs = 200_000))
        assertFalse("the next song after it", ends.next(song("B", positionMs = 0, atMs = 205_000), open = true, nowMs = 205_000))
        assertTrue("which ends in its turn", ends.next(song("C", positionMs = 0, atMs = 400_000), open = true, nowMs = 400_000))
    }

    @Test
    fun aSongPlayedAgainFromMidwayAfterItEndedEndsAgain() {
        ends.next(song("A", positionMs = 0, atMs = 0), open = true, nowMs = 0)
        assertTrue(ends.next(song("A", playing = false, positionMs = 199_500, atMs = 199_500), open = true, nowMs = 199_500))
        assertFalse("played again from the chorus", ends.next(song("A", positionMs = 90_000, atMs = 210_000), open = true, nowMs = 210_000))
        assertTrue("stopping at its end again", ends.next(song("A", playing = false, positionMs = 199_500, atMs = 320_000), open = true, nowMs = 320_000))
        assertFalse("moving on from that stop", ends.next(song("B", positionMs = 0, atMs = 330_000), open = true, nowMs = 330_000))
    }

    @Test
    fun movingOnAfterSingingItAgainFromMidwayEndsIt() {
        ends.next(song("A", positionMs = 0, atMs = 0), open = true, nowMs = 0)
        ends.next(song("A", playing = false, positionMs = 199_500, atMs = 199_500), open = true, nowMs = 199_500)
        ends.next(song("A", positionMs = 90_000, atMs = 210_000), open = true, nowMs = 210_000)
        assertTrue(ends.next(song("B", positionMs = 0, atMs = 300_000), open = true, nowMs = 300_000))
    }

    @Test
    fun aSongStoppedAtItsEndAndStartedOverEndsOncePerPlayThrough() {
        ends.next(song("A", positionMs = 0, atMs = 0), open = true, nowMs = 0)
        assertTrue(ends.next(song("A", playing = false, positionMs = 199_500, atMs = 199_500), open = true, nowMs = 199_500))
        assertFalse("starting over after that stop is the same end", ends.next(song("A", positionMs = 0, atMs = 201_000), open = true, nowMs = 201_000))
        assertTrue("the second play-through ends too", ends.next(song("A", playing = false, positionMs = 199_500, atMs = 400_500), open = true, nowMs = 400_500))
    }

    @Test
    fun theSameSongWhoseDetailsArriveLaterIsNotAnEnd() {
        ends.next(song("A", artist = null), open = true, nowMs = 0)
        assertFalse("the artist arrives", ends.next(song("A", artist = "Artist", positionMs = 30_000, atMs = 30_000), open = true, nowMs = 30_000))
        assertFalse("then the app's id", ends.next(song("A", mediaId = "42", positionMs = 40_000, atMs = 40_000), open = true, nowMs = 40_000))
        assertFalse("a title that went blank for a moment", ends.next(song(null, mediaId = "42", positionMs = 50_000, atMs = 50_000), open = true, nowMs = 50_000))
        assertTrue("another id is another song", ends.next(song("A2", mediaId = "43", positionMs = 0, atMs = 60_000), open = true, nowMs = 60_000))
    }

    @Test
    fun aSongPlayedAgainOnRepeatEndsAsItStartsOver() {
        // Playing from the top since 0, so by 199 s it is at its end.
        ends.next(song("A", positionMs = 0, atMs = 0), open = true, nowMs = 0)
        assertTrue(ends.next(song("A", positionMs = 0, atMs = 200_500), open = true, nowMs = 200_500))
    }

    @Test
    fun changesWhileTheStageIsClosedEndNothing() {
        ends.next(song("A"), open = true, nowMs = 0)
        assertFalse(ends.next(song("A"), open = false, nowMs = 1_000))
        assertFalse(ends.next(song("B"), open = false, nowMs = 2_000))
        assertFalse("the next stage starts from B", ends.next(song("B"), open = true, nowMs = 3_000))
    }

    @Test
    fun aSongLeftPausedBeforeTheStageOpenedIsNotASongSung() {
        ends.next(song("X", playing = false, positionMs = 60_000), open = false, nowMs = 0)
        assertFalse(ends.next(song("X", playing = false, positionMs = 60_000), open = true, nowMs = 1_000))
        assertFalse("the music starts 20 s later", ends.next(song("Y", positionMs = 0, atMs = 20_000), open = true, nowMs = 20_000))
        assertTrue("and Y, heard playing, ends", ends.next(song("Z", positionMs = 0, atMs = 200_000), open = true, nowMs = 200_000))
    }

    @Test
    fun aStageOpenedOnASongStoppedAtItsEndEndsNothing() {
        ends.next(song("X", playing = false, positionMs = 199_500), open = false, nowMs = 0)
        assertFalse(ends.next(song("X", playing = false, positionMs = 199_500), open = true, nowMs = 100))
    }

    @Test
    fun anotherAppsSessionThatDoesNotPlayIsNotWhereTheMusicWent() {
        ends.next(song("A"), open = true, nowMs = 0)
        val other = Track("other.app", "Z", "Artist", false, 30_000, 10_000, 1f, 200_000)
        assertFalse(ends.next(other, open = true, nowMs = 10_000))
        assertFalse("A again is the same song", ends.next(song("A", positionMs = 20_000, atMs = 20_000), open = true, nowMs = 20_000))
        assertTrue("another app that plays is", ends.next(other.copy(playing = true, updatedAtMs = 30_000), open = true, nowMs = 30_000))
    }

    @Test
    fun aSessionThatNamesNoSongEndsNothing() {
        ends.next(song(null), open = true, nowMs = 0)
        assertFalse(ends.next(song(null, positionMs = 0, atMs = 1_000), open = true, nowMs = 1_000))
    }
}
