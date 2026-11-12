package com.carstage.app.audio

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.carstage.app.AppGraph
import androidx.core.content.IntentCompat
import com.carstage.app.CarStageApp

/**
 * Holds the foreground service Android requires while an app captures what others play
 * (type mediaProjection; Android 14 hands out the projection only after it started), for as long
 * as one stage uses it. Its notification is the quietest kind and only says what the app does.
 */
class PlaybackCaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val projection = (application as CarStageApp).graph.projection
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val started = try {
            if (data == null) error("no consent in the intent")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.w(AppGraph.TAG, "playback capture: could not start the projection", e)
            null
        }
        projection.ready(started)
        if (started == null) stopSelf()
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "무대 음악 듣기", NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("노래 무대")
            .setContentText("목소리만 듣도록 차에서 나오는 음악을 빼고 있어요.")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "stage-playback"
        private const val NOTIFICATION_ID = 7
        private const val EXTRA_RESULT = "result"
        private const val EXTRA_DATA = "data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, PlaybackCaptureService::class.java).putExtra(EXTRA_RESULT, resultCode).putExtra(EXTRA_DATA, data),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackCaptureService::class.java))
        }
    }
}
