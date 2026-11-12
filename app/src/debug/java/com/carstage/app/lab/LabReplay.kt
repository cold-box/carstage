package com.carstage.app.lab

import com.carstage.app.AppGraph
import com.carstage.app.OpenOutcome
import java.io.File
import kotlinx.coroutines.delay

/**
 * Lab: plays [file] through the product's stage in place of the microphone (analysis, director,
 * simulator and car), then closes the stage. The microphone's "off" log line sums up what the
 * analysis heard.
 */
suspend fun AppGraph.replay(file: File, log: (String) -> Unit) {
    val replay = try {
        WavReplay.open(file)
    } catch (e: Exception) {
        log("replay: cannot read ${file.name}: ${e::class.java.simpleName}: ${e.message}")
        return
    }
    closeStage("Lab replay")
    mic.nextInput = replay
    var opened = false
    try {
        when (val outcome = openStage()) {
            is OpenOutcome.Refused -> {
                log("replay: stage refused: ${outcome.reason}")
                return
            }
            is OpenOutcome.Opened -> {
                if (outcome.result.alreadyOpen) {
                    log("replay: a stage was already open, so the recording would not be heard")
                    return
                }
                log("replay: playing ${file.name} (${replay.durationMs} ms)")
            }
        }
        opened = true
        delay(replay.durationMs + 500)
    } finally {
        // The microphone takes the recording when it starts, after the stage opens. If it never
        // did (refused, failed or cancelled), a later real open must not get it.
        if (mic.nextInput === replay) mic.nextInput = null
        if (opened) {
            if (mic.isListeningTo(replay)) {
                closeStage("Lab replay finished")
            } else {
                // Leave alone a stage someone opened since; the CarStage log says why ours ended.
                log("replay: the stage closed before the recording ended (call, safety, background or a replay failure; see the CarStage log)")
            }
        }
    }
}
