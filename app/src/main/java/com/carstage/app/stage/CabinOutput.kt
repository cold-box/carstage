package com.carstage.app.stage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * How an output took a command: written, with nothing to write (a change back to where nothing was
 * changed from), or not for this cabin.
 */
enum class ApplyResult { APPLIED, UNCHANGED, UNSUPPORTED }

/**
 * A write the output processed and refused (for example NOT_AVAILABLE while the climate system is
 * off), so nothing changed and nothing needs restoring. Other exceptions, such as a timeout, leave
 * the outcome unknown.
 */
open class CabinWriteRejected(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A write the car refuses because this app may not control the target (a missing permission).
 * That lasts as long as the app runs, so the output stops sending it ([ControlDenials]) and the
 * stage goes on without it. Nothing changed, as with any [CabinWriteRejected].
 */
class CabinControlDenied(message: String, cause: Throwable? = null) : CabinWriteRejected(message, cause)

/**
 * A restore the output could not do now but keeps for later: the car refuses the write only
 * because it is driving (reading lights in gear D on the emulator), and the original stays saved
 * (the car's [RestoreJournal]) until it can be written. The stage reports it apart from failures,
 * so the user can be told it will be put back once the car is parked.
 */
class CabinRestoreDeferred(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Something the stage drives: the real car, or the on-screen cabin simulator. */
interface CabinOutput {
    val name: String

    /**
     * Reads the current values and remembers them for [restore]. Targets missing from the result
     * could not be read, so the stage will not change them. Throws when nothing can be read.
     */
    suspend fun capture(): CabinState

    /**
     * Applies [command] using what the last [capture] discovered. Throws [CabinWriteRejected] when
     * the write was refused, and any other exception when its outcome is unknown.
     */
    suspend fun apply(command: CabinCommand): ApplyResult

    /**
     * Writes back the value [capture] remembered for [target]; throws [CabinRestoreDeferred] when
     * the output keeps it to restore later, and any other exception when that fails.
     */
    suspend fun restore(target: CabinTarget)

    /**
     * Called once the stage has ended and every changed target had its restore attempt, so an
     * output can drop what it kept for a stage that never closes (see [RestoreJournal]).
     */
    suspend fun ended() {}
}

/** Cabin drawn by the simulator screen. It shows what the app commanded, because the emulator does not render lights. */
class SimulatedCabin(initial: CabinState = DEFAULT) : CabinOutput {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<CabinState> = _state.asStateFlow()
    private var captured = CabinState()

    override val name = "simulator"

    override suspend fun capture(): CabinState = _state.value.also { captured = it }

    override suspend fun apply(command: CabinCommand): ApplyResult {
        _state.update { it.with(command) }
        return ApplyResult.APPLIED
    }

    override suspend fun restore(target: CabinTarget) {
        apply(checkNotNull(captured[target]) { "no captured value for $target" })
    }

    companion object {
        val DEFAULT = CabinState.of(
            CabinCommand.AmbientSwitch(true),
            CabinCommand.AmbientColor(Rgb(0xFFFFFF)),
            CabinCommand.AmbientBrightness(Percent(50)),
            CabinCommand.ReadingLight(Side.DRIVER, false),
            CabinCommand.ReadingLight(Side.PASSENGER, false),
            CabinCommand.FanStep(0),
            CabinCommand.SeatVentilation(Side.DRIVER, 0),
            CabinCommand.SeatVentilation(Side.PASSENGER, 0),
            CabinCommand.Temperature(Side.DRIVER, 0f),
            CabinCommand.Temperature(Side.PASSENGER, 0f),
        )
    }
}

/**
 * The car as a stage output, which also puts back what an earlier stage left in it
 * ([StageRecovery]). Only builds that drive the cabin have one ([carCabin]).
 */
interface CarCabin : CabinOutput {
    suspend fun restoreOriginals(saved: Map<CabinTarget, String>): List<StageRecovery.Failure>

    companion object {
        /** The car's name among the stage's outputs, as the stage logs it ("driving [vehicle, simulator]"). */
        const val NAME = "vehicle"
    }
}
