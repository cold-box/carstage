package com.carstage.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoticeTest {
    @Test
    fun aNoticeIsAddedOnceWithoutHidingAnother() {
        assertEquals("배터리", withNotice(null, "배터리"))
        assertEquals("배터리", withNotice("", "배터리"))
        assertEquals("복원 실패. 배터리", withNotice("복원 실패.", "배터리"))
        assertEquals("복원 실패. 배터리", withNotice("복원 실패. 배터리", "배터리"))
    }

    @Test
    fun aNoticeIsTakenOutAndTheRestStays() {
        assertNull(withoutNotice(null, "무음"))
        assertNull("only it was shown", withoutNotice("무음", "무음"))
        assertEquals("연결 실패.", withoutNotice("무음 연결 실패.", "무음"))
        assertEquals("added meanwhile stays", "연결 실패. 공회전", withoutNotice("무음 연결 실패. 공회전", "무음"))
        assertEquals("연결 실패. 공회전", withoutNotice("연결 실패. 무음 공회전", "무음"))
        assertEquals("not there", "연결 실패.", withoutNotice("연결 실패.", "무음"))
    }

    @Test
    fun aNoticeFirstComesBeforeTheRestAndOnlyOnce() {
        assertEquals("무음", withNoticeFirst(null, "무음"))
        assertEquals("무음 연결 실패.", withNoticeFirst("연결 실패.", "무음"))
        assertEquals("again while it lasts", "무음 연결 실패.", withNoticeFirst("무음 연결 실패.", "무음"))
        assertEquals("moved to the front", "무음 연결 실패. 공회전", withNoticeFirst("연결 실패. 무음 공회전", "무음"))
    }
}
