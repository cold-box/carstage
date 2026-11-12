package com.carstage.app.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import com.carstage.app.ChargeFinish
import com.carstage.app.ui.QuietButton
import com.carstage.app.ui.SecondaryButton
import com.carstage.app.ui.StageSizes

/**
 * After charging stopped on an open stage: whether the song being sung is the last (docs/07 "충전
 * 연동"), and once it is, that the stage closes after it. The screen's main button stays the
 * stage's, so the card's choice is a secondary one.
 */
@Composable
internal fun ChargeFinishCard(step: ChargeFinish.Step, onLastSong: () -> Unit, onKeepSinging: () -> Unit) {
    HomeCard {
        Text(
            if (step == ChargeFinish.Step.ASKING) "충전이 끝났어요.\n마지막 곡으로 마무리할까요?" else "이번 곡이 끝나면 무대를 닫을게요",
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        // Touch targets keep their distance (StageSizes.Gap), wider than the card's lines.
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(StageSizes.Gap)) {
            if (step == ChargeFinish.Step.ASKING) {
                SecondaryButton("마지막 곡으로", onClick = onLastSong, emphasized = true)
            }
            QuietButton("계속 부르기", onClick = onKeepSinging)
        }
    }
}
