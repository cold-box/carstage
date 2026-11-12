package com.carstage.app.stage

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Puts back what a stage left in the car as soon as the car is parked, instead of at the next
 * start or open: the car refuses some restores while it drives (reading lights in gear D), and a
 * restore at close can fail for other reasons too. After [request], it reads [parked] every
 * [pollMs] and, once it is true, runs [recover] (a run that knows the car is parked, so nothing is
 * left waiting after it); it keeps waiting while the gear cannot be read. An unreachable car is
 * tried [attempts] times while parked, then [onGaveUp] tells the user and the next start or open
 * tries again. Only one wait runs at a time.
 */
class ParkedRestore(
    private val scope: CoroutineScope,
    /** True when parked and the stage may open, false when not parked or a driving restriction is on, null when the gear is unknown. */
    private val parked: suspend () -> Boolean?,
    /** Runs the recovery; null when a stage is open, whose open already ran one while parked. */
    private val recover: suspend () -> StageRecovery.Result?,
    private val log: (String) -> Unit,
    private val onGaveUp: () -> Unit,
    private val pollMs: Long = POLL_MS,
    private val attempts: Int = ATTEMPTS,
) {
    private var waiting: Job? = null

    /** Starts waiting for the car to be parked, unless a wait is already running. */
    @Synchronized
    fun request() {
        if (waiting?.isActive == true) return
        waiting = scope.launch(CoroutineName("restore when parked")) { waitAndRestore() }
    }

    private suspend fun waitAndRestore() {
        log("waiting for the car to be parked to put back what the last stage left")
        var unreachable = 0
        while (true) {
            delay(pollMs)
            if (parked() != true) continue
            when (recover()) {
                StageRecovery.Result.Unreachable -> if (++unreachable >= attempts) {
                    log("could not reach the car to restore the last stage after $attempts tries while parked; the next start or open tries again")
                    onGaveUp()
                    return
                }
                else -> return
            }
        }
    }

    companion object {
        /** Soon enough after parking; each poll is one read of the gear and the driving restrictions. */
        const val POLL_MS = 2_000L
        const val ATTEMPTS = 3
    }
}
