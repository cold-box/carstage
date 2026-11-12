package com.carstage.app.lab

import com.carstage.app.audio.OnsetDetector
import com.carstage.app.audio.SignalAnalysis
import com.carstage.app.audio.YinPitchDetector

/** Accumulates level, voiced pitch and clap counts over a capture so Lab can log a summary. */
class SingingStats(sampleRate: Int) {
    private val pitchDetector = YinPitchDetector(sampleRate)
    private val onsets = OnsetDetector(sampleRate)
    private val pitches = mutableListOf<Float>()
    private var frames = 0

    var loudestDb = SignalAnalysis.SILENCE_DB
        private set

    var lastLevelDb = SignalAnalysis.SILENCE_DB
        private set
    var lastPitchHz: Float? = null
        private set
    var claps = 0
        private set

    fun add(frame: FloatArray) {
        frames++
        claps += onsets.process(frame)
        lastLevelDb = SignalAnalysis.rmsDbfs(frame)
        loudestDb = maxOf(loudestDb, lastLevelDb)
        lastPitchHz = if (lastLevelDb > VOICED_FLOOR_DB) pitchDetector.detect(frame) else null
        lastPitchHz?.let(pitches::add)
    }

    fun summary(): String {
        val median = pitches.sorted().getOrNull(pitches.size / 2)
        val pitch = median?.let { "%.0f Hz (%s)".format(it, SignalAnalysis.noteName(it)) } ?: "-"
        return "voiced ${pitches.size}/$frames frames, loudest frame=${"%.1f".format(loudestDb)} dBFS, " +
            "median pitch=$pitch, range=${pitches.minOrNull()?.toInt()}..${pitches.maxOrNull()?.toInt()} Hz, claps=$claps"
    }

    private companion object {
        const val VOICED_FLOOR_DB = -50f
    }
}
