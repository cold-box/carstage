package com.carstage.app.audio

import com.carstage.app.DevLab

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The capture of what the other apps play that the user allowed for a stage (Android's
 * MediaProjection): the first screen asks with [askIntent] when a tap opens the stage, hands the
 * answer to [answered], and [PlaybackCaptureService] turns it into a [MediaProjection] (Android 14
 * starts one only from a foreground service of type mediaProjection). The tap takes it with
 * [candidate] and lets it go with [release] when the stage ends. Thread-safe.
 */
class PlaybackProjection(private val context: Context, private val log: (String) -> Unit) {
    val consent = PlaybackConsent()
    private val manager = context.getSystemService(MediaProjectionManager::class.java)

    @Volatile
    private var projection: MediaProjection? = null

    @Volatile
    private var arrived = CountDownLatch(1)

    /** The consent to ask for before a tap opens the stage, or null when it should not ask. */
    fun askIntent(): Intent? {
        // Release builds do not ask: the capture needs a foreground service whose notification the
        // product does not show until Pleos allows notifications, the consent reads as screen
        // recording on every stage, and the canceller is not yet shown to help in a car (docs/05).
        // Development builds ask, and declare the service (src/debug manifest).
        if (!DevLab.AVAILABLE) return null
        if (!consent.shouldAsk() || manager == null) return null
        consent.asking()
        return manager.createScreenCaptureIntent()
    }

    /** The car has no consent dialog: the stage goes on without, and does not ask again this run. */
    fun unavailable(why: String) {
        log("playback capture: no consent dialog ($why)")
        consent.unavailable()
    }

    fun answered(resultCode: Int, data: Intent?) {
        val granted = resultCode == Activity.RESULT_OK && data != null
        log("playback capture: consent ${if (granted) "given" else "refused (result $resultCode)"}")
        consent.answered(granted)
        if (granted && data != null) {
            arrived = CountDownLatch(1)
            PlaybackCaptureService.start(context, resultCode, data)
        }
    }

    /** From the service: the projection the consent gave, or null when it could not be had. */
    internal fun ready(started: MediaProjection?) {
        projection = started
        if (started == null) consent.unavailable()
        started?.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    if (projection === started) projection = null
                }
            },
            Handler(Looper.getMainLooper()),
        )
        arrived.countDown()
    }

    /** For the tap's thread: the capture to use, waiting a little for a consent that was just given. */
    fun candidate(): PlaybackTap.Candidate {
        if (consent.state == PlaybackConsent.State.GRANTED) arrived.await(PROJECTION_WAIT_MS, TimeUnit.MILLISECONDS)
        val running = projection
        return if (running != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PlaybackTap.Candidate.Source(ProjectionPlayback(running))
        } else {
            PlaybackTap.Candidate.Missing(consent.missing())
        }
    }

    /** The stage ended: the consent was for it only. */
    fun release() {
        projection?.let {
            projection = null
            it.stop()
        }
        if (consent.state == PlaybackConsent.State.GRANTED) PlaybackCaptureService.stop(context)
        consent.used()
    }

    companion object {
        /** The service starts within a second or so of the answer. */
        private const val PROJECTION_WAIT_MS = 3_000L
    }
}
