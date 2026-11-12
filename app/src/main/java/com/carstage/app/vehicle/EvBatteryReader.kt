package com.carstage.app.vehicle

import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig

/** The EV battery as read once: [percent] of charge (0..100) and whether it is [charging]; null for a part not read. */
data class BatteryState(val percent: Int?, val charging: Boolean?)

/**
 * Reads the EV battery through the Pleos Vehicle SDK: the state of charge and the charge switch,
 * which the app only reads (docs/07 "충전 연동"). Never throws; failures go to [log] (see
 * [FailureLog]). A car whose capability checks report neither part has no EV battery and reads as
 * null from then on; a check that fails is asked again on the next read.
 */
class EvBatteryReader internal constructor(private val calls: Calls, private val log: (String) -> Unit) {
    constructor(session: VehicleSession, log: (String) -> Unit) : this(SdkCalls(session), log)

    /** The four SDK calls; each throws when it fails. A check is true when the car reports a configuration. */
    internal interface Calls {
        suspend fun stateOfChargeSupported(): Boolean
        suspend fun chargeSwitchSupported(): Boolean
        suspend fun stateOfCharge(): Int?
        suspend fun chargeSwitch(): Boolean?
    }

    /** The SDK behind [Calls]; `session.evBattery` is reached inside each call, so its failures are the call's. */
    private class SdkCalls(private val session: VehicleSession) : Calls {
        override suspend fun stateOfChargeSupported() =
            awaitVehicle<CapabilityConfig?> { ok, fail -> session.evBattery.checkEvBatteryStateOfChargeCapabilities(ok, fail) } != null

        override suspend fun chargeSwitchSupported() =
            awaitVehicle<CapabilityConfig?> { ok, fail -> session.evBattery.checkEvChargeSwitchCapabilities(ok, fail) } != null

        override suspend fun stateOfCharge() = awaitVehicle<Int?> { ok, fail -> session.evBattery.getEvBatteryStateOfCharge(ok, fail) }

        override suspend fun chargeSwitch() = awaitVehicle<Boolean?> { ok, fail -> session.evBattery.getEvChargeSwitchState(ok, fail) }
    }

    private val failures = FailureLog { line, _ -> log("ev battery: reading $line") }

    private data class Parts(val percent: Boolean, val charging: Boolean)

    /** Which parts the car reports, once both checks answered. */
    @Volatile
    private var parts: Parts? = null

    /**
     * Whether the car reports an EV battery (state of charge or charge switch): null until a [read]
     * got answers from both capability checks; nothing else asks them.
     */
    val hasBattery: Boolean? get() = parts?.let { it.percent || it.charging }

    /** The battery now; null for a car without an EV battery, unknown parts null when a read or a check fails. */
    suspend fun read(): BatteryState? {
        val known = parts ?: checkParts()?.also {
            parts = it
            log("ev battery: state of charge ${if (it.percent) "supported" else "not supported"}, charge switch ${if (it.charging) "supported" else "not supported"}")
        } ?: return BatteryState(null, null)
        if (!known.percent && !known.charging) return null
        val percent = if (known.percent) failures.attempt("state of charge") { calls.stateOfCharge() } else null
        val charging = if (known.charging) failures.attempt("charge switch") { calls.chargeSwitch() } else null
        return BatteryState(percent?.coerceIn(0, 100), charging)
    }

    /** Null when a check failed; a check that answers with no configuration means the part is missing. */
    private suspend fun checkParts(): Parts? {
        val percent = failures.attempt("state of charge capabilities") { calls.stateOfChargeSupported() }
        val charging = failures.attempt("charge switch capabilities") { calls.chargeSwitchSupported() }
        return if (percent == null || charging == null) null else Parts(percent, charging)
    }
}
