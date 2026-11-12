package com.carstage.app

import com.carstage.app.stage.AudioEvent
import com.carstage.app.stage.GlowDirector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMeterTest {
    private val meter = VoiceMeter()
    private val loud = AudioEvent.Frame(loudness = 1f, voiced = true)
    private val quiet = AudioEvent.Frame(loudness = 0f, voiced = false)

    private fun hear(event: AudioEvent, times: Int) = repeat(times) { meter.hear(event) }

    @Test
    fun itRisesQuicklyAndFallsSlowlyAsTheLightDoes() {
        hear(loud, 1)
        assertEquals("half way in a frame", GlowDirector.ATTACK, meter.level.value, 1e-6f)
        hear(loud, 6)
        assertTrue("near the top within a third of a second: ${meter.level.value}", meter.level.value > 0.95f)
        hear(quiet, 6)
        assertTrue("still well up a quarter of a second later: ${meter.level.value}", meter.level.value > 0.5f)
        hear(quiet, 44)
        assertEquals("empty about two seconds after the voices stop", 0f, meter.level.value, 0f)
    }

    @Test
    fun theMusicHasItsOwnMeter() {
        hear(AudioEvent.Frame(loudness = 0f, voiced = false, music = 0.8f), 40)
        assertEquals("the music fills its bar", 0.8f, meter.music.value, 0.02f)
        assertEquals("with no voices standing out the voice bar stays empty", 0f, meter.level.value, 0f)
        meter.hear(AudioEvent.Frame(loudness = 0f, voiced = false, music = Float.NaN))
        assertTrue("NaN reads as no music, falling slowly: ${meter.music.value}", meter.music.value in 0.6f..0.8f)
        meter.reset()
        assertEquals(0f, meter.music.value, 0f)
    }

    @Test
    fun onlyChangesTheMeterCanShowAreHandedOn() {
        hear(AudioEvent.Frame(loudness = 0.5f, voiced = true), 40)
        val settled = meter.level.value
        meter.hear(AudioEvent.Frame(loudness = 0.51f, voiced = true))
        assertEquals("a change under a step stays", settled, meter.level.value, 0f)
    }

    @Test
    fun outOfRangeFramesAndOtherEventsDoNotBreakIt() {
        meter.hear(AudioEvent.Frame(loudness = Float.NaN, voiced = false))
        meter.hear(AudioEvent.Frame(loudness = 7f, voiced = true))
        assertEquals("clamped to the top before smoothing", GlowDirector.ATTACK, meter.level.value, 1e-6f)
        meter.hear(AudioEvent.SongEnded)
        assertEquals(GlowDirector.ATTACK, meter.level.value, 1e-6f)
    }

    @Test
    fun everyCheerCountsAndAResetEmptiesTheMeter() {
        hear(AudioEvent.Cheer, 2)
        assertEquals(2, meter.cheers.value)
        hear(loud, 10)
        meter.reset()
        assertEquals(0f, meter.level.value, 0f)
        meter.hear(quiet)
        assertEquals("a reset does not come back with the next quiet frame", 0f, meter.level.value, 0f)
        assertEquals("cheers keep counting, so the screen flashes only for new ones", 2, meter.cheers.value)
    }
}
