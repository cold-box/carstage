package com.carstage.app

import com.carstage.app.PassengerPick.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PassengerPickTest {
    private val pick = PassengerPick()

    @Test
    fun outsideAPickAnEndEndsTheSong() {
        assertEquals(Step.SONG_ENDED, pick.next(ended = true, playing = true))
        assertEquals(Step.NONE, pick.next(ended = false, playing = true))
    }

    @Test
    fun anotherSongPickedWhilePausedStartsTheTurn() {
        pick.start(paused = true)
        assertEquals(Step.NONE, pick.next(ended = false, playing = false))
        assertEquals(Step.PICKED, pick.next(ended = true, playing = true))
        assertFalse(pick.waiting)
    }

    @Test
    fun thePausedSongPlayedOnStartsTheTurnOnceSeenPaused() {
        pick.start(paused = true)
        assertEquals("still playing as the pause was sent", Step.NONE, pick.next(ended = false, playing = true))
        assertEquals(Step.NONE, pick.next(ended = false, playing = false))
        assertEquals(Step.PICKED, pick.next(ended = false, playing = true))
    }

    @Test
    fun aSongPausedInItsLastSecondsReadsAsPlayedOutButIsNoPick() {
        // Seen in a rehearsal (2026-11-02): the driver's song ended by request at 0:29 of 0:30, the
        // pause for the pick read as played to its end, and the passenger's turn began before a pick.
        pick.start(paused = true)
        assertEquals(Step.NONE, pick.next(ended = true, playing = false))
        assertEquals("still waiting", true, pick.waiting)
        assertEquals("the song they pick starts the turn", Step.PICKED, pick.next(ended = false, playing = true))
    }

    @Test
    fun musicThatHadStoppedByItselfCountsAsPaused() {
        pick.start(paused = false)
        assertEquals(Step.PICKED, pick.next(ended = false, playing = true))
    }

    @Test
    fun aStoppedWaitEndsSongsAgain() {
        pick.start(paused = true)
        pick.stop()
        assertEquals(Step.SONG_ENDED, pick.next(ended = true, playing = true))
    }

    @Test
    fun aPickedSongNamedBeforeItPlaysStartsTheTurnWhenItPlays() {
        // The music app names the picked song while it loads (paused, then buffering), then plays it.
        pick.start(paused = true)
        assertEquals(Step.NONE, pick.next(ended = false, playing = false))
        assertEquals("named, not playing yet", Step.NONE, pick.next(ended = true, playing = false))
        assertEquals(Step.PICKED, pick.next(ended = false, playing = true))
    }
}
