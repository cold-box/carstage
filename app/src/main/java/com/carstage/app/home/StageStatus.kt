package com.carstage.app.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.carstage.app.audio.LiveMic
import com.carstage.app.ui.StageColors
import com.carstage.app.ui.StageSizes
import com.carstage.app.ui.rememberTapFeedback
import com.carstage.app.vehicle.BatteryState

/**
 * The line at the top of the stage's controls, the right half's when there are two (docs/10 4장):
 * the app's name, whether the stage is listening ("듣고 있어요", or "잠시 안 들려요" while only
 * silence arrives) and charging. The microphone shows inside the app, not only in the car's status
 * bar (docs/04 사업화 리스크). Too long for one pane's width, it goes on in a second line. The MC's
 * switch is in the screen's corner with the other setting ([McToggle]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatusLine(mic: LiveMic.State, battery: BatteryState?) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(StageSizes.Gap, Alignment.CenterHorizontally)) {
        val centered = Modifier.align(Alignment.CenterVertically)
        Text("CarStage", style = MaterialTheme.typography.titleSmall, color = StageColors.Brand, modifier = centered)
        when (mic) {
            LiveMic.State.OFF -> Unit
            LiveMic.State.LISTENING -> Status("듣고 있어요", StageColors.Live, centered)
            LiveMic.State.SILENT -> Status("잠시 안 들려요", StageColors.Caution, centered)
        }
        battery?.takeIf { it.charging == true }?.let { charging ->
            Text(
                charging.percent?.let { "충전 중 · $it%" } ?: "충전 중",
                style = MaterialTheme.typography.bodyLarge,
                color = StageColors.TextSecondary,
                modifier = centered,
            )
        }
    }
}

/**
 * Turns the MC's voice on or off (docs/12 "MC 끄기"), in the screen's bottom corner: "MC" and a
 * switch, the whole of it one touch target as high as a button. Off, the MC
 * and a duel's announcement keep quiet; the screen's words stand alone.
 */
@Composable
internal fun McToggle(on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val tap = rememberTapFeedback()
    Row(
        modifier = modifier
            .height(StageSizes.ButtonHeight)
            .clip(CircleShape)
            .toggleable(
                value = on,
                onValueChange = {
                    tap()
                    onChange(it)
                },
                role = Role.Switch,
            )
            .semantics { contentDescription = "MC 목소리" }
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("MC", style = MaterialTheme.typography.labelMedium, color = StageColors.TextSecondary)
        // The row takes the touch, so the switch only shows it.
        Switch(
            checked = on,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = StageColors.Text,
                checkedTrackColor = StageColors.Primary,
                uncheckedThumbColor = StageColors.TextSecondary,
                uncheckedTrackColor = StageColors.Track,
                uncheckedBorderColor = StageColors.Outline,
            ),
        )
    }
}

/** A dot in [color] before [text], as a microphone's light. */
@Composable
private fun Status(text: String, color: Color, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).background(color, CircleShape))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * One bar for what the cabin makes (docs/10 4장 "노래 중"): the louder of the voices standing out of
 * the music ([voice]) and the music itself ([music]), in the stage light's [color] (lightened to
 * read on its track, [StageColors.meter]), so its color says the mode and its length how much goes
 * on. While the microphone hears the car's music with the voices it moves with both; should the
 * car's echo cancelling take the music out (docs/05 "마이크 입력 종류 비교"), it follows the voices
 * alone without a change. It swells once for each new cheer ([cheers]). The levels are read while
 * drawing, so the bar redraws as it moves without recomposing the screen. No label: the voice and
 * the song are not told apart here.
 */
@Composable
internal fun VoiceMeterBar(voice: () -> Float, music: () -> Float, cheers: Int, color: Color) {
    val fill by animateColorAsState(StageColors.meter(color), tween(COLOR_MS), label = "meter")
    val flash = remember { Animatable(0f) }
    var seen by remember { mutableIntStateOf(cheers) }
    LaunchedEffect(cheers) {
        if (cheers == seen) return@LaunchedEffect
        seen = cheers
        flash.snapTo(1f)
        flash.animateTo(0f, tween(FLASH_MS))
    }
    // A layer of its own, so the bar's movement redraws only the bar; as wide as the buttons under it.
    Canvas(Modifier.width(StageSizes.ButtonWidth).height(BAR_HEIGHT).graphicsLayer()) {
        val corner = CornerRadius(size.height / 2)
        drawRoundRect(StageColors.Track, cornerRadius = corner)
        val shown = maxOf(voice(), music(), flash.value).coerceIn(0f, 1f)
        if (shown > 0f) {
            drawRoundRect(lerp(fill, StageColors.Text, flash.value * FLASH_WHITE), size = Size(size.width * shown, size.height), cornerRadius = corner)
        }
    }
}

private val BAR_HEIGHT = 20.dp

private const val COLOR_MS = 500

/** A cheer fills the bar and whitens it at once, then it eases back over this long: one flash, not a blinking. */
private const val FLASH_MS = 800
private const val FLASH_WHITE = 0.6f

