package com.carstage.app.vehicle

import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

const val VEHICLE_CALL_TIMEOUT_MS = 5_000L

class VehicleCallTimeoutException(timeoutMs: Long) : Exception("no callback within $timeoutMs ms")

private class Box<T>(val value: T)

/**
 * Bridges a callback-style Vehicle SDK call with one success value to a coroutine.
 * Declare [T] nullable: SDK callbacks are contravariant, so a nullable handler fits either nullability.
 */
suspend fun <T> awaitVehicle(
    call: (onComplete: (T) -> Unit, onFailed: (Exception) -> Unit) -> Unit,
): T {
    val result = withTimeoutOrNull(VEHICLE_CALL_TIMEOUT_MS) {
        suspendCancellableCoroutine<Box<T>> { continuation ->
            call(
                { value -> if (continuation.isActive) continuation.resume(Box(value)) },
                { error -> if (continuation.isActive) continuation.resumeWithException(error) },
            )
        }
    }
    return (result ?: throw VehicleCallTimeoutException(VEHICLE_CALL_TIMEOUT_MS)).value
}

/** Same as [awaitVehicle] for SDK callbacks that deliver two values, such as capability checks. */
suspend fun <A, B> awaitVehicle2(
    call: (onComplete: (A, B) -> Unit, onFailed: (Exception) -> Unit) -> Unit,
): Pair<A, B> {
    val result = withTimeoutOrNull(VEHICLE_CALL_TIMEOUT_MS) {
        suspendCancellableCoroutine<Box<Pair<A, B>>> { continuation ->
            call(
                { first, second -> if (continuation.isActive) continuation.resume(Box(first to second)) },
                { error -> if (continuation.isActive) continuation.resumeWithException(error) },
            )
        }
    }
    return (result ?: throw VehicleCallTimeoutException(VEHICLE_CALL_TIMEOUT_MS)).value
}

/** [awaitVehicle2] for a callback with three values; see the rules there. */
suspend fun <A, B, C> awaitVehicle3(
    call: (onComplete: (A, B, C) -> Unit, onFailed: (Exception) -> Unit) -> Unit,
): Triple<A, B, C> {
    val result = withTimeoutOrNull(VEHICLE_CALL_TIMEOUT_MS) {
        suspendCancellableCoroutine<Box<Triple<A, B, C>>> { continuation ->
            call(
                { first, second, third -> if (continuation.isActive) continuation.resume(Box(Triple(first, second, third))) },
                { error -> if (continuation.isActive) continuation.resumeWithException(error) },
            )
        }
    }
    return (result ?: throw VehicleCallTimeoutException(VEHICLE_CALL_TIMEOUT_MS)).value
}

fun SeatArea.label(): String = values.joinToString("+") { it.name }

fun List<SeatArea>?.describeAreas(): String =
    this?.joinToString(prefix = "[", postfix = "]") { it.label() } ?: "null"

fun Map<SeatArea, *>?.describeByArea(): String =
    this?.entries?.joinToString(prefix = "{", postfix = "}") { (area, value) ->
        "${area.label()}=${describeValue(value)}"
    } ?: "null"

private fun describeValue(value: Any?): String = when (value) {
    is AreaBounds<*> -> "${value.minValue}..${value.maxValue}"
    is List<*> -> value.joinToString(prefix = "[", postfix = "]")
    else -> value.toString()
}

fun Int?.hex(): String = this?.let { "0x%08X".format(it) } ?: "null"

fun Throwable.describe(): String = "${this::class.java.simpleName}: $message"

/**
 * Whether the car refused a call only because it is driving, so the call can succeed once it is
 * parked. Two forms are recognized anywhere in the cause chain: the SDK's documented
 * ErrorCode.ERROR_DRIVING_RESTRICTED named in a message (assumed: the emulator's VehicleException
 * prints as just its code name, for example "ERROR_NOT_SUPPORTED_PROPERTY"), and what the emulator
 * sends for reading light writes in gear D, an IllegalArgumentException("50001") (2026-10-23,
 * docs/05). Other messages that merely contain 50001 do not count.
 */
fun Throwable.isDrivingRestricted(): Boolean = generateSequence(this) { it.cause }.any { e ->
    val message = e.message?.trim() ?: return@any false
    DRIVING_RESTRICTED_NAME.containsMatchIn(message) ||
        (e is IllegalArgumentException && message == DRIVING_RESTRICTED_CODE) ||
        message == "${IllegalArgumentException::class.java.name}: $DRIVING_RESTRICTED_CODE"
}

/**
 * Whether the car refused a call because this app may not make it (a missing permission), which no
 * retry fixes while the app runs: a SecurityException anywhere in the cause chain, or one carried
 * only as text the way the emulator carries other platform errors ("java.lang.SecurityException:
 * …", compare [isDrivingRestricted]). Without the CONTROL_* permissions the emulator answers every
 * cabin write with SecurityException("set… need Permission") within 1 ms while reads still work
 * (2026-10-24, docs/05).
 */
fun Throwable.isControlDenied(): Boolean = generateSequence(this) { it.cause }.any { e ->
    e is SecurityException || e.message?.trim()?.startsWith("${SecurityException::class.java.name}:") == true
}

/**
 * Whether the car answered that it has no such property, which a reader may remember while the
 * app runs: ErrorCode.ERROR_NOT_SUPPORTED_PROPERTY named in a message anywhere in the cause chain,
 * as the emulator v2.1.0 answers the Odometer's trip checks and reads (2026-11-01, docs/05). Only
 * the name counts (the emulator's exception message is just the code name), so a car that sends
 * only a numeric code is asked again.
 */
fun Throwable.isNotSupported(): Boolean = generateSequence(this) { it.cause }.any { e ->
    e.message?.let(NOT_SUPPORTED_NAME::containsMatchIn) == true
}

/**
 * Whether the car supports what a capability [check] asks about: true when it answers with a
 * configuration, false when it answers with none or fails naming ERROR_NOT_SUPPORTED_PROPERTY
 * ([isNotSupported]). Any other failure is thrown, so the check can be asked again later.
 */
suspend fun <T> awaitSupported(check: (onComplete: (T?) -> Unit, onFailed: (Exception) -> Unit) -> Unit): Boolean =
    try {
        awaitVehicle(check) != null
    } catch (e: Exception) {
        if (e.isNotSupported()) false else throw e
    }

private val NOT_SUPPORTED_NAME = Regex("""\bERROR_NOT_SUPPORTED_PROPERTY\b""")
private val DRIVING_RESTRICTED_NAME = Regex("""\bERROR_DRIVING_RESTRICTED\b""")
private const val DRIVING_RESTRICTED_CODE = "50001"
