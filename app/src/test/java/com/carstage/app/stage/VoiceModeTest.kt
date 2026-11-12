package com.carstage.app.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceModeTest {
    private val mode = VoiceMode()
    private val sung = AudioEvent.Frame(loudness = VoiceMode.ENTER_LEVEL, voiced = true)
    private val quiet = AudioEvent.Frame(loudness = 0f, voiced = false)

    private fun hear(frame: AudioEvent.Frame, times: Int) = (1..times).map { mode.hear(frame) }

    @Test
    fun itStartsInTheMusicMode() {
        assertFalse(mode.voice)
        assertFalse(mode.hear(quiet))
    }

    @Test
    fun enoughSungFramesInARowStartTheVoiceMode() {
        assertTrue(hear(sung, VoiceMode.ENTER_FRAMES - 1).none { it })
        assertTrue(mode.hear(sung))
        assertEquals(1, mode.switches)
    }

    @Test
    fun aGapStartsTheCountOver() {
        repeat(5) {
            hear(sung, VoiceMode.ENTER_FRAMES - 1)
            mode.hear(quiet)
        }
        assertFalse("a note that pokes out now and then is not a voice", mode.voice)
    }

    @Test
    fun theThresholdNeedsBothLevelAndPitch() {
        val soft = AudioEvent.Frame(loudness = VoiceMode.ENTER_LEVEL - 0.01f, voiced = true)
        val unpitched = AudioEvent.Frame(loudness = 1f, voiced = false)
        val nan = AudioEvent.Frame(loudness = Float.NaN, voiced = true)
        listOf(soft, unpitched, nan).forEach { assertTrue("$it", hear(it, 100).none { voice -> voice }) }
    }

    @Test
    fun theVoiceModeHoldsThroughAShortGapThenEnds() {
        hear(sung, VoiceMode.ENTER_FRAMES)
        assertTrue("held", hear(quiet, VoiceMode.HOLD_FRAMES).all { it })
        hear(sung, 1)
        assertTrue("a sung frame starts the hold over", hear(quiet, VoiceMode.HOLD_FRAMES).all { it })
        assertFalse("then back to the music", mode.hear(quiet))
        assertEquals(2, mode.switches)
    }

    @Test
    fun itCountsTheFramesOfEachMode() {
        hear(quiet, 10)
        hear(sung, VoiceMode.ENTER_FRAMES + 4)
        assertEquals(5, mode.voiceFrames)
        assertEquals(10 + VoiceMode.ENTER_FRAMES - 1, mode.musicFrames)
    }
}
