package com.carstage.app.audio

import com.carstage.app.stage.DuelLines
import com.carstage.app.stage.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The timing of [Announcer] that needs no engine: how long a line takes, and the most it may hold focus. */
class AnnouncerTest {
    @Test
    fun aLineIsEstimatedBySyllablesAndLimitedToTwiceThatAndASecond() {
        val opening = DuelLines.OPENING
        assertEquals(300L + 130L * opening.length, Announcer.estimateMs(opening))
        assertEquals(2 * Announcer.estimateMs(opening) + 1_000L, Announcer.limitMs(opening))
    }

    @Test
    fun theMcsLinesFitWellUnderTheLimit() {
        listOf(DuelLines.OPENING, DuelLines.crown(Side.PASSENGER, 100), DuelLines.crown(null, 0)).forEach { line ->
            assertTrue("$line: within two seconds or so", Announcer.estimateMs(line) in 1_000L..2_800L)
            assertTrue(Announcer.limitMs(line) <= Announcer.MAX_LINE_MS)
        }
    }

    @Test
    fun aLongLineIsCutAtTheMost() {
        assertEquals(Announcer.MAX_LINE_MS, Announcer.limitMs("가".repeat(200)))
    }
}
