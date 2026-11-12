package com.carstage.app.lab

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.carstage.app.audio.NowPlayingListener
import kotlinx.coroutines.delay

/**
 * Checks whether the app can tell which media app is playing and what song (docs/05 "재생 중인 곡
 * 읽기 확인"): with notification access ([NowPlayingListener]) Android hands out other apps' media
 * sessions. Reports
 * every session's app, state and song, then watches [WATCH_MS] so a song change or pause in the
 * music app shows up. Grant access in the car's settings, or for tests:
 * `adb shell cmd notification allow_listener com.carstage.app/com.carstage.app.audio.NowPlayingListener 10`.
 */
class NowPlayingProbe(
    private val context: Context,
    private val report: (String) -> Unit,
) {
    private val listener = ComponentName(context, NowPlayingListener::class.java)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    suspend fun run() {
        val settings = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).resolveActivity(context.packageManager)
        report("notification access settings: ${settings?.flattenToShortString() ?: "none"}")
        val notifications = context.getSystemService(NotificationManager::class.java)
        val granted = notifications.isNotificationListenerAccessGranted(listener)
        report("notification access granted=$granted")
        if (!granted) {
            report("grant it in the settings above, or: adb shell cmd notification allow_listener ${listener.flattenToShortString()} 10")
            return
        }
        val watched = mutableMapOf<MediaController, MediaController.Callback>()
        fun watch(controllers: List<MediaController>) {
            watched.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
            watched.clear()
            controllers.forEach { controller ->
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) =
                        report("${controller.packageName} state: ${describe(state)}")

                    override fun onMetadataChanged(metadata: MediaMetadata?) =
                        report("${controller.packageName} song: ${describe(metadata)}")
                }
                controller.registerCallback(callback, handler)
                watched[controller] = callback
            }
        }
        val initial = sessions.getActiveSessions(listener)
        report("active sessions: ${initial.size}")
        initial.forEach(::reportSession)
        val changed = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            val now = controllers.orEmpty()
            report("sessions changed: ${now.joinToString { it.packageName }.ifEmpty { "none" }}")
            now.forEach(::reportSession)
            watch(now)
        }
        sessions.addOnActiveSessionsChangedListener(changed, listener, handler)
        try {
            watch(initial)
            report("watching ${WATCH_MS / 1_000}s: change the song, pause or play in the music app now")
            delay(WATCH_MS)
        } finally {
            sessions.removeOnActiveSessionsChangedListener(changed)
            watch(emptyList())
        }
        // Without access Android may stop telling this probe about changes, which would look like a quiet watch.
        val stillGranted = notifications.isNotificationListenerAccessGranted(listener)
        report("watch end: access granted=$stillGranted${if (stillGranted) "" else " (turned off during the watch; changes after that may be missing)"}")
    }

    private fun reportSession(controller: MediaController) {
        report("${controller.packageName}: ${describe(controller.playbackState)} song: ${describe(controller.metadata)}")
    }

    private fun describe(state: PlaybackState?): String {
        state ?: return "<no playback state>"
        val name = when (state.state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_STOPPED -> "stopped"
            PlaybackState.STATE_BUFFERING -> "buffering"
            PlaybackState.STATE_ERROR -> "error(${state.errorMessage ?: "no message"})"
            PlaybackState.STATE_NONE -> "state_none"
            else -> "state ${state.state}"
        }
        return "$name ${position(state)}"
    }

    /**
     * The app reports its position as of its last state update; while playing, carry it on to now
     * the way MediaController users do, and say how old the update is.
     */
    private fun position(state: PlaybackState): String {
        if (state.position < 0) return "position unknown"
        val sinceUpdate = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        val now = if (state.state == PlaybackState.STATE_PLAYING) {
            state.position + (sinceUpdate * state.playbackSpeed).toLong()
        } else {
            state.position
        }
        return "at ${now / 1_000}s (updated ${sinceUpdate / 1_000}s ago)"
    }

    /** Says which keys the app filled, left out or sent empty, since that is what this probe checks. */
    private fun describe(metadata: MediaMetadata?): String {
        metadata ?: return "<no metadata>"
        val length = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 }?.let { "${it / 1_000}s" }
        return listOf(
            "title=${text(metadata, MediaMetadata.METADATA_KEY_TITLE)}",
            "display_title=${text(metadata, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)}",
            "artist=${text(metadata, MediaMetadata.METADATA_KEY_ARTIST)}",
            "length=${length ?: "<missing>"}",
        ).joinToString(" ")
    }

    private fun text(metadata: MediaMetadata, key: String): String {
        if (!metadata.containsKey(key)) return "<missing>"
        val value = metadata.getString(key)
        return if (value.isNullOrBlank()) "<empty>" else "\"$value\""
    }

    private companion object {
        const val WATCH_MS = 20_000L
    }
}
