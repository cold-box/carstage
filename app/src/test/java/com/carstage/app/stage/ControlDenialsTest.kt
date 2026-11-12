package com.carstage.app.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlDenialsTest {
    private var told = 0
    private val denials = ControlDenials { told++ }
    private val light = CabinTarget.ReadingLight(Side.DRIVER)

    @Test
    fun aStageTellsItsFirstDenialOnce() {
        denials.stageStarted()
        denials.denied(CabinTarget.AmbientColor)
        denials.denied(light)
        assertTrue(denials.isDenied(CabinTarget.AmbientColor))
        assertTrue(denials.isDenied(light))
        assertFalse("what was not refused is still sent", denials.isDenied(CabinTarget.Fan))
        assertEquals(1, told)
    }

    @Test
    fun theNextStageTriesEveryTargetAgain() {
        denials.stageStarted()
        denials.denied(CabinTarget.AmbientColor)
        denials.stageStarted()
        assertFalse(denials.isDenied(CabinTarget.AmbientColor))
        assertEquals(1, told)
        denials.denied(CabinTarget.AmbientColor)
        assertEquals("told again in the new stage", 2, told)
    }

    @Test
    fun aStageWithoutDenialsTellsNothing() {
        denials.stageStarted()
        denials.stageStarted()
        assertEquals(0, told)
    }

    @Test
    fun aRefusalHoldsAcrossStagesUntilAWriteLands() {
        assertFalse(denials.refused.value)
        denials.stageStarted()
        denials.denied(CabinTarget.AmbientColor)
        assertTrue(denials.refused.value)
        denials.stageStarted()
        assertTrue("a new stage trying again does not drop the preview", denials.refused.value)
        denials.allowed(CabinTarget.AmbientColor)
        assertFalse(denials.refused.value)
    }

    @Test
    fun everyRefusedTargetMustLandBeforeTheRefusalEnds() {
        denials.stageStarted()
        denials.denied(CabinTarget.AmbientColor)
        denials.denied(light)
        // A refused target is sent again only in the next stage.
        denials.stageStarted()
        denials.allowed(CabinTarget.AmbientColor)
        denials.allowed(CabinTarget.Fan)
        assertTrue("the reading light is still refused", denials.refused.value)
        denials.allowed(light)
        assertFalse(denials.refused.value)
    }
}
