package com.carstage.app.stage

/**
 * Whether the cabin is singing ([voice]) or only the music plays: the stage's two modes, which the
 * light tells apart by color (docs/09 section 5 "목소리 모드와 노래 모드").
 *
 * A frame counts as a voice when it has a sung pitch and the voices stand at least [ENTER_LEVEL]
 * out of the accompaniment; [ENTER_FRAMES] such frames in a row start the voice mode, so a piano
 * note or a drum hit that pokes out for a frame or two does not. The voice mode holds through
 * [HOLD_FRAMES] frames without a voice, so a breath or a note YIN loses does not drop it. Voices in
 * the recording itself count as well, as they do for the brightness: only the car's echo
 * cancelling could tell them from the cabin (docs/05 "남은 검증").
 *
 * Only frames decide it, not cheers: a cheer the stage's own sound caused is dropped before the
 * stage hears it, and the log's count (LiveMic) must see what the stage sees.
 */
class VoiceMode {
    var voice = false
        private set

    /** Frames heard in each mode and times the mode changed, for the log. */
    var voiceFrames = 0
        private set
    var musicFrames = 0
        private set
    var switches = 0
        private set

    private var run = 0
    private var silent = 0

    /** Hears one frame and returns the mode after it. */
    fun hear(frame: AudioEvent.Frame): Boolean {
        if (frame.voiced && frame.level >= ENTER_LEVEL) {
            run++
            silent = 0
        } else {
            run = 0
            silent++
        }
        if (!voice && run >= ENTER_FRAMES || voice && silent > HOLD_FRAMES) {
            voice = !voice
            switches++
        }
        if (voice) voiceFrames++ else musicFrames++
        return voice
    }

    companion object {
        /**
         * Half the voices' full range, 10 dB over the accompaniment ([AudioEvent.Frame.level]):
         * six songs without vocals spent at most 2 % of their time in the voice mode with it,
         * a solo piano excepted (docs/09 section 5 "목소리 모드와 노래 모드").
         */
        const val ENTER_LEVEL = 0.5f

        /** About a quarter of a second. */
        const val ENTER_FRAMES = 6

        /** About 0.8 s. */
        const val HOLD_FRAMES = 18
    }
}
