package com.carstage.app.safety

import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import com.carstage.app.vehicle.FailureLog
import com.carstage.app.vehicle.VehicleSession
import com.carstage.app.vehicle.awaitVehicle
import com.carstage.app.vehicle.awaitVehicle2
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

/**
 * Reads the [DriveState] from the car: gear, defroster and wipers through the Pleos Vehicle SDK,
 * driving restrictions through [UxRestrictions]. Never throws; a value it cannot read is null and
 * its failure goes to [log] (see [FailureLog]).
 */
class VehicleDriveReader(
    private val session: VehicleSession,
    private val restrictions: UxRestrictions?,
    private val log: (String) -> Unit,
) : DriveReader {
    private val failures = FailureLog { line, _ -> log("drive state: reading $line") }

    @Volatile
    private var defrosterAreas: List<WindowArea>? = null

    @Volatile
    private var wiperAreas: List<WindowArea>? = null

    override suspend fun motion(): DriveState.Motion {
        // A gear that does not answer soon counts as unknown, which pauses the stage.
        val gear = attempt("gear") { withTimeout(GEAR_TIMEOUT_MS) { session.readGear() } }
        return DriveState.Motion(
            parked = gear?.let { it == VehicleGear.GEAR_PARK },
            gear = gear?.name,
            gearError = failures.current("gear"),
            restricted = restrictions?.requiresDistractionOptimization,
        )
    }

    override suspend fun climate(): DriveState.Climate = coroutineScope {
        val defrosting = async { defrosting() }
        val wiping = async { wiping() }
        DriveState.Climate(defrosting = defrosting.await(), wiping = wiping.await())
    }

    private suspend fun defrosting(): Boolean? {
        val areas = defrosterAreas ?: attempt("defroster capabilities", missing = NO_AREAS) {
            awaitVehicle2<CapabilityConfig?, List<WindowArea>?> { ok, fail -> session.hvac.checkHvacDefrosterCapabilities(ok, fail) }
                .second?.takeIf { it.isNotEmpty() }
        }?.also { defrosterAreas = it } ?: return null
        return anyOn(areas) { area ->
            attempt("defroster state ${area.label()}") {
                awaitVehicle<Boolean?> { ok, fail -> session.hvac.getHvacDefrosterState(area, ok, fail) }
            }
        }
    }

    private suspend fun wiping(): Boolean? {
        val areas = wiperAreas ?: attempt("wiper capabilities", missing = NO_AREAS) {
            awaitVehicle2<CapabilityConfig?, Map<WindowArea, List<WiperState>>?> { ok, fail -> session.wiper.checkWiperStateCapabilities(ok, fail) }
                .second?.keys?.toList()?.takeIf { it.isNotEmpty() }
        }?.also { wiperAreas = it } ?: return null
        return anyOn(areas) { area ->
            attempt("wiper state ${area.label()}") {
                awaitVehicle<WiperState?> { ok, fail -> session.wiper.getCurrentWiperState(area, ok, fail) }
            }?.let { it != WiperState.OFF }
        }
    }

    /** True when any area is on, false when all are known to be off, null when it cannot be told. */
    private suspend fun anyOn(areas: List<WindowArea>, read: suspend (WindowArea) -> Boolean?): Boolean? {
        val states = coroutineScope { areas.map { async { read(it) } }.awaitAll() }
        return when {
            states.any { it == true } -> true
            states.all { it == false } -> false
            else -> null
        }
    }

    private suspend fun <T : Any> attempt(what: String, missing: String = "no value", block: suspend () -> T?): T? =
        failures.attempt(what, missing, block)

    private fun WindowArea.label(): String = values.joinToString("+") { it.name }

    private companion object {
        /** The gear answered in milliseconds on the emulator; a second means something is wrong. */
        const val GEAR_TIMEOUT_MS = 1_000L
        const val NO_AREAS = "no areas reported"
    }
}
