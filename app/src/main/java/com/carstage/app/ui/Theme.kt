package com.carstage.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carstage.app.NoticeTone
import com.carstage.app.score.Grade
import com.carstage.app.stage.DuelState
import com.carstage.app.stage.Side

/**
 * The first screen's colors (docs/10 5장 "색"): a dark stage, white text and one light at a time.
 * Every text color here keeps at least 4.5:1 against [Background] and [Surface] (Pleos "대비"),
 * except [Brand], which is for the screen only; StageColorsTest checks them.
 */
object StageColors {
    val Background = Color(0xFF0E0F1A)

    /** Cards: white at 10% over [Background], opaque so the glow behind cannot lower the text's contrast. */
    val Surface = Color(0xFF262731)
    val Text = Color.White

    /** Explanations and details under the main text. */
    val TextSecondary = Color(0xFFB9BCCB)

    /** The brand magenta, where the light starts (GlowLook.NONE); 4.5:1 only against [Background], so not on cards. */
    val Brand = Color(0xFFE040C0)

    /** The main button: the brand magenta deepened until white text on it reaches 4.5:1. */
    val Primary = Color(0xFFC8309F)

    /** An outlined button's edge: 5:1 against [Background], where outlined buttons sit. */
    val Outline = Color(0xAAB9BCCB)

    /** The microphone's light while the stage listens (the car's status bar shows its microphone in green too). */
    val Live = Color(0xFF5BD68A)

    /** The empty part of a bar: the 흥 점수 parts on a card, the stage's meter on the screen. */
    val Track = Color(0xFF3B3C48)

    /**
     * The stage meter's fill for the stage [light]: the light lightened toward white until it
     * reads 4.5:1 against [Track], so a dark blue or violet light still shows (Pleos "대비").
     */
    fun meter(light: Color): Color {
        var t = 0f
        var fill = light
        while (contrast(fill, Track) < MIN_CONTRAST && t < 1f) {
            t = (t + 0.05f).coerceAtMost(1f)
            fill = lerp(light, Text, t)
        }
        return fill
    }

    /** WCAG contrast between two colors, 1 to 21. */
    fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    const val MIN_CONTRAST = 4.5f

    /** A button that stands out without being the main one: the brand magenta at 25%, on the screen or a card. */
    val Emphasis = Color(0x40E040C0)

    /** Kinds of notice (docs/10 5장 "색"): what happened, what to watch, what failed; see [notice]. */
    val Info = Color(0xFF8EC5FF)
    val Caution = Color(0xFFFFD166)
    val Error = Color(0xFFFF7A7A)

    /** The notice line's color for its most serious kind. */
    fun notice(tone: NoticeTone): Color = when (tone) {
        NoticeTone.INFO -> Info
        NoticeTone.CAUTION -> Caution
        NoticeTone.ERROR -> Error
    }

    /**
     * Text in a duel side's color: lighter tints of the light's colors (DuelState.colorOf), which
     * are too dark to read on a card; violet for a tie.
     */
    fun side(side: Side?): Color = when (side) {
        Side.DRIVER -> Color(0xFF6FA0FF)
        Side.PASSENGER -> Color(0xFFFF6B6B)
        null -> Color(0xFFB58CFF)
    }

    /**
     * A duel side's scoreboard panel while it is lit (docs/10 4장 "대결 차례", "대결 발표", "대결 결과"): the side's
     * light (DuelState.colorOf) at a fifth over [Background], opaque and dark enough that white,
     * secondary and the side's own text keep 4.5:1 on it. An unlit panel is [Background].
     */
    fun litPanel(side: Side): Color = Color(DuelState.colorOf(side).toArgb()).copy(alpha = 0.2f).compositeOver(Background)

    /** Text in a grade's color: the finale's light (FinaleLayer.colorOf), its violet lightened to read on the dark. */
    fun grade(grade: Grade): Color = when (grade) {
        Grade.BASIC -> Color(0xFFFFF1E0)
        Grade.GOOD -> Color(0xFFC8A0FF)
        Grade.BEST -> Color(0xFFFFC020)
    }

    /**
     * The best grade's ring of light behind its score (home/ScoreReveal) at its brightest: the
     * grade's gold, faint enough that the gold number keeps 4.5:1 where the ring passes behind it.
     */
    val BestRing = grade(Grade.BEST).copy(alpha = 0.35f)
}

/**
 * The cabin drawing's colors (docs/10 6장): the car in the stage's navy, each part a step lighter
 * than what it lies on except the floor, darker so the ambient light shows on it, and the lights
 * and air it shows. What tells something (a lamp, lit or off, ventilation, the fan's air where it
 * leaves the vent, a seat's name) keeps 4.5:1 against what it lies on, lit or not, as Pleos asks of
 * icons and drawings (docs/10 2장 "대비"); the car itself is their dim backdrop, and the ambient
 * strips are the light itself, in whatever color the stage sends. StageColorsTest checks them.
 */
object CabinColors {
    val Body = Color(0xFF1E2030)
    val BodyEdge = Color(0xFF3A3D55)
    val Glass = Color(0xFF2C3048)

    /** The cabin's floor, darker than the body so the ambient light shows on it. */
    val Floor = Color(0xFF13141F)
    val Dashboard = Color(0xFF242638)
    val Console = Color(0xFF222436)
    val Vent = Color(0xFF3C3F55)
    val Seat = Color(0xFF34374D)
    val SeatBack = Color(0xFF2B2E42)

    /** A seat's bolsters and the rear bench's seams. */
    val SeatLine = Color(0xFF474A63)
    val Wheel = Color(0xFF6A6E88)

    /** An ambient strip that is off. */
    val Unlit = Color(0xFF4A4D64)

    /** A reading lamp's rim, always drawn: all of a lamp that shows while it is off. */
    val Lamp = Color(0xFFB8BCD0)

    /** The reading lights' warm white, and a seat's name when its temperature went up. */
    val Warm = Color(0xFFFFD480)

    /** Seat ventilation, and a seat's name when its temperature went down. */
    val Cool = Color(0xFF5AC8FA)

    /** The fan's air. */
    val Air = Color(0xFFBFE8FF)
}

/** Sizes the first screen shares (docs/10 2장 "터치 크기", 5장 "글자와 버튼"). */
object StageSizes {
    /** The one main button on the screen. */
    val PrimaryButtonHeight = 96.dp

    /** Other buttons, and the least any touch target gets (Android for Cars: 76 × 76 dp). */
    val ButtonHeight = 76.dp
    val ButtonWidth = 400.dp
    val CardWidth = 560.dp

    /** Between touch targets (Android for Cars: at least 23 dp) and between the parts of each half. */
    val Gap = 24.dp
}

/**
 * A level of the scale. Lines break between words, not inside one ("끝났는데" whole), as Korean is
 * read: Android 13+ does so for Korean text, so the text is marked Korean whatever the system's
 * language (older versions break between any syllables); headings also balance their lines.
 */
private fun style(size: Int, weight: FontWeight, lineHeight: Float, heading: Boolean) = TextStyle(
    fontSize = size.sp,
    lineHeight = (size * lineHeight).sp,
    fontWeight = weight,
    localeList = LocaleList("ko-KR"),
    // Paragraph's own word break is the default one, which breaks Korean between any syllables.
    lineBreak = if (heading) LineBreak.Heading else LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase),
)

/**
 * Pleos's type scale (docs/10 2장 "글자 체계"; sizes and weights from the Pleos Typography page,
 * which gives no line height): each Pleos level in Material's slot of the same name, in one of the
 * weights Pleos lists for it. The emulator's screen is 160 dpi, so 1 sp is 1 px there. Display takes the Headline sizes and Label Large the Body Large
 * size (Material buttons use it), so no slot falls below the scale. The font is the system's
 * (Asta Sans on Pleos).
 */
val PleosTypography = Typography(
    displayLarge = style(60, FontWeight.ExtraBold, 1.2f, heading = true),
    displayMedium = style(56, FontWeight.ExtraBold, 1.2f, heading = true),
    displaySmall = style(48, FontWeight.ExtraBold, 1.2f, heading = true),
    headlineLarge = style(60, FontWeight.Bold, 1.2f, heading = true),
    headlineMedium = style(56, FontWeight.Bold, 1.2f, heading = true),
    headlineSmall = style(48, FontWeight.Bold, 1.2f, heading = true),
    titleLarge = style(40, FontWeight.Bold, 1.3f, heading = true),
    titleMedium = style(36, FontWeight.Bold, 1.3f, heading = true),
    titleSmall = style(32, FontWeight.Bold, 1.3f, heading = true),
    bodyLarge = style(30, FontWeight.Normal, 1.4f, heading = false),
    bodyMedium = style(28, FontWeight.Normal, 1.4f, heading = false),
    bodySmall = style(26, FontWeight.Normal, 1.4f, heading = false),
    labelLarge = style(30, FontWeight.Medium, 1.4f, heading = false),
    labelMedium = style(24, FontWeight.Normal, 1.4f, heading = false),
    labelSmall = style(20, FontWeight.Normal, 1.4f, heading = false),
)

@Composable
fun CarStageTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = StageColors.Primary,
            onPrimary = StageColors.Text,
            secondaryContainer = StageColors.Surface,
            onSecondaryContainer = StageColors.Text,
            background = StageColors.Background,
            onBackground = StageColors.Text,
            surface = StageColors.Background,
            onSurface = StageColors.Text,
            surfaceVariant = StageColors.Surface,
            onSurfaceVariant = StageColors.TextSecondary,
            outline = StageColors.Outline,
            error = StageColors.Error,
        ),
        typography = PleosTypography,
        content = content,
    )
}
