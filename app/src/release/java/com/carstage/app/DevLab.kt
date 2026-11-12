package com.carstage.app

import android.content.Intent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable

/**
 * Release builds have no developer Lab (it is in src/debug): nothing to show, and the diagnostics
 * scripts start are ignored with a log line, so the submitted APK carries none of its code.
 */
class DevLab(@Suppress("UNUSED_PARAMETER") activity: ComponentActivity) {
    @Composable
    fun Content() = Unit

    fun onResume() = Unit

    /** Release builds take no Lab commands, so no intent is one. */
    fun isCommand(@Suppress("UNUSED_PARAMETER") intent: Intent): Boolean = false

    /** Ignores a diagnostic or replay a script asks for; never shows a screen of its own. */
    fun handle(intent: Intent): Screen? {
        val asked = intent.getStringExtra(MainActivity.EXTRA_PROBE)?.let { "probe \"$it\"" }
            ?: intent.getStringExtra(MainActivity.EXTRA_REPLAY)?.let { "replay \"$it\"" }
            ?: return null
        Log.w(AppGraph.TAG, "$asked ignored: the Lab is only in debug builds")
        return null
    }

    companion object {
        /** Whether the first screen offers the Lab, and shows its development displays at first (AppGraph.devDisplays). */
        const val AVAILABLE = false
    }
}
