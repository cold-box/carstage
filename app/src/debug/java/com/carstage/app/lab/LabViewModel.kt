package com.carstage.app.lab

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carstage.app.CarStageApp
import com.carstage.app.audio.AudioSourceOption
import com.carstage.app.audio.AudioSources
import com.carstage.app.audio.MicCapture
import com.carstage.app.audio.SignalAnalysis
import com.carstage.app.stage.DuelLines
import com.carstage.app.stage.Side
import com.carstage.app.stage.SoundCue
import com.carstage.app.stage.StageController
import com.carstage.app.vehicle.describe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import kotlinx.coroutines.launch

data class LiveLevels(
    val levelDb: Float,
    val pitchHz: Float?,
    val note: String?,
    val claps: Int,
)

data class LabState(
    val micGranted: Boolean = false,
    val runningProbe: String? = null,
    val liveSource: AudioSourceOption = AudioSources.all.first(),
    val live: LiveLevels? = null,
    val logs: List<String> = emptyList(),
)

class LabViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = getApplication<CarStageApp>().graph

    /** Shared with the stage; the app owns it, so the Lab never releases it. */
    private val vehicleSession = graph.vehicleSession
    private val _state = MutableStateFlow(LabState())
    val state: StateFlow<LabState> = _state.asStateFlow()

    private var probeJob: Job? = null
    private var liveCapture: MicCapture? = null
    private var liveStats: SingingStats? = null

    init {
        refreshMicPermission()
    }

    fun refreshMicPermission() {
        val granted = ContextCompat.checkSelfPermission(
            getApplication(), Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        _state.update { it.copy(micGranted = granted) }
    }

    fun runAll() = launchProbe("all") {
        audioProbe().run()
        vehicleProbe().run()
    }

    fun runAudioProbe() = launchProbe("audio") { audioProbe().run() }

    fun runVehicleProbe() = launchProbe("vehicle") { vehicleProbe().run() }

    fun saveMicSample(seconds: Int = SAMPLE_SECONDS) = launchProbe("sample") {
        audioProbe().saveSample(seconds.coerceIn(1, MAX_SAMPLE_SECONDS) * 1_000L)
    }

    fun runPlatformProbe() = launchProbe("platform") {
        PlatformProbe(getApplication(), vehicleSession) { log("[platform] $it") }.run()
    }

    /** Reads the drive state the safety rules use, so Virtual Console changes can be checked. */
    fun runDriveProbe() = launchProbe("drive") {
        val state = graph.safety.check()
        log("[drive] $state stageAllowed=${state.stageAllowed} climateAllowed=${state.climateAllowed}")
    }

    /**
     * Starts the MC's engine as a stage does, says its three lines once, a few seconds apart, and
     * lets the engine go; it plays out loud. Logs what the engine reported. While the MC is turned
     * off on the first screen ([com.carstage.app.McSetting]), the engine does not start and the
     * lines are only logged.
     */
    fun runVoiceProbe() = launchProbe("voice") {
        graph.voice.prepare()
        try {
            delay(VOICE_START_MS)
            log("[voice] ${graph.voice.state}")
            listOf(DuelLines.OPENING, DuelLines.crown(Side.PASSENGER, 53), DuelLines.crown(null, 40)).forEach { line ->
                log("[voice] $line")
                graph.voice.speak(line)
                delay(VOICE_GAP_MS)
            }
        } finally {
            graph.voice.release()
        }
    }

    /** Plays every sound cue once, in order, so they can be heard and their logs read. */
    fun runSoundsProbe() = launchProbe("sounds") {
        SoundCue.entries.forEach { cue ->
            log("[sounds] $cue")
            graph.effects.play(cue)
            delay(SOUND_GAP_MS)
        }
    }

    /** Reads which app is playing what song through notification access, then watches for changes. */
    fun runNowPlayingProbe() = launchProbe("nowplaying") {
        NowPlayingProbe(getApplication()) { log("[nowplaying] $it") }.run()
    }

    /** Whether the stage can hear what the other apps play, to take it out of the microphone; see [EchoProbe]. */
    fun runEchoProbe(minutes: Int) = launchProbe("echo") {
        EchoProbe(getApplication(), graph.effects, musicPlaying = { graph.music.playing.value }, minutes = minutes) { log("[echo] $it") }.run()
    }

    /** Compares the microphone's sources while another app plays music: does the car take the music out? */
    fun runMicSourceProbe(seconds: Int, rounds: Int) = launchProbe("micsources") {
        MicSourceProbe(
            musicPlaying = { graph.music.playing.value },
            seconds = seconds.coerceIn(2, 60),
            rounds = rounds.coerceIn(1, 5),
        ) { log("[micsources] $it") }.run()
    }

    fun runStageProbe() = launchProbe("stage") {
        StageProbe(vehicleSession) { log("[stage] $it") }.run()
    }

    fun runSmoke() = launchProbe("smoke") {
        val mic = audioProbe().smoke()
        val vehicle = vehicleProbe().smoke()
        log("smoke: ${if (mic && vehicle) "PASS" else "FAIL"} mic=${mic.passFail()} vehicle=${vehicle.passFail()}")
    }

    /**
     * Plays cache/lab/[name] (16-bit WAV, for example the "5초 녹음 저장" sample) through the
     * product's stage in place of the microphone; the caller shows the home screen.
     */
    fun replay(name: String) = launchProbe("replay") {
        if ('/' in name || name.startsWith(".")) {
            log("replay: give a file name in cache/lab, not a path: $name")
            return@launchProbe
        }
        graph.replay(File(getApplication<Application>().cacheDir, "lab/$name")) { log("[replay] $it") }
    }

    fun logUnknownProbe(name: String) = log("unknown probe \"$name\" (expected all|audio|vehicle|sample|smoke|platform|stage|drive|sounds|voice|nowplaying|echo)")

    fun selectLiveSource(source: AudioSourceOption) {
        val wasLive = liveCapture != null
        stopLive()
        _state.update { it.copy(liveSource = source) }
        if (wasLive) startLive()
    }

    fun toggleLive() = if (liveCapture == null) startLive() else stopLive()

    private fun startLive() {
        if (probeJob?.isActive == true) {
            log("busy: ${_state.value.runningProbe} probe is running")
            return
        }
        val source = _state.value.liveSource
        val capture = MicCapture(source.id)
        val stats = SingingStats(capture.sampleRate)
        try {
            capture.start(onFailure = { why, _ -> log("[live] capture stopped by itself: $why") }) { frame ->
                stats.add(frame)
                val hz = stats.lastPitchHz
                _state.update {
                    it.copy(live = LiveLevels(stats.lastLevelDb, hz, hz?.let(SignalAnalysis::noteName), stats.claps))
                }
            }
            liveCapture = capture
            liveStats = stats
            log("[live] started ${source.name}")
        } catch (e: Exception) {
            log("[live] ${source.name} failed: ${e.describe()}")
        }
    }

    private fun stopLive() {
        val capture = liveCapture ?: return
        capture.stop()
        liveCapture = null
        _state.update { it.copy(live = null) }
        log("[live] stopped: ${liveStats?.summary()}")
        liveStats = null
    }

    private fun audioProbe() = AudioProbe(getApplication()) { log("[audio] $it") }

    private fun vehicleProbe() = VehicleProbe(vehicleSession) { log("[vehicle] $it") }

    private fun launchProbe(name: String, block: suspend () -> Unit) {
        if (probeJob?.isActive == true) {
            log("busy: ${_state.value.runningProbe} probe is running")
            return
        }
        stopLive()
        probeJob = viewModelScope.launch {
            _state.update { it.copy(runningProbe = name) }
            log("=== $name start")
            try {
                // Probes write to the same car; close an open stage first so neither undoes the other.
                if (graph.closeStage("Lab probe $name") is StageController.CloseResult.Closed) log("closed the open stage first")
                // Probes read the car's values; values a crashed stage left must be put back first.
                if (!graph.leftovers.restore()) log("the last stage's values are not restored yet; readings may show them")
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("$name failed: ${e.describe()}")
            } finally {
                log("=== $name end")
                _state.update { it.copy(runningProbe = null) }
            }
        }
    }

    private fun Boolean.passFail() = if (this) "PASS" else "FAIL"

    private fun log(line: String) {
        Log.i(TAG, line)
        _state.update { it.copy(logs = (it.logs + line).takeLast(MAX_LOG_LINES)) }
    }

    override fun onCleared() {
        stopLive()
    }

    companion object {
        const val TAG = "CarStageLab"
        private const val MAX_LOG_LINES = 400

        /** The `sample` probe's default length, and the longest it records (11.5 MB of floats). */
        const val SAMPLE_SECONDS = 5
        private const val MAX_SAMPLE_SECONDS = 60

        /** Longer than the longest cue (applause, about 4.3 s with its margin). */
        private const val SOUND_GAP_MS = 5_000L

        /** Long enough for a line of a few words to end before the next. */
        private const val VOICE_GAP_MS = 4_000L

        /** Long enough for the speech service to start (about 0.5 s on the emulator, docs/05). */
        private const val VOICE_START_MS = 2_000L
    }
}
