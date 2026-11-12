package com.carstage.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The one main button on the screen (docs/10 5장 "글자와 버튼"): filled, [StageSizes.PrimaryButtonHeight] high. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = withTapFeedback(onClick),
        enabled = enabled,
        modifier = modifier.width(StageSizes.ButtonWidth).height(StageSizes.PrimaryButtonHeight),
        colors = ButtonDefaults.buttonColors(containerColor = StageColors.Primary, contentColor = StageColors.Text),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

/**
 * A button beside the main one, [StageSizes.ButtonHeight] high: outlined, or tinted with the
 * brand's color when [emphasized] (the duel offered to two in front, the last song after charging).
 */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    val size = modifier.width(StageSizes.ButtonWidth).height(StageSizes.ButtonHeight)
    if (emphasized) {
        Button(
            onClick = withTapFeedback(onClick),
            modifier = size,
            colors = ButtonDefaults.buttonColors(containerColor = StageColors.Emphasis, contentColor = StageColors.Text),
        ) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    } else {
        OutlinedButton(
            onClick = withTapFeedback(onClick),
            modifier = size,
            border = BorderStroke(2.dp, StageColors.Outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = StageColors.Text),
        ) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** A quiet choice ("괜찮아요", "확인"): text only, but a touch target of at least 76 × 76 dp. */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = withTapFeedback(onClick),
        modifier = modifier.heightIn(min = StageSizes.ButtonHeight).widthIn(min = StageSizes.ButtonHeight),
        colors = ButtonDefaults.textButtonColors(contentColor = StageColors.Text),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
