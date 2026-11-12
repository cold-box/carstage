package com.carstage.app.home

import com.carstage.app.ResumeOffers
import com.carstage.app.stage.StageController.Phase

/**
 * The closed screen's words (docs/13): the line under its choices ([hint]) and the headline over
 * the song list ([PICK_SONG]).
 */
object StartPrompt {
    /**
     * The line under the main button while the stage is closed. While the microphone is not
     * allowed, why the stage listens and that it keeps nothing, read before the button asks
     * for it (docs/10 7장 "첫 실행과 마이크 권한"); Gleo could not open the stage then
     * either. Otherwise the way to start by voice (docs/10 2장 "조작", 7장 "음성 안내"),
     * but not while an offer to sing on is shown, which has its own button, or waits for a
     * call to end or the car to park, as Gleo's stage would be refused.
     */
    fun hint(phase: Phase, resume: ResumeOffers.Offer?, micAllowed: Boolean): String? = when {
        phase != Phase.CLOSED -> null
        !micAllowed -> MIC_NOTE
        resume == null -> GLEO_HINT
        else -> null
    }

    /** Over the song list (docs/07 "노래 목록"): a song picked there plays and opens the stage. */
    const val PICK_SONG = "부를 노래를 골라 주세요"

    /** Why the stage needs the microphone, and that what it hears is neither kept nor sent (CLAUDE.md "Product principles"). */
    const val MIC_NOTE = "노래에 반응하려고 마이크로 소리를 들어요. 소리는 저장하거나 보내지 않아요."

    /** What to say to Gleo to open the stage (assets/schema.json OPEN_STAGE; docs/05 "Gleo 음성 실행"). */
    const val GLEO_HINT = "Gleo에게 \"노래 무대 열어줘\"라고 말해 보세요"
}
