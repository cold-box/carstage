package com.carstage.app.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoWindowTest {
    private val echo = EchoWindow()

    @Test
    fun aCueIsHeardWhileItPlaysAndShortlyAfter() {
        assertFalse("nothing played yet", echo.echoing(0))
        echo.started(now = 1_000, lengthMs = 4_100)
        assertTrue(echo.echoing(1_000))
        assertTrue("the cabin's echo after the end", echo.echoing(5_100 + EchoWindow.ECHO_MS - 1))
        assertFalse(echo.echoing(5_100 + EchoWindow.ECHO_MS))
    }

    @Test
    fun aCueStoppedEarlyIsHeardOnlyForItsEcho() {
        echo.started(now = 0, lengthMs = 4_000)
        echo.cut(now = 1_000)
        assertTrue(echo.echoing(1_000 + EchoWindow.ECHO_MS - 1))
        assertFalse(echo.echoing(1_000 + EchoWindow.ECHO_MS))
    }

    @Test
    fun cuttingAfterTheEndDoesNotLengthenIt() {
        echo.started(now = 0, lengthMs = 1_000)
        echo.cut(now = 5_000)
        assertFalse(echo.echoing(1_000 + EchoWindow.ECHO_MS))
    }

    @Test
    fun focusIsHeldForTheLongestPartThatStarted() {
        assertTrue(EffectPlayer.holdMs(listOf(1_740L, 4_100L)) == 4_100L + EffectPlayer.END_MARGIN_MS)
        assertTrue("the applause failed: only the fanfare is held", EffectPlayer.holdMs(listOf(1_740L)) == 1_740L + EffectPlayer.END_MARGIN_MS)
    }
}
