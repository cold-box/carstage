package com.carstage.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSourceTest {
    private val own = "com.carstage.app"
    private val tester = MediaSource("com.carstage.mediatester", "com.carstage.mediatester.LibraryService", "Media Tester")
    private val bluetooth = MediaSource("com.android.bluetooth", "com.android.bluetooth.avrcpcontroller.BluetoothMediaBrowserService", "Bluetooth")
    private val vlc = MediaSource("org.videolan.vlc", "org.videolan.vlc.PlaybackService", "VLC")
    private val sources = listOf(tester, bluetooth, vlc)

    @Test
    fun theAppThatPlaysComesFirst() {
        assertEquals(vlc, MediaSource.choose(sources, playing = "org.videolan.vlc", remembered = "com.android.bluetooth", own = own))
    }

    @Test
    fun withNothingPlayingTheAppPickedLastIsShown() {
        assertEquals(bluetooth, MediaSource.choose(sources, playing = null, remembered = "com.android.bluetooth", own = own))
    }

    @Test
    fun aPlayingAppWithoutABrowserServiceFallsBackToTheAppPickedLast() {
        assertEquals(tester, MediaSource.choose(sources, playing = "com.example.radio", remembered = "com.carstage.mediatester", own = own))
    }

    @Test
    fun withNothingToFollowTheUserPicks() {
        assertNull(MediaSource.choose(sources, playing = null, remembered = null, own = own))
        assertNull("an app picked last that is gone", MediaSource.choose(sources, playing = null, remembered = "com.removed", own = own))
    }

    @Test
    fun carStageItselfIsNeverASource() {
        val self = MediaSource(own, "com.carstage.app.Some", "CarStage")
        assertNull(MediaSource.choose(listOf(self), playing = own, remembered = own, own = own))
        assertEquals(listOf(bluetooth), MediaSource.pickable(listOf(self, bluetooth), own))
    }

    @Test
    fun theAppsToPickFromAreOnePerAppByName() {
        val second = tester.copy(service = "com.carstage.mediatester.Other")
        assertEquals(listOf(bluetooth, tester, vlc), MediaSource.pickable(listOf(vlc, tester, second, bluetooth), own))
    }
}
