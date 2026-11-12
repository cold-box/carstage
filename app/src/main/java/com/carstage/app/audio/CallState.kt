package com.carstage.app.audio

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a call is ringing or in progress, from the audio mode, which needs no permission
 * (.claude/rules/audio.md). The stage and the microphone stop for calls. Every mode change is
 * logged, so a car whose calls do not change the mode (Bluetooth, for example) shows it.
 */
class CallState(context: Context, private val log: (String) -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val _inCall = MutableStateFlow(isCall(audio.mode))
    val inCall: StateFlow<Boolean> = _inCall.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private var lastMode = audio.mode

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.addOnModeChangedListener(context.mainExecutor, ::onMode)
        } else {
            log("call detection: Android 11 or lower, polling the audio mode every second")
            poll()
        }
    }

    private fun poll() {
        val mode = audio.mode
        if (mode != lastMode) onMode(mode)
        main.postDelayed(::poll, POLL_MS)
    }

    private fun onMode(mode: Int) {
        lastMode = mode
        val call = isCall(mode)
        log("audio mode ${modeName(mode)}: inCall=$call")
        _inCall.value = call
    }

    companion object {
        private const val POLL_MS = 1_000L

        fun isCall(mode: Int): Boolean =
            mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION

        private fun modeName(mode: Int) = when (mode) {
            AudioManager.MODE_NORMAL -> "NORMAL"
            AudioManager.MODE_RINGTONE -> "RINGTONE"
            AudioManager.MODE_IN_CALL -> "IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
            else -> "mode $mode"
        }
    }
}
