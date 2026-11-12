package com.carstage.app.vehicle

import android.car.Car
import android.car.FuelType
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What drives the car, from Android's standard vehicle information (`INFO_FUEL_TYPE`), so the
 * app knows without guessing whether there is an EV battery to read and an engine that may idle.
 */
enum class CarKind {
    /** Electricity only: the EV battery is read, and there is no engine to idle. */
    ELECTRIC,

    /** Electricity and fuel (a plug-in hybrid): the EV battery is read, and the engine may idle. */
    PLUG_IN_HYBRID,

    /**
     * Fuel only (gasoline, diesel, LPG, a hybrid without a plug: Android lists electricity only for
     * a car that can be plugged in): no EV battery to read, and the engine may idle.
     */
    COMBUSTION,
    ;

    val hasEvBattery: Boolean get() = this != COMBUSTION
    val mayIdle: Boolean get() = this != ELECTRIC

    companion object {
        private val COMBUSTIBLE = setOf(
            FuelType.UNLEADED, FuelType.LEADED, FuelType.DIESEL_1, FuelType.DIESEL_2, FuelType.BIODIESEL,
            FuelType.E85, FuelType.LPG, FuelType.CNG, FuelType.LNG,
        )

        /** The kind for the [fuelTypes] the car reports; null when they say neither (none, unknown, hydrogen, other). */
        fun of(fuelTypes: List<Int>): CarKind? {
            val electric = FuelType.ELECTRIC in fuelTypes
            val combustible = fuelTypes.any { it in COMBUSTIBLE }
            return when {
                electric && combustible -> PLUG_IN_HYBRID
                electric -> ELECTRIC
                combustible -> COMBUSTION
                else -> null
            }
        }

        /**
         * Whether the stage should warn about idling: as the [kind] says when it is known, else
         * unless the car reported an EV battery ([evBattery], from the Pleos SDK's checks).
         */
        fun mayIdle(kind: CarKind?, evBattery: Boolean?): Boolean = kind?.mayIdle ?: (evBattery != true)

        /** Whether to ask the Pleos SDK for the EV battery: not for a car known to run on fuel only. */
        fun readsEvBattery(kind: CarKind?): Boolean = kind?.hasEvBattery != false
    }
}

/**
 * Reads the [CarKind] once the car service connects, on [scope] rather than the main thread (the
 * read may take seconds); the fuel types never change, so an answer is kept for good, and a read
 * that failed is tried again when the car service connects again. Needs only the normal
 * `android.car.permission.CAR_INFO`, granted at install, so the user is never asked. Never throws;
 * [kind] stays null (unknown) when the car does not say, and why goes to [log].
 */
class CarKindReader private constructor(private val scope: CoroutineScope, private val log: (String) -> Unit) {
    @Volatile
    var kind: CarKind? = null
        private set

    /** Set while a read runs or once one answered, so the car service connecting again does not read twice. */
    private val reading = AtomicBoolean(false)

    private fun onCarService(car: Car, ready: Boolean) {
        if (!ready || !reading.compareAndSet(false, true)) return
        val manager = try {
            car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager?
        } catch (e: Exception) {
            null.also { log("car kind unknown: getCarManager threw ${e::class.java.simpleName}: ${e.message}") }
        }
        if (manager == null) {
            reading.set(false)
            return log("car kind unknown: no CarPropertyManager")
        }
        scope.launch {
            when (val answer = attempt { manager.getIntArrayProperty(VehiclePropertyIds.INFO_FUEL_TYPE, 0).toList() }) {
                is Attempt.Answered -> {
                    kind = answer.kind
                    log("car kind ${answer.kind ?: "unknown"} (fuel types ${answer.fuelTypes})")
                }
                is Attempt.Failed -> {
                    reading.set(false)
                    log("car kind unknown for now: ${answer.reason}")
                }
            }
        }
    }

    /** What one read of the fuel types gave. */
    internal sealed interface Attempt {
        /** The car answered; [kind] is null when its fuel types say neither, which is final too. */
        data class Answered(val fuelTypes: List<Int>, val kind: CarKind?) : Attempt

        /** The read threw; it may work when the car service connects again. */
        data class Failed(val reason: String) : Attempt
    }

    companion object {
        /** Reads the fuel types with [read], which throws when the car cannot answer. */
        internal fun attempt(read: () -> List<Int>): Attempt = try {
            val fuelTypes = read()
            Attempt.Answered(fuelTypes, CarKind.of(fuelTypes))
        } catch (e: Exception) {
            Attempt.Failed("reading the fuel type threw ${e::class.java.simpleName}: ${e.message}")
        }

        /** Starts connecting to the car service; null where Android has no car service at all. */
        fun connect(context: Context, scope: CoroutineScope, log: (String) -> Unit): CarKindReader? = try {
            CarKindReader(scope, log).also { reader ->
                Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { car, ready -> reader.onCarService(car, ready) }
            }
        } catch (e: Exception) {
            log("car kind unknown: createCar threw ${e::class.java.simpleName}: ${e.message}")
            null
        } catch (e: LinkageError) {
            // Not an automotive build of Android: the android.car classes are missing.
            log("car kind unknown: ${e::class.java.simpleName}")
            null
        }
    }
}
