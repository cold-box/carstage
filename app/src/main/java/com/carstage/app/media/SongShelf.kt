package com.carstage.app.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.media.MediaBrowserService
import androidx.core.content.edit
import com.carstage.app.NoticeBoard
import com.carstage.app.NoticeTone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The songs of the music app the car plays, for the first screen's song list (docs/07 "노래 목록").
 * It talks to the app the way the car's own media center does, through the app's
 * MediaBrowserService ([MediaBrowser]), so the same code serves any music app, the car's
 * Bluetooth app included: the app the player follows ([follow], from its media session) when it
 * has a browser service, else the one the user picked last, else the user picks one ([choose]).
 * A song picked from the list plays from the app's own session ([play]).
 *
 * A refusal (an app that lets only the apps it knows browse), an empty list, a failed or
 * unanswered load and a service that went away are shown on the card and logged, and told on the
 * notice line while the list is on the screen ([onScreen]), as a list nobody sees has nothing to
 * tell; the player keeps working on its own session. Runs on the main thread, as [MediaBrowser]
 * calls back there.
 */
class SongShelf(
    private val context: Context,
    private val notices: NoticeBoard,
    private val log: (String) -> Unit,
    private val warn: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _look = MutableStateFlow(ShelfLook.NONE)

    /** What the card shows. */
    val look: StateFlow<ShelfLook> = _look.asStateFlow()

    private val _session = MutableStateFlow<MediaSession.Token?>(null)

    /**
     * The media session of the app the list is connected to, null while none is; the player
     * follows it too, so the song can be read without notification access
     * ([com.carstage.app.audio.NowPlaying.shelfSession]).
     */
    val session: StateFlow<MediaSession.Token?> = _session.asStateFlow()

    /** The app whose session [session] is; kept while the list connects to that app again, so the player does not blink. */
    private var sessionApp: String? = null

    private var browser: MediaBrowser? = null

    /** The folder whose items are subscribed to, so a reload or a move unsubscribes it first. */
    private var subscribed: String? = null
    private var reconnects = 0

    /** The next try to connect again, while one waits. */
    private var reconnect: Runnable? = null

    /** How many cards show the list now (one in each place it can be). */
    private var shownOn = 0

    /** What the list would tell the notice line, put up only while the list is on the screen. */
    private var pending: Pair<String, NoticeTone>? = null

    /** Gives up on an app that does not answer a connect or a load ([ANSWER_MS]). */
    private val unanswered = Runnable {
        val app = _look.value.source?.packageName
        warn("songs: $app did not answer (${_look.value.step}) within $ANSWER_MS ms")
        disconnect()
        failed()
    }

    private val playNoticeDone = Runnable { notices.remove(PLAY_FAILED) }


    /**
     * CarStage came to the front: finds the car's sources again (an app may have been installed or
     * removed), follows the app that plays ([playing]) or the one picked last, and loads the songs
     * afresh, so a list that went wrong gets another try.
     */
    fun arrive(playing: String?) {
        val sources = sources()
        val source = MediaSource.choose(sources, playing, prefs.getString(KEY_SOURCE, null), context.packageName)
        when {
            sources.isEmpty() -> {
                disconnect()
                clearSession()
                tell(null)
                if (_look.value.step != ShelfLook.Step.NO_SOURCES) log("songs: no app in the car lets others browse its songs")
                _look.value = ShelfLook.NONE
            }
            source == null -> {
                disconnect()
                clearSession()
                tell(null)
                _look.value = ShelfLook(null, sources, emptyList(), ShelfLook.Step.NO_SOURCE, emptyList())
                log("songs: no source to follow; ${sources.size} to pick from")
            }
            else -> connect(source, sources)
        }
    }

    /**
     * The player now follows [playing]'s session: the list follows that app when it has a browser
     * service and is not shown already. A picked app stays until another app starts playing.
     */
    fun follow(playing: String?) {
        if (playing == null || playing == _look.value.source?.packageName) return
        val sources = sources()
        val source = sources.firstOrNull { it.packageName == playing }
        if (source == null) {
            log("songs: $playing plays but has no browser service; keeping ${_look.value.source?.packageName ?: "no source"}")
            return
        }
        connect(source, sources)
    }

    /** A card shows the list ([shown]) or stops showing it: the notice line tells what it says only meanwhile. */
    fun onScreen(shown: Boolean) {
        shownOn = (shownOn + if (shown) 1 else -1).coerceAtLeast(0)
        pending?.let { (text, tone) -> if (shownOn > 0) notices.add(text, tone) else notices.remove(text) }
    }

    /** Shows the apps to pick from. */
    fun chooseOther() {
        _look.update { it.copy(sources = sources(), choosing = true) }
    }

    /** Back: out of the apps, or up a folder. */
    fun back() {
        val look = _look.value
        when {
            look.choosing -> _look.update { it.copy(choosing = false) }
            look.folders.isNotEmpty() -> {
                val folders = look.up()
                _look.update { it.copy(folders = folders) }
                // The car's back key goes up even while the app is not connected; the folder above loads once it connects.
                val root = browser?.takeIf { it.isConnected }?.root
                    ?: return warn("songs: went back while ${look.source?.packageName} is not connected")
                load(folders.lastOrNull()?.id ?: root)
            }
        }
    }

    /** The user picked [source]: remembered for the next time nothing plays. */
    fun choose(source: MediaSource) {
        prefs.edit { putString(KEY_SOURCE, source.packageName) }
        log("songs: picked ${source.packageName}")
        if (source == _look.value.source && browser?.isConnected == true) {
            _look.update { it.copy(choosing = false) }
            return
        }
        connect(source, _look.value.sources)
    }

    /** Tries the app again after the list went wrong (the card's button). */
    fun retry() {
        val look = _look.value
        val source = look.source ?: return
        log("songs: retrying ${source.packageName}")
        connect(source, look.sources)
    }

    /** Opens [folder] and shows what it holds. */
    fun open(folder: BrowseItem) {
        if (!folder.folder || browser?.isConnected != true) {
            warn("songs: cannot open ${folder.id}: ${if (folder.folder) "${_look.value.source?.packageName} is not connected" else "not a folder"}")
            return
        }
        _look.update { it.copy(folders = it.folders + folder) }
        load(folder.id)
    }

    /**
     * Asks the app to play [song] from its beginning, through the app's own session; false, with
     * a notice, when the app is not connected, says it cannot play by id, or refused.
     */
    fun play(song: BrowseItem): Boolean {
        val connected = browser?.takeIf { it.isConnected }
        val app = _look.value.source?.packageName
        if (connected == null || song.folder) {
            warn("songs: cannot play ${song.id}: ${if (song.folder) "a folder" else "$app is not connected"}")
            if (!song.folder) playFailed()
            return false
        }
        return try {
            val controller = MediaController(context, connected.sessionToken)
            // An app that told what it can do and left this out would ignore the request.
            val actions = controller.playbackState?.actions ?: 0L
            if (actions != 0L && actions and PlaybackState.ACTION_PLAY_FROM_MEDIA_ID == 0L) {
                warn("songs: $app does not play by id (actions $actions)")
                playFailed()
                return false
            }
            controller.transportControls.playFromMediaId(song.id, null)
            log("songs: play \"${song.title}\" (${song.id}) on $app")
            true
        } catch (e: RuntimeException) {
            // IllegalStateException: the connection dropped meanwhile; SecurityException,
            // IllegalArgumentException: the app's session refused or is broken.
            warn("songs: play ${song.id} on $app failed: $e")
            playFailed()
            false
        }
    }

    /** The apps whose browser service Android lets CarStage see (the manifest's queries). */
    private fun sources(): List<MediaSource> {
        val pm = context.packageManager
        val found = pm.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE), PackageManager.GET_RESOLVED_FILTER)
        val sources = found.mapNotNull { resolved ->
            val service = resolved.serviceInfo ?: return@mapNotNull null
            MediaSource(service.packageName, service.name, service.applicationInfo.loadLabel(pm).toString())
        }
        return MediaSource.pickable(sources, context.packageName)
    }

    private fun connect(source: MediaSource, sources: List<MediaSource>) {
        disconnect()
        if (source.packageName != sessionApp) clearSession()
        reconnects = 0
        _look.value = ShelfLook(source, sources, emptyList(), ShelfLook.Step.CONNECTING, emptyList())
        tell(null)
        start(source, again = false)
    }

    /** Connects to [source]'s service; [again] after it went away, so a failure is not taken for a refusal. */
    private fun start(source: MediaSource, again: Boolean) {
        val connection = Connection(source, again)
        // A service that cannot be bound fails through onConnectionFailed, not here.
        val next = MediaBrowser(context, ComponentName(source.packageName, source.service), connection, null)
        connection.browser = next
        browser = next
        log("songs: connecting to ${source.packageName}/${source.service}${if (again) " (again, $reconnects)" else ""}")
        next.connect()
        handler.postDelayed(unanswered, ANSWER_MS)
    }

    private fun disconnect() {
        handler.removeCallbacks(unanswered)
        reconnect?.let(handler::removeCallbacks)
        reconnect = null
        browser?.let { old ->
            subscribed?.let { id -> if (old.isConnected) runCatching { old.unsubscribe(id) } }
            old.disconnect()
        }
        browser = null
        subscribed = null
    }

    /** The list follows no app's session any more. */
    private fun clearSession() {
        _session.value = null
        sessionApp = null
    }

    private fun load(parentId: String) {
        val connected = browser?.takeIf { it.isConnected }
        if (connected == null) {
            warn("songs: cannot list $parentId: ${_look.value.source?.packageName} is not connected")
            return
        }
        subscribed?.let { id -> runCatching { connected.unsubscribe(id) } }
        subscribed = parentId
        _look.update { it.copy(step = ShelfLook.Step.LOADING, items = emptyList()) }
        try {
            // An empty id (a root of "") is refused by subscribe.
            connected.subscribe(parentId, Children(connected))
        } catch (e: RuntimeException) {
            warn("songs: cannot list \"$parentId\" of ${_look.value.source?.packageName}: $e")
            failed()
            return
        }
        handler.removeCallbacks(unanswered)
        handler.postDelayed(unanswered, ANSWER_MS)
    }

    private fun refused(source: MediaSource) {
        clearSession()
        _look.update { it.copy(step = ShelfLook.Step.REFUSED, items = emptyList(), folders = emptyList()) }
        tell(REFUSED to NoticeTone.CAUTION)
        warn("songs: ${source.packageName} refused to list its songs to CarStage")
    }

    private fun failed() {
        clearSession()
        _look.update { it.copy(step = ShelfLook.Step.FAILED, items = emptyList()) }
        tell(LOAD_FAILED to NoticeTone.ERROR)
    }

    private fun playFailed() {
        notices.add(PLAY_FAILED, NoticeTone.ERROR)
        handler.removeCallbacks(playNoticeDone)
        handler.postDelayed(playNoticeDone, PLAY_NOTICE_MS)
    }

    /** What the list tells the notice line in place of what it told before; null for nothing. */
    private fun tell(notice: Pair<String, NoticeTone>?) {
        pending?.let { (text, _) -> notices.remove(text) }
        pending = notice
        if (notice != null && shownOn > 0) notices.add(notice.first, notice.second)
    }

    private inner class Connection(private val source: MediaSource, private val again: Boolean) : MediaBrowser.ConnectionCallback() {
        lateinit var browser: MediaBrowser

        private val current get() = browser === this@SongShelf.browser

        override fun onConnected() {
            if (!current) return
            handler.removeCallbacks(unanswered)
            tell(null)
            log("songs: connected to ${source.packageName}, root \"${browser.root}\"")
            // Connected, so the token is there; a connection that drops at once leaves none.
            runCatching { browser.sessionToken }.getOrNull()?.let {
                sessionApp = source.packageName
                _session.value = it
            }
            load(_look.value.folders.lastOrNull()?.id ?: browser.root)
        }

        override fun onConnectionFailed() {
            if (!current) return
            handler.removeCallbacks(unanswered)
            if (again) {
                warn("songs: reconnect $reconnects to ${source.packageName} failed")
                lost(source)
            } else {
                refused(source)
            }
        }

        override fun onConnectionSuspended() {
            if (!current) return
            warn("songs: ${source.packageName} went away (connection suspended)")
            lost(source)
        }
    }

    /** [source]'s service went away: connect again a few times, then give up until [retry] or the next arrival. */
    private fun lost(source: MediaSource) {
        disconnect()
        if (reconnects < MAX_RECONNECTS) {
            reconnects++
            _look.update { it.copy(step = ShelfLook.Step.LOST, items = emptyList()) }
            tell(LOST to NoticeTone.CAUTION)
            val next = Runnable {
                reconnect = null
                if (browser == null && _look.value.source == source) start(source, again = true)
            }
            reconnect = next
            handler.postDelayed(next, RECONNECT_MS)
        } else {
            warn("songs: gave up reconnecting to ${source.packageName}")
            failed()
        }
    }

    private inner class Children(private val owner: MediaBrowser) : MediaBrowser.SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) {
            if (owner !== browser || parentId != subscribed) return
            handler.removeCallbacks(unanswered)
            val items = children.mapNotNull { child ->
                val description = child.description
                BrowseItem.of(description.mediaId, description.title, description.subtitle, child.isBrowsable, child.isPlayable)
            }
            val app = _look.value.source?.packageName
            log("songs: $app $parentId has ${items.size} items (${items.count { it.folder }} folders) of ${children.size}")
            _look.update { it.copy(step = if (items.isEmpty()) ShelfLook.Step.EMPTY else ShelfLook.Step.READY, items = items) }
            tell(if (items.isEmpty()) EMPTY to NoticeTone.INFO else null)
        }

        override fun onError(parentId: String) {
            if (owner !== browser || parentId != subscribed) return
            handler.removeCallbacks(unanswered)
            warn("songs: ${_look.value.source?.packageName} failed to list $parentId")
            failed()
        }
    }

    companion object {
        const val REFUSED = "이 음악 앱은 곡 목록을 열어 주지 않아요. 음악 앱에서 곡을 골라 주세요."
        const val EMPTY = "이 음악 앱에는 보여 줄 곡이 없어요."
        const val LOST = "음악 앱과 연결이 끊겨서 다시 연결하고 있어요."
        const val LOAD_FAILED = "곡 목록을 불러오지 못했어요. 다시 불러오거나 다른 앱을 골라 주세요."
        const val PLAY_FAILED = "곡을 틀지 못했어요. 음악 앱에서 틀어 주세요."

        private const val PREFS = "songs"
        private const val KEY_SOURCE = "source"
        private const val RECONNECT_MS = 3_000L
        private const val MAX_RECONNECTS = 3

        /** How long a connect or a load may take before the list says it failed. */
        private const val ANSWER_MS = 10_000L
        private const val PLAY_NOTICE_MS = 5_000L
    }
}
