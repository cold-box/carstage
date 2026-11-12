package com.carstage.app.safety

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context

/**
 * Follows the platform's driving restrictions through CarUxRestrictionsManager, as the Pleos
 * driving-restriction guide asks of every app. The platform applies the regional rules; the app
 * only needs to know whether distraction optimization is required right now.
 *
 * Connects without blocking and reconnects by itself when the car service restarts; until it is
 * connected the value is null (unknown), so only the gear rule applies.
 */
class UxRestrictions private constructor(private val log: (String) -> Unit) {
    @Volatile
    var requiresDistractionOptimization: Boolean? = null
        private set

    private fun onCarService(car: Car, ready: Boolean) {
        if (!ready) {
            requiresDistractionOptimization = null
            log("driving restrictions: car service disconnected, waiting for it to come back")
            return
        }
        val manager = try {
            car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager?
        } catch (e: Exception) {
            requiresDistractionOptimization = null
            log("driving restrictions unavailable: getCarManager threw ${e::class.java.simpleName}: ${e.message}")
            return
        }
        if (manager == null) {
            requiresDistractionOptimization = null
            log("driving restrictions unavailable: no CarUxRestrictionsManager")
            return
        }
        requiresDistractionOptimization = manager.currentCarUxRestrictions?.isRequiresDistractionOptimization
        manager.registerListener { requiresDistractionOptimization = it.isRequiresDistractionOptimization }
        log("driving restrictions connected: requiresDistractionOptimization=$requiresDistractionOptimization")
    }

    companion object {
        /** Starts connecting to the car service; null where Android has no car service at all. */
        fun connect(context: Context, log: (String) -> Unit): UxRestrictions? = try {
            UxRestrictions(log).also { restrictions ->
                Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { car, ready -> restrictions.onCarService(car, ready) }
            }
        } catch (e: Exception) {
            log("driving restrictions unavailable: createCar threw ${e::class.java.simpleName}: ${e.message}")
            null
        } catch (e: LinkageError) {
            // Not an automotive build of Android: the android.car classes are missing.
            log("driving restrictions unavailable: ${e::class.java.simpleName}")
            null
        }
    }
}
