package com.carstage.app.audio

/**
 * When the stage's own sound may still reach the microphone: while a cue plays and [ECHO_MS]
 * after it, so the cabin's echo of it is not taken for the cabin's voices. One writer (the main
 * thread), any reader (the audio thread). Pure Kotlin; times are in milliseconds of one clock.
 */
class EchoWindow {
    @Volatile
    private var until = 0L

    /** A cue of [lengthMs] started at [now]. */
    fun started(now: Long, lengthMs: Long) {
        until = now + lengthMs + ECHO_MS
    }

    /** The cue stopped early at [now]; its echo fades within [ECHO_MS]. */
    fun cut(now: Long) {
        until = minOf(until, now + ECHO_MS)
    }

    fun echoing(now: Long): Boolean = now < until

    companion object {
        /** How long after a cue the microphone may still hear it (the cabin's echo). */
        const val ECHO_MS = 500L
    }
}
