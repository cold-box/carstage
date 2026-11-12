package com.carstage.app

import com.carstage.app.audio.PlaybackSource

/** Release builds hear the other apps' music only through the capture the user allows (audio/PlaybackProjection). */
object DevPlayback {
    fun source(): PlaybackSource? = null
}
