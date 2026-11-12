package com.carstage.app.media

/**
 * An app in the car that lets other apps browse its songs: its MediaBrowserService, as every music
 * app for Pleos has one (the Pleos Music/Audio guide), the car's Bluetooth app included.
 */
data class MediaSource(val packageName: String, val service: String, val label: String) {
    companion object {
        /**
         * Which source the song list shows (docs/07 "노래 목록"): the app that plays now, as the
         * player follows its session, when it has a browser service; else the one picked last while
         * it is still in the car; else none, and the user picks among [sources]. Never CarStage's [own].
         */
        fun choose(sources: List<MediaSource>, playing: String?, remembered: String?, own: String): MediaSource? {
            val others = sources.filter { it.packageName != own }
            return others.firstOrNull { it.packageName == playing } ?: others.firstOrNull { it.packageName == remembered }
        }

        /** The apps to pick from: every source but CarStage's [own], one per app, by name. */
        fun pickable(sources: List<MediaSource>, own: String): List<MediaSource> =
            sources.filter { it.packageName != own }.distinctBy { it.packageName }.sortedBy { it.label }
    }
}
