package com.carstage.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import com.carstage.app.gleo.GleoActions
import com.carstage.app.home.HomeScreen
import com.carstage.app.home.HomeViewModel
import com.carstage.app.ui.CarStageTheme

/** What the activity shows: the first screen, or the developer Lab of debug builds ([DevLab]). */
enum class Screen { HOME, LAB }

class MainActivity : ComponentActivity() {
    private val graph get() = (application as CarStageApp).graph
    private val homeViewModel: HomeViewModel by viewModels()
    private val lab = DevLab(this)
    private var screen by mutableStateOf(Screen.HOME)

    /** Set when the activity starts, so the resume after it counts as CarStage coming to the front. */
    private var arriving = false

    /** Whether the intent that brought the activity up was a Gleo or Lab command. */
    private var command = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A build without the Lab has nothing to show for it.
        savedInstanceState?.getString(KEY_SCREEN)?.let { screen = Screen.valueOf(it).takeIf { DevLab.AVAILABLE } ?: Screen.HOME }
        setContent {
            CarStageTheme {
                when (screen) {
                    Screen.HOME -> HomeScreen(homeViewModel, onOpenLab = { screen = Screen.LAB })
                    Screen.LAB -> {
                        BackHandler { screen = Screen.HOME }
                        lab.Content()
                    }
                }
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SCREEN, screen.name)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        // Pleos keeps the app in a pane of its home screen, where tapping its icon only hands it a
        // new intent: that is coming to the front too. A resumed activity gets no resume after it.
        val resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (!command) {
            if (resumed) arrive() else arriving = true
        }
        // No resume follows to clear it.
        if (resumed) command = false
    }

    override fun onStart() {
        super.onStart()
        // A configuration the manifest does not take (a language, say) recreates the activity where it was.
        if (homeViewModel.recreating) homeViewModel.recreating = false else arriving = true
    }

    override fun onStop() {
        super.onStop()
        homeViewModel.recreating = isChangingConfigurations
    }

    override fun onResume() {
        super.onResume()
        // The user may come back from the settings with notification access changed.
        graph.nowPlaying.refresh()
        // Only after a start or a new intent: a resume from a window over the screen (the
        // microphone's permission) is not an arrival. The intent of a start has been handled by now.
        if (arriving) arrive()
        command = false
        lab.onResume()
    }

    private fun arrive() {
        arriving = false
        homeViewModel.arrived()
        command = false
    }

    /** Dispatches Gleo voice actions (see [GleoActions]) to the stage, and the rest to [DevLab]. */
    private fun handleIntent(intent: Intent) {
        val action = intent.action
        command = (action != null && GleoActions.isGleoAction(intent)) || lab.isCommand(intent)
        if (action != null && GleoActions.isGleoAction(intent)) {
            screen = Screen.HOME
            graph.handleGleo(action, intent)
            return
        }
        lab.handle(intent)?.let { screen = it }
    }

    companion object {
        const val EXTRA_PROBE = "probe"
        const val EXTRA_REPLAY = "replay"
        private const val KEY_SCREEN = "screen"
    }
}
