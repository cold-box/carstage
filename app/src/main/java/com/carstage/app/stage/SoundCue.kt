package com.carstage.app.stage

import com.carstage.app.score.Grade

/**
 * A sound the stage plays with its light, made of one or more CC0 recordings played together
 * ([parts]; sources and licenses in README "효과음 출처"). A finale's sound
 * follows its grade ([finaleOf], docs/04 "점수 등급").
 */
enum class SoundCue(val parts: List<Part>) {
    /** A finale of the basic grade: a short round of applause. */
    SHORT_APPLAUSE(listOf(Part(Recording.SHORT_APPLAUSE))),

    /** A finale of the good grade: applause. */
    APPLAUSE(listOf(Part(Recording.APPLAUSE))),

    /** A finale of the best grade: a fanfare over the applause, which is set back so the fanfare stands out. */
    OVATION(listOf(Part(Recording.FANFARE), Part(Recording.APPLAUSE, volume = 0.7f))),

    /** A duel's announcement, while the sides take turns: a drumroll of about four seconds. */
    DRUMROLL(listOf(Part(Recording.DRUMROLL))),

    /** A duel's winner, as the light crowns them: a short fanfare. */
    FANFARE(listOf(Part(Recording.FANFARE))),
    ;

    /** The app's recordings (`res/raw`). */
    enum class Recording { SHORT_APPLAUSE, APPLAUSE, DRUMROLL, FANFARE }

    /** A recording and how loud it plays in this sound (0..1). */
    data class Part(val recording: Recording, val volume: Float = 1f)

    companion object {
        /** The finale's sound for [grade]: the better the song, the bigger the applause. */
        fun finaleOf(grade: Grade): SoundCue = when (grade) {
            Grade.BASIC -> SHORT_APPLAUSE
            Grade.GOOD -> APPLAUSE
            Grade.BEST -> OVATION
        }
    }
}

/**
 * How to change the sound playing: where it comes from ([pan]) and how fast it plays ([rate], 1 as
 * recorded; faster is also higher). A duel's announcement moves its drumroll between the sides
 * with the reading lights, faster as the turns get faster, and its fanfare to the winner's side.
 */
data class SoundChange(val pan: SoundPan, val rate: Float = 1f)

/**
 * Where a playing sound comes from: both sides, or the driver's (left, as the cars this app is
 * for drive on the right of the road) or the passenger's (right), with the far side set back to
 * [FAR]. It moves with the light (docs/11 5장 "소리"), and still says which side when the car
 * refuses to light the reading lights.
 */
enum class SoundPan {
    CENTER,
    DRIVER,
    PASSENGER,
    ;

    /** The left and right volume of a part played at [volume]. */
    fun volumes(volume: Float): Pair<Float, Float> = when (this) {
        CENTER -> volume to volume
        DRIVER -> volume to volume * FAR
        PASSENGER -> volume * FAR to volume
    }

    companion object {
        /** How loud the far side stays: set back enough to hear the side, not so far that it sounds broken. */
        const val FAR = 0.25f

        fun toward(side: Side): SoundPan = when (side) {
            Side.DRIVER -> DRIVER
            Side.PASSENGER -> PASSENGER
        }
    }
}
