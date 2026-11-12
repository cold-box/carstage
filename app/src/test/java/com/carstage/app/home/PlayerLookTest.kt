package com.carstage.app.home

import com.carstage.app.audio.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerLookTest {
    private val song = Track("org.videolan.vlc", "Big Rock", "Kevin MacLeod", playing = true, positionMs = 0, updatedAtMs = 0, speed = 1f, durationMs = 230_000)

    @Test
    fun aReadableSongShowsItsTitleAndArtist() {
        assertEquals(
            PlayerLook("Big Rock", "Kevin MacLeod", playing = true, track = song, asksAccess = false),
            PlayerLook.of(song, access = true, musicPlaying = true, pausedHere = false),
        )
    }

    @Test
    fun aPausedSongStaysWithItsTitle() {
        val paused = song.copy(playing = false)
        assertEquals(PlayerLook("Big Rock", "Kevin MacLeod", playing = false, track = paused, asksAccess = false), PlayerLook.of(paused, access = true, musicPlaying = false, pausedHere = false))
    }

    @Test
    fun aSongWithoutATitleIsNamedAsSuch() {
        assertEquals(PlayerLook.UNTITLED, PlayerLook.of(song.copy(title = null), access = true, musicPlaying = true, pausedHere = false)?.title)
    }

    @Test
    fun withoutAccessMusicShowsButtonsAndTheOfferToReadTheSong() {
        assertEquals(
            PlayerLook(PlayerLook.PLAYING, null, playing = true, track = null, asksAccess = true),
            PlayerLook.of(null, access = false, musicPlaying = true, pausedHere = false),
        )
    }

    @Test
    fun withAccessButNoSessionItDoesNotAskAgain() {
        assertEquals(
            PlayerLook(PlayerLook.PLAYING, null, playing = true, track = null, asksAccess = false),
            PlayerLook.of(null, access = true, musicPlaying = true, pausedHere = false),
        )
        assertEquals(false, PlayerLook.of(null, access = true, musicPlaying = false, pausedHere = true)?.asksAccess)
    }

    @Test
    fun aReadableSongWinsOverEverythingElse() {
        assertEquals(
            PlayerLook("Big Rock", "Kevin MacLeod", playing = true, track = song, asksAccess = false),
            PlayerLook.of(song, access = true, musicPlaying = false, pausedHere = true),
        )
    }

    @Test
    fun anOpenStageDoesNotOfferTheSettingsThatWouldCloseIt() {
        assertEquals(false, PlayerLook.of(null, access = false, musicPlaying = true, pausedHere = false, stageClosed = false)?.asksAccess)
    }

    @Test
    fun aPauseFromThePlayerKeepsItToPlayAgain() {
        assertEquals(
            PlayerLook(PlayerLook.PAUSED, null, playing = false, track = null, asksAccess = true),
            PlayerLook.of(null, access = false, musicPlaying = false, pausedHere = true),
        )
    }

    @Test
    fun noMusicAndNoSongHidesThePlayer() {
        assertNull(PlayerLook.of(null, access = true, musicPlaying = false, pausedHere = false))
        assertNull(PlayerLook.of(null, access = false, musicPlaying = false, pausedHere = false))
    }
}
