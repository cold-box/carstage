package com.carstage.app.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carstage.app.audio.Track
import com.carstage.app.media.BrowseItem
import com.carstage.app.media.MediaSource
import com.carstage.app.media.ShelfLook
import com.carstage.app.ui.QuietButton
import com.carstage.app.ui.StageColors
import com.carstage.app.ui.StageSizes
import com.carstage.app.ui.withTapFeedback

/**
 * The songs of the music app the car plays (docs/10 4장 "노래 목록"): a heading with the app's
 * name (or the open folder's, the app's under it), back and "another app" as drawn icons, and the
 * rows, which scroll when there are more than fit. A folder opens; a song plays ([onPlay]). The
 * song [track] plays is marked. While the user picks an app the rows are the car's music apps.
 * Every change of what is shown fades in 0.2 s (docs/10 5장 "움직임").
 */
@Composable
internal fun SongListCard(
    look: ShelfLook,
    track: Track?,
    onBack: () -> Unit,
    onChooseOther: () -> Unit,
    onChoose: (MediaSource) -> Unit,
    onOpen: (BrowseItem) -> Unit,
    onPlay: (BrowseItem) -> Unit,
    onRetry: () -> Unit,
) {
    // The car's back key (or gesture) leaves a folder before it leaves the app (car app quality AN-1).
    BackHandler(enabled = look.canGoBack, onBack = onBack)
    HomeCard {
        Header(look, onBack, onChooseOther)
        // Fades between kinds of content (another folder, the apps, a message), not on every update
        // of the same one, so a list keeps its scroll while it changes.
        AnimatedContent(
            targetState = look,
            transitionSpec = { fadeIn(tween(FADE_MS)) togetherWith fadeOut(tween(FADE_MS)) },
            label = "songs",
            contentKey = ::viewOf,
        ) { shown ->
            when {
                shown.showsSources -> Sources(shown, onChoose)
                shown.step == ShelfLook.Step.READY -> Songs(shown, track, onOpen, onPlay)
                shown.step == ShelfLook.Step.CONNECTING || shown.step == ShelfLook.Step.LOADING -> Loading()
                else -> Message(messageOf(shown.step), if (shown.retries) onRetry else null)
            }
        }
    }
}

/** What kind of content [look] shows, for the card's fade. */
private fun viewOf(look: ShelfLook): Any = when {
    look.showsSources -> "sources"
    look.step == ShelfLook.Step.CONNECTING || look.step == ShelfLook.Step.LOADING -> "loading"
    else -> listOf(look.source?.packageName, look.folders.lastOrNull()?.id, look.step)
}

/** What the card says in place of the rows. */
private fun messageOf(step: ShelfLook.Step): String = when (step) {
    ShelfLook.Step.EMPTY -> "곡이 없어요"
    ShelfLook.Step.REFUSED -> "이 앱은 곡 목록을 열어 주지 않아요"
    ShelfLook.Step.LOST -> "음악 앱과 연결이 끊겼어요"
    ShelfLook.Step.FAILED -> "곡 목록을 불러오지 못했어요"
    else -> ""
}

@Composable
private fun Header(look: ShelfLook, onBack: () -> Unit, onChooseOther: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (look.canGoBack) {
            IconButton(onClick = withTapFeedback(onBack), modifier = Modifier.size(StageSizes.ButtonHeight).semantics { contentDescription = "뒤로" }) {
                ListIcon(ListIconKind.BACK)
            }
        }
        Column(Modifier.weight(1f).padding(start = if (look.canGoBack) 0.dp else 8.dp)) {
            if (look.showsSources) {
                Text("음악 앱", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(look.heading.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Inside a folder, the app it belongs to stays named.
                if (look.folders.isNotEmpty()) {
                    Text(look.source?.label.orEmpty(), style = MaterialTheme.typography.labelMedium, color = StageColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (!look.showsSources && look.sources.size > 1) {
            IconButton(onClick = withTapFeedback(onChooseOther), modifier = Modifier.size(StageSizes.ButtonHeight).semantics { contentDescription = "다른 음악 앱 고르기" }) {
                ListIcon(ListIconKind.APPS)
            }
        }
    }
}

@Composable
private fun Songs(look: ShelfLook, track: Track?, onOpen: (BrowseItem) -> Unit, onPlay: (BrowseItem) -> Unit) {
    // One scroll per folder, so a folder's list keeps its place while it updates.
    val state = key(look.source?.packageName, look.folders.lastOrNull()?.id) { rememberLazyListState() }
    val current = look.items.indexOfFirst { look.isCurrent(it, track) }
    // The list starts at its top; only a song that plays below what fits is brought into view when
    // the list comes up, so a paused or finished song does not leave the top cut off.
    LaunchedEffect(state) { if (track?.playing == true && current >= FIRST_ROWS) state.scrollToItem(current) }
    LazyColumn(state = state, modifier = Modifier.fillMaxWidth().heightIn(max = LIST_HEIGHT), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(look.items, key = { it.id }) { item ->
            val isCurrent = look.isCurrent(item, track)
            ListRow(
                title = item.title,
                subtitle = item.subtitle,
                icon = when {
                    item.folder -> ListIconKind.FOLDER
                    isCurrent && track?.playing == true -> ListIconKind.PLAYING
                    else -> ListIconKind.NOTE
                },
                trailing = if (item.folder) ListIconKind.FORWARD else null,
                selected = isCurrent,
                action = if (item.folder) "열기" else "부르기",
                onClick = { if (item.folder) onOpen(item) else onPlay(item) },
            )
        }
    }
}

@Composable
private fun Sources(look: ShelfLook, onChoose: (MediaSource) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = LIST_HEIGHT), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(look.sources, key = { it.packageName }) { source ->
            val picked = source == look.source
            ListRow(
                title = source.label,
                subtitle = null,
                icon = ListIconKind.APP,
                trailing = if (picked) ListIconKind.CHECK else null,
                selected = picked,
                action = "고르기",
                onClick = { onChoose(source) },
            )
        }
    }
}

/** One row, a touch target of at least 76 dp (Android for Cars), tinted while [selected]. */
@Composable
private fun ListRow(
    title: String,
    subtitle: String?,
    icon: ListIconKind,
    trailing: ListIconKind?,
    selected: Boolean,
    action: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = StageSizes.ButtonHeight)
            .background(if (selected) StageColors.Emphasis else Color.Transparent, shape)
            .clickable(onClickLabel = action, role = Role.Button, onClick = withTapFeedback(onClick))
            .semantics(mergeDescendants = true) { this.selected = selected }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(ICON_SIZE), contentAlignment = Alignment.Center) {
            ListIcon(icon, tint = if (icon == ListIconKind.PLAYING) StageColors.Live else StageColors.TextSecondary)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = StageColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.let { ListIcon(it, tint = StageColors.TextSecondary) }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().heightIn(min = StageSizes.ButtonHeight).semantics { contentDescription = "곡 목록을 불러오는 중" }, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = StageColors.TextSecondary, trackColor = StageColors.Track, modifier = Modifier.size(ICON_SIZE))
    }
}

/** What the card says in place of the rows, with a way to try again when it may help ([onRetry]). */
@Composable
private fun Message(text: String, onRetry: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().heightIn(min = StageSizes.ButtonHeight), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = StageColors.TextSecondary, textAlign = TextAlign.Center)
        onRetry?.let { QuietButton("다시 불러오기", onClick = it) }
    }
}

internal enum class ListIconKind { BACK, FORWARD, FOLDER, NOTE, PLAYING, APPS, APP, CHECK, LIST }

/** The list's symbols, drawn so the app needs no icon library (as the player's, docs/10 5장 "아이콘"). */
@Composable
internal fun ListIcon(kind: ListIconKind, tint: Color = LocalContentColor.current) {
    Canvas(Modifier.size(ICON_SIZE)) {
        val w = size.width
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            ListIconKind.BACK -> chevron(tint, stroke, pointsRight = false)
            ListIconKind.FORWARD -> chevron(tint, stroke, pointsRight = true)
            ListIconKind.FOLDER -> {
                val path = Path().apply {
                    moveTo(w * 0.12f, w * 0.25f)
                    lineTo(w * 0.42f, w * 0.25f)
                    lineTo(w * 0.5f, w * 0.34f)
                    lineTo(w * 0.88f, w * 0.34f)
                    lineTo(w * 0.88f, w * 0.78f)
                    lineTo(w * 0.12f, w * 0.78f)
                    close()
                }
                drawPath(path, tint, style = stroke)
            }
            ListIconKind.NOTE -> {
                // An eighth note: a head, its stem and a flag.
                drawOval(tint, topLeft = Offset(w * 0.2f, w * 0.6f), size = Size(w * 0.3f, w * 0.22f))
                drawLine(tint, Offset(w * 0.47f, w * 0.7f), Offset(w * 0.47f, w * 0.16f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
                val flag = Path().apply {
                    moveTo(w * 0.47f, w * 0.16f)
                    quadraticTo(w * 0.62f, w * 0.3f, w * 0.78f, w * 0.34f)
                }
                drawPath(flag, tint, style = stroke)
            }
            ListIconKind.PLAYING -> {
                // Three bars of a level meter: the song that plays.
                listOf(0.45f, 0.75f, 0.3f).forEachIndexed { i, height ->
                    val left = w * (0.18f + i * 0.24f)
                    drawRoundRect(tint, topLeft = Offset(left, w * (0.85f - height)), size = Size(w * 0.16f, w * height), cornerRadius = CornerRadius(w * 0.04f))
                }
            }
            ListIconKind.APPS -> {
                // Four tiles: the car's apps to pick from.
                for (row in 0..1) for (col in 0..1) {
                    drawRoundRect(
                        tint,
                        topLeft = Offset(w * (0.16f + col * 0.38f), w * (0.16f + row * 0.38f)),
                        size = Size(w * 0.3f, w * 0.3f),
                        cornerRadius = CornerRadius(w * 0.06f),
                        style = stroke,
                    )
                }
            }
            ListIconKind.APP -> {
                // A music app: a note in a rounded tile.
                drawRoundRect(tint, topLeft = Offset(w * 0.1f, w * 0.1f), size = Size(w * 0.8f, w * 0.8f), cornerRadius = CornerRadius(w * 0.18f), style = stroke)
                drawOval(tint, topLeft = Offset(w * 0.3f, w * 0.55f), size = Size(w * 0.2f, w * 0.15f))
                drawLine(tint, Offset(w * 0.49f, w * 0.62f), Offset(w * 0.49f, w * 0.3f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.49f, w * 0.3f), Offset(w * 0.68f, w * 0.38f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
            }
            ListIconKind.CHECK -> {
                val path = Path().apply {
                    moveTo(w * 0.2f, w * 0.52f)
                    lineTo(w * 0.42f, w * 0.72f)
                    lineTo(w * 0.8f, w * 0.3f)
                }
                drawPath(path, tint, style = stroke)
            }
            ListIconKind.LIST -> {
                // Lines of a list, each after a dot.
                for (i in 0..2) {
                    val y = w * (0.28f + i * 0.22f)
                    drawCircle(tint, radius = w * 0.06f, center = Offset(w * 0.2f, y))
                    drawLine(tint, Offset(w * 0.36f, y), Offset(w * 0.84f, y), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
                }
            }
        }
    }
}

private fun DrawScope.chevron(color: Color, stroke: Stroke, pointsRight: Boolean) {
    val w = size.width
    val (from, tip) = if (pointsRight) w * 0.38f to w * 0.64f else w * 0.62f to w * 0.36f
    val path = Path().apply {
        moveTo(from, w * 0.22f)
        lineTo(tip, w * 0.5f)
        lineTo(from, w * 0.78f)
    }
    drawPath(path, color, style = stroke)
}

private val ICON_SIZE = 36.dp

/** About five rows; more scroll. */
private val LIST_HEIGHT = 440.dp

private const val FADE_MS = 200

/** About as many rows as the list shows before it scrolls. */
private const val FIRST_ROWS = 5
