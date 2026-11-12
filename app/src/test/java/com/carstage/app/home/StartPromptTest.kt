package com.carstage.app.home

import com.carstage.app.ResumeOffers.Offer
import com.carstage.app.ResumeOffers.Reason
import com.carstage.app.stage.StageController.Phase
import org.junit.Assert.assertEquals
import org.junit.Test

class StartPromptTest {
    private val anyOffer = Offer(Reason.CALL, ready = true)

    @Test
    fun theLineUnderTheButtonSaysWhyTheMicrophoneIsNeededUntilItIsAllowed() {
        assertEquals(StartPrompt.MIC_NOTE, StartPrompt.hint(Phase.CLOSED, resume = null, micAllowed = false))
        assertEquals("the next tap asks for it, even to sing on", StartPrompt.MIC_NOTE, StartPrompt.hint(Phase.CLOSED, anyOffer, micAllowed = false))
        assertEquals(StartPrompt.GLEO_HINT, StartPrompt.hint(Phase.CLOSED, resume = null, micAllowed = true))
        assertEquals("an offer to sing on has its own button or waits", null, StartPrompt.hint(Phase.CLOSED, anyOffer, micAllowed = true))
        assertEquals(null, StartPrompt.hint(Phase.CLOSED, Offer(Reason.DRIVE, ready = false), micAllowed = true))
        for (phase in listOf(Phase.OPENING, Phase.OPEN, Phase.CLOSING)) {
            assertEquals(null, StartPrompt.hint(phase, resume = null, micAllowed = false))
        }
    }
}
