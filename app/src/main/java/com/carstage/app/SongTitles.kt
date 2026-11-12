package com.carstage.app

/**
 * Which song the music app played when, so a song's 흥 점수 can carry the title of the song sung:
 * the one that played over most of it. It does not matter whether the gap or the app's move to the
 * next song ended it first. Fed on the main thread, read on the audio thread.
 */
class SongTitles(private val keepMs: Long = KEEP_MS) {
    /** From when each title played (elapsed realtime); a null title is no song playing. */
    private val heard = ArrayDeque<Pair<Long, String?>>()

    /** From [atMs] on, [title] plays, or none when null. */
    @Synchronized
    fun heard(title: String?, atMs: Long) {
        if (heard.lastOrNull()?.second == title) return
        heard.addLast(atMs to title)
        while (heard.size > 1 && heard[1].first < atMs - keepMs) heard.removeFirst()
    }

    /** The title that played over at least half of the [seconds] up to [endMs]; null when none did. */
    @Synchronized
    fun titleOf(endMs: Long, seconds: Int): String? {
        val length = seconds * 1_000L
        if (length <= 0) return null
        val start = endMs - length
        val spans = mutableMapOf<String, Long>()
        heard.forEachIndexed { i, (since, title) ->
            val until = heard.getOrNull(i + 1)?.first ?: endMs
            val overlap = minOf(until, endMs) - maxOf(since, start)
            if (title != null && overlap > 0) spans.merge(title, overlap, Long::plus)
        }
        return spans.maxByOrNull { it.value }?.takeIf { it.value * 2 >= length }?.key
    }

    private companion object {
        /** Longer than any song sung, so the one just ended is always covered. */
        const val KEEP_MS = 20 * 60_000L
    }
}
