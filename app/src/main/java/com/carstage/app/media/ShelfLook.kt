package com.carstage.app.media

import com.carstage.app.audio.Track

/** A song or a folder in a music app's list, as its browser service gives it. */
data class BrowseItem(val id: String, val title: String, val subtitle: String?, val folder: Boolean) {
    companion object {
        const val UNTITLED = "제목 없는 곡"

        /**
         * An item from what the service sent: a folder when it can be browsed (even if it can be
         * played too, as opening it shows what it holds), else a song when it can be played. Null
         * for an item without an id or that is neither, which nothing could be done with.
         */
        fun of(id: String?, title: CharSequence?, subtitle: CharSequence?, browsable: Boolean, playable: Boolean): BrowseItem? {
            if (id.isNullOrEmpty() || (!browsable && !playable)) return null
            return BrowseItem(
                id = id,
                title = title?.toString()?.takeIf { it.isNotBlank() } ?: UNTITLED,
                subtitle = subtitle?.toString()?.takeIf { it.isNotBlank() },
                folder = browsable,
            )
        }
    }
}

/**
 * What the song list card shows (docs/10 4장 "노래 목록"): the source app, the folders opened
 * below its root (the last one is shown), where loading stands and the items, and while
 * [choosing] (or with no source yet) the apps to pick from instead.
 */
data class ShelfLook(
    val source: MediaSource?,
    val sources: List<MediaSource>,
    val folders: List<BrowseItem>,
    val step: Step,
    val items: List<BrowseItem>,
    val choosing: Boolean = false,
) {
    enum class Step {
        /** No app in the car lets others browse its songs. */
        NO_SOURCES,

        /** No source picked yet: the user picks one. */
        NO_SOURCE,
        CONNECTING,
        LOADING,
        READY,
        EMPTY,

        /** The app refused CarStage (an app that lets only the apps it knows browse). */
        REFUSED,

        /** The app's service went away (it crashed or was stopped); it is connected again. */
        LOST,

        /** The app failed to give the folder's items. */
        FAILED,
    }

    /** Whether the card lists the apps instead of songs. */
    val showsSources: Boolean get() = choosing || source == null

    /** Whether back leads somewhere: up a folder, or out of the apps to the songs. */
    val canGoBack: Boolean get() = if (showsSources) source != null && choosing else folders.isNotEmpty()

    /** The card's heading: the open folder, else the app's name. */
    val heading: String? get() = if (showsSources) null else folders.lastOrNull()?.title ?: source?.label

    /** Whether the list is worth a card at all: some app could give songs. */
    val shown: Boolean get() = step != Step.NO_SOURCES

    /** Whether the list offers songs to pick (or an app to pick them from), so the screen asks to pick one. */
    val offersSongs: Boolean
        get() = showsSources || step == Step.CONNECTING || step == Step.LOADING || step == Step.READY

    /** Whether trying the app again may help: its list failed or came empty. */
    val retries: Boolean get() = !showsSources && (step == Step.FAILED || step == Step.EMPTY)

    /** The folders after going back one. */
    fun up(): List<BrowseItem> = folders.dropLast(1)

    /**
     * Whether [item] is the song [track] plays or paused: the same app and the same id, or the same
     * title when the app's session gives no id.
     */
    fun isCurrent(item: BrowseItem, track: Track?): Boolean {
        if (track == null || item.folder || track.app != source?.packageName) return false
        return track.mediaId?.let { it == item.id } ?: (track.title != null && track.title == item.title)
    }

    companion object {
        val NONE = ShelfLook(source = null, sources = emptyList(), folders = emptyList(), step = Step.NO_SOURCES, items = emptyList())
    }
}
