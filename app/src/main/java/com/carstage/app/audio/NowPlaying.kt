package com.carstage.app.audio

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A song another app plays, as its media session tells it. Text the app left out or sent blank is
 * null; the title falls back to the display title and the artist to the album artist.
 */
data class Track(
    val app: String,
    val title: String?,
    val artist: String?,
    val playing: Boolean,
    /** Where the song was at [updatedAtMs] (elapsed realtime); null when the app does not say. */
    val positionMs: Long?,
    val updatedAtMs: Long,
    val speed: Float,
    val durationMs: Long?,
    /** The app's own id for the song, as its song list ([com.carstage.app.media.SongShelf]) names it; null when the app does not say. */
    val mediaId: String? = null,
) {
    /**
     * Where the song is at [nowMs] (elapsed realtime), carried on from the app's last update while it
     * plays, never past the song's length when the app gives one.
     */
    fun positionAt(nowMs: Long): Long? {
        val at = positionMs ?: return null
        val moved = if (playing) ((nowMs - updatedAtMs).coerceAtLeast(0) * speed).toLong() else 0L
        val now = (at + moved).coerceAtLeast(0)
        return durationMs?.let { now.coerceAtMost(it) } ?: now
    }

    companion object {
        /**
         * A track from what a media session reports; Android's own "unknown" values (a position
         * below 0, a length of 0, no state) become null or stopped.
         */
        fun of(
            app: String,
            title: String?,
            displayTitle: String?,
            artist: String?,
            albumArtist: String?,
            state: Int?,
            positionMs: Long?,
            updatedAtMs: Long?,
            speed: Float?,
            durationMs: Long?,
            mediaId: String? = null,
        ) = Track(
            app = app,
            title = title.orNullIfBlank() ?: displayTitle.orNullIfBlank(),
            artist = artist.orNullIfBlank() ?: albumArtist.orNullIfBlank(),
            playing = state == PlaybackState.STATE_PLAYING,
            positionMs = positionMs?.takeIf { it >= 0 },
            updatedAtMs = updatedAtMs ?: 0L,
            speed = speed ?: 1f,
            durationMs = durationMs?.takeIf { it > 0 },
            mediaId = mediaId.orNullIfBlank(),
        )

        private fun String?.orNullIfBlank() = this?.takeIf { it.isNotBlank() }

        /** A time as m:ss. */
        fun clock(ms: Long): String {
            val seconds = ms.coerceAtLeast(0) / 1_000
            return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        }

        /**
         * The session to show among [sessions] (Android lists them most recent first): a playing
         * one first, else the most recent; never CarStage's [own].
         */
        fun <T> pick(sessions: List<T>, own: String, app: (T) -> String, playing: (T) -> Boolean): T? {
            val others = sessions.filter { app(it) != own }
            return others.firstOrNull(playing) ?: others.firstOrNull()
        }
    }
}

/**
 * What another app plays, for the first screen's player (docs/07 "첫 화면 미니 플레이어"). Its
 * buttons work without any permission: with no session to talk to they send media keys, which
 * Android routes to its media button session (normally the app that played media last). The song
 * needs notification access, which the user may grant in the car's settings
 * ([NowPlayingListener]), or the song list's connection to the app ([shelfSession]); without
 * either [track] stays null. [refresh] runs when the app comes back to
 * the front and when Android connects or drops the listener, since the user may have changed the
 * access. Runs on the main thread.
 */
class NowPlaying(private val context: Context, private val log: (String) -> Unit) {
    private val listener = ComponentName(context, NowPlayingListener::class.java)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val _access = MutableStateFlow(false)

    /** Whether the user granted notification access, so the song can be read. */
    val access: StateFlow<Boolean> = _access.asStateFlow()

    private val _track = MutableStateFlow<Track?>(null)

    /** The song another app plays or paused, while access is granted and an app has a session, or the song list is connected to one. */
    val track: StateFlow<Track?> = _track.asStateFlow()

    private var controller: MediaController? = null
    private var listening = false

    /** The session of the app the song list is connected to; followed with the others. */
    private var shelfController: MediaController? = null

    /**
     * Whether the list's session has played since it was followed. Until then it is not where the
     * music is (an app restores its last song as the list connects), so it is not shown and the
     * buttons send media keys to the app that plays.
     */
    private var shelfPlayed = false

    /** Whether the session followed is the list's own and has not played yet ([shelfPlayed]). */
    private var shelfIdle = false

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()

        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()

        override fun onSessionDestroyed() {
            log("now playing: ${controller?.packageName} ended its session")
            if (controller?.sessionToken == shelfController?.sessionToken) shelfController = null
            follow(if (listening) activeSessions() else emptyList())
        }
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { follow(it.orEmpty()) }

    init {
        refresh()
    }

    /**
     * Checks the access again and, while it is granted, subscribes to the apps' sessions afresh:
     * Android drops the subscription when the access is turned off, so one from before may be dead.
     */
    fun refresh() {
        val granted = notifications.isNotificationListenerAccessGranted(listener)
        if (granted != _access.value) log("now playing: notification access ${if (granted) "granted" else "not granted"}")
        _access.value = granted
        if (listening) sessions.removeOnActiveSessionsChangedListener(sessionsChanged)
        listening = false
        if (!granted) {
            follow(emptyList())
            return
        }
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, handler)
            listening = true
        } catch (e: SecurityException) {
            // The access was turned off between the check and the call.
            log("now playing: sessions refused: ${e.message}")
            _access.value = false
        }
        follow(if (listening) activeSessions() else emptyList())
    }

    /**
     * The song list connected to an app's browser service and passes on its session [token], or
     * lost it (null). That session is followed with the ones notification access shows, so the
     * song can be read without the access.
     */
    fun shelfSession(token: MediaSession.Token?) {
        if (token == shelfController?.sessionToken) return
        shelfController = token?.let {
            try {
                MediaController(context, it)
            } catch (e: RuntimeException) {
                log("now playing: the song list's session refused: $e")
                null
            }
        }
        shelfPlayed = false
        log("now playing: the song list's session ${shelfController?.packageName ?: "gone"}")
        follow(if (listening) activeSessions() else emptyList())
    }

    /** Pauses the song if [playing], else plays it. */
    fun playPause(playing: Boolean) {
        val controls = controlled()?.transportControls
        log("now playing: ${if (playing) "pause" else "play"} ${controlled()?.packageName ?: "(no session; media key sent)"}")
        when {
            controls == null -> key(if (playing) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY)
            playing -> controls.pause()
            else -> controls.play()
        }
    }

    /**
     * Pauses what plays: the followed session when it plays, else a media key for the app Android
     * routes media buttons to (the first screen's song reset, docs/07 "노래 목록").
     */
    fun pause() {
        val playing = controlled()?.takeIf { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        log("now playing: pause ${playing?.packageName ?: "(no playing session; media key sent)"}")
        playing?.transportControls?.pause() ?: key(KeyEvent.KEYCODE_MEDIA_PAUSE)
    }

    fun next() {
        log("now playing: next ${controlled()?.packageName ?: "(no session; media key sent)"}")
        controlled()?.transportControls?.skipToNext() ?: key(KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun previous() {
        log("now playing: previous ${controlled()?.packageName ?: "(no session; media key sent)"}")
        controlled()?.transportControls?.skipToPrevious() ?: key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    /** The session the buttons talk to: the one followed, unless it is the list's and has not played yet. */
    private fun controlled(): MediaController? = controller?.takeUnless { shelfIdle }

    /** Opens the car's notification access settings from [from]; false when the car has none. */
    fun openSettings(from: Context): Boolean = try {
        from.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        true
    } catch (e: ActivityNotFoundException) {
        log("now playing: no notification access settings: ${e.message}")
        false
    } catch (e: SecurityException) {
        log("now playing: notification access settings refused: ${e.message}")
        false
    }

    private fun key(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /** The apps' sessions, or none when Android refuses them (the access was just turned off). */
    private fun activeSessions(): List<MediaController> = try {
        sessions.getActiveSessions(listener)
    } catch (e: SecurityException) {
        log("now playing: sessions refused: ${e.message}")
        emptyList()
    }

    private fun follow(controllers: List<MediaController>) {
        // The list's app is among the sessions when the access shows them; else it is added.
        val shelf = shelfController?.takeIf { list -> controllers.none { it.packageName == list.packageName } }
        val next = Track.pick(controllers + listOfNotNull(shelf), context.packageName, { it.packageName }) {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        if (next?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(controllerCallback)
            controller = next
            next?.registerCallback(controllerCallback, handler)
            log("now playing: following ${next?.packageName ?: "no session"}")
        }
        publish()
    }

    private fun publish() {
        val track = controller?.let(::trackOf)
        val fromList = controller != null && controller === shelfController
        if (fromList && track?.playing == true) shelfPlayed = true
        shelfIdle = fromList && !shelfPlayed
        _track.value = track?.takeUnless { shelfIdle }
    }

    private fun trackOf(controller: MediaController): Track {
        val state = controller.playbackState
        val metadata = controller.metadata
        return Track.of(
            app = controller.packageName,
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            displayTitle = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            albumArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            state = state?.state,
            positionMs = state?.position,
            updatedAtMs = state?.lastPositionUpdateTime,
            speed = state?.playbackSpeed,
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION),
            mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
        )
    }
}
