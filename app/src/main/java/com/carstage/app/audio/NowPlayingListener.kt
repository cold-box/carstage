package com.carstage.app.audio

import android.service.notification.NotificationListenerService
import com.carstage.app.CarStageApp

/**
 * Reads no notifications: once the user grants it notification access, Android lets this app read
 * other apps' media sessions ([NowPlaying]), which needs MEDIA_CONTENT_CONTROL otherwise. Android
 * connects it when the access is granted and drops it when the access is taken away, which can
 * happen while CarStage stays on the screen, so both tell [NowPlaying] to check again.
 */
class NowPlayingListener : NotificationListenerService() {
    override fun onListenerConnected() = nowPlaying().refresh()

    override fun onListenerDisconnected() = nowPlaying().refresh()

    private fun nowPlaying() = (application as CarStageApp).graph.nowPlaying
}
