package com.carstage.app

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the next stage is a duel, as the user chose on the closed screen's [기본 | 대결]
 * ([com.carstage.app.home.BasicControlItems], docs/13 "기본 시나리오"); kept across starts. The
 * stage is the same either way: a song is picked, the stage is that song (it plays while the stage
 * is open and is paused when the stage closes), with "그만하기" and the player. A duel adds its
 * button and card, starts the stage as a duel, asks when charging stops, lets the MC offer a duel
 * and Gleo start one, and lets Gleo open a stage with no music.
 */
class FeatureMode(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _all = MutableStateFlow(prefs.getBoolean(KEY_ALL, false))

    val all: StateFlow<Boolean> = _all.asStateFlow()

    /** True while the basic flow runs. */
    val basic: Boolean get() = !_all.value

    fun set(all: Boolean) {
        _all.value = all
        prefs.edit { putBoolean(KEY_ALL, all) }
    }

    private companion object {
        const val PREFS = "settings"
        const val KEY_ALL = "all_features"
    }
}
