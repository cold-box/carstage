package com.carstage.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.EvBattery
import ai.pleos.playground.vehicle.api.Gear
import ai.pleos.playground.vehicle.api.HVAC
import ai.pleos.playground.vehicle.api.Light
import ai.pleos.playground.vehicle.api.Odometer
import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.Wiper
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Owns the single Pleos [Vehicle] session for the app. Call [release] when the owner is destroyed. */
class VehicleSession(context: Context) {
    private val vehicle = Vehicle(context.applicationContext)

    @Volatile
    var isInitialized = false
        private set

    val light: Light get() = vehicle.getLight()
    val hvac: HVAC get() = vehicle.getHVAC()
    val seat: Seat get() = vehicle.getSeat()
    val gear: Gear get() = vehicle.getGear()
    val evBattery: EvBattery get() = vehicle.getEvBattery()
    val odometer: Odometer get() = vehicle.getOdometer()
    val wiper: Wiper get() = vehicle.getWiper()

    @Synchronized
    fun initialize() {
        if (isInitialized) return
        vehicle.initialize()
        isInitialized = true
    }

    /**
     * Initializes if needed and waits until the vehicle service answers a gear read, which it may
     * not do for a moment after [initialize]. Returns the attempts used, or the last error.
     */
    suspend fun awaitReady(): Result<Int> {
        initialize()
        var lastError: Exception? = null
        for (attempt in 1..READY_ATTEMPTS) {
            try {
                readGear()
                return Result.success(attempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
            if (attempt < READY_ATTEMPTS) delay(READY_RETRY_MS)
        }
        return Result.failure(lastError ?: IllegalStateException("vehicle service not ready"))
    }

    suspend fun readGear(): VehicleGear? =
        awaitVehicle<VehicleGear?> { ok, fail -> gear.getCurrentGearSelectionState(ok, fail) }

    @Synchronized
    fun release() {
        if (!isInitialized) return
        vehicle.release()
        isInitialized = false
    }

    private companion object {
        const val READY_ATTEMPTS = 10
        const val READY_RETRY_MS = 500L
    }
}
