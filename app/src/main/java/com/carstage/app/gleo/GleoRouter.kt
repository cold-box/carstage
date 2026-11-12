package com.carstage.app.gleo

import com.carstage.app.DuelOutcome
import com.carstage.app.OpenOutcome
import com.carstage.app.StageMc.Said
import com.carstage.app.stage.CarCabin

/**
 * Carries out Gleo voice actions on the stage and decides what Gleo says back (in Korean).
 * [endSong] ends the song on an open stage and is false when none is open; [startDuel] opens the
 * stage if needed and asks for a duel.
 */
class GleoRouter(
    /** Whether this build drives the cabin; one that does not reaches no car on purpose. */
    private val drivesCabin: Boolean = true,
    private val endSong: () -> Boolean,
    private val startDuel: suspend () -> DuelOutcome,
    private val openStage: suspend () -> OpenOutcome,
) {
    /**
     * What Gleo answers. [mcSays] is true when the MC says the same itself, with what the car
     * knows (a new stage's greeting, the driver's turn of a duel starting now), so Gleo need only
     * beep (docs/12 "Gleo와 MC"). [says] is what [message] tells of the MC's lines, which the MC
     * leaves out when Gleo reads it.
     */
    data class Reply(
        val status: GleoActions.Status,
        val message: String,
        val mcSays: Boolean = false,
        val says: Set<Said> = emptySet(),
    ) {
        /** Gleo only beeps for a success the MC says itself, when the MC [can speak][mcCanSpeak]; else it reads [message]. */
        fun mode(mcCanSpeak: Boolean): GleoActions.Mode =
            if (status == GleoActions.Status.SUCCESS && mcSays && mcCanSpeak) GleoActions.Mode.BEEP else GleoActions.Mode.VOICE
    }

    /** [parameters] is what [GleoActions.parameters] read; none of the current actions take any. */
    suspend fun handle(action: String, parameters: Result<*>): Reply {
        if (parameters.isFailure) return Reply(GleoActions.Status.ERROR, "요청을 이해하지 못했어요.")
        // Gleo reads its own error sentence for FAIL, so a FAIL message mainly helps the logs.
        return when (action) {
            GleoActions.OPEN_STAGE -> when (val outcome = openStage()) {
                is OpenOutcome.Refused -> Reply(GleoActions.Status.FAIL, outcome.reason)
                is OpenOutcome.Opened -> {
                    val opened = outcome.result
                    when {
                        drivesCabin && CarCabin.NAME !in opened.driven -> Reply(GleoActions.Status.SUCCESS, "차에 연결하지 못해서 화면에서만 반응해요.")
                        opened.alreadyOpen -> Reply(GleoActions.Status.SUCCESS, "무대가 이미 열려 있어요. 노래를 불러 보세요.")
                        else -> Reply(GleoActions.Status.SUCCESS, "무대를 열었어요. 노래를 불러 보세요.", mcSays = true, says = setOf(Said.GREETING))
                    }
                }
            }
            GleoActions.FINISH_SONG ->
                if (endSong()) {
                    // A song under 10 s is not scored, so this does not promise a score.
                    Reply(GleoActions.Status.SUCCESS, "곡을 마쳤어요.")
                } else {
                    Reply(GleoActions.Status.FAIL, "열려 있는 무대가 없어요. 먼저 노래 무대를 열어 주세요.")
                }
            GleoActions.START_BATTLE -> when (val outcome = startDuel()) {
                is DuelOutcome.Refused -> Reply(GleoActions.Status.FAIL, outcome.reason)
                is DuelOutcome.Asked -> when (outcome.afterThisSong) {
                    true -> Reply(GleoActions.Status.SUCCESS, "지금 곡이 끝나면 대결을 시작해요. 다음 곡은 운전석부터 불러 주세요.")
                    false -> Reply(
                        GleoActions.Status.SUCCESS,
                        "대결을 시작해요. 운전석부터 한 곡 불러 주세요.",
                        // Asked again on the driver's turn, the MC has no new turn to call.
                        mcSays = outcome.turnIsNew,
                        says = setOf(Said.GREETING, Said.DRIVER_TURN),
                    )
                    null -> Reply(GleoActions.Status.SUCCESS, "대결을 시작해요. 화면에서 차례를 확인해 주세요.")
                }
            }
            else -> Reply(GleoActions.Status.ERROR, "알 수 없는 요청이에요.")
        }
    }
}
