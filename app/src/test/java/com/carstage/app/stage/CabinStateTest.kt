package com.carstage.app.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CabinStateTest {
    /** One sample per command type; a new command type must be added here. */
    private val samples = listOf(
        CabinCommand.AmbientSwitch(false),
        CabinCommand.AmbientColor(Rgb(0xFF1744)),
        CabinCommand.AmbientBrightness(Percent(35)),
        CabinCommand.ReadingLight(Side.DRIVER, true),
        CabinCommand.ReadingLight(Side.PASSENGER, true),
        CabinCommand.FanStep(5),
        CabinCommand.SeatVentilation(Side.DRIVER, 3),
        CabinCommand.SeatVentilation(Side.PASSENGER, 1),
    )

    @Test
    fun everyCommandTypeRoundTripsThroughRestore() {
        samples.forEach { command ->
            assertEquals(listOf(command), CabinState().with(command).restoreCommands())
        }
    }

    @Test
    fun restoringFromAnyStateReproducesTheCapturedState() {
        val captured = SimulatedCabin.DEFAULT
        val changed = samples.fold(captured) { state, command -> state.with(command) }
        val restored = captured.restoreCommands().fold(changed) { state, command -> state.with(command) }
        assertEquals(captured, restored)
        assertEquals(CabinTarget.AmbientSwitch, captured.restoreCommands().last().target)
    }

    @Test
    fun restoreCommandsCanBeLimitedToTargets() {
        val targets = setOf<CabinTarget>(CabinTarget.Fan, CabinTarget.ReadingLight(Side.PASSENGER))
        assertEquals(
            listOf(CabinCommand.ReadingLight(Side.PASSENGER, false), CabinCommand.FanStep(0)),
            SimulatedCabin.DEFAULT.restoreCommands(targets),
        )
    }

    @Test
    fun gettersReadTheStoredValues() {
        val state = CabinState.of(
            CabinCommand.ReadingLight(Side.DRIVER, true),
            CabinCommand.SeatVentilation(Side.PASSENGER, 2),
            CabinCommand.AmbientColor(Rgb(0x2979FF)),
        )
        assertEquals(true, state.readingLight(Side.DRIVER))
        assertNull(state.readingLight(Side.PASSENGER))
        assertEquals(2, state.seatVentilation(Side.PASSENGER))
        assertEquals(Rgb(0x2979FF), state.ambientColor)
        assertNull(state.ambientOn)
    }

    @Test
    fun invalidLevelsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CabinCommand.SeatVentilation(Side.DRIVER, -1) }
        assertEquals("the fan may go below where the stage found it", -1, CabinCommand.FanStep(-1).steps)
    }
}
