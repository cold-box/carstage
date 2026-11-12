package com.carstage.app

/**
 * Whether a duel's passenger has picked the song of their turn (docs/13), from what the music
 * app's session does while the music is paused for the pick. Main thread only.
 */
class PassengerPick {
    enum class Step {
        /** Nothing to act on. */
        NONE,

        /** The passenger's song plays: their turn starts with it. */
        PICKED,

        /** The song being sung ended, outside a pick. */
        SONG_ENDED,
    }

    /** Whether the passenger's turn waits for their song. */
    var waiting = false
        private set

    /** Whether the music has been seen stopped since the wait began. */
    private var pauseSeen = false

    /** The passenger's turn came, the music [paused] for the pick, or stopped already by itself. */
    fun start(paused: Boolean) {
        waiting = true
        pauseSeen = !paused
    }

    /** The turn ended, or the stage closed. */
    fun stop() {
        waiting = false
    }

    /**
     * The session changed: [ended] is [TrackEnds]'s word that the song ended, [playing] whether
     * music plays now. While waiting, only music playing is a pick: another song, the same one again,
     * or the paused one played on, once it was seen stopped (the session may report the song still
     * playing just after the pause was sent). So the turn starts when the picked song plays, not when
     * the app first names it while it loads, and a song that reaches its end while stopped for the
     * pick is no pick (a pause sent in its last seconds reads as played out).
     */
    fun next(ended: Boolean, playing: Boolean): Step {
        if (!waiting) return if (ended) Step.SONG_ENDED else Step.NONE
        if (!playing) {
            pauseSeen = true
            return Step.NONE
        }
        if (!ended && !pauseSeen) return Step.NONE
        waiting = false
        return Step.PICKED
    }
}
