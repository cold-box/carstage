package com.carstage.app.audio

import android.media.AudioAttributes
import com.carstage.app.audio.MusicPlayback.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPlaybackTest {
    private fun isMusic(vararg usages: Int) = MusicPlayback.isMusic(usages.map { Player(it) })

    @Test
    fun onlyMediaCountsAsMusic() {
        assertTrue(isMusic(AudioAttributes.USAGE_MEDIA))
        assertTrue(isMusic(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, AudioAttributes.USAGE_MEDIA))
        assertFalse("nothing plays", isMusic())
        assertFalse(
            "navigation, a call, a ringtone",
            isMusic(
                AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
                AudioAttributes.USAGE_VOICE_COMMUNICATION,
                AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
            ),
        )
        assertFalse("Gleo speaking", isMusic(AudioAttributes.USAGE_ASSISTANT))
        assertFalse("a game, or a player that says nothing", isMusic(AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN))
    }

    @Test
    fun speechPlayedAsMediaIsNotMusic() {
        val voice = Player(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_SPEECH)
        assertFalse("the car's speech service saying the MC's line", MusicPlayback.isMusic(listOf(voice)))
        assertTrue("music under it still counts", MusicPlayback.isMusic(listOf(voice, Player(AudioAttributes.USAGE_MEDIA))))
        assertTrue("media that says nothing of its content", MusicPlayback.isMusic(listOf(Player(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_UNKNOWN))))
    }
}
