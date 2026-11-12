package com.carstage.app.audio

import com.carstage.app.stage.StageController.Phase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MicGateTest {
    private val phase = MutableStateFlow(Phase.CLOSED)
    private val inCall = MutableStateFlow(false)
    private var listening = false
    private var micWorks = true
    private var micFailures = 0
    private var callStops = 0
    private var stopForCall: suspend () -> Unit = { callStops++ }

    private fun TestScope.startGate() = MicGate(
        phase = phase,
        inCall = inCall,
        startMic = { micWorks.also { listening = it } },
        stopMic = { listening = false },
        onMicFailed = { micFailures++ },
        stopForCall = { stopForCall() },
    ).launchIn(backgroundScope)

    private fun gateTest(body: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher()) {
        startGate()
        body()
    }

    @Test
    fun theMicrophoneRunsExactlyWhileTheStageIsOpen() = gateTest {
        assertFalse(listening)
        phase.value = Phase.OPENING
        assertFalse("not while opening", listening)
        phase.value = Phase.OPEN
        assertTrue(listening)
        phase.value = Phase.CLOSING
        assertFalse(listening)
        phase.value = Phase.CLOSED
        assertFalse(listening)
        assertEquals(0, callStops)
    }

    @Test
    fun aCallStopsTheMicrophoneAndTheStage() = gateTest {
        phase.value = Phase.OPEN
        inCall.value = true
        assertFalse(listening)
        assertEquals(1, callStops)
        phase.value = Phase.CLOSING
        phase.value = Phase.CLOSED
        inCall.value = false
        assertEquals("one stop per call", 1, callStops)
    }

    @Test
    fun aCallThatStartedWhileTheOpenWasCheckedIsCaughtWhenTheStageOpens() = gateTest {
        inCall.value = true
        assertEquals("nothing to stop while closed", 0, callStops)
        phase.value = Phase.OPENING
        assertEquals(1, callStops)
        phase.value = Phase.OPEN
        assertFalse("the microphone never starts during a call", listening)
    }

    @Test
    fun theMicrophoneStaysOffWhileACallStopWaitsForTheOpenToFinish() = runTest(UnconfinedTestDispatcher()) {
        // The real stop waits for the stage lock, which the open holds until the stage is OPEN.
        stopForCall = {
            callStops++
            phase.first { it == Phase.CLOSED }
        }
        startGate()
        inCall.value = true
        phase.value = Phase.OPENING
        phase.value = Phase.OPEN
        assertFalse(listening)
        phase.value = Phase.CLOSING
        phase.value = Phase.CLOSED
        assertEquals(1, callStops)
        assertFalse(listening)
    }

    @Test
    fun aMicrophoneThatCannotStartIsReported() = gateTest {
        micWorks = false
        phase.value = Phase.OPEN
        assertEquals(1, micFailures)
        assertFalse(listening)
    }
}
