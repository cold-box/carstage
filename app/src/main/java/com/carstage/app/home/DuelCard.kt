package com.carstage.app.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.carstage.app.stage.DuelState
import com.carstage.app.stage.Side
import com.carstage.app.ui.QuietButton
import com.carstage.app.ui.StageColors
import com.carstage.app.ui.StageSizes

/**
 * A duel (docs/04 "기능 2", docs/10 4장): waiting for the song being sung, whose turn it is in their
 * color, then the announcement, then the winner, over a scoreboard of both sides ([BoardSide]).
 * Until the announcement's crown the board's sides light with the reading lights of [lit]; the
 * winner is told with the crown's light, before the announcement ends; the buttons wait for its end, as
 * the announcement takes no touch. Kind words for both sides, as for the grades. [onStart] asks
 * for a duel, at once when given true.
 */
@Composable
internal fun DuelCard(duel: DuelState, lit: Set<Side>, onStart: (now: Boolean) -> Unit, onDismiss: () -> Unit, again: Boolean = true) {
    HomeCard {
        Text("노래 대결", style = MaterialTheme.typography.bodyLarge, color = StageColors.TextSecondary)
        val winner = duel.winner
        when {
            duel.step == DuelState.Step.WAITING ->
                Text("지금 곡이 끝나면 운전석부터 시작해요", style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
            duel.step == DuelState.Step.TURN ->
                Text("${sideName(duel.turn)} 차례예요", style = MaterialTheme.typography.headlineSmall, color = StageColors.side(duel.turn))
            duel.winnerShown -> Text(
                if (winner == null) "무승부! 환상의 케미예요" else "승자는 ${sideName(winner)}!",
                style = MaterialTheme.typography.titleLarge,
                color = StageColors.side(winner),
                textAlign = TextAlign.Center,
            )
            else -> Text("결과를 발표하겠습니다", style = MaterialTheme.typography.titleLarge)
        }
        Scoreboard(BoardSide.of(duel, lit))
        when (duel.step) {
            DuelState.Step.WAITING -> QuietButton("바로 시작", onClick = { onStart(true) })
            DuelState.Step.TURN -> Text(
                "10초 이상 한 곡을 부르면 차례가 넘어가요. 플레이어에서 다음 곡으로 넘기면 바로 다음 차례예요.",
                style = MaterialTheme.typography.labelMedium,
                color = StageColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            DuelState.Step.ANNOUNCING -> Unit
            DuelState.Step.DONE -> {
                if (winner != null) Text("다음 곡에서 역전해 보세요!", style = MaterialTheme.typography.bodyMedium, color = StageColors.TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(StageSizes.Gap)) {
                    // On a closed stage a new duel starts with a song, as every stage does (docs/13).
                    if (again) QuietButton("다시 대결", onClick = { onStart(false) })
                    QuietButton("확인", onClick = onDismiss)
                }
            }
        }
    }
}

/** The driver's side on the left and the passenger's on the right, as they sit. */
@Composable
private fun Scoreboard(board: Map<Side, BoardSide>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StageSizes.Gap)) {
        listOf(Side.DRIVER, Side.PASSENGER).forEach { side -> BoardPanel(side, board.getValue(side), Modifier.weight(1f)) }
    }
}

/**
 * One side: its seat's name and its score or what it is doing. Lit, it takes its side's color
 * within a fade (docs/10 5장 "움직임"), quick enough for the announcement's shortest turn (at
 * least DuelAnnouncement.FASTEST_SWITCH_SECONDS, about 0.38 s in whole frames); the winner's grows
 * a little.
 */
@Composable
private fun BoardPanel(side: Side, look: BoardSide, modifier: Modifier) {
    // Read while drawing only, so a fade redraws the panel without recomposing it.
    val fill by animateColorAsState(if (look.lit) StageColors.litPanel(side) else StageColors.Background, tween(FADE_MS), label = "panel")
    val edge by animateColorAsState(if (look.lit) StageColors.side(side) else StageColors.Background, tween(FADE_MS), label = "edge")
    val scale by animateFloatAsState(if (look.raised) RAISED else 1f, tween(RAISE_MS), label = "raise")
    // The score's line keeps its height for words too, so the board does not jump as the turns go by.
    val scoreHeight = with(LocalDensity.current) { MaterialTheme.typography.headlineLarge.lineHeight.toDp() }
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .drawBehind {
                val corner = PANEL_CORNER.toPx()
                drawRoundRect(fill, cornerRadius = CornerRadius(corner))
                // The edge's stroke is centered on its outline, so it is drawn inset by half its width.
                val stroke = EDGE.toPx()
                drawRoundRect(
                    edge,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(corner - stroke / 2),
                    style = Stroke(stroke),
                )
            }
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(sideName(side), style = MaterialTheme.typography.titleSmall, color = StageColors.side(side))
        Box(Modifier.height(scoreHeight), contentAlignment = Alignment.Center) {
            if (look.score) {
                Text(look.text, style = MaterialTheme.typography.headlineLarge)
            } else {
                Text(look.text, style = MaterialTheme.typography.bodyLarge, color = if (look.lit) StageColors.Text else StageColors.TextSecondary)
            }
        }
    }
}

private fun sideName(side: Side) = when (side) {
    Side.DRIVER -> "운전석"
    Side.PASSENGER -> "조수석"
}

private val PANEL_CORNER = 16.dp
private val EDGE = 3.dp

/** docs/10 5장 "움직임": a state change fades in 0.2 s. */
private const val FADE_MS = 200

/** The winner's side grows by this much, over [RAISE_MS], without moving the board (a layer's scale). */
private const val RAISED = 1.08f
private const val RAISE_MS = 600
