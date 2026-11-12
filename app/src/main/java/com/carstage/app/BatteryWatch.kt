package com.carstage.app

import com.carstage.app.vehicle.BatteryState
import com.carstage.app.vehicle.describe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * The EV battery for the first screen and the stage (docs/07 "충전 연동"). While anyone collects
 * [state] (the first screen while it is shown, the stage while it is open) the battery is read at
 * once and then [POLL_MS] after each read; reads stop [STOP_AFTER_MS] after the last collector
 * left, and [state] is null again then, so an old value is never shown or taken as current.
 * [state] is null before the first read and for a car without an EV battery. A read that throws,
 * which it should not, is logged and counts as unknown. Charging starting and stopping are logged.
 */
class BatteryWatch(scope: CoroutineScope, read: suspend () -> BatteryState?, log: (String) -> Unit) {
    val state: StateFlow<BatteryState?> = flow {
        var charging: Boolean? = null
        while (true) {
            val battery = try {
                read()
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                log("reading the ev battery failed: ${e.describe()}")
                BatteryState(null, null)
            }
            battery?.charging?.let { now ->
                if (charging != null && now != charging) log("charging ${if (now) "started" else "stopped"} at ${battery.percent ?: "?"}%")
                charging = now
            }
            emit(battery)
            delay(POLL_MS)
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS, replayExpirationMillis = 0), null)

    companion object {
        /** Charging ending shows within seconds, which is soon enough to ask about the last song. */
        const val POLL_MS = 5_000L

        /** A screen turning (a configuration change) does not restart the reads. */
        const val STOP_AFTER_MS = 5_000L
    }
}
