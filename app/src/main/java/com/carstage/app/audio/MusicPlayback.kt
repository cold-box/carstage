package com.carstage.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether some app is playing music, so the first screen can offer the stage (docs/07 "음악 감지
 * 실행 제안"). It reads Android's list of active players, which needs no permission and no
 * microphone: what plays and which app plays it are not known, so CarStage's own media would count
 * too (it plays none outside the Lab's echo test). Speech does not count, so a voice such as the
 * MC's, which the car's speech service plays, is not taken for music. `isMusicActive()` stays true for a while after
 * the music stops, so it is not used (docs/05 "플랫폼 신호 확인"). The value at start and every
 * change are logged.
 */
class MusicPlayback(context: Context, private val log: (String) -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val _playing = MutableStateFlow(isMusic(players(audio.activePlaybackConfigurations)))

    /** True while some player plays media (music, video sound); not speech, navigation, calls or alerts. */
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            val now = isMusic(players(configs))
            if (now != _playing.value) log("music playing=$now")
            _playing.value = now
        }
    }

    init {
        log("music playing=${_playing.value} at start")
        audio.registerAudioPlaybackCallback(callback, Handler(Looper.getMainLooper()))
    }

    private fun players(configs: List<AudioPlaybackConfiguration>): List<Player> =
        configs.map { Player(it.audioAttributes.usage, it.audioAttributes.contentType) }

    /** What a player says it plays: its [usage] and [content] (`AudioAttributes` values). */
    data class Player(val usage: Int, val content: Int = AudioAttributes.CONTENT_TYPE_MUSIC)

    companion object {
        /** Whether the active [players] include media other than speech. */
        fun isMusic(players: List<Player>): Boolean =
            players.any { it.usage == AudioAttributes.USAGE_MEDIA && it.content != AudioAttributes.CONTENT_TYPE_SPEECH }
    }
}
