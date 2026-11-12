package com.carstage.app.vehicle

import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.SeatValue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Reads whether the driver's and the front passenger's seats are taken, to offer a duel (docs/04
 * "기능 2"). Never throws; a seat it cannot read counts as unknown and its failure goes to [log]
 * (see [FailureLog]).
 *
 * The SDK's Boolean is read as "taken", as the name `getCurrentSeatOccupancy` suggests; its
 * documentation only calls it the occupancy state. On the Pleos emulator (v2.1.0) it is the
 * opposite of the Virtual Console's "Occupied" and of the standard `SEAT_OCCUPANCY` value (docs/05
 * "착석 감지 확인", 2026-10-24). Which reading is right has to be confirmed for a real car.
 */
class SeatOccupancyReader(private val session: VehicleSession, private val log: (String) -> Unit) {
    private val failures = FailureLog { line, _ -> log("seat occupancy: reading $line") }

    /** The areas holding exactly one front seat, once the capability check found both. */
    @Volatile
    private var frontSeats: List<SeatArea>? = null

    /** True when both front seats are taken, false when one is known to be free, null when it cannot be told. */
    suspend fun bothFrontSeatsTaken(): Boolean? {
        val areas = frontSeats ?: failures.attempt("capabilities", missing = "no area for each front seat") {
            awaitVehicle2<CapabilityConfig?, List<SeatArea>?> { ok, fail -> session.seat.checkSeatOccupancyCapabilities(ok, fail) }
                .second.orEmpty().let(::frontSeatAreas)
        }?.also { frontSeats = it } ?: return null
        val taken = coroutineScope {
            areas.map { area ->
                async {
                    failures.attempt(area.label()) {
                        awaitVehicle<Boolean?> { ok, fail -> session.seat.getCurrentSeatOccupancy(area, ok, fail) }
                    }
                }
            }.awaitAll()
        }
        return allTaken(taken)
    }

    internal companion object {
        private val FRONT = setOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT)

        /** One area for each front seat, each holding only that seat; null when the car reports less. */
        fun frontSeatAreas(areas: List<SeatArea>): List<SeatArea>? =
            areas.filter { it.values.toList().singleOrNull() in FRONT }
                .distinctBy { it.values.single() }
                .takeIf { it.size == FRONT.size }

        /** True when every seat was read as taken, false when one was read as free, null otherwise. */
        fun allTaken(taken: List<Boolean?>): Boolean? = when {
            taken.any { it == false } -> false
            taken.all { it == true } -> true
            else -> null
        }
    }
}
