package com.carstage.mediatester

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * Starts or stops [LibraryService] and finishes at once; the translucent theme keeps the app
 * under test on screen: `adb shell am start -n com.carstage.mediatester/.TesterActivity --es cmd play`.
 * Only `play` and `stop` are accepted; a launcher tap (no extra) plays. `play` resumes the current
 * song or starts the test song; `--es song <title>` plays that song from the library instead.
 */
class TesterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = Intent(this, LibraryService::class.java)
        when (val cmd = intent.getStringExtra("cmd") ?: "play") {
            "play" -> startForegroundService(
                service.setAction(LibraryService.ACTION_PLAY)
                    .putExtra(LibraryService.EXTRA_SONG, intent.getStringExtra(LibraryService.EXTRA_SONG))
            )
            "stop" -> startService(service.setAction(LibraryService.ACTION_STOP))
            else -> {
                Log.e(TAG, "unknown cmd \"$cmd\" (expected play or stop)")
                Toast.makeText(this, "unknown cmd: $cmd", Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }

    private companion object {
        const val TAG = "MediaTester"
    }
}
