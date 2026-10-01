package com.cappielloantonio.tempo.subsonic.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import java.util.*

@Keep
class PlayQueue {
    @SerializedName("entry")
    var entries: List<Child>? = null
    var current: String? = null

    /*
     * indexBasedQueue extension. `current` is a song id, so a queue holding the
     * same track twice cannot say which copy is playing - the client has to
     * guess, and always guessed the first one. `currentIndex` is the position
     * itself and is unambiguous.
     */
    var currentIndex: Int? = null
    var position: Long? = null
    var username: String? = null
    var changed: Date? = null
    var changedBy: String? = null

    /** The queue as a list that is safe to iterate; empty when the server sent none. */
    fun entryList(): List<Child> = entries ?: emptyList()

    /**
     * Where playback should resume, or -1 when it cannot be worked out.
     *
     * Prefers the index the server sent and falls back to matching on the
     * current song id, which is all a server without the extension gives.
     */
    fun resolvedIndex(): Int {
        val queue = entryList()
        if (queue.isEmpty()) return -1

        currentIndex?.let { index ->
            if (index in queue.indices) return index
        }

        val currentId = current ?: return -1

        return queue.indexOfFirst { currentId == it.id }
    }
}
