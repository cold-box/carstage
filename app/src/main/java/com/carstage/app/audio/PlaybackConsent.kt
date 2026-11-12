package com.carstage.app.audio

import android.os.Build

/**
 * Whether to ask the user to let the stage hear what the other apps play (Android's screen
 * capture consent, which playback capture needs), and why the music is not taken out when it may
 * not be. A consent is good for one stage, as Android 14 lets a consent start one capture
 * ([used] after each); a refusal and a car that cannot ask hold for the app run, so the user is
 * not asked again and again. Pure, so the decisions are tested without a car.
 */
class PlaybackConsent(private val sdk: Int = Build.VERSION.SDK_INT) {
    enum class State { NOT_ASKED, ASKING, GRANTED, DENIED, UNAVAILABLE }

    @Volatile
    var state = if (sdk >= Build.VERSION_CODES.Q) State.NOT_ASKED else State.UNAVAILABLE
        private set

    /** Whether a tap that opens the stage should ask first. */
    fun shouldAsk(): Boolean = state == State.NOT_ASKED

    fun asking() {
        if (state == State.NOT_ASKED) state = State.ASKING
    }

    fun answered(granted: Boolean) {
        state = if (granted) State.GRANTED else State.DENIED
    }

    /** The car has no consent dialog (or a granted consent would not start a capture). */
    fun unavailable() {
        state = State.UNAVAILABLE
    }

    /** A stage used (or let go of) the consent it was given. */
    fun used() {
        if (state == State.GRANTED || state == State.ASKING) state = State.NOT_ASKED
    }

    /** Why there is no capture to give, as of now. */
    fun missing(): EchoOff = when (state) {
        State.DENIED -> EchoOff.DENIED
        State.UNAVAILABLE -> EchoOff.UNAVAILABLE
        // Granted but the capture never came: what was granted could not be started.
        State.GRANTED -> EchoOff.FAILED
        State.NOT_ASKED, State.ASKING -> EchoOff.NOT_ASKED
    }
}
