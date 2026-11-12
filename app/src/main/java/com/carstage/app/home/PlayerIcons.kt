package com.carstage.app.home

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carstage.app.audio.Track
import com.carstage.app.ui.QuietButton
import com.carstage.app.ui.StageColors
import com.carstage.app.ui.StageSizes
import kotlinx.coroutines.delay

/** The player's buttons' symbols ([CompactPlayer]). */
internal enum class PlayerIconKind { PREVIOUS, PLAY, PAUSE, NEXT }

/** The player's symbols, drawn so the app needs no icon library. */
@Composable
internal fun PlayerIcon(kind: PlayerIconKind, iconSize: Dp = 36.dp) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(iconSize)) {
        when (kind) {
            PlayerIconKind.PLAY -> triangle(color, left = size.width * 0.2f, right = size.width * 0.85f, pointsRight = true)
            PlayerIconKind.PAUSE -> {
                bar(color, size.width * 0.22f)
                bar(color, size.width * 0.6f)
            }
            PlayerIconKind.NEXT -> {
                triangle(color, left = size.width * 0.12f, right = size.width * 0.72f, pointsRight = true)
                bar(color, size.width * 0.72f, width = size.width * 0.12f)
            }
            PlayerIconKind.PREVIOUS -> {
                bar(color, size.width * 0.16f, width = size.width * 0.12f)
                triangle(color, left = size.width * 0.28f, right = size.width * 0.88f, pointsRight = false)
            }
        }
    }
}

private fun DrawScope.triangle(color: Color, left: Float, right: Float, pointsRight: Boolean) {
    val top = size.height * 0.15f
    val bottom = size.height * 0.85f
    val path = Path().apply {
        if (pointsRight) {
            moveTo(left, top)
            lineTo(right, size.height / 2)
            lineTo(left, bottom)
        } else {
            moveTo(right, top)
            lineTo(left, size.height / 2)
            lineTo(right, bottom)
        }
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.bar(color: Color, left: Float, width: Float = size.width * 0.18f) {
    drawRect(color, topLeft = Offset(left, size.height * 0.15f), size = Size(width, size.height * 0.7f))
}
