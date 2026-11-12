package com.carstage.app.audio

import com.carstage.app.stage.StageController.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Decides when the stage's microphone runs and when a call must stop the stage: the microphone
 * runs exactly while the stage is open and no call is on, and a call while the stage is opening
 * or open stops it. Both follow the stage phase and the call state together, so a call that
 * starts while an open is still being checked is caught as soon as the stage starts opening.
 */
class MicGate(
    private val phase: StateFlow<Phase>,
    private val inCall: StateFlow<Boolean>,
    private val startMic: () -> Boolean,
    private val stopMic: () -> Unit,
    private val onMicFailed: () -> Unit,
    private val stopForCall: suspend () -> Unit,
) {
    fun launchIn(scope: CoroutineScope) {
        scope.launch {
            combine(phase, inCall) { phase, call -> phase == Phase.OPEN && !call }
                .distinctUntilChanged()
                .collect { listen ->
                    if (!listen) stopMic() else if (!startMic()) onMicFailed()
                }
        }
        scope.launch {
            combine(phase, inCall) { phase, call -> call && (phase == Phase.OPENING || phase == Phase.OPEN) }
                .distinctUntilChanged()
                .collect { mustStop -> if (mustStop) stopForCall() }
        }
    }
}
