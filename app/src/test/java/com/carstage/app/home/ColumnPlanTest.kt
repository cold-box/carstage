package com.carstage.app.home

import org.junit.Assert.assertEquals
import org.junit.Test

class ColumnPlanTest {
    private fun plan(controls: Int, player: Int = 0) = ColumnPlan.of(viewport = 1146, player = player, controls = controls, gap = 24, minDrawing = 360)

    @Test
    fun theDrawingTakesWhatTheControlsLeave() {
        assertEquals(ColumnPlan(drawing = 1146 - 300 - 24, top = 0, height = 1146), plan(controls = 300))
    }

    @Test
    fun thePlayerComesOutOfTheDrawingsShareWithItsGap() {
        assertEquals(ColumnPlan(drawing = 1146 - 300 - 330 - 24 - 24, top = 0, height = 1146), plan(controls = 300, player = 330))
    }

    @Test
    fun tooLittleRoomLeavesTheDrawingOutAndCentersTheRest() {
        // An open stage with a result card: about 1000 of controls leave 122 for the drawing.
        assertEquals(ColumnPlan(drawing = 0, top = (1146 - 1000) / 2, height = 1146), plan(controls = 1000))
        // Exactly the least worth drawing still draws it.
        assertEquals(360, plan(controls = 1146 - 24 - 360).drawing)
        assertEquals(0, plan(controls = 1146 - 24 - 359).drawing)
    }

    @Test
    fun controlsTallerThanTheWindowStartAtTheTopAndScroll() {
        assertEquals(ColumnPlan(drawing = 0, top = 0, height = 1000 + 330 + 24), plan(controls = 1000, player = 330))
    }
}
