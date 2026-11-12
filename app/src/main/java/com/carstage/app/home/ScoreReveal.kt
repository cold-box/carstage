package com.carstage.app.home

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carstage.app.McLines
import com.carstage.app.score.Grade
import com.carstage.app.score.SongResult
import com.carstage.app.ui.StageColors
import com.carstage.app.ui.withTapFeedback
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A song's score revealed on the open stage (docs/10 "점수 공개"): its title, the points counting
 * up from 0 over [COUNT_MS], then the grade's word popping in and what the song did best (the MC's
 * praise, so it reads with the voice off or drowned out). The best grade alone gets one slow gold
 * ring of light behind the number, the rare celebration; nothing flashes. A tap shows the end at
 * once, and with the system's animations off it starts there.
 */
@Composable
internal fun ScoreReveal(result: SongResult, modifier: Modifier = Modifier) {
    val still = rememberAnimationsOff()
    val count = remember(result) { Animatable(if (still) result.score.toFloat() else 0f) }
    val stamp = remember(result) { Animatable(if (still) 1f else 0f) }
    val ring = remember(result) { Animatable(0f) }
    var skipped by remember(result) { mutableStateOf(still) }
    LaunchedEffect(result, skipped) {
        if (skipped) {
            count.snapTo(result.score.toFloat())
            stamp.snapTo(1f)
            ring.snapTo(0f)
            return@LaunchedEffect
        }
        count.animateTo(result.score.toFloat(), tween(COUNT_MS, easing = FastOutSlowInEasing))
        // Past 1 and back: the word lands like a stamp.
        launch { stamp.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow)) }
        if (result.grade == Grade.BEST) {
            ring.animateTo(1f, tween(RING_MS / 2))
            ring.animateTo(0f, tween(RING_MS / 2))
        }
    }
    val color = StageColors.grade(result.grade)
    Column(
        modifier = modifier
            .clickable(onClickLabel = "점수 바로 보기", onClick = withTapFeedback { skipped = true })
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "${result.title?.let { "$it, " } ?: ""}${result.score}점, ${gradeWord(result.grade)}. ${McLines.praise(result)}"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        result.title?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = StageColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.drawBehind {
                val shown = ring.value
                if (shown > 0f) {
                    // An oval around the number within its own row and the gaps beside it, so no
                    // other line lies under it; where it passes behind the digits it is faint enough
                    // for them (StageColors.BestRing).
                    val gap = RING_GAP.toPx()
                    val tall = size.height / 2 + gap
                    val wide = size.width / 2 + gap * 2
                    scale(scaleX = wide / tall, scaleY = 1f, pivot = center) {
                        drawCircle(
                            Brush.radialGradient(
                                0f to Color.Transparent,
                                RING_CLEAR to Color.Transparent,
                                RING_PEAK to StageColors.BestRing.copy(alpha = StageColors.BestRing.alpha * shown),
                                1f to Color.Transparent,
                                center = center,
                                radius = tall,
                            ),
                            radius = tall,
                        )
                    }
                }
            },
        ) {
            // Digits of one width, so the number does not shake sideways as it counts.
            Text("${count.value.roundToInt()}점", style = MaterialTheme.typography.headlineLarge.copy(fontFeatureSettings = "tnum"), color = color)
        }
        Text(
            gradeWord(result.grade),
            style = MaterialTheme.typography.titleLarge,
            color = color,
            modifier = Modifier.graphicsLayer {
                val s = stamp.value
                alpha = s.coerceIn(0f, 1f)
                // From half again as large down to its size, a little under on the way.
                val scale = 1.5f - 0.5f * s
                scaleX = scale
                scaleY = scale
            },
        )
        Text(
            McLines.praise(result),
            style = MaterialTheme.typography.bodyMedium,
            color = StageColors.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer { alpha = stamp.value.coerceIn(0f, 1f) },
        )
    }
}

/**
 * What the open stage's score row shows: the score [revealing] now, or else the one [line] with
 * the newest score whose reveal is over. The line keeps the score before a new one until its reveal
 * ends, so the line fading out as the reveal comes in does not give the new score away.
 */
internal class ScoreRow(val revealing: SongResult?, val line: SongResult?)

/**
 * The stage's newest score is revealed from the moment it came, for [REVEAL_MS] and then for as
 * long as the MC is still on it ([busy]: its line waits for the finale's applause, about 2.3 s for
 * the short one and 5 s for the longer ones, then takes several seconds), at most [REVEAL_MAX_MS].
 * One already there when this screen came up is not news, so coming back to the screen does not
 * reveal it again.
 */
@Composable
internal fun rememberScoreRow(result: SongResult?, busy: () -> Boolean): ScoreRow {
    // The last score whose reveal is over; at first the one already there.
    // By identity, as below: a song may score just like the one before it.
    var over by remember { mutableStateOf(result, referentialEqualityPolicy()) }
    val stillBusy by rememberUpdatedState(busy)
    LaunchedEffect(result) {
        if (result == null || result === over) return@LaunchedEffect
        delay(REVEAL_MS)
        withTimeoutOrNull(REVEAL_MAX_MS - REVEAL_MS) {
            while (stillBusy()) delay(BUSY_POLL_MS)
        }
        over = result
    }
    // Decided while composing, so the new score never shows a frame as the one line first.
    return ScoreRow(revealing = result?.takeIf { it !== over }, line = over.takeIf { result != null })
}

/** A grade in a word, kindly (docs/04 "점수 등급"). */
internal fun gradeWord(grade: Grade) = when (grade) {
    Grade.BASIC -> "좋은 출발"
    Grade.GOOD -> "흥이 올랐어요"
    Grade.BEST -> "흥부자"
}

/** Whether the system's animations are off (developer options or accessibility), which the reveal then skips. */
@Composable
private fun rememberAnimationsOff(): Boolean {
    val context = LocalContext.current
    return remember(context) { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/** How long the points count up; the finale's applause plays meanwhile. */
private const val COUNT_MS = 1_200

/** The best grade's gold ring, out and back once. */
private const val RING_MS = 1_400

/** How far beyond the number the ring reaches: the column's gap, so it stays off the lines above and below. */
private val RING_GAP = 8.dp

/** Where the ring starts and peaks, as shares of its reach; inside it the middle of the number stays clear. */
private const val RING_CLEAR = 0.82f
private const val RING_PEAK = 0.92f

/** The least a score stays revealed; the finale's applause and the count-up are over by then. */
private const val REVEAL_MS = 6_000L

/** The most it stays while the MC is still on it. */
private const val REVEAL_MAX_MS = 12_000L
private const val BUSY_POLL_MS = 200L
