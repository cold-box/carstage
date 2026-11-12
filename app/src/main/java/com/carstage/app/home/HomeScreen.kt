package com.carstage.app.home

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carstage.app.ChargeFinish
import com.carstage.app.stage.DuelState
import com.carstage.app.stage.StageController.Phase
import com.carstage.app.ui.PrimaryButton
import com.carstage.app.ui.QuietButton
import com.carstage.app.ui.SecondaryButton
import com.carstage.app.ui.StageColors
import com.carstage.app.ui.StageSizes

/**
 * First screen of the app: the cabin simulator on the left and the stage controls on the right,
 * or both in one column when the window is too narrow for a card on each side, as when Pleos
 * shows the app in one pane beside another app (docs/10 4장 "세로 한 칸").
 */
@Composable
fun HomeScreen(viewModel: HomeViewModel, onOpenLab: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // The glow reaches the screen's edges, behind the padded content.
        StageGlow(viewModel, Modifier.fillMaxSize())
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(32.dp)) {
            if (maxWidth >= TWO_COLUMNS) {
                Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP)) {
                    CabinPanel(viewModel, Modifier.weight(1f).fillMaxHeight())
                    StageControls(viewModel, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                SingleColumn(viewModel, Modifier.fillMaxSize())
            }
            val mcOn by viewModel.mcOn.collectAsStateWithLifecycle()
            // The MC's voice, out of the way in the corner; the mode is chosen on the closed screen (docs/13).
            McToggle(mcOn, viewModel::setMc, Modifier.align(Alignment.BottomEnd))
            val devDisplays by viewModel.devDisplays.collectAsStateWithLifecycle()
            if (devDisplays) {
                TextButton(onClick = onOpenLab, modifier = Modifier.align(Alignment.TopEnd)) {
                    Text("개발자 Lab", style = MaterialTheme.typography.labelMedium, color = StageColors.TextSecondary)
                }
            }
        }
    }
}

/** The right half, the stage's controls ([ControlItems]), centered while they fit. */
@Composable
private fun StageControls(viewModel: HomeViewModel, modifier: Modifier) {
    // Notices that stack up with two cards can outgrow the height, so it scrolls then.
    BoxWithConstraints(modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(StageSizes.Gap, Alignment.CenterVertically),
        ) {
            ControlItems(viewModel, narrow = false)
            // Room for the corner's settings, which float over the column's end when it scrolls.
            Spacer(Modifier.height(StageSizes.ButtonHeight))
        }
    }
}

/**
 * A narrow window's one column (docs/10 4장 "세로 한 칸"): the cabin drawing on top, without its
 * status icons, then the stage's controls, the player among them (docs/13). The drawing, with its preview line
 * over it, gets what they leave of the height and is left out when that is less than [MIN_DRAWING]
 * and the line, so the buttons and the status come first ([ColumnPlan]); the column scrolls only
 * when they alone outgrow it.
 */
@Composable
private fun SingleColumn(viewModel: HomeViewModel, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val viewport = constraints.maxHeight
        Layout(
            content = {
                // A box, so the line is one part even while there is none.
                Box { CabinPreviewLine(viewModel) }
                CabinDrawing(viewModel, Modifier, status = false)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(StageSizes.Gap)) {
                    ControlItems(viewModel, narrow = true)
                    // Room for the corner's switch, which floats over the column's end.
                    Spacer(Modifier.height(StageSizes.ButtonHeight))
                }
            },
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        ) { (line, drawing, controls), column ->
            // The scroll leaves the height open; the window's height above is the viewport.
            val width = column.maxWidth
            val gap = StageSizes.Gap.roundToPx()
            val linePart = line.measure(Constraints(maxWidth = width))
            val lineRoom = if (linePart.height > 0) linePart.height + gap else 0
            val controlsPart = controls.measure(Constraints(maxWidth = width))
            // The plan's drawing holds the line too, so the car itself never gets less than MIN_DRAWING.
            val plan = ColumnPlan.of(viewport, 0, controlsPart.height, gap, MIN_DRAWING.roundToPx() + lineRoom)
            val drawingPart = if (plan.drawing > 0) drawing.measure(Constraints.fixed(width, plan.drawing - lineRoom)) else null
            // The line speaks of the drawing, so it goes with it.
            val shownLine = linePart.takeIf { drawingPart != null && it.height > 0 }
            layout(width, plan.height) {
                var y = plan.top
                listOfNotNull(shownLine, drawingPart, controlsPart).forEach { part ->
                    part.placeRelative((width - part.width) / 2, y)
                    y += part.height + gap
                }
            }
        }
    }
}

/** The stage's controls ([BasicControlItems], docs/13), with the duel and the charge's finish when every feature is on. */
@Composable
private fun ControlItems(viewModel: HomeViewModel, narrow: Boolean) {
    val all by viewModel.allFeatures.collectAsStateWithLifecycle()
    // Two columns put the player's song list on the left, in the drawing's place ([CabinPanel]).
    BasicControlItems(viewModel, listHere = narrow, extras = all)
}


/**
 * How a tap starts the stage: with the microphone, asked for first when not allowed yet, and the
 * capture of what plays when the build asks for it ([HomeViewModel.captureConsent]). [micAllowed]
 * follows the permission while the screen shows.
 */
internal class StageStart(val start: () -> Unit, val micAllowed: Boolean)

@Composable
internal fun rememberStageStart(viewModel: HomeViewModel): StageStart {
    // The stage listens to the microphone, so it asks for it when the user starts singing.
    val context = LocalContext.current
    var micAllowed by remember { mutableStateOf(micGranted(context)) }
    // The user may allow it in the settings while the app is away.
    LifecycleResumeEffect(Unit) {
        micAllowed = micGranted(context)
        onPauseOrDispose {}
    }
    // To take the car's music out of the microphone, the stage hears what the other apps play,
    // which Android lets it do only with the user's consent for the stage (docs/09 "에코 제거").
    val askCapture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { answer ->
        viewModel.captureAnswered(answer.resultCode, answer.data)
        viewModel.openStage()
    }
    val open = {
        val consent = viewModel.captureConsent()
        if (consent == null) {
            viewModel.openStage()
        } else {
            try {
                askCapture.launch(consent)
            } catch (e: ActivityNotFoundException) {
                // The car has no consent dialog: the stage listens without taking the music out.
                viewModel.captureUnavailable(e.message ?: "no activity")
                viewModel.openStage()
            }
        }
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micAllowed = granted
        if (granted) open() else viewModel.micPermissionDenied()
    }
    // Once it is allowed, the notice asking for it no longer applies.
    LaunchedEffect(micAllowed) { if (micAllowed) viewModel.micAllowed() }
    return StageStart(
        start = {
            // Also allowed in another pane's settings, where this one is never paused.
            micAllowed = micGranted(context)
            if (micAllowed) open() else askMic.launch(Manifest.permission.RECORD_AUDIO)
        },
        micAllowed = micAllowed,
    )
}

/**
 * The duel's card. Its board follows the reading lights only during the announcement until its
 * crown, collected here, so a cheer's sparkle during a turn recomposes nothing of the card.
 */
@Composable
internal fun Duel(viewModel: HomeViewModel, duel: DuelState, again: Boolean = true) {
    val beforeTheCrown = duel.step == DuelState.Step.ANNOUNCING && !duel.winnerShown
    val lit = if (beforeTheCrown) viewModel.readingLit.collectAsStateWithLifecycle().value else emptySet()
    DuelCard(duel, lit, onStart = { now -> viewModel.startDuel(now) }, onDismiss = viewModel::dismissDuel, again = again)
}

/** The voice meter, collecting what moves often here so only it recomposes (docs/10 4장 "노래 중"). */
@Composable
internal fun StageMeter(viewModel: HomeViewModel) {
    // Read while drawing only, so the level's movement redraws the bar without recomposing.
    val voice = viewModel.voiceLevel.collectAsStateWithLifecycle()
    val music = viewModel.musicLevel.collectAsStateWithLifecycle()
    val cheers by viewModel.cheers.collectAsStateWithLifecycle()
    val color by viewModel.meterColor.collectAsStateWithLifecycle()
    VoiceMeterBar(voice = { voice.value }, music = { music.value }, cheers = cheers, color = Color(color))
}

/**
 * The left half, the car (docs/10 "2번 PR 구현"): the simulator with its status icons, or, while the
 * player on the right opened it, the song list in the drawing's place, so the right column keeps
 * its height (docs/13).
 */
@Composable
private fun CabinPanel(viewModel: HomeViewModel, modifier: Modifier) {
    val songsOpen by viewModel.songsUnderPlayer.collectAsStateWithLifecycle()
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(StageSizes.Gap, Alignment.CenterVertically),
    ) {
        if (songsOpen) {
            // The song list takes the drawing's place while it is open; the icons still tell the cabin.
            val cabin by viewModel.cabin.collectAsStateWithLifecycle()
            CabinStatus(cabin)
            Songs(viewModel, onPlayed = {})
        } else {
            CabinPreviewLine(viewModel)
            CabinDrawing(viewModel, Modifier.weight(1f))
        }
    }
}

/**
 * Once the car refused this app some control, and until it takes a write to each refused target
 * again ([com.carstage.app.stage.ControlDenials.refused]), or always in a build that does not drive
 * the cabin ([com.carstage.app.AppGraph.cabinPreview]), a line over the drawing says it is a
 * preview of what the car would do (docs/11 5장).
 */
@Composable
private fun CabinPreviewLine(viewModel: HomeViewModel) {
    val preview by viewModel.cabinPreview.collectAsStateWithLifecycle()
    if (preview) {
        Text(CABIN_PREVIEW, style = MaterialTheme.typography.labelMedium, color = StageColors.TextSecondary, textAlign = TextAlign.Center)
    }
}

/**
 * The simulator and, with [status], its settings as icons ([CabinStatus]); reads the cabin here so
 * its frequent changes redraw only this part.
 */
@Composable
private fun CabinDrawing(viewModel: HomeViewModel, modifier: Modifier, status: Boolean = true) {
    val cabin by viewModel.cabin.collectAsStateWithLifecycle()
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(StageSizes.Gap)) {
        CabinView(cabin, Modifier.weight(1f))
        if (status) CabinStatus(cabin)
    }
}


/** The song list ([SongListCard]); [onPlayed] runs once a picked song was asked to play. */
@Composable
internal fun Songs(viewModel: HomeViewModel, onPlayed: () -> Unit) {
    val shelf by viewModel.shelf.collectAsStateWithLifecycle()
    val track by viewModel.track.collectAsStateWithLifecycle()
    // What the list says goes to the notice line only while the list is on the screen.
    DisposableEffect(Unit) {
        viewModel.songsOnScreen(true)
        onDispose { viewModel.songsOnScreen(false) }
    }
    SongListCard(
        shelf,
        track,
        onBack = viewModel::songsBack,
        onChooseOther = viewModel::chooseOtherSource,
        onChoose = viewModel::chooseSource,
        onOpen = viewModel::openFolder,
        onPlay = { if (viewModel.playSong(it)) onPlayed() },
        onRetry = viewModel::retrySongs,
    )
}

/** The frame the first screen's cards share ([DuelCard], [ChargeFinishCard], [SongListCard], the stage's scores): centered lines in a card. */
@Composable
internal fun HomeCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.width(StageSizes.CardWidth),
        colors = CardDefaults.cardColors(containerColor = StageColors.Surface, contentColor = StageColors.Text),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/** How long the song list takes to fade in or out (docs/10 5장 "움직임"). */
private const val FADE_MS = 200

/** Between the two halves of a wide window. */
private val COLUMN_GAP = 48.dp

/** The least width for the two halves, each as wide as a card; narrower windows take one column. */
private val TWO_COLUMNS = StageSizes.CardWidth * 2 + COLUMN_GAP

/** The least height worth drawing the cabin in, in one column (about 220 dp wide). */
private val MIN_DRAWING = 360.dp

/** Over the drawing while the car may not do what it shows ([CabinPreviewLine]). */
private const val CABIN_PREVIEW = "미리보기 · 차가 허용하면 실내가 이렇게 반응해요"

private fun micGranted(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
