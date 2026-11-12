package com.carstage.app.vehicle

import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** This trip as the car counts it, each part null when not read: [minutes] driven and [kilometers] covered. */
data class Trip(val minutes: Int?, val kilometers: Float?) {
    /**
     * The minutes driven, when they can be believed: both parts read, at most [MAX_MINUTES], and
     * making an average speed above [SLOWEST_KMH] and up to [FASTEST_KMH]. The units are assumed
     * (minutes and kilometers: the reference pages show none, and the emulator reads neither,
     * docs/05), so a time in seconds taken for minutes, which makes a speed 60 times slower, is
     * left unsaid rather than said wrong: under [SLOWEST_KMH] for a trip that really averaged up to
     * 180 km/h, and over [MAX_MINUTES] for one longer than that many seconds.
     */
    fun believableMinutes(): Int? {
        val driven = minutes?.takeIf { it in 1..MAX_MINUTES } ?: return null
        val covered = kilometers ?: return null
        val kmh = covered * MINUTES_PER_HOUR / driven
        return driven.takeIf { kmh > SLOWEST_KMH && kmh <= FASTEST_KMH }
    }

    companion object {
        /** Slower than a jam's crawl over a whole trip. */
        const val SLOWEST_KMH = 3f
        const val FASTEST_KMH = 200f

        /** Half a day at the wheel without a stop is not a trip to believe. */
        const val MAX_MINUTES = 12 * 60
        private const val MINUTES_PER_HOUR = 60f
    }
}

/**
 * Reads this trip's driving time and distance through the Pleos Vehicle SDK's Odometer, for the
 * MC's greeting (docs/12). Never throws; failures go to [log] (see [FailureLog]). Which parts the
 * car supports is remembered once both checks answered; a check answering with no configuration
 * or with ERROR_NOT_SUPPORTED_PROPERTY (as the emulator v2.1.0 answers every trip check and read)
 * means the part is not supported. While either check fails any other way, a read is
 * `Trip(null, null)` and both checks are asked again next time. The checks, and then the reads,
 * run together, as each SDK call may take up to [VEHICLE_CALL_TIMEOUT_MS].
 */
class TripReader internal constructor(private val calls: Calls, private val log: (String) -> Unit) {
    constructor(session: VehicleSession, log: (String) -> Unit) : this(SdkCalls(session), log)

    /**
     * The four SDK calls. A check is false when the car reports no configuration or answers
     * ERROR_NOT_SUPPORTED_PROPERTY; each call throws on any other failure.
     */
    internal interface Calls {
        suspend fun timeSupported(): Boolean
        suspend fun distanceSupported(): Boolean
        suspend fun minutes(): Int?
        suspend fun kilometers(): Float?
    }

    /** The SDK behind [Calls]; `session.odometer` is reached inside each call, so its failures are the call's. */
    private class SdkCalls(private val session: VehicleSession) : Calls {
        override suspend fun timeSupported() =
            awaitSupported<CapabilityConfig> { ok, fail -> session.odometer.checkTripCurrentDrivingTimeCapabilities(ok, fail) }

        override suspend fun distanceSupported() =
            awaitSupported<CapabilityConfig> { ok, fail -> session.odometer.checkTripCurrentDrivingDistanceCapabilities(ok, fail) }

        override suspend fun minutes() = awaitVehicle<Int?> { ok, fail -> session.odometer.getTripCurrentDrivingTime(ok, fail) }

        override suspend fun kilometers() = awaitVehicle<Float?> { ok, fail -> session.odometer.getTripCurrentDrivingDistance(ok, fail) }
    }

    private val failures = FailureLog { line, _ -> log("trip: reading $line") }

    private data class Parts(val time: Boolean, val distance: Boolean)

    /** Which parts the car supports, once both checks answered. */
    @Volatile
    private var parts: Parts? = null

    /** This trip now; null for a car that supports neither part, unknown parts null when a read or a check fails. */
    suspend fun read(): Trip? {
        val known = parts ?: checkParts()?.also {
            parts = it
            log("trip: driving time ${if (it.time) "supported" else "not supported"}, distance ${if (it.distance) "supported" else "not supported"}")
        } ?: return Trip(null, null)
        if (!known.time && !known.distance) return null
        return coroutineScope {
            val minutes = async { if (known.time) failures.attempt("driving time") { calls.minutes() } else null }
            val kilometers = async { if (known.distance) failures.attempt("distance") { calls.kilometers() } else null }
            Trip(minutes.await(), kilometers.await())
        }
    }

    /** Null when a check failed. */
    private suspend fun checkParts(): Parts? = coroutineScope {
        val time = async { failures.attempt("driving time capabilities") { calls.timeSupported() } }
        val distance = async { failures.attempt("distance capabilities") { calls.distanceSupported() } }
        val hasTime = time.await()
        val hasDistance = distance.await()
        if (hasTime == null || hasDistance == null) null else Parts(hasTime, hasDistance)
    }
}
