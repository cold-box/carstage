package com.carstage.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AccompanimentTest {
    private val accompaniment = Accompaniment(bins = 3)
    private val out = FloatArray(3)

    private fun feed(vararg values: Float) = accompaniment.estimate(values, out)

    @Test
    fun aSteadySoundIsAllAccompaniment() {
        repeat(5) { feed(1f, 2f, 3f) }
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), out, 0f)
    }

    @Test
    fun aShortBurstStandsOut() {
        repeat(Accompaniment.HISTORY_FRAMES) { feed(1f, 1f, 1f) }
        repeat(5) { feed(1f, 10f, 1f) }
        assertEquals("the burst bin keeps the music's level", 1f, out[1], 0f)
    }

    @Test
    fun theEstimateNeverExceedsTheFrame() {
        repeat(10) { feed(5f, 5f, 5f) }
        feed(1f, 5f, 5f)
        assertEquals(1f, out[0], 0f)
    }

    @Test
    fun aChangeThatLastsTakesOverWithinTheHistory() {
        repeat(Accompaniment.HISTORY_FRAMES) { feed(1f, 1f, 1f) }
        // The 20th percentile moves once fewer than about a fifth of the frames are the old ones.
        repeat(29) { feed(4f, 4f, 4f) }
        assertEquals(4f, out[0], 0.01f)
    }
}
