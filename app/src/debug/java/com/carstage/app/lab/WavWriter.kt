package com.carstage.app.lab

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes mono 16-bit PCM WAV files for inspecting lab captures on a computer. */
object WavWriter {
    fun write(file: File, samples: FloatArray, sampleRate: Int) {
        val dataSize = samples.size * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(dataSize)
        samples.forEach { buffer.putShort((it.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()) }
        file.writeBytes(buffer.array())
    }
}
