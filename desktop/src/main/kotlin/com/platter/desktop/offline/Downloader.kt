package com.platter.desktop.offline

import com.platter.desktop.i18n.t
import androidx.compose.runtime.mutableStateMapOf
import com.platter.desktop.api.Song
import com.platter.desktop.api.SubsonicClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext

/** Where a download stands, for the Downloads screen. */
sealed interface Transfer {
    data object Queued : Transfer

    /** [fraction] is 0..1, or -1 where the server does not say how long the file is (a transcode). */
    data class Active(val fraction: Float) : Transfer
    data class Failed(val message: String) : Transfer
}

/**
 * Fetches songs to the disk, a couple at a time, and files them in the index.
 *
 * The file is the original (`download`) unless a bitrate cap or a format is asked for, in which case the server
 * transcodes it (`stream`) - the same choice the Android app's download quality makes. It is written beside its
 * destination as `.part` and moved into place once whole, so a closed window leaves no half song that looks like a
 * whole one. Covers are kept apart, by cover id, so the Downloads screen has pictures with no server.
 */
class Downloader(
    private val scope: CoroutineScope,
    private val store: DownloadStore,
    private val root: () -> Path,
    private val coversDir: Path,
    private val http: OkHttpClient = SubsonicClient.defaultHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Told whenever something finished, failed or went - so screens that list downloads load again. */
    private val onChange: () -> Unit = {},
) {
    /** Songs being fetched, waiting, or failed, by song id. Gone once on the disk. */
    val transfers = mutableStateMapOf<String, Transfer>()

    private val jobs = HashMap<String, Job>()
    private val sources = HashMap<String, String>()

    /** What each queued or failed song was asked for with, so a failure can be tried again as it was. */
    private class Wanted(val song: Song, val maxBitRate: Int?, val format: String?)

    private val requests = HashMap<String, Wanted>()

    /** Paths being written right now: two songs that would share a name must not both choose it before either exists. */
    private val reserved = HashSet<Path>()
    private val slots = Semaphore(PARALLEL)

    /**
     * Queues [songs] from [client]'s server for [source]. A song already on the disk is not fetched again - but if it
     * is now wanted for a stronger reason (asked for by hand, where it had only been saved while listening) it keeps
     * the stronger one, so it is not let go later.
     */
    @Synchronized
    fun enqueue(client: SubsonicClient, server: String, songs: List<Song>, source: String, maxBitRate: Int? = null, format: String? = null) {
        for (song in songs) {
            val id = song.id ?: continue
            if (!song.isMusic) continue

            if (store.find(server, id) != null && store.playablePath(server, id) != null) {
                promote(server, id, source)
                continue
            }
            if (jobs[id]?.isActive == true) {
                if (Source.rank(source) > Source.rank(sources[id] ?: Source.SMART)) sources[id] = source
                continue
            }
            sources[id] = source
            requests[id] = Wanted(song, maxBitRate, format)
            transfers[id] = Transfer.Queued
            jobs[id] = scope.launch {
                try {
                    slots.withPermit { fetch(client, server, song, maxBitRate, format) }
                } catch (e: CancellationException) {
                    transfers.remove(id)
                    throw e
                } finally {
                    synchronized(this@Downloader) {
                        jobs.remove(id)
                        // A failure keeps its source: trying again must be for the same reason.
                        if (transfers[id] !is Transfer.Failed) sources.remove(id)
                    }
                }
            }
        }
    }

    private fun promote(server: String, id: String, source: String) {
        val now = store.find(server, id) ?: return
        if (Source.rank(source) > Source.rank(now.source)) {
            store.update(server, id) { this.source = source }
            onChange()
        }
    }

    @Synchronized
    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        transfers.keys.toList().forEach { transfers.remove(it) }
        requests.clear()
    }

    /** The song a transfer is for, while it is queued, running or failed. */
    @Synchronized
    fun songFor(id: String): Song? = requests[id]?.song

    /** Asks again for every song that failed, as it was first asked for. */
    @Synchronized
    fun retryFailed(client: SubsonicClient, server: String) {
        val failed = transfers.filterValues { it is Transfer.Failed }.keys.toList()
        for (id in failed) {
            val request = requests[id] ?: continue
            val source = sources[id] ?: Source.MANUAL
            transfers.remove(id)
            enqueue(client, server, listOf(request.song), source, request.maxBitRate, request.format)
        }
    }

    @Synchronized
    fun cancel(id: String) {
        jobs[id]?.cancel()
        transfers.remove(id)
        requests.remove(id)
    }

    /** Takes a song off the disk: the file, the entry, and any folder it leaves empty. */
    fun remove(server: String, id: String) {
        val gone = store.remove(server, id) ?: return
        runCatching { Files.deleteIfExists(gone.file) }
        pruneEmptyFolders(gone.file.parent)
        onChange()
    }

    fun removeAll(server: String) {
        store.all(server).forEach { song ->
            store.remove(server, song.id)
            runCatching { Files.deleteIfExists(song.file) }
            pruneEmptyFolders(song.file.parent)
        }
        onChange()
    }

    fun coverFile(coverArtId: String): Path = coversDir.resolve(clean(coverArtId) + ".jpg")

    // --- one song ----------------------------------------------------------------------

    private suspend fun fetch(client: SubsonicClient, server: String, song: Song, maxBitRate: Int?, format: String?) {
        val id = song.id!!
        transfers[id] = Transfer.Active(0f)
        var part: Path? = null
        var claimed: Path? = null
        try {
            val transcoded = maxBitRate != null || format != null
            val extension = if (transcoded) (format ?: "mp3") else song.suffix?.takeIf { it.isNotBlank() && it.length <= 5 } ?: "mp3"
            val target = synchronized(this) { targetFor(server, song, extension).also { reserved.add(it) } }
            claimed = target
            Files.createDirectories(target.parent)
            part = target.resolveSibling(target.fileName.toString() + ".part")

            val url = if (transcoded) client.streamUrl(id, maxBitRate, format ?: "mp3") else client.downloadUrl(id)
            val bytes = withContext(Dispatchers.IO) { copy(url, part, id) }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
            part = null

            val source = synchronized(this) { sources[id] } ?: Source.MANUAL
            // The cover first: a song that is on the disk should never be one whose picture is still on its way.
            song.coverArtId?.let { runCatching { withContext(Dispatchers.IO) { ensureCover(client, it) } } }
            store.put(DownloadedSong.of(server, song, target, bytes, source, clock()))
            transfers.remove(id)
            synchronized(this) { requests.remove(id) }
            onChange()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            transfers[id] = Transfer.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            part?.let { runCatching { Files.deleteIfExists(it) } }
            claimed?.let { synchronized(this) { reserved.remove(it) } }
        }
    }

    /** Streams [url] into [to]; a Subsonic error comes back as JSON or XML with a 200, so the type is checked. */
    private suspend fun copy(url: String, to: Path, id: String): Long {
        val call = http.newCall(Request.Builder().url(url).build())
        val handle = coroutineContext.job.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException(t("The server answered %d", response.code))
                val body = response.body ?: throw IOException(t("The server sent nothing"))
                val type = body.contentType()?.toString().orEmpty().lowercase()
                if ("json" in type || "xml" in type || type.startsWith("text/")) {
                    throw IOException(serverMessage(body.string().take(2_000)) ?: t("The server refused the download"))
                }
                val total = body.contentLength()
                var done = 0L
                var lastShown = 0f
                Files.newOutputStream(to).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            val fraction = if (total > 0) done.toFloat() / total else -1f
                            // A change per percent is plenty to draw; one per buffer would redraw the screen for nothing.
                            if (fraction < 0 || fraction - lastShown >= 0.01f) {
                                transfers[id] = Transfer.Active(fraction)
                                lastShown = fraction
                            }
                        }
                    }
                }
                return done
            }
        } finally {
            handle.dispose()
        }
    }

    private fun serverMessage(body: String): String? =
        Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            ?: Regex("message=\"([^\"]+)\"").find(body)?.groupValues?.get(1)

    private fun ensureCover(client: SubsonicClient, coverArtId: String) {
        val file = coverFile(coverArtId)
        if (Files.exists(file)) return
        Files.createDirectories(coversDir)
        val request = Request.Builder().url(client.coverArtUrl(coverArtId, COVER_PX)).build()
        http.newCall(request).execute().use { response ->
            val type = response.body?.contentType()?.toString().orEmpty()
            if (!response.isSuccessful || !type.startsWith("image/")) return
            val bytes = response.body!!.bytes()
            val tmp = file.resolveSibling(file.fileName.toString() + ".part")
            Files.write(tmp, bytes)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // --- where a song goes ------------------------------------------------------------------

    /** `<folder>/<Artist>/<Album>/<NN - Title>.<ext>`; a name already taken by another song gets the song's id on it. */
    fun targetFor(server: String, song: Song, extension: String): Path {
        val dir = root().resolve(clean(song.artistLine() ?: song.artist ?: "Unknown artist")).resolve(clean(song.album ?: "Unknown album"))
        val number = song.track?.takeIf { it > 0 }?.let { n ->
            val disc = song.discNumber?.takeIf { it > 1 }?.let { "$it-" }.orEmpty()
            disc + "%02d - ".format(n)
        }.orEmpty()
        val name = number + clean(song.title ?: "Unknown title")
        val plain = dir.resolve("$name.$extension")
        val own = store.find(server, song.id.orEmpty())?.path
        return if ((Files.exists(plain) && own != plain.toString()) || plain in reserved) dir.resolve("$name [${clean(song.id.orEmpty())}].$extension") else plain
    }

    private fun pruneEmptyFolders(from: Path?) {
        var dir = from
        val top = root().toAbsolutePath().normalize()
        while (dir != null && dir.toAbsolutePath().normalize().startsWith(top) && dir.toAbsolutePath().normalize() != top) {
            val empty = runCatching { Files.list(dir).use { !it.findAny().isPresent } }.getOrDefault(false)
            if (!empty) return
            runCatching { Files.delete(dir) }
            dir = dir.parent
        }
    }

    companion object {
        const val PARALLEL = 2
        private const val COVER_PX = 600
        private const val MAX_NAME = 100

        /** A name Windows will take as a file or folder: no reserved characters, no trailing dots or spaces. */
        fun clean(name: String): String {
            val s = name.replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001f]"), "_").trim().trimEnd('.', ' ').take(MAX_NAME).trimEnd('.', ' ')
            val reserved = Regex("^(con|prn|aux|nul|com[1-9]|lpt[1-9])$", RegexOption.IGNORE_CASE)
            return when {
                s.isEmpty() -> "_"
                reserved.matches(s.substringBefore('.')) -> "_$s"
                else -> s
            }
        }
    }
}
