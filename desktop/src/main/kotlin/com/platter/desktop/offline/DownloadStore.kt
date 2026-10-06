package com.platter.desktop.offline

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.platter.desktop.api.Song
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Why a song is on the disk: the listener asked, it was saved while listening, or it is liked. */
object Source {
    const val MANUAL = "manual"
    const val LIKED = "liked"
    const val SMART = "smart"

    /** A song asked for by hand is never let go; one kept for a like goes with the like; one saved by listening goes first. */
    fun rank(source: String): Int = when (source) {
        MANUAL -> 2
        LIKED -> 1
        else -> 0
    }
}

/**
 * A song saved for offline play, with what is needed to show and play it with no server: the tags, and where the
 * file is. Written as a whole to the index, so the Downloads screen works on a train.
 */
class DownloadedSong {
    var server: String = ""
    var id: String = ""
    var title: String? = null
    var artist: String? = null
    var displayArtist: String? = null
    var album: String? = null
    var albumId: String? = null
    var artistId: String? = null
    var track: Int? = null
    var discNumber: Int? = null
    var year: Int? = null
    var genre: String? = null
    var duration: Int? = null
    var coverArtId: String? = null
    var suffix: String? = null
    var bitrate: Int? = null

    /** The file's size on disk. */
    var bytes: Long = 0
    var path: String = ""
    var source: String = Source.MANUAL
    var downloadedAt: Long = 0
    var lastPlayedAt: Long = 0

    fun toSong(): Song = Song().also {
        it.id = id
        it.title = title
        it.artist = artist
        it.displayArtist = displayArtist
        it.album = album
        it.albumId = albumId
        it.artistId = artistId
        it.track = track
        it.discNumber = discNumber
        it.year = year
        it.genre = genre
        it.duration = duration
        it.coverArtId = coverArtId
        it.suffix = suffix
        it.bitrate = bitrate
        it.size = bytes
    }

    val file: Path get() = Path.of(path)

    companion object {
        fun of(server: String, song: Song, path: Path, bytes: Long, source: String, now: Long) = DownloadedSong().also {
            it.server = server
            it.id = song.id.orEmpty()
            it.title = song.title
            it.artist = song.artist
            it.displayArtist = song.displayArtist
            it.album = song.album
            it.albumId = song.albumId
            it.artistId = song.artistId
            it.track = song.track
            it.discNumber = song.discNumber
            it.year = song.year
            it.genre = song.genre
            it.duration = song.duration
            it.coverArtId = song.coverArtId
            it.suffix = path.fileName.toString().substringAfterLast('.', "")
            it.bitrate = song.bitrate
            it.bytes = bytes
            it.path = path.toString()
            it.source = source
            it.downloadedAt = now
            it.lastPlayedAt = now
        }
    }
}

/**
 * The index of what is on the disk, kept in one JSON file beside the settings. A song belongs to the server it came
 * from, since ids are only meaningful there.
 *
 * Whether the file is still there is asked every time it is needed: the listener may have cleaned the folder.
 */
class DownloadStore(private val file: Path) {
    private val gson = Gson()
    private val songs = LinkedHashMap<String, DownloadedSong>()

    init {
        load().forEach { songs[key(it.server, it.id)] = it }
    }

    private fun key(server: String, id: String) = "$server\u0000$id"

    @Synchronized
    fun find(server: String, id: String): DownloadedSong? = songs[key(server, id)]

    /** The file of a downloaded song, or null when it was never downloaded or has since gone from the disk. */
    @Synchronized
    fun playablePath(server: String, id: String): Path? =
        songs[key(server, id)]?.file?.takeIf { Files.isRegularFile(it) }

    @Synchronized
    fun all(server: String): List<DownloadedSong> = songs.values.filter { it.server == server }

    @Synchronized
    fun totalBytes(server: String): Long = songs.values.filter { it.server == server }.sumOf { it.bytes }

    @Synchronized
    fun put(song: DownloadedSong) {
        songs[key(song.server, song.id)] = song
        save()
    }

    @Synchronized
    fun remove(server: String, id: String): DownloadedSong? = songs.remove(key(server, id))?.also { save() }

    @Synchronized
    fun update(server: String, id: String, change: DownloadedSong.() -> Unit) {
        songs[key(server, id)]?.let { it.change(); save() }
    }

    /** Takes in what was found on the disk but is not in the index - a song downloaded, then the index lost. */
    @Synchronized
    fun forget(server: String) {
        songs.values.removeAll { it.server == server }
        save()
    }

    private fun load(): List<DownloadedSong> = try {
        if (Files.exists(file)) Files.newBufferedReader(file).use { gson.fromJson<List<DownloadedSong>>(it, TYPE) }.orEmpty() else emptyList()
    } catch (e: Exception) {
        // A damaged index loses the list, not the app; the files are still on the disk.
        emptyList()
    }

    private fun save() {
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.newBufferedWriter(tmp).use { gson.toJson(songs.values.toList(), it) }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        val TYPE = object : TypeToken<List<DownloadedSong>>() {}.type!!
    }
}
