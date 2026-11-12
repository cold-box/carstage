package com.carstage.app.stage

import com.carstage.app.stage.GlowDirector.Companion.FLASH_FRAMES

/**
 * Answers a cheer with the reading lights: both flash on for [FLASH_FRAMES] and are then wanted
 * off. Until the first cheer they are left alone, so a light a passenger is reading by stays on;
 * after a flash they stay off until the stage closes and puts back what was there. The swell of
 * the ambient light is the [ParticipationLayer]'s, since it falls back to the voices.
 */
class CheerLayer : EffectLayer {
    private var flashed = false
    private var flashFramesLeft = 0

    override fun hear(event: AudioEvent) {
        when (event) {
            AudioEvent.Cheer -> {
                flashed = true
                flashFramesLeft = FLASH_FRAMES
            }
            is AudioEvent.Frame -> if (flashFramesLeft > 0) flashFramesLeft--
            is AudioEvent.Mood, AudioEvent.SongEnded -> Unit
        }
    }

    override fun wants(): List<CabinCommand> =
        if (flashed) Side.entries.map { CabinCommand.ReadingLight(it, flashFramesLeft > 0) } else emptyList()
}
