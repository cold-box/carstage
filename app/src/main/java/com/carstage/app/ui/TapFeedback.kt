package com.carstage.app.ui

import android.view.HapticFeedbackConstants
import android.view.SoundEffectConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * The click a tap makes, and the tick of a screen that can vibrate (Pleos design guide,
 * "Introduction": "Provide haptic feedback (vibration) or audio feedback upon touch
 * interactions"). Both follow the car's touch sound and haptic settings. A click is far shorter
 * than the 0.3 s of sound a cheer takes ([com.carstage.app.audio.CheerDetector.FRAMES]), so an
 * open stage does not hear a tap as one.
 */
@Composable
fun rememberTapFeedback(): () -> Unit {
    val view = LocalView.current
    return remember(view) {
        {
            view.playSoundEffect(SoundEffectConstants.CLICK)
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }
}

/** [action] after the tap's feedback ([rememberTapFeedback]). */
@Composable
fun withTapFeedback(action: () -> Unit): () -> Unit {
    val tap = rememberTapFeedback()
    return remember(tap, action) {
        {
            tap()
            action()
        }
    }
}
