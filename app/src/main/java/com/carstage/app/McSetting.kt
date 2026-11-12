package com.carstage.app

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the MC speaks, as the user set it with the first screen's switch (docs/12 "MC 끄기"):
 * on, the MC's lines ([StageMc]) and a duel's announcement are said; off, the screen's words stand
 * alone. Kept across app starts; on until the user turns it off.
 */
class McSetting(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _on = MutableStateFlow(prefs.getBoolean(KEY_ON, true))

    val on: StateFlow<Boolean> = _on.asStateFlow()

    fun set(on: Boolean) {
        _on.value = on
        prefs.edit { putBoolean(KEY_ON, on) }
    }

    private companion object {
        const val PREFS = "settings"
        const val KEY_ON = "mc_on"
    }
}
