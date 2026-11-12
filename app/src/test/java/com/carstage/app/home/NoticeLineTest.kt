package com.carstage.app.home

import com.carstage.app.NoticeTone
import org.junit.Assert.assertEquals
import org.junit.Test

class NoticeLineTest {
    @Test
    fun onlyWhatTheAppDidFoldsAsCautionsAndErrorsSayWhatToDo() {
        assertEquals(
            mapOf(NoticeTone.INFO to true, NoticeTone.CAUTION to false, NoticeTone.ERROR to false),
            NoticeTone.entries.associateWith(::folds),
        )
    }
}
