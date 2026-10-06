package com.platter.desktop.player

import com.platter.desktop.api.Song

enum class RepeatMode { Off, All, One }

/** The list of tracks being played and where in it we are. No audio here, so it can be tested alone. */
data class PlayQueue(
    val songs: List<Song> = emptyList(),
    val index: Int = -1,
    val repeat: RepeatMode = RepeatMode.Off,
    /** Shuffle is on: [songs] are in shuffled order, and [original] is the order they came in, to go back to. */
    val shuffle: Boolean = false,
    val original: List<Song>? = null,
) {
    val current: Song? get() = songs.getOrNull(index)

    /** A new list to play. With shuffle on it is shuffled too, the song chosen first, as pressing play on a list does elsewhere. */
    fun replace(newSongs: List<Song>, start: Int = 0): PlayQueue = when {
        newSongs.isEmpty() -> copy(songs = emptyList(), index = -1, original = null)
        shuffle -> copy(songs = newSongs, index = start.coerceIn(0, newSongs.lastIndex), original = null).shuffled(keepPlayed = false)
        else -> copy(songs = newSongs, index = start.coerceIn(0, newSongs.lastIndex), original = null)
    }

    /**
     * Shuffle on: what is still to come is put in random order and the order it came in is remembered; off: that order
     * comes back, with the track now playing where it was in it and anything added meanwhile after the rest.
     */
    fun toggleShuffle(): PlayQueue = if (shuffle) unshuffled() else shuffled(keepPlayed = true)

    /** [keepPlayed]: tracks up to the current one stay where they are; otherwise the current one goes first and all the rest are mixed. */
    private fun shuffled(keepPlayed: Boolean): PlayQueue {
        if (songs.isEmpty()) return copy(shuffle = true)
        val now = current
        val kept = if (keepPlayed) songs.subList(0, index + 1) else listOfNotNull(now)
        val rest = if (keepPlayed) songs.subList(index + 1, songs.size) else songs.filterIndexed { i, _ -> i != index }
        return copy(shuffle = true, original = original ?: songs, songs = kept + rest.shuffled(), index = kept.lastIndex)
    }

    private fun unshuffled(): PlayQueue {
        val first = original ?: return copy(shuffle = false)
        val now = current
        val have = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Song, Boolean>()).apply { addAll(songs) }
        val was = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Song, Boolean>()).apply { addAll(first) }
        val restored = first.filter { it in have } + songs.filter { it !in was }
        val at = if (now == null) -1 else restored.indexOfFirst { it === now }.coerceAtLeast(0)
        return copy(shuffle = false, original = null, songs = restored, index = at)
    }

    /** Right after the current track. */
    fun playNext(song: Song): PlayQueue = insert(index + 1, song)

    /** Several tracks right after the current one, in order - an instant mix, an album, "play next" on a selection. */
    fun playNext(more: List<Song>): PlayQueue = when {
        more.isEmpty() -> this
        songs.isEmpty() -> replace(more)
        else -> copy(songs = songs.toMutableList().apply { addAll((index + 1).coerceIn(0, size), more) })
    }

    fun enqueue(song: Song): PlayQueue = insert(songs.size, song)

    /** Several tracks at the end - what the wave does when it tops itself up. */
    fun append(more: List<Song>): PlayQueue = if (songs.isEmpty()) replace(more) else copy(songs = songs + more)

    private fun insert(at: Int, song: Song): PlayQueue {
        if (songs.isEmpty()) return replace(listOf(song))
        val i = at.coerceIn(0, songs.size)
        return copy(songs = songs.toMutableList().apply { add(i, song) })
    }

    /** Removing a track before the current one shifts the index so the same track keeps playing. */
    fun removeAt(i: Int): PlayQueue {
        if (i !in songs.indices) return this
        val rest = songs.toMutableList().apply { removeAt(i) }
        val newIndex = when {
            rest.isEmpty() -> -1
            i < index -> index - 1
            else -> index.coerceAtMost(rest.lastIndex)
        }
        return copy(songs = rest, index = newIndex)
    }

    fun jumpTo(i: Int): PlayQueue = if (i in songs.indices) copy(index = i) else this

    /**
     * Where to go after the current track. [auto] is a track running out on its
     * own, where repeat-one stays put; pressing "next" always moves on.
     * Null means the queue is finished.
     */
    fun after(auto: Boolean): Int? {
        if (songs.isEmpty()) return null
        if (auto && repeat == RepeatMode.One) return index
        val next = index + 1
        return when {
            next <= songs.lastIndex -> next
            repeat == RepeatMode.All -> 0
            else -> null
        }
    }

    fun cycleRepeat(): PlayQueue =
        copy(repeat = RepeatMode.entries[(repeat.ordinal + 1) % RepeatMode.entries.size])
}
