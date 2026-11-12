package com.carstage.app

import android.Manifest
import android.content.Intent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import com.carstage.app.audio.AudioSources
import com.carstage.app.lab.LabScreen
import com.carstage.app.lab.LabViewModel
import com.carstage.app.lab.MicSourceProbe
import com.carstage.app.stage.StageController
import kotlinx.coroutines.launch

/**
 * The developer Lab (lab/), which only debug builds carry: its screen, the microphone permission
 * it asks for, and the diagnostics scripts start. Release builds get one without any of it
 * (src/release), so the Lab's code is not in the submitted APK. Made with the activity, before it
 * is created, so it can register for the permission result.
 */
class DevLab(private val activity: ComponentActivity) {
    private val viewModel: LabViewModel by activity.viewModels()

    private val graph get() = (activity.application as CarStageApp).graph

    private val micPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshMicPermission()
    }

    /** The Lab in Material's default type sizes: its dense diagnostics do not fit the first screen's Pleos scale. */
    @Composable
    fun Content() {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = Typography()) {
            LabScreen(viewModel, onRequestMicPermission = { micPermission.launch(Manifest.permission.RECORD_AUDIO) })
        }
    }

    fun onResume() {
        viewModel.refreshMicPermission()
    }

    /** Whether [intent] carries a Lab command, so its arrival is not the user coming to the screen. */
    fun isCommand(intent: Intent): Boolean =
        listOf(EXTRA_EFFECTS, EXTRA_NIGHT, EXTRA_DEMO, EXTRA_STAGE, EXTRA_MC, EXTRA_FEATURES, EXTRA_TRIP, EXTRA_ECHO, EXTRA_ECHO_HOLD_MS, MainActivity.EXTRA_REPLAY, MainActivity.EXTRA_PROBE).any(intent::hasExtra)

    /**
     * Runs a diagnostic for scripts: `adb shell am start -n com.carstage.app/.MainActivity --es probe all`,
     * or plays a recording through the stage on the home screen: `--es replay sing-test.wav`. Returns
     * the screen to show, or null when [intent] asks nothing of the Lab. Tests (scripts/regress.sh,
     * or by hand) may also turn the sound effects off or on (`--es effects off|on`, until the app
     * ends), make it night or day whatever the car says or follow the car again (`--es night
     * on|off|car`, until the app ends; the emulator is always day), hide the first screen's
     * development displays for a demo recording or show them again (`--es demo on|off`, until the
     * app ends), turn the MC on or off as its switch does (`--es mc on|off`, kept like the switch's
     * setting), run every feature or the basic flow as the corner's switch does (`--es features
     * all|basic`, kept like the switch's setting), set the minutes this trip has driven whatever the car says or follow the car again
     * (`--es trip 95|car`, until the app ends; the emulator reads no trip), open the stage as the
     * screen's button does (`--es stage open`), close it (`--es stage close`) and open the microphone from another source
     * from the next stage on (`--es micsource VOICE_RECOGNITION`, a name from [AudioSources], until
     * the app ends), and run the echo canceller or only measure the captured music beside it from
     * the next stage on (`--es echo on|off`, until the app ends, to compare the two); these keep
     * the screen and answer on the Lab's log. `--es probe echo` takes `--ei echo_minutes` (default 1).
     */
    fun handle(intent: Intent): Screen? {
        intent.getStringExtra(EXTRA_EFFECTS)?.let(::setEffects)
        intent.getStringExtra(EXTRA_NIGHT)?.let(::setNight)
        intent.getStringExtra(EXTRA_DEMO)?.let(::setDemo)
        intent.getStringExtra(EXTRA_MC)?.let(::setMc)
        intent.getStringExtra(EXTRA_FEATURES)?.let(::setFeatures)
        intent.getStringExtra(EXTRA_TRIP)?.let(::setTrip)
        intent.getStringExtra(EXTRA_STAGE)?.let(::stage)
        intent.getStringExtra(EXTRA_MIC_SOURCE)?.let(::setMicSource)
        intent.getStringExtra(EXTRA_ECHO)?.let(::setEcho)
        if (intent.hasExtra(EXTRA_ECHO_HOLD_MS)) setEchoHold(intent.getIntExtra(EXTRA_ECHO_HOLD_MS, 0))
        intent.getStringExtra(MainActivity.EXTRA_REPLAY)?.let { name ->
            viewModel.replay(name)
            return Screen.HOME
        }
        val probe = intent.getStringExtra(MainActivity.EXTRA_PROBE) ?: return null
        when (probe) {
            "all" -> viewModel.runAll()
            "audio" -> viewModel.runAudioProbe()
            "vehicle" -> viewModel.runVehicleProbe()
            "sample" -> viewModel.saveMicSample(intent.getIntExtra(EXTRA_SECONDS, LabViewModel.SAMPLE_SECONDS))
            "smoke" -> viewModel.runSmoke()
            "platform" -> viewModel.runPlatformProbe()
            "stage" -> viewModel.runStageProbe()
            "drive" -> viewModel.runDriveProbe()
            "sounds" -> viewModel.runSoundsProbe()
            "voice" -> viewModel.runVoiceProbe()
            "nowplaying" -> viewModel.runNowPlayingProbe()
            "echo" -> viewModel.runEchoProbe(intent.getIntExtra(EXTRA_ECHO_MINUTES, 1))
            "micsources" -> viewModel.runMicSourceProbe(
                intent.getIntExtra(EXTRA_MIC_SECONDS, MicSourceProbe.DEFAULT_SECONDS),
                intent.getIntExtra(EXTRA_MIC_ROUNDS, MicSourceProbe.DEFAULT_ROUNDS),
            )
            else -> viewModel.logUnknownProbe(probe)
        }
        return Screen.LAB
    }

    private fun setEffects(value: String) {
        val muted = when (value) {
            "off" -> true
            "on" -> false
            else -> {
                Log.w(TAG, "test: unknown effects \"$value\" (expected off|on)")
                return
            }
        }
        graph.effects.muted = muted
        graph.voice.muted = muted
        Log.i(TAG, "test: effects ${if (muted) "muted" else "on"}")
    }

    private fun setNight(value: String) {
        val night = when (value) {
            "on" -> true
            "off" -> false
            "car" -> null
            else -> {
                Log.w(TAG, "test: unknown night \"$value\" (expected on|off|car)")
                return
            }
        }
        graph.night.force(night)
        Log.i(TAG, "test: night ${night?.let { if (it) "on" else "off" } ?: "follows the car"}")
    }

    private fun setDemo(value: String) {
        val demo = when (value) {
            "on" -> true
            "off" -> false
            else -> {
                Log.w(TAG, "test: unknown demo \"$value\" (expected on|off)")
                return
            }
        }
        graph.devDisplays.value = !demo
        Log.i(TAG, "test: demo ${if (demo) "on: development displays hidden" else "off: development displays shown"}")
    }

    private fun setFeatures(value: String) {
        val all = when (value) {
            "all" -> true
            "basic" -> false
            else -> {
                Log.w(TAG, "test: unknown features \"$value\" (expected all|basic)")
                return
            }
        }
        graph.features.set(all)
        Log.i(TAG, "test: ${if (all) "every feature" else "basic flow"}")
    }

    private fun setMc(value: String) {
        val on = when (value) {
            "on" -> true
            "off" -> false
            else -> {
                Log.w(TAG, "test: unknown mc \"$value\" (expected on|off)")
                return
            }
        }
        graph.mcSetting.set(on)
        Log.i(TAG, "test: MC ${if (on) "on" else "off"}")
    }

    private fun setMicSource(value: String) {
        val source = AudioSources.all.firstOrNull { it.name.equals(value, ignoreCase = true) }
        if (source == null) {
            Log.w(TAG, "test: unknown micsource \"$value\" (expected ${AudioSources.all.joinToString("|") { it.name }})")
            return
        }
        graph.mic.source = source.id
        Log.i(TAG, "test: microphone source ${source.name} from the next stage")
    }

    private fun setEcho(value: String) {
        val cancels = when (value) {
            "on" -> true
            "off" -> false
            else -> {
                Log.w(TAG, "test: unknown echo \"$value\" (expected on|off)")
                return
            }
        }
        graph.tap.cancels = cancels
        Log.i(TAG, "test: echo canceller ${if (cancels) "on" else "off (the music is still captured and measured)"} from the next stage")
    }

    private fun setEchoHold(ms: Int) {
        val clamped = ms.coerceIn(0, MAX_ECHO_HOLD_MS)
        graph.tap.extraHoldMs = clamped
        Log.i(TAG, "test: echo reference held back $clamped ms more from the next stage")
    }

    private fun setTrip(value: String) {
        val minutes = if (value == "car") {
            null
        } else {
            value.toIntOrNull()?.takeIf { it >= 0 } ?: run {
                Log.w(TAG, "test: unknown trip \"$value\" (expected minutes or car)")
                return
            }
        }
        graph.trip.force(minutes)
        Log.i(TAG, "test: trip ${minutes?.let { "of $it min" } ?: "follows the car"}")
    }

    private fun stage(value: String) {
        if (value == "open") {
            graph.request(open = true)
            Log.i(TAG, "test: stage opening as the screen's button does")
            return
        }
        if (value != "close") {
            Log.w(TAG, "test: unknown stage \"$value\" (expected open|close)")
            return
        }
        graph.scope.launch {
            val result = graph.closeStage("closed by a test")
            Log.i(TAG, "test: stage closed${if (result is StageController.CloseResult.NotOpen) " (it was not open)" else ""}")
        }
    }

    companion object {
        /** Whether the first screen offers the Lab, and shows its development displays at first (AppGraph.devDisplays). */
        const val AVAILABLE = true

        const val EXTRA_EFFECTS = "effects"
        const val EXTRA_STAGE = "stage"
        const val EXTRA_NIGHT = "night"
        const val EXTRA_DEMO = "demo"
        const val EXTRA_MC = "mc"
        const val EXTRA_FEATURES = "features"
        const val EXTRA_TRIP = "trip"
        const val EXTRA_MIC_SOURCE = "micsource"
        const val EXTRA_ECHO = "echo"
        const val EXTRA_ECHO_MINUTES = "echo_minutes"

        /** `--es probe micsources`: seconds per recording and rounds over the sources ([MicSourceProbe]). */
        const val EXTRA_MIC_SECONDS = "mic_seconds"
        const val EXTRA_MIC_ROUNDS = "mic_rounds"

        /** Extra delay for the echo canceller's reference (`--ei echo_hold_ms 400`), for the emulator's virtual cabin. */
        const val EXTRA_ECHO_HOLD_MS = "echo_hold_ms"

        /** The reference buffer keeps 2 s; leave room for the frame and the default hold. */
        private const val MAX_ECHO_HOLD_MS = 1_500

        /** Length of the `sample` probe's recording (`--ei seconds 30`), for measuring the echo canceller. */
        const val EXTRA_SECONDS = "seconds"
        private const val TAG = "CarStageLab"
    }
}
