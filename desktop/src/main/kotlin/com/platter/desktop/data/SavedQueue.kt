package com.platter.desktop.data

import com.google.gson.Gson
import com.platter.desktop.api.Song
import com.platter.desktop.player.PlayQueue
import com.platter.desktop.player.RepeatMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.IdentityHashMap

/**
 * The play queue as the listener left it: the songs, the one they were on, how far in, and the shuffle and repeat
 * switches. Written beside the settings while the app runs and read back on the next start, so closing the window does
 * not empty the queue. It belongs to one signed-in account: another one does not get to see it.
 */
class SavedQueue(private val file: Path) {
    /** A song with the two things the server's own fields do not carry (see [Song.kind]), which Gson would leave out. */
    private class Entry(var song: Song? = null, var kind: String? = null, var streamUrl: String? = null)

    private class Snapshot(
        var owner: String? = null,
        var songs: List<Entry> = emptyList(),
        var index: Int = -1,
        var positionMs: Long = 0,
        var repeat: String = RepeatMode.Off.name,
        var shuffle: Boolean = false,
        /** With shuffle on: where each song of the order they came in sits in [songs]. */
        var original: List<Int>? = null,
    )

    class Restored(val queue: PlayQueue, val positionMs: Long)

    private val gson = Gson()

    @Synchronized
    fun write(owner: String, queue: PlayQueue, positionMs: Long) {
        if (queue.songs.isEmpty() || queue.index !in queue.songs.indices) {
            clear()
            return
        }
        val at = IdentityHashMap<Song, Int>().apply { queue.songs.forEachIndexed { i, s -> put(s, i) } }
        val snapshot = Snapshot(
            owner = owner,
            songs = queue.songs.map { Entry(it, it.kind, it.streamUrl) },
            index = queue.index,
            positionMs = positionMs.coerceAtLeast(0),
            repeat = queue.repeat.name,
            shuffle = queue.shuffle,
            original = queue.original?.mapNotNull { at[it] },
        )
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.newBufferedWriter(tmp).use { gson.toJson(snapshot, it) }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
    }

    /** The queue left by [owner], or null when there is none, it is somebody else's, or the file is damaged. */
    @Synchronized
    fun read(owner: String): Restored? = try {
        val snapshot = if (Files.exists(file)) Files.newBufferedReader(file).use { gson.fromJson(it, Snapshot::class.java) } else null
        if (snapshot == null || snapshot.owner != owner) {
            null
        } else {
            val songs = snapshot.songs.mapNotNull { e -> e.song?.also { it.kind = e.kind; it.streamUrl = e.streamUrl } }
            if (songs.size != snapshot.songs.size || snapshot.index !in songs.indices) {
                null
            } else {
                val repeat = RepeatMode.entries.firstOrNull { it.name == snapshot.repeat } ?: RepeatMode.Off
                val original = if (snapshot.shuffle) snapshot.original?.mapNotNull { songs.getOrNull(it) } else null
                Restored(PlayQueue(songs, snapshot.index, repeat, snapshot.shuffle, original), snapshot.positionMs)
            }
        }
    } catch (e: Exception) {
        // A damaged file costs the queue, not the start of the app.
        null
    }

    @Synchronized
    fun clear() {
        runCatching { Files.deleteIfExists(file) }
    }
}
