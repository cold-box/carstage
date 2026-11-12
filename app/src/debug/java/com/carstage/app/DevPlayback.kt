package com.carstage.app

import android.media.AudioAttributes
import android.os.Process
import com.carstage.app.audio.PlaybackSource
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Development builds first try the helper that scripts/playback-tap.sh runs on the emulator, which
 * has no MediaProjection consent dialog (docs/05 "재생 소리 캡처 확인"); without it, the stage
 * falls back to the capture the user allows, as in a release build.
 */
object DevPlayback {
    fun source(): PlaybackSource = ShellTap()
}

/**
 * The music the other apps play, from the helper (tools/playbacktap) on 127.0.0.1: it captures
 * as the shell user, which may, and leaves this app's own sound out ([exclude]). The audio
 * arrives over the device's loopback only and is not kept beyond the stage's buffer.
 */
class ShellTap(
    private val usages: List<Int> = PlaybackSource.USAGES,
    private val exclude: Int? = Process.myUid(),
    private val port: Int = PORT,
) : PlaybackSource {
    override val label = "shell tap"

    @Volatile
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var bytes = ByteArray(0)

    override fun open(): PlaybackSource.Format {
        val connected = Socket()
        socket = connected
        connected.connect(InetSocketAddress("127.0.0.1", port), CONNECT_MS)
        connected.tcpNoDelay = true
        val request = "usages=${usages.joinToString(",")}" + (exclude?.let { " exclude=$it" } ?: "") + "\n"
        connected.getOutputStream().apply {
            write(request.toByteArray())
            flush()
        }
        val stream = BufferedInputStream(connected.getInputStream())
        input = stream
        val header = readLine(stream)
        val words = header.split(' ').mapNotNull { word -> word.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        check(header.startsWith("ok ")) { "the helper said: $header" }
        val rate = words["rate"]?.toIntOrNull() ?: error("no rate in \"$header\"")
        val channels = words["channels"]?.toIntOrNull() ?: error("no channels in \"$header\"")
        return PlaybackSource.Format(rate, channels)
    }

    override fun read(pcm: ShortArray): Int {
        val stream = input ?: return -1
        if (bytes.size < pcm.size * 2) bytes = ByteArray(pcm.size * 2)
        // Whole samples only: a read may end within one.
        var filled = stream.read(bytes, 0, pcm.size * 2)
        if (filled < 0) return -1
        if (filled % 2 == 1) {
            val last = stream.read()
            if (last < 0) return -1
            bytes[filled++] = last.toByte()
        }
        for (i in 0 until filled / 2) pcm[i] = ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
        return filled / 2
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
    }

    private fun readLine(stream: InputStream): String {
        val line = StringBuilder()
        while (line.length < MAX_HEADER) {
            val c = stream.read()
            if (c < 0) error("the helper closed before answering")
            if (c == '\n'.code) return line.toString()
            line.append(c.toChar())
        }
        error("the helper's answer is too long")
    }

    companion object {
        const val PORT = 47_123
        private const val CONNECT_MS = 300
        private const val MAX_HEADER = 200

        /** The usage names the Lab's probe reports. */
        fun usageName(usage: Int): String = when (usage) {
            AudioAttributes.USAGE_MEDIA -> "MEDIA"
            AudioAttributes.USAGE_GAME -> "GAME"
            AudioAttributes.USAGE_UNKNOWN -> "UNKNOWN"
            else -> "usage $usage"
        }
    }
}
