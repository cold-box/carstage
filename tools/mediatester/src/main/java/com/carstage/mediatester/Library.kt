package com.carstage.mediatester

import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The songs the app offers: the built-in test song first, then every audio file in files/music
 * sorted by name. Titles and artists come from the file's tags, falling back to the file name.
 */
class Library(private val filesDir: File) {
    /** Synchronized: the browse thread and the main thread may both scan first and write the test song. */
    @Synchronized
    fun scan(): List<Song> {
        val files = File(filesDir, MUSIC_DIR).listFiles { f -> f.isFile && f.extension.lowercase() in EXTENSIONS }
            .orEmpty()
            .sortedBy { it.name }
        return listOf(testSong()) + files.map(::song)
    }

    private fun song(file: File): Song {
        var title: String? = null
        var artist: String? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
        } catch (e: Exception) {
            // Listed anyway: the player reports the same file as unplayable with its own error.
            Log.e(TAG, "cannot read ${file.name}; it will likely not play", e)
        } finally {
            retriever.release()
        }
        return Song(
            id = "file:${file.name}",
            title = title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
            artist = artist?.takeIf { it.isNotBlank() } ?: UNKNOWN_ARTIST,
            file = file,
            loop = false,
        )
    }

    /** Eight seconds of kick drum and a four-note melody that loop without a seam. */
    private fun testSong(): Song {
        val file = File(filesDir, TEST_FILE)
        if (!file.exists()) writeTestSong(file)
        return Song(TEST_ID, TEST_TITLE, "Media Tester", file, loop = true)
    }

    private fun writeTestSong(file: File) {
        val samples = SAMPLE_RATE * TEST_SECONDS
        val data = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (n in 0 until samples) {
            val t = n.toDouble() / SAMPLE_RATE
            val beat = t % BEAT_SECONDS
            val kick = exp(-beat * 30) * sin(2 * PI * 60 * beat)
            val melody = 0.25 * sin(2 * PI * MELODY_HZ[t.toInt() % MELODY_HZ.size] * t)
            data.putShort(((0.6 * kick + melody).coerceIn(-1.0, 1.0) * 20_000).toInt().toShort())
        }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
        }
        val tmp = File(file.path + ".tmp")
        RandomAccessFile(tmp, "rw").use {
            it.setLength(0)
            it.write(header.array())
            it.write(data.array())
        }
        check(tmp.renameTo(file)) { "could not write $file" }
    }

    private companion object {
        const val TAG = "MediaTester"
        const val MUSIC_DIR = "music"
        const val TEST_FILE = "test-song.wav"
        const val TEST_ID = "test-song"
        const val TEST_TITLE = "Test Song"
        const val UNKNOWN_ARTIST = "알 수 없는 아티스트"
        val EXTENSIONS = setOf("mp3", "ogg", "oga", "aac", "m4a", "wav", "flac")
        const val SAMPLE_RATE = 44_100
        const val TEST_SECONDS = 8
        const val BEAT_SECONDS = 0.5
        val MELODY_HZ = doubleArrayOf(262.0, 330.0, 392.0, 330.0)
    }
}
