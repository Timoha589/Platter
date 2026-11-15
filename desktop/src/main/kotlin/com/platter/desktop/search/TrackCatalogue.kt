package com.platter.desktop.search

import com.platter.desktop.api.Song
import com.platter.desktop.api.SubsonicClient
import com.platter.search.TrackChanges
import com.platter.search.TrackIndex
import com.platter.search.TrackSnapshot
import com.platter.search.TrackStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.io.IOException

internal class TrackCatalogue(private val scope: CoroutineScope, directory: Path, server: String, user: String) {
    private val snapshot = TrackSnapshot(Song::class.java, Song::id, Song::title, Song::artistLine, Song::artist, Song::album)
    private val store = TrackStore(directory.resolve(TrackSnapshot.filename(server, user)).toFile(), snapshot) { source, destination ->
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    private var job: Job? = null
    @Volatile private var refreshAfter = 0L

    suspend fun find(query: String, allowCorrections: Boolean): List<TrackIndex.Match<Song>> {
        if (!store.ready()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                store.find(query, if (allowCorrections) EditDistance::within else null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                store.invalidate()
                refreshAfter = 0
                emptyList()
            }
        }
    }

    fun refresh(client: SubsonicClient, onChanged: () -> Unit) {
        if (job?.isActive == true || System.currentTimeMillis() < refreshAfter) return
        job = scope.launch {
            if (!store.ready() && store.exists()) {
                if (withContext(Dispatchers.IO) { runCatching { store.load() }.isSuccess }) onChanged()
            }

            val (changed, nextCheck) = try {
                val refreshContext = currentCoroutineContext()
                withContext(Dispatchers.IO) {
                    synchronize(client) { withContext(refreshContext) { onChanged() } }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                refreshAfter = System.currentTimeMillis() + TrackSnapshot.RETRY_AFTER_MS
                return@launch
            }

            refreshAfter = nextCheck
            if (changed) onChanged()
        }
    }

    private suspend fun synchronize(client: SubsonicClient, onDiscard: suspend () -> Unit): Pair<Boolean, Long> {
        var cursor = store.cursor()
        val page = client.trackChanges(cursor)
        if (page == null) {
            val now = System.currentTimeMillis()
            val staleAt = store.modifiedAt() + TrackSnapshot.MAX_AGE_MS
            if (store.ready() && now < staleAt) return false to minOf(staleAt, now + TrackStore.DISCOVERY_POLL_MS)
            rebuild(client, null, onDiscard)
            return true to (System.currentTimeMillis() + TrackStore.DISCOVERY_POLL_MS)
        }

        var changed = false
        var current: TrackChanges = page
        while (true) {
            current.validate(cursor)
            if (cursor == null || current.reset) {
                discardIfRequired(current, onDiscard)
                val checkpoint = if (cursor == null) current else requireChanges(client.trackChanges(null))
                rebuild(client, checkpoint.unchangedCheckpoint(null), onDiscard)
                changed = true
                break
            }
            changed = store.apply(current) || changed
            if (!current.hasMore) break
            cursor = current.cursor
            current = requireChanges(client.trackChanges(cursor))
        }
        return changed to (System.currentTimeMillis() + TrackStore.JOURNAL_POLL_MS)
    }

    private suspend fun rebuild(client: SubsonicClient, checkpoint: String?, onDiscard: suspend () -> Unit) {
        val temporary = store.temporary()
        try {
            val fresh = store.writer(temporary).use { writer ->
                do {
                    currentCoroutineContext().ensureActive()
                } while (writer.add(client.searchSongs("", TrackSnapshot.PAGE_SIZE, writer.offset())))
                writer.finish()
            }
            val confirmed = checkpoint?.let { verifyCheckpoint(client, it, onDiscard) }
            currentCoroutineContext().ensureActive()
            store.commitBaseline(temporary, fresh, confirmed)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private suspend fun verifyCheckpoint(client: SubsonicClient, checkpoint: String, onDiscard: suspend () -> Unit): String {
        // Asking without a cursor forces the journal to observe the library again.
        val head = requireChanges(client.trackChanges(null)).unchangedCheckpoint(null)
        if (head == checkpoint) return head
        val changes = requireChanges(client.trackChanges(checkpoint))
        changes.validate(checkpoint)
        discardIfRequired(changes, onDiscard)
        val confirmed = changes.unchangedCheckpoint(checkpoint)
        if (confirmed == checkpoint) throw IOException("Track journal did not reach the observed checkpoint")
        return confirmed
    }

    private suspend fun discardIfRequired(page: TrackChanges, onDiscard: suspend () -> Unit) {
        if (!page.discardsBaseline()) return
        try {
            store.discardBaseline()
        } finally {
            onDiscard()
        }
    }

    private fun requireChanges(page: TrackChanges?): TrackChanges = page ?: throw IOException("Track journal disappeared during sync")

    fun cancel() = job?.cancel()
}
