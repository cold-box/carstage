package com.carstage.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongTitlesTest {
    private val titles = SongTitles()

    @Test
    fun aScoreTakesTheTitleThatPlayedOverMostOfTheSong() {
        titles.heard("A", 0)
        titles.heard("B", 180_000)
        assertEquals("ended as B began", "A", titles.titleOf(endMs = 181_000, seconds = 180))
        assertEquals("ended at the gap, before B", "A", titles.titleOf(endMs = 179_000, seconds = 178))
    }

    @Test
    fun aSongWithoutMusicForMostOfItHasNoTitle() {
        titles.heard(null, 0)
        titles.heard("A", 50_000)
        assertNull("music only for its last 10 s of 60", titles.titleOf(endMs = 60_000, seconds = 60))
        assertNull("nothing heard yet", SongTitles().titleOf(endMs = 1_000, seconds = 1))
    }

    @Test
    fun aPauseDoesNotCountForTheSong() {
        titles.heard("A", 0)
        titles.heard(null, 30_000)
        titles.heard("A", 90_000)
        assertEquals("A played 60 s of the 120: exactly half is enough", "A", titles.titleOf(endMs = 120_000, seconds = 120))
        assertNull("A played 50 s of the 110 up to 110 s", titles.titleOf(endMs = 110_000, seconds = 110))
    }

    @Test
    fun justUnderHalfIsNotEnough() {
        titles.heard(null, 0)
        titles.heard("A", 60_001)
        assertNull("A played 59.999 s of the 120", titles.titleOf(endMs = 120_000, seconds = 120))
    }

    @Test
    fun forgettingOldSongsKeepsTheOneStillPlaying() {
        val short = SongTitles(keepMs = 1_000)
        short.heard("A", 0)
        short.heard("B", 10_000)
        assertEquals("A began before the window but played over most of the song", "A", short.titleOf(endMs = 10_500, seconds = 10))
        short.heard("C", 20_000)
        assertEquals("B is kept as it played up to C", "B", short.titleOf(endMs = 20_500, seconds = 10))
    }
}
