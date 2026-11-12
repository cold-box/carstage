package com.carstage.mediatester

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.media.MediaBrowserService
import android.util.Log
import kotlin.concurrent.thread

/**
 * A music app as the Pleos Music/Audio guide asks for one: a MediaBrowserService that lists the
 * songs in files/music (scripts/media-library.sh puts them there) plus a built-in test song, and a
 * MediaSession that plays them with play, pause, stop, skip, seek and play-from-media-id. Any app
 * may browse it, so CarStage can be tested the way it will meet Melon or the car's Bluetooth app.
 * Audio focus works like a real music app: GAIN pauses other media, a transient loss pauses,
 * ducking lowers the volume and a permanent loss stops and deactivates the session.
 */
class LibraryService : MediaBrowserService() {
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(::onFocusChange)
            .build()
    }
    private lateinit var session: MediaSession
    private val library by lazy { Library(filesDir) }

    // Main thread only: session callbacks, player listeners and focus changes all arrive there.
    private var songs: List<Song> = emptyList()
    private var current: Song? = null
    private var player: MediaPlayer? = null
    private var prepared = false

    /** Whether the song should play once prepared; a pause or focus loss while preparing clears it. */
    private var playWhenReady = false
    private var resumeOnGain = false

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, TAG).apply {
            setCallback(Callback())
            setPlaybackState(state(PlaybackState.STATE_NONE))
        }
        sessionToken = session.sessionToken
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        Log.i(TAG, "browse root for $clientPackageName")
        return BrowserRoot(ROOT, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        if (parentId != ROOT) {
            result.sendResult(mutableListOf())
            return
        }
        result.detach()
        thread(name = "library-scan") {
            val scanned = try {
                library.scan()
            } catch (e: Exception) {
                Log.e(TAG, "library scan failed", e)
                // null tells the browser the load failed, rather than leaving it waiting.
                mainExecutor.execute { result.sendResult(null) }
                return@thread
            }
            mainExecutor.execute {
                songs = scanned
                Log.i(TAG, "library: ${scanned.size} songs")
                result.sendResult(scanned.mapTo(mutableListOf()) { it.item() })
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                // startForegroundService needs startForeground even when playback cannot start.
                goForeground()
                resumeOrStart(intent.getStringExtra(EXTRA_SONG))
            }
            ACTION_STOP -> stop()
            // goForeground starts the service without an action only to keep it alive.
            null -> Unit
            else -> Log.w(TAG, "ignored start action ${intent.action}")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stop()
        session.release()
        super.onDestroy()
    }

    private inner class Callback : MediaSession.Callback() {
        override fun onPlay() = resumeOrStart(null)
        override fun onPause() = pause()
        override fun onStop() = stop()
        override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) {
            // An unknown id leaves whatever plays as it is, so the session never reports a state that is not true.
            findSong(mediaId)?.let(::play) ?: Log.e(TAG, "no song with id $mediaId; ${describe(songs)}")
        }
        override fun onSkipToNext() {
            if (!skip(1)) Log.i(TAG, "no next song")
        }
        override fun onSkipToPrevious() {
            val position = player?.takeIf { prepared }?.currentPosition ?: 0
            if (position > RESTART_MS) seek(0) else skip(-1)
        }
        override fun onSeekTo(pos: Long) = seek(pos.toInt())
    }

    private fun findSong(id: String): Song? {
        if (songs.none { it.id == id }) songs = library.scan()
        return songs.firstOrNull { it.id == id }
    }

    private fun resumeOrStart(name: String?) {
        if (name != null) {
            songs = library.scan()
            val named = songs.firstOrNull { it.title == name || it.id == name }
            if (named == null) {
                // Playing something else would pass for the song asked for.
                Log.e(TAG, "no song named \"$name\"; ${describe(songs)}")
                fail("곡을 찾을 수 없어요: $name")
            } else {
                play(named)
            }
            return
        }
        val p = player
        when {
            p != null && prepared -> {
                if (!requestFocus()) return
                p.start()
                playWhenReady = true
                goForeground()
                publish(PlaybackState.STATE_PLAYING)
            }
            else -> play(current ?: library.scan().also { songs = it }.first())
        }
    }

    /** Returns false when the song could not start. */
    private fun play(song: Song): Boolean {
        if (!requestFocus()) return false
        releasePlayer()
        current = song
        prepared = false
        playWhenReady = true
        session.setMetadata(song.metadata(null))
        session.isActive = true
        publish(PlaybackState.STATE_BUFFERING)
        goForeground()
        val p = MediaPlayer()
        player = p
        p.setAudioAttributes(attributes)
        p.isLooping = song.loop
        p.setOnPreparedListener {
            prepared = true
            session.setMetadata(song.metadata(it.duration.toLong()))
            if (playWhenReady) {
                it.start()
                Log.i(TAG, "playing ${song.title}")
                publish(PlaybackState.STATE_PLAYING)
            } else {
                Log.i(TAG, "prepared ${song.title}, paused")
                publish(PlaybackState.STATE_PAUSED)
            }
        }
        p.setOnCompletionListener {
            Log.i(TAG, "finished ${song.title}")
            if (!skip(1)) stop()
        }
        p.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "MediaPlayer error $what/$extra on ${song.title}")
            fail("재생할 수 없는 곡이에요")
            true
        }
        return try {
            p.setDataSource(song.file.path)
            p.prepareAsync()
            true
        } catch (e: Exception) {
            Log.e(TAG, "cannot open ${song.file}", e)
            fail("재생할 수 없는 곡이에요")
            false
        }
    }

    private fun pause() {
        val p = player ?: return
        playWhenReady = false
        if (prepared && p.isPlaying) p.pause()
        publish(PlaybackState.STATE_PAUSED)
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    /** Returns false when there is no song in that direction. */
    private fun skip(step: Int): Boolean {
        if (songs.isEmpty()) songs = library.scan()
        val index = songs.indexOfFirst { it.id == current?.id }
        val next = songs.getOrNull(index + step) ?: return false
        return play(next)
    }

    private fun seek(ms: Int) {
        val p = player?.takeIf { prepared } ?: run {
            Log.w(TAG, "seek to $ms ignored: no prepared song")
            return
        }
        p.seekTo(ms.coerceIn(0, p.duration))
        publish(if (p.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED)
    }

    private fun stop() {
        releasePlayer()
        playWhenReady = false
        audioManager.abandonAudioFocusRequest(focusRequest)
        resumeOnGain = false
        publish(PlaybackState.STATE_STOPPED)
        session.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        // A pause detached the notification from the service, so removing the foreground alone leaves it.
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        stopSelf()
    }

    /**
     * Keeps the service alive while it plays: started, so it outlives a client that only bound to
     * browse, and in the foreground with its notification.
     */
    private fun goForeground() {
        startService(Intent(this, LibraryService::class.java))
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        prepared = false
    }

    private fun requestFocus(): Boolean {
        if (audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return true
        // Android refuses media focus mostly during a call.
        Log.e(TAG, "audio focus denied (audio mode ${audioManager.mode}); not playing")
        fail("지금은 재생할 수 없어요 (통화 중일 수 있어요)")
        return false
    }

    private fun onFocusChange(change: Int) {
        Log.i(TAG, "focus change $change")
        val p = player
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                p?.setVolume(1f, 1f)
                if (resumeOnGain && p != null && prepared) {
                    p.start()
                    playWhenReady = true
                    goForeground()
                    publish(PlaybackState.STATE_PLAYING)
                }
                resumeOnGain = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> p?.setVolume(DUCK_VOLUME, DUCK_VOLUME)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnGain = p != null && playWhenReady
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS -> stop()
        }
    }

    private fun publish(state: Int) {
        session.setPlaybackState(state(state))
        if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }

    /**
     * Stops whatever played and gives the focus and the foreground back, so other apps play again,
     * then reports [message]. The session stays active so the error shows where the song showed.
     */
    private fun fail(message: String) {
        releasePlayer()
        playWhenReady = false
        resumeOnGain = false
        audioManager.abandonAudioFocusRequest(focusRequest)
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_ERROR, 0, 0f)
                .setErrorMessage(message)
                .setActions(ACTIONS)
                .build()
        )
    }

    private fun describe(songs: List<Song>) = "library: " + songs.joinToString { "\"${it.title}\" (${it.id})" }

    private fun state(state: Int): PlaybackState {
        val position = player?.takeIf { prepared }?.currentPosition?.toLong() ?: 0L
        val speed = if (state == PlaybackState.STATE_PLAYING) 1f else 0f
        return PlaybackState.Builder()
            .setState(state, position, speed)
            .setActions(ACTIONS)
            .build()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(current?.title ?: "Media Tester")
            .setContentText(current?.artist)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
            .build()
    }

    companion object {
        const val ACTION_PLAY = "com.carstage.mediatester.PLAY"
        const val ACTION_STOP = "com.carstage.mediatester.STOP"
        const val EXTRA_SONG = "song"
        private const val TAG = "MediaTester"
        private const val ROOT = "root"
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 1
        private const val RESTART_MS = 3_000
        private const val DUCK_VOLUME = 0.3f
        private const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID
    }
}

/** A playable file. [loop] is set only for the built-in test song, which is a few seconds long. */
data class Song(val id: String, val title: String, val artist: String, val file: java.io.File, val loop: Boolean) {
    fun item(): MediaBrowser.MediaItem = MediaBrowser.MediaItem(
        MediaDescription.Builder().setMediaId(id).setTitle(title).setSubtitle(artist).build(),
        MediaBrowser.MediaItem.FLAG_PLAYABLE,
    )

    fun metadata(durationMs: Long?): MediaMetadata = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, id)
        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
        .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
        .apply { if (durationMs != null) putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs) }
        .build()
}
