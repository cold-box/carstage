package com.carstage.app.stage

/**
 * What the audio side reports to the stage. This and [Director] are the only link between audio
 * analysis and cabin effects, so the two can be built and tested separately.
 */
sealed interface AudioEvent {
    /**
     * One analysis frame (about 23 per second at 48 kHz and 2,048 samples).
     * [loudness] should be 0..1, where 0 is no voice standing out of the music and 1 is voices
     * well above it; values outside that range or NaN are accepted, and [level] clamps them so
     * nothing on the audio thread throws. [voiced] is true when the frame has a sung pitch.
     * [music] should be 0..1 too: how loud the accompaniment (the music under the voices) is, from
     * quiet to the car's music played loud; [musicLevel] clamps it the same way.
     */
    data class Frame(val loudness: Float, val voiced: Boolean, val music: Float = 0f) : AudioEvent {
        /** [loudness] clamped to 0..1, with NaN as 0. */
        val level: Float get() = if (loudness.isNaN()) 0f else loudness.coerceIn(0f, 1f)

        /** [music] clamped to 0..1, with NaN as 0. */
        val musicLevel: Float get() = if (music.isNaN()) 0f else music.coerceIn(0f, 1f)
    }

    /**
     * A cheer, a shout or loud applause: sound in the voice band, neither pitched nor gathered in
     * a few peaks, that jumped well above the music and above the voices' recent level. Loud
     * singing with a clear pitch and a loud chord are not cheers. Voices in the recording itself
     * count too, as they do for [Frame].
     */
    data object Cheer : AudioEvent

    /**
     * What the music is like, about every 2 s once 6 s of sound were heard; each one restates the
     * current mood. [feel] comes from how hard and how regularly the drums drive the song; when
     * that is unclear (talk, a soft beat under singing) it is [Feel.MODERATE]. [energy] should be
     * 0..1: where the accompaniment is within its range over the last 30 s of sound, so a chorus
     * reads higher than the verse before it. Values outside that range or NaN are accepted, and
     * [level] clamps them. [beatsPerMinute] is the tempo of the strongest period in the last 6 s,
     * NaN when unknown; it is there even when the music has no clear beat, so it only sets the
     * length of a breath of light ([MoodLayer]), never anything that must be right.
     */
    data class Mood(val feel: Feel, val energy: Float, val beatsPerMinute: Float = Float.NaN) : AudioEvent {
        /** [energy] clamped to 0..1, with NaN as 0. */
        val level: Float get() = if (energy.isNaN()) 0f else energy.coerceIn(0f, 1f)

        enum class Feel { CALM, MODERATE, LIVELY }
    }

    /**
     * The song seems to have ended: the cabin went quiet for a second, well under the music's
     * recent level, as between tracks or at the end of a fade-out, at least 30 s of sound after
     * the stage opened or the last end. A guess from the sound: a quiet passage (ambient music) or
     * pausing the music can read as an end, and a gapless next track or a song shorter than 30 s
     * is missed (docs/09 section 5 "곡 종료 감지"). The stage also ends a song by hand at any time
     * ([StageController.endSong]), which the audio side does not know of.
     */
    data object SongEnded : AudioEvent
}

/**
 * Turns audio events into cabin commands. It is called on the audio thread, so it must return
 * quickly; implementations may keep their own state, such as smoothing.
 */
fun interface Director {
    fun direct(event: AudioEvent): List<CabinCommand>

    /**
     * Called on the same thread when the safety or battery rules held back [command], so a
     * director that remembers what it sent can send it again later.
     */
    fun dropped(command: CabinCommand) {}

    companion object {
        /** Sends nothing, so an open stage changes nothing until a real director is set. */
        val Silent = Director { emptyList() }
    }
}
