package com.carstage.app.home

import com.carstage.app.audio.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPauseTest {
    private val unreadablePlaying = PlayerLook.of(null, access = false, musicPlaying = true, pausedHere = false)
    private val song = Track("org.videolan.vlc", "Big Rock", null, playing = true, positionMs = 0, updatedAtMs = 0, speed = 1f, durationMs = null)

    @Test
    fun pausingUnreadableMusicKeepsThePlayer() {
        val pause = PlayerPause()
        assertTrue(pause.press(unreadablePlaying, musicPlaying = true))
        assertTrue(pause.pausedHere.value)
        assertNotNull(PlayerLook.of(null, access = false, musicPlaying = false, pausedHere = pause.pausedHere.value))
    }

    @Test
    fun playingAgainKeepsThePlayerUntilMusicStarts() {
        val pause = PlayerPause()
        pause.press(unreadablePlaying, musicPlaying = true)
        val paused = PlayerLook.of(null, access = false, musicPlaying = false, pausedHere = true)
        assertFalse(pause.press(paused, musicPlaying = false))
        // The music app may ignore the key; the player must still be there to press again.
        assertNotNull(PlayerLook.of(null, access = false, musicPlaying = false, pausedHere = pause.pausedHere.value))
    }

    @Test
    fun musicStartingFromAnyAppClearsThePause() {
        val pause = PlayerPause()
        pause.press(unreadablePlaying, musicPlaying = true)
        pause.onMusicPlaying(true)
        assertFalse(pause.pausedHere.value)
    }

    @Test
    fun musicStoppingByItselfDoesNotKeepThePlayer() {
        val pause = PlayerPause()
        pause.onMusicPlaying(false)
        assertFalse(pause.pausedHere.value)
    }

    @Test
    fun aReadableSongNeedsNoPause() {
        val pause = PlayerPause()
        val look = PlayerLook.of(song, access = true, musicPlaying = true, pausedHere = false)
        assertTrue(pause.press(look, musicPlaying = true))
        assertFalse(pause.pausedHere.value)
    }

    @Test
    fun theButtonFollowsWhatThePlayerShows() {
        val pausedSong = PlayerLook.of(song.copy(playing = false), access = true, musicPlaying = true, pausedHere = false)
        assertEquals(false, PlayerPause().press(pausedSong, musicPlaying = true))
        assertEquals(true, PlayerPause().press(null, musicPlaying = true))
    }
}
