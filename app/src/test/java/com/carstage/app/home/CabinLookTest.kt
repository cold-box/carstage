package com.carstage.app.home

import com.carstage.app.stage.CabinCommand
import com.carstage.app.stage.CabinState
import com.carstage.app.stage.Percent
import com.carstage.app.stage.Rgb
import com.carstage.app.stage.Side
import com.carstage.app.stage.SimulatedCabin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CabinLookTest {
    @Test
    fun brightnessFollowsTheLightAndIsZeroWhenOff() {
        assertEquals(0.5f, CabinLook.of(SimulatedCabin.DEFAULT).brightness, 1e-6f)
        val full = SimulatedCabin.DEFAULT.with(CabinCommand.AmbientBrightness(Percent(100)))
        assertEquals(1f, CabinLook.of(full).brightness, 1e-6f)
        val off = SimulatedCabin.DEFAULT.with(CabinCommand.AmbientSwitch(false))
        assertEquals(0f, CabinLook.of(off).brightness, 0f)
    }

    @Test
    fun theDefaultCabinIsDimWhite() {
        val look = CabinLook.of(SimulatedCabin.DEFAULT)
        assertEquals(Rgb(0xFFFFFF).toArgb(), look.ambientArgb)
        assertEquals(CabinLook.MIN_GLOW + (1 - CabinLook.MIN_GLOW) * 0.5f, look.ambientGlow, 1e-6f)
        assertEquals(
            listOf(
                "앰비언트 켜짐 · 밝기 50%",
                "독서등 운전석 꺼짐 · 조수석 꺼짐",
                "시트 통풍 운전석 0단 · 조수석 0단",
                "온도 운전석 그대로 · 조수석 그대로",
                "풍량 그대로",
            ),
            look.captions,
        )
    }

    @Test
    fun whatTheStageSentIsWhatIsDrawn() {
        val state = SimulatedCabin.DEFAULT
            .with(CabinCommand.AmbientColor(Rgb(0xFF00AA)))
            .with(CabinCommand.AmbientBrightness(Percent(100)))
            .with(CabinCommand.ReadingLight(Side.PASSENGER, true))
            .with(CabinCommand.SeatVentilation(Side.DRIVER, 2))
            .with(CabinCommand.FanStep(3))
        val look = CabinLook.of(state)
        assertEquals(1f, look.ambientGlow, 1e-6f)
        assertEquals(mapOf(Side.DRIVER to false, Side.PASSENGER to true), look.readingLights)
        assertEquals(mapOf(Side.DRIVER to 2, Side.PASSENGER to 0), look.ventilation)
        assertEquals(3, look.fan)
        assertEquals("앰비언트 켜짐 · 밝기 100%", look.captions[0])
        assertEquals("독서등 운전석 꺼짐 · 조수석 켜짐", look.captions[1])
    }

    @Test
    fun anOffOrUnknownLightIsDrawnOff() {
        val off = CabinLook.of(SimulatedCabin.DEFAULT.with(CabinCommand.AmbientSwitch(false)))
        assertNull(off.ambientArgb)
        assertEquals(0f, off.ambientGlow)
        assertEquals("앰비언트 꺼짐", off.captions[0])
        val unknown = CabinLook.of(CabinState())
        assertNull(unknown.ambientArgb)
        assertEquals("풍량 그대로", unknown.captions.last())
    }

    @Test
    fun aCoolCelebrationShowsUnderTheWinnersSeat() {
        val look = CabinLook.of(SimulatedCabin.DEFAULT.with(CabinCommand.Temperature(Side.PASSENGER, -2f)))
        assertEquals("온도 운전석 그대로 · 조수석 2.0도 낮춤", look.captions[3])
        assertEquals("운전석", look.seatLabel(Side.DRIVER))
        assertEquals("조수석 -2°", look.seatLabel(Side.PASSENGER))
        assertEquals("the winner's name is cool", CabinLook.Change.DOWN, look.temperatureChange(Side.PASSENGER))
        assertEquals(CabinLook.Change.NONE, look.temperatureChange(Side.DRIVER))
        assertEquals(CabinLook.Change.UP, CabinLook.of(SimulatedCabin.DEFAULT.with(CabinCommand.Temperature(Side.DRIVER, 1f))).temperatureChange(Side.DRIVER))
        val half = CabinLook.of(SimulatedCabin.DEFAULT.with(CabinCommand.Temperature(Side.DRIVER, -2.5f)))
        assertEquals("운전석 -2.5°", half.seatLabel(Side.DRIVER))
    }

    @Test
    fun theCheerWindShowsAsStepsAboveTheStart() {
        assertEquals("풍량 1단 올림", CabinLook.of(SimulatedCabin.DEFAULT.with(CabinCommand.FanStep(1))).captions.last())
        assertEquals("풍량 2단 내림", CabinLook.of(SimulatedCabin.DEFAULT.with(CabinCommand.FanStep(-2))).captions.last())
    }

    @Test
    fun aCabinAsItWasAttachesNothingToThePill() {
        assertEquals(emptyList<CabinLook.Badge>(), CabinLook.of(SimulatedCabin.DEFAULT).badges)
        // The ambient light is always in the pill, on or off.
        assertEquals(emptyList<CabinLook.Badge>(), CabinLook.of(SimulatedCabin.DEFAULT.with(CabinCommand.AmbientSwitch(false))).badges)
        assertEquals(CabinLook.Change.NONE to emptyList<Side>(), CabinLook.of(SimulatedCabin.DEFAULT).temperatureBadge)
    }

    @Test
    fun theFinalesWindAttachesTheFanAlone() {
        assertEquals(listOf(CabinLook.Badge.FAN), CabinLook.of(SimulatedCabin.DEFAULT.with(CabinCommand.FanStep(1))).badges)
        assertEquals(listOf(CabinLook.Badge.FAN), CabinLook.of(SimulatedCabin.DEFAULT.with(CabinCommand.FanStep(-1))).badges)
    }

    @Test
    fun eitherReadingLightAttachesTheLamps() {
        val driver = CabinLook.of(SimulatedCabin.DEFAULT.with(CabinCommand.ReadingLight(Side.DRIVER, true)))
        assertEquals(listOf(CabinLook.Badge.READING_LIGHTS), driver.badges)
        val passenger = CabinLook.of(SimulatedCabin.DEFAULT.with(CabinCommand.ReadingLight(Side.PASSENGER, true)))
        assertEquals(listOf(CabinLook.Badge.READING_LIGHTS), passenger.badges)
    }

    @Test
    fun theDuelsCrownAttachesTheWinnersSeatInOrder() {
        val state = SimulatedCabin.DEFAULT
            .with(CabinCommand.Temperature(Side.PASSENGER, -2f))
            .with(CabinCommand.SeatVentilation(Side.PASSENGER, 3))
            .with(CabinCommand.ReadingLight(Side.PASSENGER, true))
        val look = CabinLook.of(state)
        assertEquals(
            listOf(CabinLook.Badge.READING_LIGHTS, CabinLook.Badge.VENTILATION, CabinLook.Badge.TEMPERATURE),
            look.badges,
        )
        assertEquals(listOf(Side.PASSENGER), look.ventilatedSides)
        assertEquals(CabinLook.Change.DOWN to listOf(Side.PASSENGER), look.temperatureBadge)
    }

    @Test
    fun theThermometerPointsDownWhenAnySeatWasCooled() {
        val warmed = CabinLook.of(SimulatedCabin.DEFAULT.with(CabinCommand.Temperature(Side.DRIVER, 1f)))
        assertEquals(CabinLook.Change.UP to listOf(Side.DRIVER), warmed.temperatureBadge)
        val both = CabinLook.of(
            SimulatedCabin.DEFAULT
                .with(CabinCommand.Temperature(Side.DRIVER, 1f))
                .with(CabinCommand.Temperature(Side.PASSENGER, -2f)),
        )
        assertEquals(CabinLook.Change.DOWN to listOf(Side.PASSENGER), both.temperatureBadge)
        assertEquals(listOf(CabinLook.Badge.TEMPERATURE), both.badges)
    }
}
