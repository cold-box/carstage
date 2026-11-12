package com.carstage.app

import com.carstage.app.vehicle.Trip
import com.carstage.app.vehicle.describe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * How long this trip has driven, for the MC's greeting (docs/12 "차를 아는 MC"): read from the car
 * once for each stage, as soon as it is [active] (opening or open), and kept only as far as
 * [Trip.believableMinutes] believes it. The greeting is said at least 1.2 s after the stage opened,
 * so the read is usually done by then; one still running leaves [minutes] null and the greeting
 * the usual one ([minutesNow] logs it). A closed stage drops the trip, and a stage that closes
 * while its read runs cancels it. A test may set the trip instead of the car ([force]), as the
 * emulator reads none.
 */
class TripWatch(
    scope: CoroutineScope,
    active: Flow<Boolean>,
    private val read: suspend () -> Trip?,
    private val log: (String) -> Unit,
) {
    @Volatile
    private var fromCar: Int? = null

    @Volatile
    private var forced: Int? = null

    @Volatile
    private var reading = false

    /** The minutes driven on this trip; null when unknown or not believed. */
    val minutes: Int? get() = forced ?: fromCar

    init {
        scope.launch {
            active.distinctUntilChanged().collectLatest { on ->
                // Last stage's trip is not this one's, even when this read fails.
                fromCar = null
                if (!on) return@collectLatest
                reading = true
                try {
                    val trip = read()
                    fromCar = trip?.believableMinutes()
                    log(describe(trip, fromCar))
                } catch (e: Exception) {
                    if (e is CancellationException) currentCoroutineContext().ensureActive()
                    log("trip: reading failed: ${e.describe()}")
                } finally {
                    reading = false
                }
            }
        }
    }

    /** [minutes] for a line said now, logging when the read has not finished, so the log does not show a trip the line could not use. */
    fun minutesNow(): Int? {
        if (reading && forced == null) log("trip: greeted before the read finished")
        return minutes
    }

    /** Sets the minutes driven whatever the car says, or follows the car again with null. */
    fun force(minutes: Int?) {
        forced = minutes
    }

    private fun describe(trip: Trip?, believed: Int?): String {
        val read = when {
            trip == null -> "trip: the car reports none"
            trip.minutes == null && trip.kilometers == null -> "trip: not read"
            else -> "trip: ${trip.minutes ?: "?"} min, ${trip.kilometers ?: "?"} km${if (believed == null) ", not believed" else ""}"
        }
        return read + (forced?.let { ", forced to $it min by a test" } ?: "")
    }
}
