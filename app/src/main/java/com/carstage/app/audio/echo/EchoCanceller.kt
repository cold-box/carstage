package com.carstage.app.audio.echo

/**
 * Removes from the microphone what the music the car plays put there through the cabin, leaving
 * the voices. Mono floats in -1..1 at [sampleRate]. [reference] is the music as it was played
 * (captured from the other app), time-aligned only roughly: the canceller finds and follows the
 * delay itself (up to [MAX_DELAY_MS]). Called on the audio thread, one block at a time; blocks
 * may be any length. Not thread-safe.
 */
interface EchoCanceller {
    val sampleRate: Int
    fun process(mic: FloatArray, reference: FloatArray, out: FloatArray, length: Int = mic.size)
    fun reset()

    companion object {
        const val MAX_DELAY_MS = 500
    }
}
