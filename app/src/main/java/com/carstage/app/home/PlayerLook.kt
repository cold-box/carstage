package com.carstage.app.home

import com.carstage.app.audio.Track

/**
 * What the first screen's player shows: the song when a session can be read, otherwise only whether
 * music plays, with an offer to show the song while notification access is off. Null hides the
 * player.
 */
data class PlayerLook(
    val title: String,
    val artist: String?,
    val playing: Boolean,
    /** The song, for its position; null when it cannot be read. */
    val track: Track?,
    /** Offer to show the song's title: notification access is off and the stage is closed, as the settings would close it. */
    val asksAccess: Boolean,
) {
    companion object {
        const val UNTITLED = "제목 없는 곡"
        const val PLAYING = "음악 재생 중"
        const val PAUSED = "일시정지됨"

        /**
         * [pausedHere] keeps the player after its own pause button stopped music it could not
         * read, so the user can play it again. The settings cover CarStage and so close an open
         * stage, so the offer waits for a closed one ([stageClosed]).
         */
        fun of(track: Track?, access: Boolean, musicPlaying: Boolean, pausedHere: Boolean, stageClosed: Boolean = true): PlayerLook? = when {
            track != null -> PlayerLook(track.title ?: UNTITLED, track.artist, track.playing, track, asksAccess = false)
            musicPlaying || pausedHere -> PlayerLook(if (musicPlaying) PLAYING else PAUSED, null, musicPlaying, null, asksAccess = !access && stageClosed)
            else -> null
        }
    }
}
