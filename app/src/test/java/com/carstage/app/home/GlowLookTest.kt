package com.carstage.app.home

import com.carstage.app.stage.CabinCommand
import com.carstage.app.stage.CabinState
import com.carstage.app.stage.DuelState
import com.carstage.app.stage.Percent
import com.carstage.app.stage.Rgb
import com.carstage.app.stage.Side
import org.junit.Assert.assertEquals
import org.junit.Test

class GlowLookTest {
    private fun cabin(
        on: Boolean = true,
        rgb: Int = 0x2060FF,
        brightness: Int = 100,
        driverLight: Boolean = false,
        passengerLight: Boolean = false,
    ) = CabinLook.of(
        CabinState.of(
            CabinCommand.AmbientSwitch(on),
            CabinCommand.AmbientColor(Rgb(rgb)),
            CabinCommand.AmbientBrightness(Percent(brightness)),
            CabinCommand.ReadingLight(Side.DRIVER, driverLight),
            CabinCommand.ReadingLight(Side.PASSENGER, passengerLight),
        ),
    )

    private val blue = DuelState.colorOf(Side.DRIVER).toArgb()
    private val red = DuelState.colorOf(Side.PASSENGER).toArgb()

    @Test
    fun anOpenStageGlowsInTheLightsColourAsBrightAsTheLight() {
        val bright = GlowLook.of(cabin(brightness = 100), open = true)
        assertEquals(Rgb(0x2060FF).toArgb(), bright.argb)
        assertEquals("every edge in the light's colour", bright.argb, bright.leftArgb)
        assertEquals(bright.argb, bright.rightArgb)
        assertEquals(GlowLook.BRIGHTEST, bright.strength, 1e-6f)
        assertEquals("the sides as the top", GlowLook.BRIGHTEST, bright.left, 1e-6f)
        assertEquals(GlowLook.FAINTEST, GlowLook.of(cabin(brightness = 0), open = true).strength, 1e-6f)
        assertEquals((GlowLook.FAINTEST + GlowLook.BRIGHTEST) / 2, GlowLook.of(cabin(brightness = 50), open = true).strength, 1e-6f)
    }

    @Test
    fun aReadingLightBrightensItsSidesEdge() {
        val glow = GlowLook.of(cabin(driverLight = true), open = true)
        assertEquals("the driver sits on the left", glow.strength + GlowLook.READING_LIGHT, glow.left, 1e-6f)
        assertEquals(glow.strength, glow.right, 1e-6f)
    }

    @Test
    fun anAnnouncementLightsTheSideOfTheReadingLightInItsColourAndDimsTheRest() {
        val normal = GlowLook.of(cabin(rgb = 0x2060FF, brightness = 80, driverLight = true), open = true)
        val driver = GlowLook.of(cabin(rgb = 0x2060FF, brightness = 80, driverLight = true), open = true, announcing = true)
        assertEquals(GlowLook.SIDE_LIT, driver.left, 1e-6f)
        assertEquals(blue, driver.leftArgb)
        assertEquals(GlowLook.SIDE_DIM, driver.right, 1e-6f)
        assertEquals(red, driver.rightArgb)
        assertEquals("the top and bottom step back", normal.strength * GlowLook.ANNOUNCING_TOP, driver.strength, 1e-6f)

        val passenger = GlowLook.of(cabin(rgb = 0xFF3030, brightness = 80, passengerLight = true), open = true, announcing = true)
        assertEquals("the light crosses to the right", GlowLook.SIDE_LIT, passenger.right, 1e-6f)
        assertEquals(GlowLook.SIDE_DIM, passenger.left, 1e-6f)
    }

    @Test
    fun aTiesCrownSplitsTheScreenInTheTwoColours() {
        val tie = GlowLook.of(cabin(rgb = 0x9040FF, driverLight = true, passengerLight = true), open = true, announcing = true)
        assertEquals(GlowLook.SIDE_LIT, tie.left, 1e-6f)
        assertEquals(GlowLook.SIDE_LIT, tie.right, 1e-6f)
        assertEquals("blue on the left", blue, tie.leftArgb)
        assertEquals("red on the right", red, tie.rightArgb)
    }

    @Test
    fun theSidesStayOnTheirOwnUntilTheReadingLightsAreOffAfterTheAnnouncement() {
        val crown = GlowLook.of(cabin(rgb = 0xFF3030, passengerLight = true), open = true, announcing = true)
        val ending = GlowLook.of(cabin(rgb = 0xFF3030, passengerLight = true), open = true, previous = crown)
        assertEquals("the winner's side holds while its light is still on", crown, ending)
        val after = GlowLook.of(cabin(rgb = 0x2060FF), open = true, previous = ending)
        assertEquals("then every edge follows the light again", false, after.split)
        assertEquals(after.strength, after.left, 1e-6f)
        assertEquals("a cheer's sparkle outside an announcement brightens, not splits", false, GlowLook.of(cabin(driverLight = true), open = true).split)
    }

    @Test
    fun theHushOfAnAnnouncementDimsBothSides() {
        val hush = GlowLook.of(cabin(brightness = 5), open = true, announcing = true)
        assertEquals(GlowLook.SIDE_DIM, hush.left, 1e-6f)
        assertEquals(GlowLook.SIDE_DIM, hush.right, 1e-6f)
    }

    @Test
    fun aClosedStageOrADarkLightFadesOutInTheLastColours() {
        val lit = GlowLook.of(cabin(rgb = 0xFF3030, driverLight = true), open = true, announcing = true)
        val out = lit.copy(strength = 0f, left = 0f, right = 0f)
        assertEquals(out, GlowLook.of(cabin(), open = false, previous = lit))
        assertEquals(out, GlowLook.of(cabin(on = false), open = true, previous = lit))
        assertEquals("a stage closing during an announcement fades out too", out, GlowLook.of(cabin(), open = false, announcing = true, previous = lit))
    }

    @Test
    fun nothingGlowsBeforeTheFirstStage() {
        assertEquals(GlowLook.NONE, GlowLook.of(cabin(), open = false))
        assertEquals(0f, GlowLook.NONE.strength + GlowLook.NONE.left + GlowLook.NONE.right, 0f)
    }
}
