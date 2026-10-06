package com.platter.desktop.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays the server has not heard about yet - the Android app's `PendingScrobbles`.
 *
 * A play made while the server cannot be reached (a laptop on a train) used to be sent anyway, fail, and be
 * forgotten. Every play is written down here first, with the time it started, and crossed off once the server has
 * it; whatever could not be sent goes the next time a play counts, or the app starts, or a minute passes. A play
 * belongs to the server it was made against and waits for that one if the app has since been signed in to another.
 */
class PendingScrobbles(private val file: Path) {
    data class Play(val server: String?, val id: String, val time: Long)

    private val gson = Gson()
    private val flushing = AtomicBoolean(false)

    @Synchronized
    fun add(server: String?, id: String, time: Long) {
        save((load() + Play(server, id, time)).takeLast(LIMIT))
    }

    @Synchronized
    fun count(server: String?): Int = load().count { it.server == server }

    /**
     * Sends the waiting plays of [server], oldest first so the server files them in the order they were heard, and
     * stops at the first that does not get through: the rest would not either. [send] says whether the server
     * answered at all - an answer that refuses the play (a song since deleted) still counts, or it would block the
     * queue for ever.
     */
    suspend fun flush(server: String?, send: suspend (id: String, time: Long) -> Boolean) {
        if (!flushing.compareAndSet(false, true)) return
        try {
            while (true) {
                val play = synchronized(this) { load().firstOrNull { it.server == server } } ?: return
                if (!send(play.id, play.time)) return
                synchronized(this) { save(load() - play) }
            }
        } finally {
            flushing.set(false)
        }
    }

    private fun load(): List<Play> = try {
        if (Files.exists(file)) Files.newBufferedReader(file).use { gson.fromJson<List<Play>>(it, TYPE) }.orEmpty() else emptyList()
    } catch (e: Exception) {
        // A damaged file loses the waiting plays, not the app.
        emptyList()
    }

    private fun save(plays: List<Play>) {
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.newBufferedWriter(tmp).use { gson.toJson(plays, it) }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        /** Weeks of listening. Past it the oldest plays give way. */
        const val LIMIT = 1000
        val TYPE = object : TypeToken<List<Play>>() {}.type!!
    }
}
