package com.carstage.app

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * After the stage stopped for a call or because the car was driven (not parked, or a driving
 * restriction), offers to sing on once that is over and the car is parked (docs/07 "통화 중 자동
 * 멈춤"). The offer is [Offer.ready] then; the first screen asks,
 * and the stop's notice, if still shown as it was, gives way to the question. A stage closed
 * because CarStage left the screen is offered too when the user comes back within [RETURN_MS]
 * ([leftWhileOpen], [returned]), once parked and any call is over: Pleos' app review asks an app
 * reopened from the background to restore its last state, and allows the default one after
 * several hours.
 *
 * The drive state is read every [POLL_MS]. An offer not ready within [WATCH_MS] is dropped, and a
 * ready one after [READY_MS] or as soon as the car is driven off again: by then the singing it
 * interrupted is long over, and asking would surprise the user. Opening a stage or saying no
 * ([clear]) drops it at once.
 */
class ResumeOffers(
    private val scope: CoroutineScope,
    private val inCall: StateFlow<Boolean>,
    /** Whether the stage may run now: parked, no driving restriction, the gear known. */
    private val parked: suspend () -> Boolean,
    /** The screen's notice, which a stop set and the ready offer replaces. */
    private val notice: MutableStateFlow<String?>,
    private val log: (String) -> Unit,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    enum class Reason { CALL, DRIVE, BACKGROUND }

    data class Offer(val reason: Reason, val ready: Boolean)

    private val _offer = MutableStateFlow<Offer?>(null)
    val offer: StateFlow<Offer?> = _offer.asStateFlow()

    private var watch: Job? = null

    /** When CarStage left the screen with a stage open; null when it did not, or is back. */
    private var leftAt: Long? = null

    /**
     * The stage stopped for [reason] and the screen shows [stopNotice] (none when nobody is looking);
     * the offer waits until the reason is over and the car is parked. Replaces any earlier offer.
     */
    @Synchronized
    fun stoppedFor(reason: Reason, stopNotice: String?) {
        watch?.cancel()
        val waiting = Offer(reason, ready = false)
        _offer.value = waiting
        watch = scope.launch {
            val over = withTimeoutOrNull(WATCH_MS) {
                if (reason == Reason.CALL) inCall.first { !it }
                while (!parked()) delay(POLL_MS)
            } != null
            if (!over) {
                drop(waiting, "the stage may still not run after ${WATCH_MS / 60_000} min")
                return@launch
            }
            val ready = Offer(reason, ready = true)
            if (!_offer.compareAndSet(waiting, ready)) return@launch
            // Only the plain stop notice gives way; one that also says what was not put back stays.
            stopNotice?.let { notice.compareAndSet(it, null) }
            log("offering to sing on after the ${reason.name.lowercase()}")
            val drivenOff = withTimeoutOrNull(READY_MS) {
                while (parked()) delay(POLL_MS)
            } != null
            drop(ready, if (drivenOff) "driven off" else "unanswered for ${READY_MS / 60_000} min")
        }
    }

    /** A stage opened, or the user said no: nothing to offer. */
    @Synchronized
    fun clear() {
        watch?.cancel()
        watch = null
        leftAt = null
        _offer.value = null
    }

    /** CarStage left the screen with the stage open, which closes for it; see [returned]. */
    @Synchronized
    fun leftWhileOpen() {
        leftAt = now()
    }

    /**
     * CarStage is on the screen again: when it left with the stage open at most [RETURN_MS] ago,
     * offers to sing on once the car is parked, and, during a call, once the call is over (worded
     * as after a call). An offer already there, after a call or a drive, stays as it is.
     */
    @Synchronized
    fun returned() {
        val at = leftAt ?: return
        leftAt = null
        if (_offer.value != null) return
        val away = now() - at
        if (away > RETURN_MS) return log("not offering to sing on: away for ${away / 60_000} min")
        // The stage cannot open during a call, so the offer waits for it like a call's.
        stoppedFor(if (inCall.value) Reason.CALL else Reason.BACKGROUND, stopNotice = null)
    }

    private fun drop(offer: Offer, why: String) {
        if (_offer.compareAndSet(offer, null)) log("no longer offering to sing on: $why")
    }

    companion object {
        const val POLL_MS = 2_000L
        const val WATCH_MS = 30 * 60_000L
        const val READY_MS = 10 * 60_000L

        /** Away longer than this, the user starts afresh: Pleos allows it after "several hours", and three is the fewest that says. */
        const val RETURN_MS = 3 * 60 * 60_000L
    }
}
