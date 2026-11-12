package com.carstage.app.score

import com.carstage.app.audio.AudioAnalyzer
import com.carstage.app.stage.AudioEvent
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Scores scenes made outside the repo (docs/09 "흥 점수 v2"): each 16-bit mono WAV in the folder named
 * by CARSTAGE_SCORE_CLIPS runs through [AudioAnalyzer] and [ScoreKeeper] as on the stage, and the
 * song's score and, with CARSTAGE_SCORE_FRAMES set, each frame's event (`<clip>.csv`) are written
 * beside it. Skipped without the variable, so ordinary runs never need the clips; Gradle reuses a
 * past run unless it is given `--rerun`.
 */
class ScoreClipsTest {
    @Test
    fun scoreTheClips() {
        val folder = System.getenv("CARSTAGE_SCORE_CLIPS")?.let(::File)
        assumeTrue("CARSTAGE_SCORE_CLIPS is not set", folder != null)
        // A mistyped folder must not pass as a skipped run.
        require(folder!!.isDirectory) { "CARSTAGE_SCORE_CLIPS is not a folder: $folder" }
        val frames = System.getenv("CARSTAGE_SCORE_FRAMES") != null
        val report = StringBuilder()
        folder.listFiles { file -> file.extension == "wav" }!!.sortedBy { it.name }.forEach { wav ->
            val (rate, samples) = readWav(wav)
            val keeper = ScoreKeeper(rate.toFloat() / AudioAnalyzer.ANALYSIS_SIZE)
            val rows = mutableListOf<FloatArray>()
            val analyzer = AudioAnalyzer(rate, emit = { event ->
                when (event) {
                    is AudioEvent.Frame -> rows += floatArrayOf(event.loudness, if (event.voiced) 1f else 0f, event.music, 0f)
                    // A cheer follows the frame it was heard in.
                    AudioEvent.Cheer -> rows.last()[3] = 1f
                    else -> Unit
                }
                // Each clip is one song, scored at its end, whatever gaps it has.
                if (event != AudioEvent.SongEnded) keeper.hear(event)
            })
            var at = 0
            while (at + AudioAnalyzer.ANALYSIS_SIZE <= samples.size) {
                analyzer.process(samples.copyOfRange(at, at + AudioAnalyzer.ANALYSIS_SIZE))
                at += AudioAnalyzer.ANALYSIS_SIZE
            }
            val result = keeper.hear(AudioEvent.SongEnded)
            if (frames) {
                File(wav.parentFile, wav.nameWithoutExtension + ".csv").writeText(
                    "loudness,voiced,music,cheer\n" + rows.joinToString("\n") { it.joinToString(",") } + "\n",
                )
            }
            report.append("${wav.nameWithoutExtension} ${result?.score} ${result?.participation} ${result?.heat} ${result?.response} ${result?.completion} ${analyzer.cheers}\n")
        }
        File(folder, "scores.txt").writeText(report.toString())
        println(report)
    }

    /** The rate and the samples of a 16-bit PCM WAV's first channel. */
    private fun readWav(file: File): Pair<Int, FloatArray> {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var rate = 0
        var channels = 1
        var at = 12
        while (at + 8 <= bytes.limit()) {
            val id = String(ByteArray(4) { bytes.get(at + it) })
            val size = bytes.getInt(at + 4)
            when (id) {
                "fmt " -> {
                    // PCM, plain or extensible; anything else would read as noise and a plausible score.
                    val format = bytes.getShort(at + 8).toInt() and 0xFFFF
                    val bits = bytes.getShort(at + 22).toInt()
                    require((format == 1 || format == 0xFFFE) && bits == 16) { "${file.name} is not 16-bit PCM (format $format, $bits bits)" }
                    channels = bytes.getShort(at + 10).toInt()
                    rate = bytes.getInt(at + 12)
                }
                "data" -> {
                    val count = size / 2 / channels
                    return rate to FloatArray(count) { bytes.getShort(at + 8 + it * 2 * channels) / 32768f }
                }
            }
            at += 8 + size + (size and 1)
        }
        error("${file.name} has no data")
    }
}
