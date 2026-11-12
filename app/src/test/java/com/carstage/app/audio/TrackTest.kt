package com.carstage.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTest {
    private val song = Track(
        app = "org.videolan.vlc",
        title = "아리랑 응원가",
        artist = "윤도현",
        playing = true,
        positionMs = 26_000,
        updatedAtMs = 1_000_000,
        speed = 1f,
        durationMs = 201_000,
    )

    @Test
    fun aPlayingSongMovesOnFromItsLastUpdate() {
        assertEquals(36_000L, song.positionAt(1_010_000))
    }

    @Test
    fun aPausedSongStaysWhereItStopped() {
        assertEquals(26_000L, song.copy(playing = false).positionAt(1_010_000))
    }

    @Test
    fun thePlaybackSpeedScalesTheTimeSinceTheUpdate() {
        assertEquals(46_000L, song.copy(speed = 2f).positionAt(1_010_000))
    }

    @Test
    fun thePositionStopsAtTheSongsEnd() {
        assertEquals(201_000L, song.positionAt(2_000_000))
    }

    @Test
    fun aClockBehindTheUpdateDoesNotMoveTheSongBack() {
        assertEquals(26_000L, song.positionAt(999_000))
    }

    @Test
    fun anUnknownPositionStaysUnknown() {
        assertNull(song.copy(positionMs = null).positionAt(1_010_000))
    }

    @Test
    fun aSongWithoutALengthKeepsCounting() {
        assertEquals(1_026_000L, song.copy(durationMs = null).positionAt(2_000_000))
    }

    @Test
    fun clockShowsMinutesAndPaddedSeconds() {
        assertEquals("0:00", Track.clock(0))
        assertEquals("0:26", Track.clock(26_999))
        assertEquals("3:21", Track.clock(201_000))
        assertEquals("61:05", Track.clock(3_665_000))
        assertEquals("0:00", Track.clock(-5))
    }

    private data class Session(val app: String, val playing: Boolean)

    private fun pick(vararg sessions: Session) = Track.pick(sessions.toList(), own = "com.carstage.app", app = { it.app }, playing = { it.playing })

    @Test
    fun aPlayingSessionWinsOverAMoreRecentPausedOne() {
        assertEquals(Session("melon", true), pick(Session("vlc", false), Session("melon", true)))
    }

    @Test
    fun withNothingPlayingTheMostRecentSessionIsShown() {
        assertEquals(Session("vlc", false), pick(Session("vlc", false), Session("melon", false)))
    }

    @Test
    fun carStagesOwnSessionIsNeverShown() {
        assertEquals(Session("vlc", false), pick(Session("com.carstage.app", true), Session("vlc", false)))
        assertNull(pick(Session("com.carstage.app", true)))
        assertNull(pick())
    }

    private fun of(
        title: String? = null,
        displayTitle: String? = null,
        artist: String? = null,
        albumArtist: String? = null,
        state: Int? = PLAYING,
        position: Long? = 0,
        duration: Long? = 0,
    ) = Track.of("vlc", title, displayTitle, artist, albumArtist, state, position, updatedAtMs = 0, speed = null, durationMs = duration)

    @Test
    fun aBlankTitleFallsBackToTheDisplayTitle() {
        assertEquals("Big Rock", of(title = " ", displayTitle = "Big Rock").title)
        assertNull(of(title = "", displayTitle = " ").title)
    }

    @Test
    fun aBlankArtistFallsBackToTheAlbumArtist() {
        assertEquals("Kevin MacLeod", of(artist = "", albumArtist = "Kevin MacLeod").artist)
        assertNull(of(artist = " ").artist)
    }

    @Test
    fun androidsUnknownValuesBecomeNull() {
        val track = of(position = -1, duration = 0)
        assertNull(track.positionMs)
        assertNull(track.durationMs)
    }

    @Test
    fun onlyThePlayingStatePlays() {
        assertTrue(of().playing)
        assertFalse(of(state = BUFFERING).playing)
        assertFalse(of(state = null).playing)
        assertEquals(1f, of(state = null).speed)
    }

    @Test
    fun aRewindingSpeedNeverGoesBelowTheStart() {
        assertEquals(0L, song.copy(speed = -10f).positionAt(1_010_000))
    }

    private companion object {
        // PlaybackState.STATE_PLAYING and STATE_BUFFERING, compile-time constants.
        const val PLAYING = 3
        const val BUFFERING = 6
    }
}
