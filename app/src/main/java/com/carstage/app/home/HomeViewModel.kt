package com.carstage.app.home

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carstage.app.CarStageApp
import com.carstage.app.ChargeFinish
import com.carstage.app.NoticeBoard
import com.carstage.app.NoticeTone
import com.carstage.app.ResumeOffers
import com.carstage.app.ShownNotice
import com.carstage.app.audio.LiveMic
import com.carstage.app.audio.Track
import com.carstage.app.media.BrowseItem
import com.carstage.app.media.MediaSource
import com.carstage.app.media.ShelfLook
import com.carstage.app.score.SongResult
import com.carstage.app.stage.DuelState
import com.carstage.app.stage.Side
import com.carstage.app.stage.StageController
import com.carstage.app.vehicle.BatteryState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The product's first screen: opens and closes the stage and shows what the cabin is doing. */
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = getApplication<CarStageApp>().graph

    val phase: StateFlow<StageController.Phase> = graph.stage.phase
    /** What the simulator draws: the cabin as the app has set it. */
    val cabin: StateFlow<CabinLook> = graph.simulator.state
        .map(CabinLook::of)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CabinLook.of(graph.simulator.state.value))

    /**
     * Whether the drawing may show what the car does not do: the car refused this app some control
     * when last tried, or this build does not drive the cabin at all (docs/11 5장, 9장).
     */
    val cabinPreview: StateFlow<Boolean> = graph.cabinPreview

    /** The stage glow along the screen's edges, from the simulator while the stage is open and a duel's state; see [GlowLook]. */
    val glow: StateFlow<GlowLook> = combine(cabin, phase, graph.songs.announcing) { look, phase, announcing ->
        Triple(look, phase == StageController.Phase.OPEN, announcing)
    }
        .runningFold(GlowLook.NONE) { previous, (look, open, announcing) -> GlowLook.of(look, open, announcing, previous) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlowLook.NONE)

    /** The notice line with its most serious kind, which picks its color (docs/10 5장 "색"). */
    val notice: StateFlow<ShownNotice?> = graph.notices.notice
        .map(::shown)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), shown(graph.notices.notice.value))

    private fun shown(text: String?): ShownNotice? = text?.let { ShownNotice(it, graph.notices.toneOf(it)) }

    /** The last song's 흥 점수, shown until it is dismissed, a new stage opens or the car drives off. */
    val result: StateFlow<SongResult?> = graph.songs.result

    /** Whether a song on the open stage has been scored yet. */
    val scored: StateFlow<Boolean> = graph.songs.scored

    /** Whether another app plays music, for the offer to open the stage. */
    val musicPlaying: StateFlow<Boolean> = graph.music.playing

    private val pause = PlayerPause()

    /** The player for the music another app plays; null hides it. */
    val player: StateFlow<PlayerLook?> =
        combine(graph.nowPlaying.track, graph.nowPlaying.access, graph.music.playing, pause.pausedHere, phase) { track, access, music, paused, phase ->
            PlayerLook.of(track, access, music, paused, stageClosed = phase == StageController.Phase.CLOSED)
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The song list of the music app the car plays (docs/07 "노래 목록"). */
    val shelf: StateFlow<ShelfLook> = graph.shelf.look

    /** The song another app plays, which the list marks. */
    val track: StateFlow<Track?> = graph.nowPlaying.track

    private val _listOpen = MutableStateFlow(false)

    /**
     * Whether a duel's passenger is to pick a song: their turn has come and the music is paused for
     * it (AppGraph), so the list opens and the screen asks for one (docs/13).
     */
    val pickForTurn: StateFlow<Boolean> = combine(graph.songs.duel, graph.music.playing, graph.nowPlaying.track) { duel, playing, track ->
        duel?.step == DuelState.Step.TURN && duel.turn == Side.PASSENGER && !playing && track?.playing != true
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Whether the player's list button opened the song list on an open stage. */
    val listOpen: StateFlow<Boolean> = _listOpen.asStateFlow()

    /** Whether the song list shows under the player: opened by its button on a stage that is not closed. */
    val songsUnderPlayer: StateFlow<Boolean> = combine(phase, shelf, listOpen, player, pickForTurn) { phase, shelf, open, player, pick ->
        // The player shows only on an open stage (docs/13); a duel's next singer is shown the list.
        (open || pick) && player != null && phase == StageController.Phase.OPEN && shelf.shown
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        graph.music.playing.onEach(pause::onMusicPlaying).launchIn(viewModelScope)
        // A closed stage shows the list in its own place, so the player's opens anew each stage.
        phase.onEach { if (it == StageController.Phase.CLOSED) _listOpen.value = false }.launchIn(viewModelScope)
    }

    /**
     * CarStage came to the front ([command]: for Gleo or a Lab command): the song reset and the
     * list loaded afresh ([com.carstage.app.AppGraph.arrive]). Music the player cannot read that
     * was paused keeps the player, so it can be played again.
     */
    fun arrived() = graph.arrive()

    fun toggleList() = _listOpen.update { !it }

    fun songsBack() = graph.shelf.back()

    fun retrySongs() = graph.shelf.retry()

    /** A card shows the song list or stops showing it; see [com.carstage.app.media.SongShelf.onScreen]. */
    fun songsOnScreen(shown: Boolean) = graph.shelf.onScreen(shown)

    /** Set while the activity is recreated for a new configuration, which is not CarStage coming to the front. */
    var recreating = false

    fun chooseOtherSource() = graph.shelf.chooseOther()

    fun chooseSource(source: MediaSource) = graph.shelf.choose(source)

    fun openFolder(folder: BrowseItem) = graph.shelf.open(folder)

    /** Plays [song] in its app; true when asked, so a closed stage then opens the way its button does. */
    fun playSong(song: BrowseItem): Boolean = graph.shelf.play(song)

    /** An offer to sing on after a call or a drive stopped the stage. */
    val resumeOffer: StateFlow<ResumeOffers.Offer?> = graph.resume.offer

    /** A duel on the stage, or its result. */
    val duel: StateFlow<DuelState?> = graph.songs.duel

    /**
     * The sides whose reading light is on in the simulator, so also when the car refuses control,
     * which the duel's board lights with during the announcement until its crown.
     */
    val readingLit: StateFlow<Set<Side>> = cabin
        .map(::litSides)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), litSides(cabin.value))

    private fun litSides(look: CabinLook): Set<Side> = look.readingLights.filterValues { it }.keys

    /** Whether the stage is open and both front seats are taken, to offer a duel. */
    val duelOffered: StateFlow<Boolean> = graph.duelOffer.offered

    /** The EV battery, read while this is collected (an open stage reads it too); null before the first read and for a car without one. */
    val battery: StateFlow<BatteryState?> = graph.battery.state

    /** Where the question about ending with the song being sung stands after charging stopped: none, asking, or the last song chosen. */
    val chargeFinish: StateFlow<ChargeFinish.Step> = graph.chargeFinish.step

    /** Whether the stage listens, for the status line ("듣고 있어요", "잠시 안 들려요"). */
    val mic: StateFlow<LiveMic.State> = graph.mic.state

    /** Whether the MC speaks, for the status line's switch (docs/12 "MC 끄기"). */
    val mcOn: StateFlow<Boolean> = graph.mcSetting.on

    fun setMc(on: Boolean) = graph.mcSetting.set(on)

    /** Whether the MC has a line waiting or the stage's sound plays, so a score's reveal waits for it. */
    fun mcBusy(): Boolean = graph.mcBusy()

    /** Every feature, or the basic flow (docs/13), as the corner's switch sets it. */
    val allFeatures: StateFlow<Boolean> = graph.features.all

    fun setAllFeatures(all: Boolean) = graph.features.set(all)

    /** The songs scored on the last stage, which the basic flow shows once it closes. */
    val stageScores: StateFlow<List<SongResult>> = graph.songs.stageScores

    fun dismissStageScores() = graph.songs.dismissStageScores()

    /** The basic flow's "이어서 부르기" plays the paused music again. */
    fun resumeMusic() = graph.resumeMusic()

    /** How far the voices stand out of the music, for the meter while the stage is open; see [com.carstage.app.VoiceMeter]. */
    val voiceLevel: StateFlow<Float> = graph.meter.level

    /** How loud the music under the voices is, which the meter shows with them (0..1). */
    val musicLevel: StateFlow<Float> = graph.meter.music

    /** The stage light's color as opaque ARGB, for the meter; changes less often than [glow]. */
    val meterColor: StateFlow<Int> = glow.map { it.argb }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), glow.value.argb)

    /** The cheers heard so far; each new one flashes the meter. */
    val cheers: StateFlow<Int> = graph.meter.cheers

    /** Whether to show the Lab's button and the values under the cabin drawing; see [com.carstage.app.AppGraph.devDisplays]. */
    val devDisplays: StateFlow<Boolean> = graph.devDisplays

    /** The button names what the user saw, so a tap that lands mid-transition cannot flip the stage. */
    fun openStage() = graph.request(open = true)

    fun closeStage() = graph.request(open = false)

    /** The consent to ask before a tap opens the stage, so the music can be taken out of the microphone; null when not to ask. */
    fun captureConsent(): Intent? = graph.projection.askIntent()

    fun captureAnswered(resultCode: Int, data: Intent?) = graph.projection.answered(resultCode, data)

    fun captureUnavailable(why: String) = graph.projection.unavailable(why)

    fun micPermissionDenied() = graph.micPermissionDenied()

    /** The microphone is allowed, so a notice asking for it is taken down. */
    fun micAllowed() = graph.notices.remove(NoticeBoard.MIC_NOT_ALLOWED)

    /** Ends the song by hand, for when the gap after it was not heard. */
    fun endSong() = graph.endSong()

    fun dismissResult() = graph.songs.dismissResult()

    fun dismissResume() = graph.dismissResume()

    /** Asks for a duel, the driver first, opening the stage if needed; [now] does not wait for the song being sung. */
    fun startDuel(now: Boolean) = graph.songs.requestDuel(now)

    /** The duel's result is closed; its songs' scores go with it, the duel having told them. */
    fun dismissDuel() {
        graph.songs.dismissDuel()
        if (phase.value == StageController.Phase.CLOSED) graph.songs.dismissStageScores()
    }

    fun lastSong() = graph.chargeFinish.lastSong()

    fun keepSinging() = graph.chargeFinish.keepSinging()

    fun playPause() = graph.nowPlaying.playPause(pause.press(player.value, graph.music.playing.value))

    fun nextSong() = graph.nowPlaying.next()

    fun previousSong() = graph.nowPlaying.previous()

    /** Opens the car's settings where the user can let the app read the playing song. */
    fun askSongAccess(context: Context) {
        if (graph.nowPlaying.openSettings(context)) return
        // Added, not shown, so the line keeps the notices that stay until their cause ends.
        graph.notices.add(NoticeBoard.NO_ACCESS_SETTINGS, NoticeTone.CAUTION)
        viewModelScope.launch {
            delay(NOTICE_MS)
            graph.notices.remove(NoticeBoard.NO_ACCESS_SETTINGS)
        }
    }

    private companion object {
        const val NOTICE_MS = 5_000L
    }
}
