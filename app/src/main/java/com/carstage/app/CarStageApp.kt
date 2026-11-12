package com.carstage.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.carstage.app.audio.Announcer
import com.carstage.app.audio.CallState
import com.carstage.app.audio.EffectPlayer
import com.carstage.app.audio.LiveMic
import com.carstage.app.audio.MicGate
import com.carstage.app.audio.MusicPlayback
import com.carstage.app.audio.NowPlaying
import com.carstage.app.audio.PlaybackProjection
import com.carstage.app.audio.PlaybackTap
import com.carstage.app.gleo.GleoActions
import com.carstage.app.gleo.GleoRouter
import com.carstage.app.media.SongShelf
import com.carstage.app.safety.DriveState
import com.carstage.app.safety.SafetyGuard
import com.carstage.app.safety.UxRestrictions
import com.carstage.app.safety.VehicleDriveReader
import com.carstage.app.stage.AudioEvent
import com.carstage.app.stage.CarCabin
import com.carstage.app.stage.ControlDenials
import com.carstage.app.stage.DuelState
import com.carstage.app.stage.RestoreJournal
import com.carstage.app.stage.Side
import com.carstage.app.stage.SimulatedCabin
import com.carstage.app.stage.StageController
import com.carstage.app.stage.StageEngine
import com.carstage.app.stage.StageRecovery
import com.carstage.app.stage.carCabin
import com.carstage.app.stage.underway
import com.carstage.app.vehicle.CarKind
import com.carstage.app.vehicle.CarKindReader
import com.carstage.app.vehicle.EvBatteryReader
import com.carstage.app.vehicle.NightModeReader
import com.carstage.app.vehicle.SeatOccupancyReader
import com.carstage.app.vehicle.TripReader
import com.carstage.app.vehicle.VehicleSession
import com.carstage.app.vehicle.describe
import java.io.File
import java.time.LocalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What happened when someone asked to open the stage. */
sealed interface OpenOutcome {
    data class Opened(val result: StageController.OpenResult) : OpenOutcome

    /** The safety rules did not allow it; [reason] is a Korean sentence for the user. */
    data class Refused(val reason: String) : OpenOutcome
}

/** What happened when someone asked for a duel. */
sealed interface DuelOutcome {
    /**
     * Asked for; with [afterThisSong] the driver's turn starts once the song being sung ends, and
     * null when the stage did not say in time how it started. [turnIsNew] is false when the
     * driver's turn was already on, so asking again calls no new turn (the MC says nothing).
     */
    data class Asked(val afterThisSong: Boolean?, val turnIsNew: Boolean = true) : DuelOutcome

    /** The stage could not open, or has no director; [reason] is a Korean sentence for the user. */
    data class Refused(val reason: String) : DuelOutcome
}

class CarStageApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        // Until the microphone runs in a foreground service, the stage must not keep the cabin
        // changed while no CarStage screen is visible.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = graph.cameBack()

            override fun onStop(owner: LifecycleOwner) = graph.leftScreen()
        })
    }
}

/**
 * Objects the whole app shares, built once per process: one vehicle connection, one simulator
 * and one stage, so every screen and Gleo see the same state. Wiring is done by hand on purpose;
 * the app is small enough not to need a dependency-injection library. Opening and closing the
 * stage lives here; what the songs came to is [songs], the screen's notice line is [notices], and
 * putting back what an earlier stage left is [leftovers].
 */
class AppGraph(private val context: Context) {
    /**
     * Last resort for a coroutine that failed: log it and close the stage so the cabin is restored.
     * A close started here is not retried from here when it fails too, so a repeating failure cannot loop.
     */
    private val crashHandler: CoroutineExceptionHandler = CoroutineExceptionHandler { failed, e ->
        Log.e(TAG, "uncaught failure in the app scope", e)
        if (failed[CoroutineName] != CLOSE_AFTER_FAILURE && stage.phase.value == StageController.Phase.OPEN) {
            scope.launch(CLOSE_AFTER_FAILURE) { closeStage("uncaught failure") }
        }
    }

    /** Lives as long as the process, so a stage outlives the screen that opened it. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashHandler)

    /** The line of Korean the first screen shows about what did not go as planned. */
    val notices = NoticeBoard()

    /**
     * Whether the first screen shows what is only for development: the Lab's button and the
     * values under the cabin drawing (docs/10 6장, 7장 "개발용 표시"). Debug builds show them
     * unless a demo recording hides them (DevLab, `--es demo on`, and `off` to show them again);
     * release builds never do.
     */
    val devDisplays = MutableStateFlow(DevLab.AVAILABLE)

    val vehicleSession = VehicleSession(context)
    val simulator = SimulatedCabin()

    /** Originals of what an open stage changed in the car, kept on disk until it closes. */
    private val journal = RestoreJournal(File(context.filesDir, "stage/restore-journal.properties"))
    /** A car that refuses control to this app (Vehicle Control is "Coming Soon") still gets a stage on the screen. */
    private val denials = ControlDenials { notices.add(NoticeBoard.CONTROL_DENIED, NoticeTone.CAUTION) }

    /**
     * The car as the stage's output, in a build that drives the cabin (app/build.gradle.kts
     * `releaseDrivesCabin`, docs/11 9장). A build that does not has none and carries no code that
     * calls the car's control API, whose permissions Pleos App Market has not agreed for this app.
     */
    private val vehicleCabin = carCabin(vehicleSession, ::log, journal, denials)

    /**
     * Whether the cabin drawing is a preview of what the car would do: it refused some control when
     * last tried ([ControlDenials.refused]), or this build does not drive the cabin at all.
     */
    val cabinPreview: StateFlow<Boolean> = if (vehicleCabin != null) denials.refused else MutableStateFlow(true)

    /** Drive state for the safety rules: parked, driving restrictions, defroster, wipers. */
    val safety = SafetyGuard(
        VehicleDriveReader(vehicleSession, UxRestrictions.connect(context, ::warn), ::warn),
        log = { line, e -> Log.w(TAG, line, e) },
    )

    val stage: StageController = StageController(
        outputs = listOfNotNull(vehicleCabin, simulator),
        scope = scope,
        log = ::log,
        // batterySaver is built below; the gate only runs once a stage is open.
        gate = cabinGate(safety::allows) { batterySaver.allows(it) },
    )

    /** Puts back what an earlier stage left in the car; see [Leftovers]. */
    val leftovers = Leftovers(
        scope,
        StageRecovery(
            journal,
            // Only a build that drove the cabin leaves originals; one installed over it may not write them back.
            vehicleCabin?.let { it::restoreOriginals } ?: { originals -> originals.keys.map { StageRecovery.Failure(it, "this build does not drive the cabin") } },
        ) { line, e -> Log.w(TAG, line, e) },
        whileClosed = { block -> stage.whileClosed(block) },
        parkedNow = ::parkedNow,
        notices = notices,
        warn = ::warn,
    )

    /** Calls stop the stage, and with it the microphone. */
    val calls = CallState(context, ::log)

    /** Whether another app plays music, so the first screen offers the stage (docs/07). */
    val music = MusicPlayback(context, ::log)

    /** The song another app plays and the first screen's player buttons (docs/07 "첫 화면 미니 플레이어"). */
    val nowPlaying = NowPlaying(context, ::log)

    /** The songs of the music app the car plays, for the first screen's song list (docs/07 "노래 목록"). */
    val shelf = SongShelf(context, notices, ::log, ::warn)

    /** The stage's sounds (applause, drumroll, fanfare); see [EffectPlayer]. The Lab plays them too. */
    val effects = EffectPlayer(context, ::log)

    /** The MC's voice: a duel's announcement and the MC's lines ([StageMc]); see [Announcer]. */
    val voice = Announcer(context, ::log) { line, e -> Log.w(TAG, line, e) }

    /** Whether the MC speaks, as the user set it on the first screen (docs/12 "MC 끄기"). */
    val mcSetting = McSetting(context)

    /** The basic flow or every feature, as the user set it in the first screen's corner (docs/13). */
    val features = FeatureMode(context)

    /** The first screen's voice meter, fed every event the microphone hands the stage (not the cheers taken for its own sound). */
    val meter = VoiceMeter()

    /** The capture of what the other apps play that the user allows when a tap opens the stage (docs/09 "에코 제거"). */
    val projection = PlaybackProjection(context, ::log)

    /**
     * Hears what the other apps play while the microphone listens, for the echo canceller: a
     * development build's helper first (the emulator has no consent dialog), then the capture the
     * user allowed. Without either the stage listens as before and says so once.
     */
    val tap = PlaybackTap(
        candidates = {
            sequence {
                DevPlayback.source()?.let { yield(PlaybackTap.Candidate.Source(it)) }
                yield(projection.candidate())
            }
        },
        musicPlaying = { music.playing.value },
        show = { notices.add(it, NoticeTone.INFO) },
        hide = notices::remove,
        log = ::log,
        warn = { line, e -> Log.w(TAG, line, e) },
        release = projection::release,
    )

    /** Listens only while the stage is open (see [init]); the Lab replays recordings through it. */
    val mic = LiveMic(
        hasPermission = ::hasMicPermission,
        // The stage's own sound coming back through the microphone is not the cabin cheering.
        onEvent = { event ->
            if (event == AudioEvent.Cheer && (effects.echoing() || voice.echoing())) {
                log("cheer ignored: the stage's own sound was playing")
            } else {
                meter.hear(event)
                stage.onAudio(event)
            }
        },
        onFailed = { reason, cause ->
            // A fault in the app's own audio code is not the microphone's fault; say so honestly.
            val notice = if (cause == null) "마이크가 끊겨서 무대를 닫았어요. 다시 열어 주세요." else "문제가 생겨서 무대를 닫았어요. 다시 열어 주세요."
            scope.launch { stopStage("audio input stopped: $reason", notice, NoticeTone.ERROR) }
        },
        // A notice shown before the silence (such as "not connected to the car") or added meanwhile stays.
        onSilence = notices::silence,
        log = ::log,
        warn = { line, e -> Log.w(TAG, line, e) },
        tap = tap,
    )

    /** Which song the music app played when, so a score can name its song. */
    private val titles = SongTitles()

    /** Ends the song being sung as the music app moves on to another (docs/09 "곡 종료 감지"). */
    private val trackEnds = TrackEnds()

    /** The songs' 흥 점수 and a duel; see [SongSession]. */
    val songs = SongSession(
        scope,
        stage.phase,
        music.playing,
        ::openStage,
        notices,
        ::log,
        ::warn,
        fail = { line, e -> Log.e(TAG, line, e) },
        titleOf = { seconds -> titles.titleOf(SystemClock.elapsedRealtime(), seconds) },
    )

    private val gleo = GleoRouter(
        drivesCabin = vehicleCabin != null,
        endSong = stage::endSong,
        startDuel = {
            // The basic flow has no duel on its screen to show the turns.
            if (features.basic) refuseBasic("duel asked in the basic flow", NoticeBoard.DUEL_IN_ALL).let { DuelOutcome.Refused(it.reason) } else songs.startDuel()
        },
        openStage = {
            // The basic flow's stage is a song: with none playing, the user picks one on the screen.
            if (features.basic && !music.playing.value) refuseBasic("opened by voice with no music in the basic flow", NoticeBoard.PICK_FIRST) else openStage()
        },
    )

    /** Whether a duel's passenger is picking the song of their turn, the music paused for it (main thread). */
    private val pick = PassengerPick()

    /** Connects early so the first open does not wait for the vehicle service; see [awaitVehicleBriefly]. */
    @Volatile
    private var vehicleReady: Deferred<Result<Int>> = connectVehicle()

    /** After a call or a drive stopped the stage, offers to sing on once it is over and the car is parked. */
    val resume = ResumeOffers(scope, calls.inCall, parked = { safety.checkMotion().stageAllowed }, notice = notices.flow, log = ::log)

    /** Offers a duel while the stage is open and both front seats are taken (docs/04 "기능 2"). */
    val duelOffer = DuelOffer(
        scope,
        open = stage.phase.map { it == StageController.Phase.OPEN },
        bothSeated = SeatOccupancyReader(vehicleSession, ::log)::bothFrontSeatsTaken,
        log = ::log,
    )

    private val evBattery = EvBatteryReader(vehicleSession, ::log)

    /** Reads what drives the car (Android's standard vehicle information); null where Android has no car service. */
    private val carKind = CarKindReader.connect(context, scope, ::log)

    /**
     * The EV battery while the first screen shows or the stage is open (docs/07 "충전 연동"); a car
     * known to run on fuel only has none, so it is not asked.
     */
    val battery = BatteryWatch(scope, read = { if (CarKind.readsEvBattery(carKind?.kind)) evBattery.read() else null }, log = ::log)

    /** Holds back climate and seat effects on a low battery and warns a car that may idle (docs/07 "배터리·공회전 보호"). */
    private val batterySaver = BatterySaver(
        scope,
        open = stage.phase.map { it == StageController.Phase.OPEN },
        battery = battery.state,
        mayIdle = { CarKind.mayIdle(carKind?.kind, evBattery.hasBattery) },
        show = { notices.add(it, NoticeTone.CAUTION) },
        hide = notices::remove,
        log = ::log,
    )

    /** Asks to end with the song being sung when charging stops on an open stage. */
    val chargeFinish: ChargeFinish = ChargeFinish(
        scope,
        // The basic flow has no card to ask on, so it ends with "그만하기" only.
        open = combine(stage.phase, features.all) { phase, all -> phase == StageController.Phase.OPEN && all },
        battery = battery.state,
        announcing = songs.announcing,
        close = { stopStage("charging stopped and the last song is over", NoticeBoard.CHARGE_DONE, NoticeTone.INFO) },
        log = ::log,
        mcBusy = { mc.busy },
    )

    /** Whether it is night for each stage's director, which dims the light, and the screen's word on it (docs/07 "야간 밝기 배려"). */
    val night = NightDimming(
        scope,
        open = stage.phase.map { it == StageController.Phase.OPEN },
        night = NightModeReader.connect(context, scope, ::log)?.night ?: flowOf(null),
        show = { notices.add(it, NoticeTone.INFO) },
        hide = notices::remove,
        log = ::log,
    )

    /** How long this trip has driven, read as each stage opens for the MC's greeting (docs/12). */
    val trip = TripWatch(
        scope,
        active = stage.phase.map { it == StageController.Phase.OPENING || it == StageController.Phase.OPEN },
        read = TripReader(vehicleSession, ::log)::read,
        log = ::log,
    )

    /** The MC that greets a stage, says each score and calls a duel's turns with what the car knows (docs/12). */
    private val mc: StageMc = StageMc(
        scope,
        stage.phase,
        songs.result,
        songs.duel,
        chargeFinish.step,
        moment = {
            val battery = battery.state.value
            McLines.Moment(
                night = night.dim,
                charging = battery?.charging == true,
                batteryPercent = battery?.percent,
                // A duel to offer only where the screen has one, and not over one already under way
                // (the duel mode's stage opens as a duel).
                twoInFront = duelOffer.offered.value && !features.basic && !songs.duel.value.underway,
                hour = LocalTime.now().hour,
                drivenMinutes = trip.minutesNow(),
            )
        },
        soundPlaying = { effects.echoing() || voice.echoing() },
        on = { mcSetting.on.value },
        speak = voice::speak,
        log = ::log,
    )

    init {
        // Undo what the last stage left in the car before anyone opens a new one.
        scope.launch { leftovers.restoreAtStart() }
        // A duel's announcement speaks through the voice too, so the switch turns the voice itself off.
        scope.launch { mcSetting.on.collect(voice::setOn) }
        // The drive state is watched for exactly as long as a stage is opening or open, whoever
        // opened or closed it.
        scope.launch {
            stage.phase
                .map { it == StageController.Phase.OPENING || it == StageController.Phase.OPEN }
                .distinctUntilChanged()
                .collectLatest { active -> if (active) stopForSafety(safety.awaitUnsafe()) }
        }
        MicGate(
            phase = stage.phase,
            inCall = calls.inCall,
            // Emptied before the capture thread starts, so no late frame of the last stage refills it.
            startMic = {
                meter.reset()
                mic.start()
            },
            stopMic = mic::stop,
            onMicFailed = {
                scope.launch {
                    stopStage("microphone failed to start", "마이크를 열지 못해서 무대를 닫았어요. 잠시 후 다시 열어 주세요.", NoticeTone.ERROR)
                }
            },
            stopForCall = {
                if (stopStage("call started", NoticeBoard.CALL_STOP, NoticeTone.INFO)) resume.stoppedFor(ResumeOffers.Reason.CALL, NoticeBoard.CALL_STOP)
            },
        ).launchIn(scope)
        // The stage is the song (docs/13): once the music it played stops (the player's
        // pause, the music app's own, the end of an album) for MUSIC_STOP_GRACE_MS with none of the
        // stage's own sound playing, the stage ends
        // too (docs/13). Only after music played on this stage, so a stage opened before its song
        // starts, or a Lab replay with no music, stays open; the grace covers the gap between songs.
        scope.launch {
            stage.phase.map { it == StageController.Phase.OPEN }.distinctUntilChanged().collectLatest { open ->
                if (!open) return@collectLatest
                var played = false
                combine(music.playing, nowPlaying.track) { playing, track -> playing || track?.playing == true }
                    .distinctUntilChanged()
                    .collectLatest { playing ->
                        if (playing) {
                            played = true
                            return@collectLatest
                        }
                        if (!played) return@collectLatest
                        // A duel waits between turns while the music app sits on the last song: the
                        // stage stays for the next turn until "그만하기" (docs/13).
                        if (songs.duel.value.let { it != null && it.step != DuelState.Step.DONE }) return@collectLatest
                        // The stage's own sound (a cue, the MC) may pause a music app that does not
                        // duck for speech; the grace counts only while none plays.
                        var quiet = 0L
                        while (quiet < MUSIC_STOP_GRACE_MS) {
                            delay(MUSIC_STOP_POLL_MS)
                            quiet = if (effects.echoing() || voice.echoing() || mc.busy) 0L else quiet + MUSIC_STOP_POLL_MS
                        }
                        scope.launch { closeStage("the music stopped") }
                    }
            }
        }
        // Each turn of a duel sings its own song (docs/13): when the passenger's turn comes, the
        // music the driver's song led to is paused so the passenger picks one from the list, and
        // the announcement is heard without music.
        scope.launch {
            songs.duel.map { duel -> duel?.let { it.step to it.turn } }.distinctUntilChanged().collect { now ->
                val passengersTurn = now?.first == DuelState.Step.TURN && now.second == Side.PASSENGER
                // A pick ends with the turn, and with the duel when the stage closes meanwhile.
                if (!passengersTurn) withContext(Dispatchers.Main) { pick.stop() }
                if (!passengersTurn && now?.first != DuelState.Step.ANNOUNCING) return@collect
                if (stage.phase.value != StageController.Phase.OPEN) return@collect
                // A turn that ended as the music app moved on comes while its next song is still
                // starting: wait a moment for it to play, then pause it.
                val plays = withTimeoutOrNull(DUEL_PAUSE_WAIT_MS) {
                    combine(music.playing, nowPlaying.track) { playing, track -> playing || track?.playing == true }.first { it }
                } == true
                withContext(Dispatchers.Main) {
                    val stillThere = stage.phase.value == StageController.Phase.OPEN && songs.duel.value?.let { it.step to it.turn } == now
                    if (!stillThere) return@withContext
                    if (plays) {
                        log("duel: pausing the music for ${if (passengersTurn) "the passenger to pick a song" else "the announcement"}")
                        nowPlaying.pause()
                    }
                    // Music that had stopped by itself is as good as paused for the pick.
                    if (passengersTurn) pick.start(paused = plays) else pick.stop()
                }
            }
        }
        // A duel's stage ends with its result (docs/13): once the winner is out and the stage's own
        // sound (the fanfare, the MC's word on the winner) is over, it closes as "그만하기" does,
        // pausing the music, and the closed screen keeps the result until the user dismisses it.
        scope.launch {
            songs.duel.map { it?.step == DuelState.Step.DONE }.distinctUntilChanged().collectLatest { done ->
                if (!done || stage.phase.value != StageController.Phase.OPEN) return@collectLatest
                delay(DUEL_END_HOLD_MS)
                while (effects.echoing() || voice.echoing() || mc.busy) delay(MUSIC_STOP_POLL_MS)
                if (stage.phase.value == StageController.Phase.OPEN) scope.launch { closeStage("the duel is over") }
            }
        }
        // The list follows the app whose session the player follows, on the main thread its browser needs.
        scope.launch(Dispatchers.Main) {
            nowPlaying.track.map { it?.app }.distinctUntilChanged().collect(shelf::follow)
        }
        // And the player follows the session of the app the list is connected to.
        scope.launch(Dispatchers.Main) { shelf.session.collect(nowPlaying::shelfSession) }
        // What the session says names the songs, and its move to another song ends the one being sung.
        scope.launch(Dispatchers.Main) {
            combine(nowPlaying.track, stage.phase) { track, phase -> track to phase }.collect { (track, phase) ->
                val now = SystemClock.elapsedRealtime()
                titles.heard(track?.takeIf { it.playing }?.title, now)
                if (phase != StageController.Phase.OPEN) pick.stop()
                val ended = trackEnds.next(track, phase == StageController.Phase.OPEN, now)
                if (ended) log("song changed in ${track?.app}: \"${track?.title}\"")
                when (pick.next(ended, playing = track?.playing == true)) {
                    // The passenger's song plays: their turn starts with it, not before.
                    PassengerPick.Step.PICKED -> {
                        log("duel: the passenger's song starts")
                        songs.restartSong()
                    }
                    PassengerPick.Step.SONG_ENDED -> stage.endSong(StageController.EndCause.TRACK)
                    PassengerPick.Step.NONE -> if (ended) log("duel: \"${track?.title}\" is not playing; the passenger's turn starts when music plays")
                }
            }
        }
    }

    /**
     * CarStage came to the front: loads the song list afresh. The song another app plays goes on,
     * as the screen offers it ("이 곡으로 시작", docs/13); arriving pauses nothing. Main thread.
     */
    fun arrive() {
        shelf.arrive(nowPlaying.track.value?.app)
    }

    /**
     * Opens the stage for any caller (screen or Gleo) if the car is parked, no call is on and the
     * microphone may be used; tells the screen what happened.
     */
    suspend fun openStage(): OpenOutcome {
        if (calls.inCall.value) return refuse("in a call", "통화 중에는 무대를 열 수 없어요. 통화가 끝나면 다시 시도해 주세요.", NoticeTone.CAUTION)
        awaitVehicleBriefly()
        // Parked first, so a recovery for an open runs while parked and leaves nothing waiting.
        safety.checkMotion().let { drive -> drive.block?.let { block -> return refuse("${block.name} $drive", block.refusal, block.refusalTone) } }
        // A recovery can wait on a slow car (the first vehicle calls after a cold start took 6 s on
        // the emulator). Say so instead of leaving the button silent, and give up after a while.
        val restored = withTimeoutOrNull(OPEN_READY_WAIT_MS) { leftovers.restore() } ?: run {
            notices.show("지난 무대에서 바뀐 차량 설정을 되돌리는 중이에요. 끝나면 무대를 열게요.", NoticeTone.INFO)
            withTimeoutOrNull(RECOVERY_WAIT_MS) { leftovers.restore() }
        }
        // Again, since the recovery may have taken a while; a car that drove off meanwhile is also
        // why values may still wait for parking, so this refusal comes first.
        safety.checkMotion().let { drive -> drive.block?.let { block -> return refuse("${block.name} $drive", block.refusal, block.refusalTone) } }
        if (restored != true) {
            // The new stage would capture what is left as its originals.
            return refuse("the last stage is not restored yet", "지난 무대에서 바뀐 차량 설정을 아직 되돌리지 못했어요. 잠시 후 다시 시도해 주세요.", NoticeTone.CAUTION)
        }
        if (!hasMicPermission()) return refuse("microphone not allowed", NoticeBoard.MIC_NOT_ALLOWED, NoticeTone.CAUTION)
        val result = stage.open(newDirector = {
            songs.newDirector(
                onSongEnded = chargeFinish::songEnded,
                onSound = effects::play,
                onSoundStop = {
                    effects.stop()
                    voice.stop()
                },
                onSoundChange = effects::change,
                onSpeak = voice::speak,
                night = night::dim,
            )
        })
        songs.opened(result.alreadyOpen)
        // The MC speaks only on an open stage, so the engine is held only then.
        voice.prepare()
        resume.clear()
        if (playOnOpen) {
            playOnOpen = false
            log("basic flow: playing the music again")
            withContext(Dispatchers.Main) { nowPlaying.playPause(playing = false) }
        }
        // A build that leaves the cabin alone reaches no car on purpose; its drawing says it is a preview.
        val connection = if (vehicleCabin == null || CarCabin.NAME in result.driven) null else NoticeBoard.NOT_CONNECTED
        notices.opened(result.alreadyOpen, leftovers.takeNotice(), connection)
        return OpenOutcome.Opened(result)
    }

    /** A request the basic flow does not take; [notice] says what to do instead. */
    private fun refuseBasic(why: String, notice: String): OpenOutcome.Refused {
        warn("stage refused: $why")
        // What to do instead, so it stays open on the line (a notice of kind INFO folds away).
        notices.show(notice, NoticeTone.CAUTION)
        return OpenOutcome.Refused(notice)
    }

    private fun refuse(why: String, notice: String, tone: NoticeTone): OpenOutcome.Refused {
        warn("stage refused: $why")
        playOnOpen = false
        // A tap may have just got the user's consent to capture what plays; it was for this stage,
        // which did not open, so let the projection and its service go (the tap never began).
        if (stage.phase.value != StageController.Phase.OPEN) projection.release()
        notices.show(notice, tone)
        return OpenOutcome.Refused(notice)
    }

    /** Tells the screen that the user did not allow the microphone, which the stage needs. */
    fun micPermissionDenied() {
        warn("stage refused: microphone permission denied on the screen")
        playOnOpen = false
        notices.show(NoticeBoard.MIC_NOT_ALLOWED, NoticeTone.CAUTION)
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun connectVehicle(): Deferred<Result<Int>> = scope.async {
        vehicleSession.awaitReady().onFailure { warn("vehicle service not ready: ${it.describe()}") }
    }

    /**
     * Gleo can start the app cold, so an open waits for the vehicle service before reading the
     * gear: on the startup connection (or a new one, if that failed), but at most
     * [OPEN_READY_WAIT_MS], so a hung service ends in a quick refusal instead of a silent button.
     */
    private suspend fun awaitVehicleBriefly() {
        val last = vehicleReady
        val attempt = if (last.isCompleted && last.await().isFailure) connectVehicle().also { vehicleReady = it } else last
        if (withTimeoutOrNull(OPEN_READY_WAIT_MS) { attempt.await() } == null) {
            warn("vehicle service still not ready after $OPEN_READY_WAIT_MS ms; reading the gear anyway")
        }
    }

    /** True when parked and the stage may open, false when not parked or a driving restriction is on, null when the gear is unknown. */
    private suspend fun parkedNow(): Boolean? = when (safety.checkMotion().block) {
        null -> true
        DriveState.Block.GEAR_UNKNOWN -> null
        else -> false
    }

    /**
     * Closes the stage because the drive state no longer allows it and tells the user why. Runs to
     * the end although the watch that called it is cancelled by the stage closing.
     */
    private suspend fun stopForSafety(state: DriveState) {
        val block = state.block ?: return
        warn("drive state no longer allows the stage: ${block.name} $state")
        val stopped = stopStage("safety: ${block.name}", block.stopNotice, block.stopTone)
        // An unreadable gear is not a drive: nothing to wait for, and "parked again" would be untrue.
        if (stopped && block != DriveState.Block.GEAR_UNKNOWN) resume.stoppedFor(ResumeOffers.Reason.DRIVE, block.stopNotice)
        // The screen hides the stage while driving; the score and a duel go with it.
        songs.clear()
    }

    /**
     * Closes the stage for a reason the user did not choose, and tells them why with [notice] of
     * kind [tone] (and whether all was put back). True when a stage was open and is now closed.
     */
    private suspend fun stopStage(reason: String, notice: String, tone: NoticeTone): Boolean = withContext(NonCancellable) {
        val result = closeStage(reason)
        if (result is StageController.CloseResult.Closed) {
            if (result.stop.restoredAll) {
                notices.show(notice, tone)
            } else {
                notices.show("$notice ${restoreNotice(result.stop)}", maxOf(tone, restoreTone(result.stop)))
            }
        }
        result is StageController.CloseResult.Closed
    }

    /** Whether the MC has a line waiting or the stage's sound plays; see [StageMc.busy]. */
    fun mcBusy(): Boolean = mc.busy

    /** The screen's "no thanks" to singing on. */
    fun dismissResume() = resume.clear()

    /**
     * No CarStage screen is visible: the stage closes, and coming back soon offers to sing on
     * ([ResumeOffers.returned]). Noted before the close, which may take seconds on a slow car, so
     * a quick return still finds it; only an open stage counts, as one already closing was ended
     * by the user or a stop that offers on its own.
     */
    fun leftScreen() {
        if (stage.phase.value == StageController.Phase.OPEN) resume.leftWhileOpen()
        scope.launch { closeStage("app moved to the background") }
    }

    /** A CarStage screen is visible again; see [leftScreen]. */
    fun cameBack() = resume.returned()

    /**
     * The basic flow's "이어서 부르기": the music the stage paused plays again once the next stage
     * really opens ([openStage]), so a refused stage leaves it paused.
     */
    fun resumeMusic() {
        playOnOpen = true
    }

    @Volatile
    private var playOnOpen = false

    /**
     * Closes the stage for any caller and tells the screen when something could not be put back.
     * What the car did not take back stays in the journal, and [leftovers] retries it once the car
     * is parked (soon after, when it already is).
     */
    suspend fun closeStage(reason: String): StageController.CloseResult = withContext(NonCancellable) {
        log("closing stage: $reason")
        // The song the stage was, taken before the close, which may take seconds on a slow car:
        // a song the user picks in the music app meanwhile is theirs and plays on.
        val sung = if (music.playing.value || nowPlaying.track.value?.playing == true) nowPlaying.track.value else null
        val pauseSong = sung != null || music.playing.value
        effects.stop()
        voice.stop()
        val result = stage.close()
        // A cue or a line the audio thread started while the stage was closing is stopped too.
        effects.stop()
        voice.release()
        // It spoke of this stage; the next one tries the car again.
        notices.remove(NoticeBoard.CONTROL_DENIED)
        if (result is StageController.CloseResult.Closed && !result.stop.restoredAll) {
            Log.w(TAG, "restore failures: ${result.stop.failures}, deferred until parked: ${result.stop.deferred}")
            notices.show(restoreNotice(result.stop), restoreTone(result.stop))
            leftovers.restoreWhenParked()
        }
        // The stage is the song (docs/13): whatever closes it (the user, a drive, a call, leaving
        // the app) pauses the music too. After the restore's notice, which matters more.
        val now = nowPlaying.track.value
        val sameSong = sung == null || now == null || (now.app == sung.app && (now.mediaId ?: now.title) == (sung.mediaId ?: sung.title))
        if (result is StageController.CloseResult.Closed && pauseSong && sameSong) {
            log("pausing the music with the stage")
            try {
                withContext(Dispatchers.Main) { nowPlaying.pause() }
            } catch (e: RuntimeException) {
                Log.e(TAG, "basic flow: could not pause the music", e)
            }
        }
        result
    }

    /** The parking promise only when nothing but restores the car refused for driving is left. */
    private fun restoreNotice(stop: StageEngine.StopResult): String =
        if (stop.onlyDeferred) NoticeBoard.RESTORE_AFTER_PARKING else NoticeBoard.RESTORE_FAILED

    /** A promise to put back once parked is something to watch; values not put back went wrong. */
    private fun restoreTone(stop: StageEngine.StopResult): NoticeTone = if (stop.onlyDeferred) NoticeTone.CAUTION else NoticeTone.ERROR

    /** Ends the song from the screen's button, when the gap after it was missed; see [StageController.endSong]. */
    fun endSong() {
        if (!stage.endSong()) warn("song end asked while no stage is open")
    }

    /** Opens or closes the stage from a screen tap; failures become a notice instead of a crash. */
    fun request(open: Boolean) {
        scope.launch {
            try {
                if (open) {
                    // The duel mode's stage starts as a duel, the driver first (docs/13).
                    if (openStage() is OpenOutcome.Opened && !features.basic) songs.startDuel(now = true)
                } else {
                    closeStage("closed from the screen")
                }
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                Log.e(TAG, "stage ${if (open) "open" else "close"} failed", e)
                notices.show(if (open) "무대를 열지 못했어요. 잠시 후 다시 시도해 주세요." else "무대를 닫지 못했어요. 다시 눌러 주세요.", NoticeTone.ERROR)
            }
        }
    }

    /**
     * Runs a Gleo voice action in the app scope and always sends Gleo a reply, within
     * [GLEO_REPLY_TIMEOUT_MS]; a slow open keeps running and the screen shows the result.
     */
    fun handleGleo(action: String, intent: Intent) {
        val parameters = GleoActions.parameters(intent)
        scope.launch {
            var reply = GleoRouter.Reply(GleoActions.Status.ERROR, "지금은 처리할 수 없어요.")
            try {
                val work = scope.async { gleo.handle(action, parameters) }
                reply = withTimeoutOrNull(GLEO_REPLY_TIMEOUT_MS) { work.await() }
                    ?: GleoRouter.Reply(GleoActions.Status.FAIL, "차 연결이 늦어지고 있어요. 잠시 후 화면을 확인해 주세요.")
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                Log.e(TAG, "gleo $action failed", e)
            } finally {
                // The MC says it with what the car knows, so Gleo only beeps; else Gleo reads it out and the MC lets it.
                val mode = reply.mode(mcCanSpeak = mcSetting.on.value && !voice.failed)
                log("gleo $action -> ${reply.status} (${mode.wire})${parameters.exceptionOrNull()?.let { " ($it)" } ?: ""}")
                GleoActions.reply(context, action, reply.status, reply.message, mode)
                if (mode == GleoActions.Mode.VOICE) mc.gleoSpoke(reply.says) else mc.gleoBeeped()
            }
        }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
    }

    private fun warn(line: String) {
        Log.w(TAG, line)
    }

    companion object {
        /** Logcat tag for the product (the Lab uses CarStageLab). */
        const val TAG = "CarStage"

        /** Gleo cancels an action after 7 s (Pleos docs: action_callback), so the reply goes out a second before. */
        const val GLEO_REPLY_TIMEOUT_MS = 6_000L

        /**
         * How long an open waits for the vehicle service, and then for a recovery, before it says
         * so. An open with the service ready is decided well within [GLEO_REPLY_TIMEOUT_MS]; one
         * that waits for both does not, and Gleo then hears that the car is slow while the open goes on.
         */
        const val OPEN_READY_WAIT_MS = 3_000L

        /** How long the basic flow's music may stay stopped before its stage ends: longer than a gap between songs. */
        const val MUSIC_STOP_GRACE_MS = 4_000L
        private const val MUSIC_STOP_POLL_MS = 250L

        /** After a duel's result, before its stage closes: time to take in the winner. */
        private const val DUEL_END_HOLD_MS = 2_000L

        /** How long a duel's turn waits for the next song to start, to pause it for the next singer. */
        private const val DUEL_PAUSE_WAIT_MS = 3_000L
        private const val RECOVERY_WAIT_MS = 20_000L
        private val CLOSE_AFTER_FAILURE = CoroutineName("close after failure")
    }
}
