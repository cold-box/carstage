package com.carstage.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** Each condition of [CheerDetector] on its own, with values instead of audio. */
class CheerDetectorTest {
    private val detector = CheerDetector()

    /**
     * Feeds [count] frames and returns the 1-based frames that completed a cheer. [tonal] comes
     * before [pitched], the reverse of `process`, so a trailing lambda is `pitched`.
     */
    private fun feed(count: Int, energy: Double, ratioDb: Float, tonal: (Int) -> Boolean = { false }, pitched: (Int) -> Boolean = { false }): List<Int> =
        (1..count).filter { detector.process(energy, ratioDb, pitched(it - 1), tonal(it - 1)) }

    /** The music alone for 5 s: little stands out of it. */
    private fun music() = feed(115, energy = 1e-4, ratioDb = -10f)

    @Test
    fun sevenLoudUnpitchedFramesAreACheerSixAreNot() {
        music()
        assertEquals(emptyList<Int>(), feed(6, 1.0, 20f))
        music()
        assertEquals(listOf(7), feed(7, 1.0, 20f))
    }

    @Test
    fun itMustStandWellOutOfTheMusic() {
        music()
        assertEquals(emptyList<Int>(), feed(20, 1.0, CheerDetector.OVER_ACCOMPANIMENT_DB - 0.1f))
    }

    @Test
    fun itMustRiseAboveTheVoicesRecentEnergy() {
        // Voices standing out a little (not a cheer) for long enough to set the recent energy.
        feed(600, energy = 1.0, ratioDb = 5f)
        assertEquals("2.6 dB above", emptyList<Int>(), feed(7, 1.8, 20f))
        feed(1, 1.0, 5f)
        assertEquals("4 dB above", listOf(7), feed(7, 2.5, 20f))
    }

    @Test
    fun theRecentEnergyIsAPlainMeanUntilFiveSecondsHaveBeenHeard() {
        feed(1, energy = 1e-4, ratioDb = -10f)
        feed(50, energy = 1.0, ratioDb = 5f)
        // 2 dB above the mean so far; a moving average from the first frame would still be near 0.35.
        assertEquals(emptyList<Int>(), feed(7, 1.6, 20f))
    }

    @Test
    fun atMostThreeOfTheSevenFramesMayBePitched() {
        music()
        assertEquals(listOf(7), feed(7, 1.0, 20f) { it < 3 })
        val other = CheerDetector()
        repeat(115) { other.process(1e-4, -10f, false, false) }
        assertEquals(0, (0 until 7).count { other.process(1.0, 20f, it < 4, false) })
    }

    @Test
    fun tonalFramesCountLikePitchedOnes() {
        // A chord: too many notes for a pitch, but its energy in a few peaks.
        music()
        assertEquals(emptyList<Int>(), feed(7, 1.0, 20f, tonal = { it < 4 }))
        val other = CheerDetector()
        repeat(115) { other.process(1e-4, -10f, false, false) }
        assertEquals("two pitched and two tonal are four", 0, (0 until 7).count { other.process(1.0, 20f, it < 2, it in 2..3) })
        music()
        assertEquals("three tonal frames are still a cheer", listOf(7), feed(7, 1.0, 20f, tonal = { it < 3 }))
    }

    @Test
    fun theSevenFramesSlideUntilFewEnoughArePitched() {
        music()
        assertEquals("frames 2 to 8 have three pitched", listOf(8), feed(10, 1.0, 20f) { it < 4 })
    }

    @Test
    fun aSecondCheerWaitsForTheCooldown() {
        music()
        assertEquals(listOf(7), feed(7, 1.0, 20f))
        feed(1, 1e-4, -10f)
        assertEquals("within about 4 s", emptyList<Int>(), feed(20, 1.0, 20f))
        feed(CheerDetector.COOLDOWN_FRAMES, 1e-4, -10f)
        assertEquals(listOf(7), feed(7, 1.0, 20f))
        assertEquals(2, detector.cheers)
    }

    @Test
    fun anInterruptionStartsTheRunAgain() {
        music()
        feed(6, 1.0, 20f)
        detector.interrupt()
        assertEquals(listOf(7), feed(7, 1.0, 20f))
    }
}
