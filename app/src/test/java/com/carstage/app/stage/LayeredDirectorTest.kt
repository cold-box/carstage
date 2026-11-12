package com.carstage.app.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayeredDirectorTest {
    /** A layer that wants whatever the test sets. */
    private class Fixed(var wanted: List<CabinCommand> = emptyList()) : EffectLayer {
        val heard = mutableListOf<AudioEvent>()
        override fun hear(event: AudioEvent) {
            heard += event
        }

        override fun wants() = wanted
    }

    private val frame = AudioEvent.Frame(loudness = 0f, voiced = false)
    private val red = CabinCommand.AmbientColor(Rgb(0xFF0000))
    private val blue = CabinCommand.AmbientColor(Rgb(0x0000FF))

    private fun brightness(value: Int) = CabinCommand.AmbientBrightness(Percent(value))

    @Test
    fun anExclusiveLayerReplacesTheMergeForWhatItWants() {
        val voices = Fixed(listOf(brightness(80), red, CabinCommand.ReadingLight(Side.DRIVER, true)))
        val show = Fixed()
        val director = LayeredDirector(listOf(voices), exclusive = show)
        director.direct(frame)
        show.wanted = listOf(brightness(5), blue)
        assertEquals("dimmer than the voices, in its own color", listOf(brightness(5), blue), director.direct(frame))
        show.wanted = emptyList()
        assertEquals("then the layers take them back", listOf(brightness(80), red), director.direct(frame))
        assertEquals("the exclusive layer hears every event", 3, show.heard.size)
    }

    @Test
    fun theHighestBrightnessFanAndVentilationWin() {
        val a = Fixed(listOf(brightness(30), CabinCommand.FanStep(2), CabinCommand.SeatVentilation(Side.DRIVER, 1)))
        val b = Fixed(listOf(brightness(80), CabinCommand.FanStep(1), CabinCommand.SeatVentilation(Side.DRIVER, 3)))
        val sent = LayeredDirector(listOf(a, b)).direct(frame)
        assertEquals(listOf(brightness(80), CabinCommand.FanStep(2), CabinCommand.SeatVentilation(Side.DRIVER, 3)), sent)
    }

    @Test
    fun theCoolestTemperatureWinsWhateverTheOrder() {
        val a = Fixed(listOf(CabinCommand.Temperature(Side.PASSENGER, 0f)))
        val b = Fixed(listOf(CabinCommand.Temperature(Side.PASSENGER, -2f)))
        assertEquals(listOf(CabinCommand.Temperature(Side.PASSENGER, -2f)), LayeredDirector(listOf(a, b)).direct(frame))
        assertEquals(listOf(CabinCommand.Temperature(Side.PASSENGER, -2f)), LayeredDirector(listOf(b, a)).direct(frame))
    }

    @Test
    fun theHighestAndAnyOnRulesDoNotDependOnLayerOrder() {
        val low = listOf(
            brightness(30), CabinCommand.FanStep(1), CabinCommand.SeatVentilation(Side.DRIVER, 1),
            CabinCommand.AmbientSwitch(false), CabinCommand.ReadingLight(Side.DRIVER, false),
        )
        val high = listOf(
            brightness(80), CabinCommand.FanStep(2), CabinCommand.SeatVentilation(Side.DRIVER, 3),
            CabinCommand.AmbientSwitch(true), CabinCommand.ReadingLight(Side.DRIVER, true),
        )
        for (layers in listOf(listOf(Fixed(low), Fixed(high)), listOf(Fixed(high), Fixed(low)))) {
            assertEquals(high, LayeredDirector(layers).direct(frame))
        }
    }

    @Test
    fun switchesAndReadingLightsAreOnIfAnyLayerWantsThemOn() {
        val a = Fixed(listOf(CabinCommand.AmbientSwitch(false), CabinCommand.ReadingLight(Side.DRIVER, false)))
        val b = Fixed(listOf(CabinCommand.AmbientSwitch(true), CabinCommand.ReadingLight(Side.DRIVER, true)))
        assertEquals(
            listOf(CabinCommand.AmbientSwitch(true), CabinCommand.ReadingLight(Side.DRIVER, true)),
            LayeredDirector(listOf(a, b)).direct(frame),
        )
        assertEquals(
            listOf(CabinCommand.ReadingLight(Side.DRIVER, false)),
            LayeredDirector(listOf(Fixed(listOf(CabinCommand.ReadingLight(Side.DRIVER, false))))).direct(frame),
        )
    }

    @Test
    fun theFirstLayerWithAColorWins() {
        val director = LayeredDirector(listOf(Fixed(), Fixed(listOf(red)), Fixed(listOf(blue))))
        assertEquals(listOf(red), director.direct(frame))
    }

    @Test
    fun onlyChangesAreSentAndTargetsWithoutAnOpinionAreLeftAlone() {
        val a = Fixed(listOf(brightness(30)))
        val director = LayeredDirector(listOf(a))
        assertEquals(listOf(brightness(30)), director.direct(frame))
        assertTrue(director.direct(frame).isEmpty())
        a.wanted = listOf(brightness(31))
        assertEquals(listOf(brightness(31)), director.direct(frame))
        a.wanted = emptyList()
        assertTrue("no opinion sends nothing", director.direct(frame).isEmpty())
    }

    @Test
    fun whatTheSafetyRulesHeldBackIsSentAgain() {
        val a = Fixed(listOf(brightness(30), red))
        val director = LayeredDirector(listOf(a))
        val first = director.direct(frame)
        director.dropped(brightness(30))
        assertEquals(listOf(brightness(30)), director.direct(frame))
        // A command that is no longer the last one sent does not undo a newer one.
        a.wanted = listOf(brightness(40), red)
        director.direct(frame)
        director.dropped(first.first())
        assertTrue(director.direct(frame).isEmpty())
    }

    @Test
    fun aChangeFromTheStartGoesBackWhenNobodyWantsItAndKeepsAskingUntilItIsLetOut() {
        val wind = Fixed(listOf(CabinCommand.FanStep(1)))
        val director = LayeredDirector(listOf(wind))
        assertEquals(listOf(CabinCommand.FanStep(1)), director.direct(frame))
        wind.wanted = emptyList()
        assertEquals("back to the start once nobody wants a change", listOf(CabinCommand.FanStep(0)), director.direct(frame))
        director.dropped(CabinCommand.FanStep(0))
        assertEquals("held back, so asked for again", listOf(CabinCommand.FanStep(0)), director.direct(frame))
        assertTrue("then at the start, nothing more", director.direct(frame).isEmpty())
    }

    @Test
    fun aChangeTheRulesNeverLetOutIsNeverUndone() {
        val wind = Fixed(listOf(CabinCommand.FanStep(1)))
        val director = LayeredDirector(listOf(wind))
        director.direct(frame)
        director.dropped(CabinCommand.FanStep(1))
        wind.wanted = listOf(CabinCommand.FanStep(0))
        assertTrue("the car never left the start, so no back goes out", director.direct(frame).isEmpty())
        wind.wanted = emptyList()
        assertTrue(director.direct(frame).isEmpty())
    }

    @Test
    fun theStartIsNotSentForATargetThatNeverLeftIt() {
        val layer = Fixed(listOf(CabinCommand.Temperature(Side.PASSENGER, 0f), CabinCommand.FanStep(0)))
        assertTrue(LayeredDirector(listOf(layer)).direct(frame).isEmpty())
    }

    @Test
    fun everyLayerHearsEveryEvent() {
        val a = Fixed()
        val b = Fixed()
        val director = LayeredDirector(listOf(a, b))
        director.direct(frame)
        director.direct(AudioEvent.Cheer)
        assertEquals(listOf(frame, AudioEvent.Cheer), a.heard)
        assertEquals(a.heard, b.heard)
    }

    @Test
    fun aCeilingScalesEveryBrightnessIntoItsRangeAndLeavesTheRest() {
        assertEquals(brightness(40), LayeredDirector.dimmed(brightness(100), Percent(40)))
        assertEquals("the voices' floor", brightness(8), LayeredDirector.dimmed(brightness(20), Percent(40)))
        assertEquals("rounded: 1.6", brightness(2), LayeredDirector.dimmed(brightness(4), Percent(40)))
        assertEquals("rounded: 39.6", brightness(40), LayeredDirector.dimmed(brightness(99), Percent(40)))
        assertEquals("rounded: 29.2", brightness(29), LayeredDirector.dimmed(brightness(73), Percent(40)))
        assertEquals(brightness(0), LayeredDirector.dimmed(brightness(0), Percent(40)))
        assertEquals(red, LayeredDirector.dimmed(red, Percent(40)))
        assertEquals("the full range leaves it as it is", brightness(73), LayeredDirector.dimmed(brightness(73), Percent(100)))
    }

    @Test
    fun theCeilingIsReadAtEveryEventAndDimsTheExclusiveLayerToo() {
        var ceiling = Percent(40)
        val voices = Fixed(listOf(brightness(100), red))
        val show = Fixed()
        val director = LayeredDirector(listOf(voices), exclusive = show, ceiling = { ceiling })
        assertEquals(listOf(brightness(40), red), director.direct(frame))
        assertTrue("nothing new", director.direct(frame).isEmpty())
        show.wanted = listOf(brightness(10))
        assertEquals("the announcement dims too", listOf(brightness(4)), director.direct(frame))
        show.wanted = emptyList()
        assertEquals(listOf(brightness(40)), director.direct(frame))
        ceiling = Percent(100)
        assertEquals("by day the full range comes back at once", listOf(brightness(100)), director.direct(frame))
    }

    @Test
    fun aDimmedBrightnessHeldBackIsSentAgain() {
        val director = LayeredDirector(listOf(Fixed(listOf(brightness(50)))), ceiling = { Percent(40) })
        val first = director.direct(frame)
        assertEquals(listOf(brightness(20)), first)
        first.forEach(director::dropped)
        assertEquals(first, director.direct(frame))
        assertTrue(director.direct(frame).isEmpty())
    }
}
