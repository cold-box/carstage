package com.carstage.app.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carstage.app.ChargeFinish
import com.carstage.app.ResumeOffers
import com.carstage.app.score.SongResult
import com.carstage.app.stage.Side
import com.carstage.app.stage.StageController.Phase
import com.carstage.app.ui.PrimaryButton
import com.carstage.app.ui.QuietButton
import com.carstage.app.ui.SecondaryButton
import com.carstage.app.ui.StageColors
import com.carstage.app.ui.StageSizes
import com.carstage.app.ui.withTapFeedback

/**
 * The stage's controls (docs/13 "기본 시나리오"): a song is picked, the stage is that song, and it
 * ends with "그만하기". With [extras] (every feature on) a duel and the question when charging
 * stops come on top: their cards, and "대결 시작" on an open stage.
 * - Closed: the song playing now, if any, with "이 곡으로 시작", and the song list, whose songs start
 *   at a tap; the last stage's scores once it closed; an offer to sing on after a call or a drive.
 * - Open: what to do, the meter and the last score, and "그만하기" (closes the stage
 *   and pauses the music, [com.carstage.app.AppGraph.closeStage]); the player under it
 *   moves to another song, whose change ends and scores the one sung, or pauses.
 */
@Composable
internal fun BasicControlItems(viewModel: HomeViewModel, listHere: Boolean, extras: Boolean) {
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val mic by viewModel.mic.collectAsStateWithLifecycle()
    val battery by viewModel.battery.collectAsStateWithLifecycle()
    val resumeOffer by viewModel.resumeOffer.collectAsStateWithLifecycle()
    val stageStart = rememberStageStart(viewModel)

    StatusLine(mic, battery)
    when (phase) {
        Phase.CLOSED -> Closed(viewModel, resumeOffer, stageStart, extras)
        Phase.OPEN -> Open(viewModel, listHere, extras)
        Phase.OPENING -> Busy("무대를 여는 중입니다", "그만하기")
        Phase.CLOSING -> Busy("차량을 원래대로 되돌리는 중입니다", "그만하기")
    }
    notice?.let { NoticeLine(it) }
}

@Composable
private fun Closed(viewModel: HomeViewModel, resumeOffer: ResumeOffers.Offer?, stageStart: StageStart, extras: Boolean) {
    val scores by viewModel.stageScores.collectAsStateWithLifecycle()
    val musicPlaying by viewModel.musicPlaying.collectAsStateWithLifecycle()
    val track by viewModel.track.collectAsStateWithLifecycle()
    val shelf by viewModel.shelf.collectAsStateWithLifecycle()

    // A duel announced as the stage closed keeps its result.
    val duel by viewModel.duel.collectAsStateWithLifecycle()
    val shownDuel = duel?.takeIf { extras }
    // A stage's result has the screen to itself until "확인", which leaves the first screen as at a
    // start; the duel's result says how its songs went, so the stage's scores go with it.
    if (shownDuel != null) {
        Duel(viewModel, shownDuel, again = false)
        return
    }
    if (scores.isNotEmpty()) {
        StageScoresCard(scores, onDismiss = viewModel::dismissStageScores)
        return
    }
    // What the next stage is: the basic one or a duel (docs/13), chosen before a song starts it.
    if (resumeOffer == null) ModeChoice(duel = extras, onChange = viewModel::setAllFeatures)
    when {
        resumeOffer?.ready == true -> {
            Headline(
                when (resumeOffer.reason) {
                    ResumeOffers.Reason.CALL -> "통화가 끝났어요. 이어서 부를까요?"
                    ResumeOffers.Reason.DRIVE -> "다시 정차했어요. 이어서 부를까요?"
                    ResumeOffers.Reason.BACKGROUND -> "다시 오셨네요. 이어서 부를까요?"
                },
            )
            PrimaryButton("이어서 부르기", onClick = {
                viewModel.resumeMusic()
                stageStart.start()
            })
            QuietButton("괜찮아요", onClick = viewModel::dismissResume)
        }
        resumeOffer != null ->
            Headline(if (resumeOffer.reason == ResumeOffers.Reason.CALL) "통화가 끝나면 이어서 부를 수 있어요" else "정차하면 이어서 부를 수 있어요")
        musicPlaying -> {
            Headline("지금 나오는 곡으로 부를까요?")
            Text(track?.takeIf { it.playing }?.title ?: "음악 재생 중", style = MaterialTheme.typography.bodyLarge, color = StageColors.TextSecondary, textAlign = TextAlign.Center)
            PrimaryButton("이 곡으로 시작", onClick = stageStart.start)
        }
        else -> Headline(if (shelf.shown && shelf.offersSongs) StartPrompt.PICK_SONG else "음악 앱에서 노래를 틀어 주세요")
    }
    // A picked song plays and starts the stage, asking for the microphone first (docs/07 "노래 목록").
    AnimatedVisibility(visible = shelf.shown && resumeOffer == null, enter = fadeIn(tween(LIST_FADE_MS)), exit = fadeOut(tween(LIST_FADE_MS))) {
        Songs(viewModel, onPlayed = stageStart.start)
    }
    StartPrompt.hint(Phase.CLOSED, resumeOffer, stageStart.micAllowed)?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = StageColors.TextSecondary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Open(viewModel: HomeViewModel, listHere: Boolean, extras: Boolean) {
    val result by viewModel.result.collectAsStateWithLifecycle()
    val scored by viewModel.scored.collectAsStateWithLifecycle()
    val duel by viewModel.duel.collectAsStateWithLifecycle()
    val chargeFinish by viewModel.chargeFinish.collectAsStateWithLifecycle()
    val duelOffered by viewModel.duelOffered.collectAsStateWithLifecycle()
    if (extras && chargeFinish != ChargeFinish.Step.NONE) {
        ChargeFinishCard(chargeFinish, onLastSong = viewModel::lastSong, onKeepSinging = viewModel::keepSinging)
    }
    val row = rememberScoreRow(result, viewModel::mcBusy)
    val shownDuel = duel?.takeIf { extras }
    if (shownDuel != null) {
        // The duel's card says whose turn it is and what comes; it stands in for the lines below.
        Duel(viewModel, shownDuel)
        // The passenger's turn waits for their song, picked from the list the music paused for.
        val pick by viewModel.pickForTurn.collectAsStateWithLifecycle()
        if (pick) {
            Text("조수석, 부를 노래를 골라 주세요", style = MaterialTheme.typography.titleSmall, color = StageColors.side(Side.PASSENGER), textAlign = TextAlign.Center)
        }
    } else {
        // A song just scored takes the place of the stage's lines for its reveal (docs/10 "점수
        // 공개"); the swap fades and resizes over SWAP_MS as the notices do, so nothing below jumps.
        AnimatedContent(
            targetState = row.revealing,
            // Centered as the column is, so neither side slides sideways while the size changes.
            contentAlignment = Alignment.TopCenter,
            transitionSpec = { fadeIn(tween(SWAP_MS)) togetherWith fadeOut(tween(SWAP_MS)) using SizeTransform(clip = false) { _, _ -> tween(SWAP_MS) } },
            label = "reveal",
        ) { shown ->
            if (shown != null) {
                ScoreReveal(shown)
            } else {
                val last = row.line
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(StageSizes.Gap)) {
                    // The song's title is on the player below.
                    Text(
                        if (extras && duelOffered) "두 분이 타셨네요. 노래 대결도 해 보세요" else "노래를 불러 보세요. 목소리에 맞춰 조명이 움직여요",
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        if (scored && last != null) "방금 곡 ${last.score}점 · ${gradeWord(last.grade)}" else "곡이 끝나면 점수가 나와요",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (scored && last != null) StageColors.grade(last.grade) else StageColors.TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
    StageMeter(viewModel)
    // Over the player, so its song list opening under it never pushes the way out off the screen.
    PrimaryButton("그만하기", onClick = viewModel::closeStage)
    if (extras && shownDuel == null) {
        SecondaryButton("대결 시작", onClick = { viewModel.startDuel(now = false) }, emphasized = duelOffered)
    }
    // The player moves between songs and pauses (docs/13).
    BasicPlayer(viewModel, listHere)
}

/**
 * The compact player of the stage's song ([CompactPlayer]); its list button opens the song list
 * under it in one column ([listHere]), or on the left in the drawing's place in two.
 */
@Composable
private fun BasicPlayer(viewModel: HomeViewModel, listHere: Boolean) {
    val player by viewModel.player.collectAsStateWithLifecycle()
    val shelf by viewModel.shelf.collectAsStateWithLifecycle()
    val listOpen by viewModel.listOpen.collectAsStateWithLifecycle()
    val songsOpen by viewModel.songsUnderPlayer.collectAsStateWithLifecycle()
    val look = player ?: return
    CompactPlayer(
        look,
        onPrevious = viewModel::previousSong,
        // The stage is the song: its own pause ends the stage at once, as "그만하기" does (which
        // pauses the music); only a pause from elsewhere waits AppGraph.MUSIC_STOP_GRACE_MS.
        onPlayPause = { if (look.playing) viewModel.closeStage() else viewModel.playPause() },
        onNext = viewModel::nextSong,
        listOpen = if (shelf.shown) listOpen else null,
        onToggleList = viewModel::toggleList,
    )
    if (listHere) {
        AnimatedVisibility(visible = songsOpen, enter = fadeIn(tween(LIST_FADE_MS)), exit = fadeOut(tween(LIST_FADE_MS))) {
            Songs(viewModel, onPlayed = {})
        }
    }
}

/**
 * The closed screen's choice of the next stage, "기본" or "대결", as two halves of one pill, the
 * chosen one filled; read as two radio buttons. A duel's stage starts as a duel when a song starts
 * it ([com.carstage.app.AppGraph.request]); the choice is kept across starts ([com.carstage.app.FeatureMode]).
 */
@Composable
private fun ModeChoice(duel: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .width(StageSizes.ButtonWidth)
            // Each half keeps a button's touch height inside the pill's rim.
            .height(StageSizes.ButtonHeight + RIM * 2)
            .clip(CircleShape)
            .background(StageColors.Surface)
            .selectableGroup()
            .padding(RIM),
    ) {
        listOf(false to "기본", true to "대결").forEach { (value, label) ->
            val chosen = duel == value
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(if (chosen) StageColors.Primary else Color.Transparent)
                    .selectable(selected = chosen, onClick = withTapFeedback { onChange(value) }, role = Role.RadioButton),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.bodyLarge, color = if (chosen) StageColors.Text else StageColors.TextSecondary)
            }
        }
    }
}

/**
 * While the stage opens or closes: what goes on, turning beside it, and the main button held. A
 * slow car can take seconds, and Pleos asks an app to show it is working when a tap takes over
 * two (app review guide, "functionality, performance and stability").
 */
@Composable
private fun Busy(headline: String, button: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        // As the song list's loading: the brand color is under 4.5:1 on the screen's background.
        CircularProgressIndicator(Modifier.size(BUSY_SIZE), color = StageColors.TextSecondary, trackColor = StageColors.Track, strokeWidth = 4.dp)
        Headline(headline)
    }
    PrimaryButton(button, onClick = {}, enabled = false)
}

private val BUSY_SIZE = 32.dp

@Composable
private fun Headline(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
}

/** The last stage's songs and their scores, once it closed, its best song first in the heading. */
@Composable
private fun StageScoresCard(scores: List<SongResult>, onDismiss: () -> Unit) {
    val best = scores.maxBy { it.score }
    HomeCard {
        Text("오늘의 무대 · ${scores.size}곡", style = MaterialTheme.typography.bodyLarge, color = StageColors.TextSecondary)
        Text("최고 ${best.score}점", style = MaterialTheme.typography.headlineMedium, color = StageColors.grade(best.grade))
        best.title?.let { Text(it, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center) }
        if (scores.size > 1) {
            scores.forEachIndexed { i, song ->
                Text(
                    "${i + 1}. ${song.title ?: "이름 모를 곡"} · ${song.score}점",
                    style = MaterialTheme.typography.bodyMedium,
                    color = StageColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
        QuietButton("확인", onClick = onDismiss)
    }
}

/** The rim of the mode choice around its chosen half. */
private val RIM = 6.dp

/** How long the song list takes to fade in or out (docs/10 5장 "움직임"). */
private const val LIST_FADE_MS = 200

/** How long the score's reveal fades and resizes in and out, as the notice line does. */
private const val SWAP_MS = 200
