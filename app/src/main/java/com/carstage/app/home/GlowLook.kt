package com.carstage.app.home

import com.carstage.app.stage.DuelState
import com.carstage.app.stage.Side

/**
 * What the stage glow draws (docs/10 "화면이 조명을 따라감"): while the stage is open, the screen's
 * edges glow in the ambient light's colour, as strongly as the light is bright, and the left or
 * right edge more while the driver's or passenger's reading light is on (a cheer's sparkle). During
 * a duel's announcement the sides take over (docs/04 "좌우 연출 레이어" 4, "발표 모드 연출 순서"):
 * the side whose reading light is on glows clearly in its colour (the driver's blue on the left, the
 * passenger's red on the right) while the other side and the top and bottom dim, so the light
 * crosses the screen with the reading lights, and a tie's crown, which lights both, splits the
 * screen in the two colours. It follows the simulator, so it glows when the car refuses control
 * too. Plain values, so it can be unit-tested.
 */
data class GlowLook(
    /** The top and bottom edges' colour as opaque ARGB; every colour is kept when the glow goes out, so it fades in the colours it had. */
    val argb: Int,
    /** The alpha at the very top and bottom edges. */
    val strength: Float,
    /** The left (driver's) edge's colour. */
    val leftArgb: Int,
    /** The alpha at the very left edge. */
    val left: Float,
    /** The right (passenger's) edge's colour. */
    val rightArgb: Int,
    /** The alpha at the very right edge. */
    val right: Float,
    /**
     * Whether the sides are on their own (an announcement), so they follow faster than the top
     * and bottom; until both reading lights are off after it, so its end does not flare up.
     */
    val split: Boolean = false,
) {
    companion object {
        private const val BRAND = 0xFFE040C0.toInt()

        /** No glow, in the brand magenta (docs/10 "색") until a stage gives a colour; a stage's glow fades in from it. */
        val NONE = GlowLook(BRAND, 0f, BRAND, 0f, BRAND, 0f)

        /**
         * Edge alpha at the dimmest and the brightest ambient light, kept low for the text near the
         * edges. A reading light adds [READING_LIGHT] to its side, and the corners, where two bands
         * meet, are brighter still (about 0.7 at the brightest); only the debug Lab button sits there.
         */
        const val FAINTEST = 0.12f
        const val BRIGHTEST = 0.45f

        /** What a reading light that is on adds to its side's edge. */
        const val READING_LIGHT = 0.15f

        /** During an announcement, the edge of the side whose reading light is on. */
        const val SIDE_LIT = 0.6f

        /** During an announcement, the edge of a side whose reading light is off. */
        const val SIDE_DIM = 0.06f

        /** During an announcement, the share of the usual strength left to the top and bottom. */
        const val ANNOUNCING_TOP = 0.3f

        /**
         * The glow for [look] while the stage is [open] or not and a duel's winner is being
         * [announcing] or not, after [previous].
         */
        fun of(look: CabinLook, open: Boolean, announcing: Boolean = false, previous: GlowLook = NONE): GlowLook {
            val argb = look.ambientArgb
            if (!open || argb == null) return previous.copy(strength = 0f, left = 0f, right = 0f)
            val level = ((look.ambientGlow - CabinLook.MIN_GLOW) / (1 - CabinLook.MIN_GLOW)).coerceIn(0f, 1f)
            val strength = FAINTEST + (BRIGHTEST - FAINTEST) * level
            fun lit(side: Side) = look.readingLights[side] == true
            // The stage turns the reading lights off a moment after the announcement ends.
            if (announcing || (previous.split && Side.entries.any(::lit))) {
                fun side(side: Side) = if (lit(side)) SIDE_LIT else SIDE_DIM
                return GlowLook(
                    argb, strength * ANNOUNCING_TOP,
                    DuelState.colorOf(Side.DRIVER).toArgb(), side(Side.DRIVER),
                    DuelState.colorOf(Side.PASSENGER).toArgb(), side(Side.PASSENGER),
                    split = true,
                )
            }
            fun side(side: Side) = strength + if (lit(side)) READING_LIGHT else 0f
            return GlowLook(argb, strength, argb, side(Side.DRIVER), argb, side(Side.PASSENGER))
        }
    }
}
