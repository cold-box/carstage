package com.carstage.app.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The player's play/pause button. When it pauses music it cannot read, the player would vanish
 * with the music, so it stays as paused ([pausedHere]) until music plays again from any app; a
 * play the music app ignores keeps it too, so the user can press again.
 */
class PlayerPause {
    private val _pausedHere = MutableStateFlow(false)

    /** Whether the player paused music it cannot read and must stay to play it again. */
    val pausedHere: StateFlow<Boolean> = _pausedHere.asStateFlow()

    /** Returns whether the music plays (so the button pauses it), as the player shows it, else as Android reports it. */
    fun press(look: PlayerLook?, musicPlaying: Boolean): Boolean {
        val playing = look?.playing ?: musicPlaying
        if (playing && look?.track == null) _pausedHere.value = true
        return playing
    }

    fun onMusicPlaying(playing: Boolean) {
        if (playing) _pausedHere.value = false
    }
}
