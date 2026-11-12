package com.carstage.app.stage

import com.carstage.app.score.Grade
import com.carstage.app.stage.SoundCue.Recording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundCueTest {
    @Test
    fun theBetterTheSongTheBiggerTheApplause() {
        assertEquals(SoundCue.SHORT_APPLAUSE, SoundCue.finaleOf(Grade.BASIC))
        assertEquals(SoundCue.APPLAUSE, SoundCue.finaleOf(Grade.GOOD))
        assertEquals(SoundCue.OVATION, SoundCue.finaleOf(Grade.BEST))
    }

    @Test
    fun anOvationIsAFanfareOverTheApplause() {
        assertEquals(setOf(Recording.FANFARE, Recording.APPLAUSE), SoundCue.OVATION.parts.map { it.recording }.toSet())
        val volumes = SoundCue.OVATION.parts.associate { it.recording to it.volume }
        assertTrue("the fanfare stands out", volumes.getValue(Recording.FANFARE) > volumes.getValue(Recording.APPLAUSE))
    }

    @Test
    fun aPannedSoundSetsTheFarSideBackAndTheMiddleKeepsBoth() {
        assertEquals(0.8f to 0.8f, SoundPan.CENTER.volumes(0.8f))
        assertEquals("the driver sits on the left", 1f to SoundPan.FAR, SoundPan.DRIVER.volumes(1f))
        assertEquals(0.7f * SoundPan.FAR to 0.7f, SoundPan.PASSENGER.volumes(0.7f))
        assertEquals(SoundPan.DRIVER, SoundPan.toward(Side.DRIVER))
        assertEquals(SoundPan.PASSENGER, SoundPan.toward(Side.PASSENGER))
        assertEquals("a change keeps the rate as recorded unless told", 1f, SoundChange(SoundPan.DRIVER).rate, 0f)
    }

    @Test
    fun everyRecordingIsUsedAndPlaysWithinFullVolume() {
        assertEquals(Recording.entries.toSet(), SoundCue.entries.flatMap { cue -> cue.parts.map { it.recording } }.toSet())
        assertTrue(SoundCue.entries.flatMap { it.parts }.all { it.volume > 0f && it.volume <= 1f })
    }
}
