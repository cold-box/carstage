package com.carstage.app.home

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carstage.app.audio.Track
import com.carstage.app.ui.StageColors
import com.carstage.app.ui.StageSizes
import com.carstage.app.ui.withTapFeedback
import kotlinx.coroutines.delay

/**
 * The basic flow's player on an open stage (docs/13): one row of the song (title over artist) and
 * its buttons, and under it a thin bar of where the song is, between its time and its length, so
 * it takes little height under the stage's main button. The buttons keep the 76 dp touch size of
 * every button (docs/10 5장); the play button is the filled one. [listOpen] tints the list button
 * while the song list is open.
 */
@Composable
internal fun CompactPlayer(
    look: PlayerLook,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    listOpen: Boolean?,
    onToggleList: () -> Unit,
) {
    Card(
        modifier = Modifier.width(StageSizes.CardWidth),
        colors = CardDefaults.cardColors(containerColor = StageColors.Surface, contentColor = StageColors.Text),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(look.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    look.artist?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = StageColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = withTapFeedback(onPrevious), modifier = Modifier.size(StageSizes.ButtonHeight).semantics { contentDescription = "이전 곡" }) {
                    PlayerIcon(PlayerIconKind.PREVIOUS, ICON)
                }
                FilledIconButton(
                    onClick = withTapFeedback(onPlayPause),
                    modifier = Modifier.size(StageSizes.ButtonHeight).semantics { contentDescription = if (look.playing) "일시정지" else "재생" },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = StageColors.Primary, contentColor = StageColors.Text),
                ) {
                    PlayerIcon(if (look.playing) PlayerIconKind.PAUSE else PlayerIconKind.PLAY, ICON)
                }
                IconButton(onClick = withTapFeedback(onNext), modifier = Modifier.size(StageSizes.ButtonHeight).semantics { contentDescription = "다음 곡" }) {
                    PlayerIcon(PlayerIconKind.NEXT, ICON)
                }
                listOpen?.let { open ->
                    IconButton(
                        onClick = withTapFeedback(onToggleList),
                        modifier = Modifier.size(StageSizes.ButtonHeight).semantics { contentDescription = if (open) "노래 목록 닫기" else "노래 목록 열기" },
                        colors = if (open) IconButtonDefaults.iconButtonColors(containerColor = StageColors.Emphasis) else IconButtonDefaults.iconButtonColors(),
                    ) {
                        ListIcon(ListIconKind.LIST)
                    }
                }
            }
            look.track?.let { Progress(it) }
        }
    }
}

/** The song's time, a thin bar of how far it is, and its length; ticks once a second while it plays. */
@Composable
private fun Progress(track: Track) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(track) {
        now = SystemClock.elapsedRealtime()
        while (track.playing) {
            delay(1_000)
            now = SystemClock.elapsedRealtime()
        }
    }
    val position = track.positionAt(now) ?: return
    val length = track.durationMs
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(Track.clock(position), style = MaterialTheme.typography.labelMedium, color = StageColors.TextSecondary)
        Canvas(Modifier.weight(1f).height(BAR)) {
            val corner = CornerRadius(size.height / 2)
            drawRoundRect(StageColors.Track, cornerRadius = corner)
            val done = if (length != null && length > 0) (position.toFloat() / length).coerceIn(0f, 1f) else 0f
            if (done > 0f) drawRoundRect(StageColors.Primary, size = Size(size.width * done, size.height), cornerRadius = corner)
        }
        Text(length?.let(Track::clock) ?: "", style = MaterialTheme.typography.labelMedium, color = StageColors.TextSecondary)
    }
}

/** The symbols inside the 76 dp buttons, smaller than the full player's. */
private val ICON = 30.dp

private val BAR = 6.dp
