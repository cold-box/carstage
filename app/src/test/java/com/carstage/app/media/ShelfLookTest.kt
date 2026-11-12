package com.carstage.app.media

import com.carstage.app.audio.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfLookTest {
    private val tester = MediaSource("com.carstage.mediatester", "com.carstage.mediatester.LibraryService", "Media Tester")
    private val other = MediaSource("com.android.bluetooth", "x.Service", "Bluetooth")
    private val vibe = BrowseItem("file:vibe-ace.ogg", "Vibe Ace", "Kevin MacLeod", folder = false)
    private val album = BrowseItem("album:1", "Album", null, folder = true)
    private val ready = ShelfLook(tester, listOf(tester, other), emptyList(), ShelfLook.Step.READY, listOf(album, vibe))
    private val playing = Track(tester.packageName, "Vibe Ace", "Kevin MacLeod", playing = true, positionMs = 0, updatedAtMs = 0, speed = 1f, durationMs = 61_000, mediaId = vibe.id)

    @Test
    fun anItemIsAFolderWhenItCanBeBrowsedElseASong() {
        assertEquals(album, BrowseItem.of("album:1", "Album", "", browsable = true, playable = true))
        assertEquals(vibe, BrowseItem.of(vibe.id, "Vibe Ace", "Kevin MacLeod", browsable = false, playable = true))
    }

    @Test
    fun anItemNothingCanBeDoneWithIsLeftOut() {
        assertNull(BrowseItem.of("x", "Header", null, browsable = false, playable = false))
        assertNull(BrowseItem.of(null, "No id", null, browsable = false, playable = true))
        assertNull(BrowseItem.of("", "Empty id", null, browsable = true, playable = false))
    }

    @Test
    fun aSongWithoutATitleIsNamedAsSuch() {
        assertEquals(BrowseItem.UNTITLED, BrowseItem.of("x", " ", null, browsable = false, playable = true)?.title)
    }

    @Test
    fun theHeadingIsTheOpenFolderElseTheApp() {
        assertEquals("Media Tester", ready.heading)
        assertEquals("Album", ready.copy(folders = listOf(album)).heading)
        assertNull("the apps to pick from have their own heading", ready.copy(choosing = true).heading)
    }

    @Test
    fun backLeadsUpAFolderOrOutOfTheApps() {
        assertFalse(ready.canGoBack)
        assertTrue(ready.copy(folders = listOf(album)).canGoBack)
        assertEquals(emptyList<BrowseItem>(), ready.copy(folders = listOf(album)).up())
        assertTrue(ready.copy(choosing = true).canGoBack)
        assertFalse("nothing to go back to before an app is picked", ShelfLook(null, listOf(tester), emptyList(), ShelfLook.Step.NO_SOURCE, emptyList()).canGoBack)
    }

    @Test
    fun withoutASourceTheAppsAreShown() {
        assertTrue(ShelfLook(null, listOf(tester), emptyList(), ShelfLook.Step.NO_SOURCE, emptyList()).showsSources)
        assertFalse(ready.showsSources)
        assertFalse("no card when no app gives songs", ShelfLook.NONE.shown)
        assertTrue("a refusal is told on the card", ready.copy(step = ShelfLook.Step.REFUSED).shown)
    }

    @Test
    fun theSongThatPlaysIsMarkedByItsId() {
        assertTrue(ready.isCurrent(vibe, playing))
        assertTrue("paused too", ready.isCurrent(vibe, playing.copy(playing = false)))
        assertFalse(ready.isCurrent(vibe.copy(id = "file:other.ogg"), playing))
        assertFalse("never a folder", ready.isCurrent(album, playing.copy(mediaId = album.id)))
        assertFalse("another app's song", ready.isCurrent(vibe, playing.copy(app = other.packageName)))
        assertFalse(ready.isCurrent(vibe, null))
    }

    @Test
    fun withoutAnIdTheSongIsMarkedByItsTitle() {
        assertTrue(ready.isCurrent(vibe, playing.copy(mediaId = null)))
        assertFalse(ready.isCurrent(vibe, playing.copy(mediaId = null, title = "Big Rock")))
        assertFalse(ready.isCurrent(vibe, playing.copy(mediaId = null, title = null)))
    }
}
