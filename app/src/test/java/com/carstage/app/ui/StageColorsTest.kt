package com.carstage.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.carstage.app.score.Grade
import com.carstage.app.stage.DuelState
import com.carstage.app.stage.FinaleLayer
import com.carstage.app.stage.GlowDirector
import com.carstage.app.stage.Side
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pleos asks for 4.5:1 between text, icons and drawings and what is behind them (docs/10 2장 "대비"). */
class StageColorsTest {
    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    private fun assertReadable(what: String, text: Color, behind: Color) {
        val ratio = contrast(text, behind)
        assertTrue("$what: %.2f:1".format(ratio), ratio >= 4.5f)
    }

    private val texts = mapOf(
        "text" to StageColors.Text,
        "secondary" to StageColors.TextSecondary,
        "info" to StageColors.Info,
        "caution" to StageColors.Caution,
        "error" to StageColors.Error,
    ) + Side.entries.associate { "side $it" to StageColors.side(it) } + ("tie" to StageColors.side(null)) +
        Grade.entries.associate { "grade $it" to StageColors.grade(it) }

    @Test
    fun everyTextColorReadsOnTheScreenAndOnACard() {
        texts.forEach { (name, color) ->
            assertReadable("$name on the screen", color, StageColors.Background)
            assertReadable("$name on a card", color, StageColors.Surface)
        }
    }

    @Test
    fun aLitDuelPanelKeepsItsTextAndEdgeReadable() {
        Side.entries.forEach { side ->
            val panel = StageColors.litPanel(side)
            assertReadable("white on the lit $side panel", StageColors.Text, panel)
            assertReadable("secondary on the lit $side panel", StageColors.TextSecondary, panel)
            assertReadable("the $side name and edge on its lit panel", StageColors.side(side), panel)
            assertReadable("the $side edge on the card", StageColors.side(side), StageColors.Surface)
        }
    }

    @Test
    fun theBrandReadsOnTheScreenAndButtonsCarryWhiteText() {
        assertReadable("the app's name", StageColors.Brand, StageColors.Background)
        assertReadable("the main button", StageColors.Text, StageColors.Primary)
        assertReadable("an emphasized button on the screen", StageColors.Text, StageColors.Emphasis.compositeOver(StageColors.Background))
        assertReadable("an emphasized button on a card", StageColors.Text, StageColors.Emphasis.compositeOver(StageColors.Surface))
        assertReadable("the listening light", StageColors.Live, StageColors.Background)
        Grade.entries.forEach { assertReadable("a $it score bar on its track", StageColors.grade(it), StageColors.Track) }
        // The stage's controls, and the score revealed among them, sit on the screen, not on a card.
        assertReadable("the best score on its ring", StageColors.grade(Grade.BEST), StageColors.BestRing.compositeOver(StageColors.Background))
        // The meter follows the stage light: every hue the light takes, and the duel's and finales' colors.
        val lights = (0 until 360 step 5).map { Color(GlowDirector.hsv(it.toFloat()).toArgb()) } +
            (Side.entries.map { DuelState.colorOf(it) } + DuelState.colorOf(null) + Grade.entries.map { FinaleLayer.colorOf(it) })
                .map { Color(it.toArgb()) }
        lights.forEach { assertReadable("the meter for light $it", StageColors.meter(it), StageColors.Track) }
        // Pleos asks 4.5:1 of graphics too; outlined buttons sit on the screen.
        assertReadable("an outlined button's edge", StageColors.Outline.compositeOver(StageColors.Background), StageColors.Background)
    }

    @Test
    fun whatTheCabinDrawingTellsStandsOutFromWhereItLies() {
        // The ambient light at full white glow lights the floor and dashboard by about white 0.15
        // where the seat names and lamps are (CabinView's TINT and HALOS).
        fun lit(behind: Color) = Color.White.copy(alpha = 0.15f).compositeOver(behind)
        // The lamps sit on the roof's front edge, between the windshield (fading into the body) and the dashboard.
        mapOf(
            "glass" to CabinColors.Glass,
            "body" to CabinColors.Body,
            "dashboard" to CabinColors.Dashboard,
            "lit dashboard" to lit(CabinColors.Dashboard),
        ).forEach { (name, behind) ->
            assertReadable("an off lamp's rim on the $name", CabinColors.Lamp, behind)
            assertReadable("a lit lamp on the $name", CabinColors.Warm, behind)
        }
        // Ventilation's waves are rimmed in the seat's color, over a reading light's pool or not;
        // the fan's air leaves the vent rimmed in the dashboard's color, over the lit strip or not.
        assertReadable("ventilation on its rim", CabinColors.Cool, CabinColors.Seat)
        assertReadable("the fan's air on its rim", CabinColors.Air, CabinColors.Dashboard)
        mapOf("plain" to StageColors.TextSecondary, "cool" to CabinColors.Cool, "warm" to CabinColors.Warm).forEach { (name, color) ->
            assertReadable("a $name seat name on the floor", color, CabinColors.Floor)
            assertReadable("a $name seat name on the lit floor", color, lit(CabinColors.Floor))
        }
    }
}
