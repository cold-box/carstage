package com.carstage.app.home

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carstage.app.NoticeTone
import com.carstage.app.ShownNotice
import com.carstage.app.ui.StageColors
import com.carstage.app.ui.StageSizes
import com.carstage.app.ui.withTapFeedback
import kotlinx.coroutines.delay

/**
 * The notice line (docs/10 7장 "안내 문구"): the icon of its most serious kind beside the text,
 * both in the kind's color, as one block whose text is no wider than a card. A line that only tells what the
 * app did or is doing folds to one smaller line [FOLD_AFTER_MS] after it appears (longer when the
 * system asks for more time to act), and a tap unfolds it until the notice changes; cautions and
 * errors stay whole, as they say what to do or what went wrong ([folds]). A changed notice
 * starts unfolded. TalkBack reads the kind and the whole text as one item.
 */
@Composable
internal fun NoticeLine(notice: ShownNotice) {
    val folds = folds(notice.tone)
    var folded by remember(notice) { mutableStateOf(false) }
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(notice) {
        if (folds) {
            delay(accessibility?.calculateRecommendedTimeoutMillis(FOLD_AFTER_MS, containsIcons = true, containsText = true) ?: FOLD_AFTER_MS)
            folded = true
        }
    }
    val color = StageColors.notice(notice.tone)
    // A line that folds is a touch target (Android for Cars: 76 dp) from the start, so folding does not change it.
    val target = if (folds) Modifier.heightIn(min = StageSizes.ButtonHeight) else Modifier
    val unfold = if (folded) Modifier.clickable(onClickLabel = "안내 펼치기", onClick = withTapFeedback { folded = false }) else Modifier
    Box(
        modifier = target.then(unfold).semantics(mergeDescendants = true) {}.animateContentSize(tween(RESIZE_MS)),
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.widthIn(max = ICON + ICON_GAP + StageSizes.CardWidth), horizontalArrangement = Arrangement.spacedBy(ICON_GAP)) {
            // Level with the text's first line, so a paragraph reads from beside its icon.
            NoticeIcon(notice.tone, color, Modifier.padding(top = 4.dp))
            Text(
                notice.text,
                modifier = Modifier.weight(1f, fill = false),
                style = if (folded) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                color = color,
                textAlign = TextAlign.Start,
                maxLines = if (folded) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Whether a notice line of [tone] folds: only one that tells what the app did or is doing. A
 * caution says what to watch or do (allow the microphone, park first) and an error what went
 * wrong, so both stay whole (docs/10 5장 "안내 종류").
 */
internal fun folds(tone: NoticeTone): Boolean = tone == NoticeTone.INFO

/**
 * A notice's kind as a symbol in its color, drawn so the app needs no icon library: "i" in a
 * circle for information, "!" in a triangle for a caution and "!" in a circle for an error, as
 * Material's symbols have them; the mark is cut out in the screen's background.
 */
@Composable
private fun NoticeIcon(tone: NoticeTone, color: Color, modifier: Modifier) {
    val name = when (tone) {
        NoticeTone.INFO -> "정보"
        NoticeTone.CAUTION -> "주의"
        NoticeTone.ERROR -> "오류"
    }
    Canvas(modifier.size(ICON).semantics { contentDescription = name }) {
        val w = size.width
        // The bar's ends and the dot's center, as fractions of the icon: the dot above the bar
        // for "i", below it for "!", at least 0.08 clear of the bar's round ends.
        val (barTop, barBottom, dot) = when (tone) {
            NoticeTone.INFO -> Triple(0.46f, 0.74f, 0.25f)
            NoticeTone.CAUTION -> Triple(0.38f, 0.56f, 0.77f)
            NoticeTone.ERROR -> Triple(0.24f, 0.54f, 0.75f)
        }
        if (tone == NoticeTone.CAUTION) {
            val triangle = Path().apply {
                moveTo(w / 2, 0.1f * w)
                lineTo(0.95f * w, 0.92f * w)
                lineTo(0.05f * w, 0.92f * w)
                close()
            }
            // Filled and stroked with round joins, so its corners are soft like the circles.
            drawPath(triangle, color)
            drawPath(triangle, color, style = Stroke(0.08f * w, join = StrokeJoin.Round))
        } else {
            drawCircle(color)
        }
        val mark = StageColors.Background
        drawLine(mark, Offset(w / 2, barTop * w), Offset(w / 2, barBottom * w), 0.12f * w, StrokeCap.Round)
        drawCircle(mark, 0.07f * w, Offset(w / 2, dot * w))
    }
}

/** docs/10 7장 "안내 문구": information folds this long after it appears. */
private const val FOLD_AFTER_MS = 3_000L

/** A line folding or unfolding changes its height over 0.2 s, the screen's time for a change (docs/10 5장 "움직임"). */
private const val RESIZE_MS = 200

/** About the height of the notice's text (Body Small, 26). */
private val ICON = 28.dp
private val ICON_GAP = 12.dp
