package com.carstage.app.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The stage glow ([GlowLook]) along the screen's edges, behind everything else. It follows the
 * light on critically damped springs, which keep their speed when the stage changes the light
 * again (about every 120 ms), so it never flickers (docs/10 5장 "움직임"): every edge settles in
 * about a second, and during a duel's announcement ([GlowLook.split]) the sides settle in about
 * 0.6 s, reaching about 90 % within the shortest turn (0.38 s), so the light visibly crosses the
 * screen with the reading lights (a side relights no sooner than 0.7 s). Its own graphics layer,
 * and edge masks built once per size and only tinted per frame, keep a frame of the glow from
 * re-recording the rest of the screen.
 */
@Composable
internal fun StageGlow(viewModel: HomeViewModel, modifier: Modifier) {
    val glow by viewModel.glow.collectAsStateWithLifecycle()
    val sides = if (glow.split) SIDES_STIFFNESS else Spring.StiffnessVeryLow
    val color by animateColorAsState(Color(glow.argb), spring(stiffness = Spring.StiffnessVeryLow), label = "glowColor")
    val leftColor by animateColorAsState(Color(glow.leftArgb), spring(stiffness = sides), label = "glowLeftColor")
    val rightColor by animateColorAsState(Color(glow.rightArgb), spring(stiffness = sides), label = "glowRightColor")
    val strength by animateFloatAsState(glow.strength, spring(stiffness = Spring.StiffnessVeryLow), label = "glowStrength")
    val left by animateFloatAsState(glow.left, spring(stiffness = sides), label = "glowLeft")
    val right by animateFloatAsState(glow.right, spring(stiffness = sides), label = "glowRight")

    Spacer(
        modifier.graphicsLayer().drawWithCache {
            val band = BAND * size.minDimension
            // White masks, bright at the edge and fading out a little faster than a straight line so the band has no hard end.
            val mask = arrayOf(0f to Color.White, 0.45f to Color.White.copy(alpha = 0.35f), 1f to Color.White.copy(alpha = 0f))
            val flipped = mask.map { (at, c) -> 1f - at to c }.reversed().toTypedArray()
            val top = Brush.verticalGradient(*mask, startY = 0f, endY = band)
            val bottom = Brush.verticalGradient(*flipped, startY = size.height - band, endY = size.height)
            val leftEdge = Brush.horizontalGradient(*mask, startX = 0f, endX = band)
            val rightEdge = Brush.horizontalGradient(*flipped, startX = size.width - band, endX = size.width)
            onDrawBehind {
                if (strength + left + right < VISIBLE) return@onDrawBehind
                // The mask's alpha in each edge's colour; outside an announcement every edge has one.
                val tint = ColorFilter.tint(color)
                fun tintOf(edge: Color) = if (edge == color) tint else ColorFilter.tint(edge)
                drawRect(top, size = Size(size.width, band), alpha = strength.coerceIn(0f, 1f), colorFilter = tint)
                drawRect(bottom, Offset(0f, size.height - band), Size(size.width, band), alpha = strength.coerceIn(0f, 1f), colorFilter = tint)
                drawRect(leftEdge, size = Size(band, size.height), alpha = left.coerceIn(0f, 1f), colorFilter = tintOf(leftColor))
                drawRect(
                    rightEdge, Offset(size.width - band, 0f), Size(band, size.height),
                    alpha = right.coerceIn(0f, 1f), colorFilter = tintOf(rightColor),
                )
            }
        },
    )
}

/** The sides' spring during an announcement: about 0.6 s to settle (docs/10's half a second at least), about 90 % within a 0.38 s turn. */
private const val SIDES_STIFFNESS = 100f

/** How deep the glow reaches in from each edge, as a share of the screen's shorter side. */
private const val BAND = 0.18f

/** Below this total alpha nothing is drawn. */
private const val VISIBLE = 0.005f
