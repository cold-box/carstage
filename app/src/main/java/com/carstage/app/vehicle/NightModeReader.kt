package com.carstage.app.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch

/**
 * Whether it is night for the car, from Android's standard vehicle information (`NIGHT_MODE`,
 * which Android describes as coming from a night mode sensor), so the stage does not dazzle a dark
 * cabin (docs/07 "야간 밝기 배려"). The headlamps are not read for this (Android keeps their state
 * to privileged apps), and no exterior light is ever controlled. Needs only the normal
 * `android.car.permission.CAR_EXTERIOR_ENVIRONMENT`, granted at install, so the user is never
 * asked. Never throws; why the night is unknown goes to [log].
 *
 * Subscribes once the car service connects, and again when it connects again; the subscription
 * (a binder call) runs on [scope], while the car's callbacks come on the main thread. Through a
 * car service restart the last answer stays, as subscribing again brings the car's value again; a
 * subscription that cannot be made makes the night unknown, as nothing would change it any more.
 * An error event is only logged.
 */
class NightModeReader private constructor(private val scope: CoroutineScope, private val log: (String) -> Unit) {
    private val _night = MutableStateFlow<Boolean?>(null)

    /** Whether the car says it is night; null until it answers, or when it says it cannot tell. */
    val night: StateFlow<Boolean?> = _night.asStateFlow()

    /** Why the night is unknown, as last logged; touched on the main thread only. */
    private var unknownWhy: String? = null

    private val main = Handler(Looper.getMainLooper())

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            // getPropertyStatus() replaces it only from API 35; minSdk is 28 and the emulator runs 34.
            @Suppress("DEPRECATION")
            val status = value.status
            val now = valueOf(status, value.value)
            answered(now, if (now == null) " (status $status, value ${value.value})" else "")
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            log("night mode error event (area $areaId); keeping ${describe(_night.value)}")
        }
    }

    /** Logs an answer that changed, and an unknown again when its reason changed, so the log says why it stays unknown. */
    private fun answered(now: Boolean?, why: String) {
        val before = _night.getAndUpdate { now }
        if (before != now || (now == null && why != unknownWhy)) log("night mode ${describe(now)}$why")
        unknownWhy = if (now == null) why else null
    }

    private fun onCarService(car: Car, ready: Boolean) {
        if (!ready) {
            // The subscription went with the car service; it is made again when it connects again.
            return log("car service disconnected; keeping night mode ${describe(_night.value)} until it connects again")
        }
        val manager = try {
            car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager?
        } catch (e: Exception) {
            return answered(null, ": getCarManager threw ${e::class.java.simpleName}: ${e.message}")
        } ?: return answered(null, ": no CarPropertyManager")
        scope.launch {
            val failure = try {
                // subscribePropertyEvents() replaces it only from API 35; minSdk is 28 and the
                // emulator runs 34. An ON_CHANGE property sends its current value first.
                @Suppress("DEPRECATION")
                val listening = manager.registerCallback(callback, VehiclePropertyIds.NIGHT_MODE, CarPropertyManager.SENSOR_RATE_ONCHANGE)
                if (listening) null else ": registerCallback returned false (not reported or not readable, or the car service went away)"
            } catch (e: Exception) {
                ": registerCallback threw ${e::class.java.simpleName}: ${e.message}"
            }
            if (failure == null) log("listening for night mode") else main.post { answered(null, failure) }
        }
    }

    companion object {
        /** What a `NIGHT_MODE` value says: only an available Boolean is an answer. */
        internal fun valueOf(status: Int, value: Any?): Boolean? =
            if (status == CarPropertyValue.STATUS_AVAILABLE) value as? Boolean else null

        private fun describe(night: Boolean?) = when (night) {
            true -> "on"
            false -> "off"
            null -> "unknown"
        }

        /** Starts connecting to the car service; null where Android has no car service at all. */
        fun connect(context: Context, scope: CoroutineScope, log: (String) -> Unit): NightModeReader? = try {
            NightModeReader(scope, log).also { reader ->
                Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { car, ready -> reader.onCarService(car, ready) }
            }
        } catch (e: Exception) {
            log("night mode unknown: createCar threw ${e::class.java.simpleName}: ${e.message}")
            null
        } catch (e: LinkageError) {
            // Not an automotive build of Android: the android.car classes are missing.
            log("night mode unknown: ${e::class.java.simpleName}")
            null
        }
    }
}
