package com.carstage.app

import com.carstage.app.audio.Track

/**
 * Tells when the music app moved on from the song being sung while the stage is open, so the song
 * ends right then rather than at a gap the microphone may not hear (gapless albums, short gaps,
 * talk or cheers in the gap; docs/09 "곡 종료 감지"): another song in the session, the same song
 * started again from the top as it ended (repeat), or the song played to its end and stopped.
 * Only a song heard playing on this stage ends, so one left paused before it opened is not a song
 * sung. A pause, a seek, details of the same song that arrive late, another app's session that does
 * not play, or a session that went away is not an end. Plain values, fed on the main thread with
 * every change the session reports.
 */
class TrackEnds {
    /** The song last told apart in the session, as last reported; null before one could be. */
    private var song: Track? = null

    /** Whether [song] already ended here as it reached its end, so moving on from it is not a second end. */
    private var endedAtEnd = false

    /** Whether [song] has been heard playing on this stage. */
    private var played = false

    /** [track] is what the session says at [nowMs] (elapsed realtime) while the stage is [open]; true when the song ended. */
    fun next(track: Track?, open: Boolean, nowMs: Long): Boolean {
        val known = track?.takeIf(::isKnown)
        if (!open) {
            // A stage that opens mid-song starts from it.
            song = known
            played = known?.playing == true
            endedAtEnd = false
            return false
        }
        // A session that went away or names no song keeps the song.
        if (known == null) return false
        val previous = song
        if (previous == null) {
            song = known
            played = known.playing
            return false
        }
        if (!sameSong(previous, known)) {
            // Another app's session that does not play is not where the music went.
            if (known.app != previous.app && !known.playing) return false
            val ended = played && !endedAtEnd
            song = known
            played = known.playing
            endedAtEnd = false
            return ended
        }
        song = known
        val wasPlayed = played
        played = played || known.playing
        if (!wasPlayed) return false
        val duration = known.durationMs ?: return false
        val position = known.positionAt(nowMs) ?: return false
        if (known.playing) {
            // Started again from the top just as it was ending: played again on repeat.
            val wasEnding = previous.positionAt(nowMs)?.let { it >= duration - END_SLACK_MS } == true
            val again = position < START_SLACK_MS && wasEnding && !endedAtEnd
            // Playing again, so its next end is an end, wherever it plays from.
            endedAtEnd = false
            return again
        }
        if (position >= duration - END_SLACK_MS && !endedAtEnd) {
            // Played to its end and stopped there.
            endedAtEnd = true
            return true
        }
        return false
    }

    companion object {
        /** How near its end a song counts as played out. */
        const val END_SLACK_MS = 3_000L

        /** How near its start a song counts as begun again. */
        const val START_SLACK_MS = 5_000L

        /** Whether the session tells which song [track] is: the app's own id or a title. */
        fun isKnown(track: Track): Boolean = track.mediaId != null || track.title != null

        /**
         * Whether [a] and [b] are one song: by the app's own id when both have one, else by title
         * (an artist that arrives late is the same song). An app that changed is another song.
         */
        fun sameSong(a: Track, b: Track): Boolean = when {
            a.app != b.app -> false
            a.mediaId != null && b.mediaId != null -> a.mediaId == b.mediaId
            a.title != null && b.title != null -> a.title == b.title
            else -> true
        }
    }
}
