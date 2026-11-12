package com.carstage.app

import com.carstage.app.vehicle.BatteryState
import com.carstage.app.vehicle.describe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ends the stage when charging ends (docs/07 "충전 연동"): when charging stops while the stage is
 * [open], asks whether the song being sung should be the last ([Step.ASKING]); the first screen
 * asks. Charging already on when the stage opened counts too, but it must be seen on, then off,
 * while the stage is open; an unknown charge switch changes nothing.
 *
 * [lastSong] says yes. Once that song ends ([songEnded]), its finale plays ([FINALE_MS]), a duel
 * being [announcing] its winner is done and the MC has said its score ([mcBusy], at most
 * [MC_WAIT_MS]), [close] closes the stage. [keepSinging] says no, also
 * after a yes, until [close] is called. [close] runs inside the collector that closing the stage
 * cancels, so it must finish even when cancelled. Closing the stage forgets the question and a
 * close still waiting.
 */
class ChargeFinish(
    scope: CoroutineScope,
    open: Flow<Boolean>,
    battery: Flow<BatteryState?>,
    private val announcing: Flow<Boolean>,
    private val close: suspend () -> Unit,
    private val log: (String) -> Unit,
    /** Whether the MC is saying the last song's score ([StageMc.busy]), which the close waits for. */
    private val mcBusy: () -> Boolean = { false },
) {
    enum class Step { NONE, ASKING, LAST_SONG }

    private val _step = MutableStateFlow(Step.NONE)
    val step: StateFlow<Step> = _step.asStateFlow()

    private val songEnds = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        scope.launch {
            open.distinctUntilChanged().collectLatest { isOpen ->
                _step.value = Step.NONE
                if (!isOpen) return@collectLatest
                coroutineScope {
                    launch {
                        var charging: Boolean? = null
                        battery.collect { state ->
                            val now = state?.charging ?: return@collect
                            if (charging == true && !now && _step.compareAndSet(Step.NONE, Step.ASKING)) {
                                log("charging stopped: asking whether this song is the last")
                            }
                            charging = now
                        }
                    }
                    songEnds.collect {
                        if (_step.value != Step.LAST_SONG) return@collect
                        delay(FINALE_MS)
                        announcing.first { !it }
                        withTimeoutOrNull(MC_WAIT_MS) { while (mcBusy()) delay(MC_POLL_MS) }
                        if (_step.value != Step.LAST_SONG) return@collect
                        log("the last song after charging is over")
                        closeStage()
                    }
                }
            }
        }
    }

    /** [close], with a failure logged and the question dropped, so this keeps working for the next stage. */
    private suspend fun closeStage() {
        try {
            close()
        } catch (e: Exception) {
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            log("closing the stage after the last song failed: ${e.describe()}")
        }
        _step.value = Step.NONE
    }

    /** The screen's yes: the song being sung is the last. Ignored unless asking. */
    fun lastSong() {
        if (_step.compareAndSet(Step.ASKING, Step.LAST_SONG)) log("the song being sung is the last")
    }

    /** The screen's no: sing on as before. */
    fun keepSinging() {
        _step.value = Step.NONE
    }

    /** A song ended on the open stage; called on the audio thread. */
    fun songEnded() {
        songEnds.tryEmit(Unit)
    }

    companion object {
        /**
         * The finale ([com.carstage.app.stage.FinaleLayer], three seconds) and its sound (1.6 to 4.1
         * seconds by grade, [com.carstage.app.stage.SoundCue]) with a little more, so the stage closes
         * after both.
         */
        const val FINALE_MS = 4_500L

        /** Long enough for the MC to say the last song's score after its finale. */
        const val MC_WAIT_MS = 10_000L

        private const val MC_POLL_MS = 200L
    }
}
