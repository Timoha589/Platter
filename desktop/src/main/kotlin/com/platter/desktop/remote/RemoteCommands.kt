package com.platter.desktop.remote

import com.platter.desktop.AppController
import com.platter.desktop.api.Album
import com.platter.desktop.api.Artist
import com.platter.desktop.api.Playlist
import com.platter.desktop.api.Song
import com.platter.desktop.api.SubsonicClient
import com.platter.desktop.player.RepeatMode
import com.platter.desktop.search.RankedResults
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What [RemoteControl] can be asked. Each command does what the matching button does - the same queue, the same
 * likes, the same toasts - so the window shows what the remote did. Runs on the app's own thread.
 *
 * Songs, albums, artists and playlists are named by the ids `search` hands out. Queue positions count from 0.
 *
 * - `status` - what plays, where in it, the volume, shuffle and repeat, what comes next
 * - `queue {limit}` - the queue and which of it is playing
 * - `play`, `pause`, `toggle`, `next`, `previous` - the transport; `play` goes on from where it stopped
 * - `seek {seconds}`, `volume {level | delta}`, `shuffle {on?}`, `repeat {mode?: off|all|one}` - left out, they toggle
 * - `search {query, limit}` - the search box's ranked results, and the playlists whose name has the query in it
 * - `playlists` - every playlist the listener can see
 * - `play_item {kind, id, mode, radio}` - kind is song, album, artist, playlist, genre (id is its name) or liked (no
 *   id); mode is now (the default), next, last or shuffle. A song played now with `radio` has similar ones follow it
 * - `radio {kind?, id?}` - songs like a song (the one playing, without an id), an album or an artist
 * - `wave` - My Wave, where the server has it; `shuffle_all` - a hundred songs from anywhere
 * - `like {id?, on?}`, `dislike {id?, on?}`, `rate {id?, stars}` - the song playing, without an id
 * - `jump {index}`, `remove {index}`, `clear` - the queue
 * - `add_to_playlist {playlistId, songIds?}`, `create_playlist {name, songIds?}` - the song playing, without songIds
 * - `lyrics {id?}` - the words of a song, as lines
 */
class RemoteCommands(private val app: AppController) {
    private val player get() = app.player

    suspend fun run(command: String, a: Args): Any = when (command) {
        "status" -> status()
        "queue" -> queue(a.int("limit") ?: 100)
        "play" -> transport { if (!player.state.value.isPlaying) player.toggle() }
        "pause" -> transport { if (player.state.value.isPlaying) player.toggle() }
        "toggle" -> transport { player.toggle() }
        "next" -> transport { player.next() }
        "previous" -> transport { player.previous() }
        "seek" -> {
            val seconds = a.double("seconds") ?: throw RemoteError(400, "\"seconds\" is needed")
            transport { player.seekMs((seconds * 1000).toLong()) }
        }
        "volume" -> volume(a)
        "shuffle" -> shuffle(a.bool("on"))
        "repeat" -> setRepeat(a.string("mode"))
        "search" -> search(a.need("query"), a.int("limit") ?: 10)
        "playlists" -> mapOf("playlists" to playlists().map(::playlistJson))
        "play_item" -> playItem(a)
        "radio" -> radio(a)
        "wave" -> wave()
        "shuffle_all" -> {
            client()
            app.shuffleAll()
            mapOf("started" to true)
        }
        "like" -> like(a)
        "dislike" -> dislike(a)
        "rate" -> rate(a)
        "jump" -> {
            val i = queueIndex(a)
            transport { player.jumpTo(i) }
        }
        "remove" -> {
            val i = queueIndex(a)
            val gone = player.state.value.queue.songs[i]
            player.removeAt(i)
            settle()
            mapOf("removed" to songJson(gone)) + status()
        }
        "clear" -> transport { player.play(emptyList()) }
        "add_to_playlist" -> addToPlaylist(a)
        "create_playlist" -> createPlaylist(a)
        "lyrics" -> lyrics(a)
        else -> throw RemoteError(404, "Unknown command \"$command\"")
    }

    // --- the player ---------------------------------------------------------------

    private fun status(): Map<String, Any?> {
        val s = player.state.value
        val q = s.queue
        return mapOf(
            "signedIn" to (app.client != null),
            "playing" to s.isPlaying,
            "current" to q.current?.let(::songJson),
            "index" to q.index,
            "queueSize" to q.songs.size,
            "positionSec" to s.positionMs / 1000.0,
            "durationSec" to s.durationMs / 1000.0,
            "volume" to s.volume,
            "shuffle" to q.shuffle,
            "repeat" to q.repeat.name.lowercase(),
            "wave" to app.waveState.isWaveTrack(q.current?.id),
            "waveAvailable" to app.waveAvailable,
            "upcoming" to q.songs.drop(q.index + 1).take(UPCOMING).map(::songJson),
            "error" to s.error,
            "version" to app.version,
        )
    }

    private fun queue(limit: Int): Map<String, Any?> {
        val q = player.state.value.queue
        return mapOf("index" to q.index, "total" to q.songs.size, "songs" to q.songs.take(limit.coerceAtLeast(1)).map(::songJson))
    }

    /** A command for the player's worker, then what came of it: the worker takes a moment to get there. */
    private suspend fun transport(block: () -> Unit): Map<String, Any?> {
        block()
        settle()
        return status()
    }

    private suspend fun settle() = delay(SETTLE_MS)

    private fun volume(a: Args): Map<String, Any?> {
        val now = player.state.value.volume
        val level = a.int("level") ?: a.int("delta")?.let { now + it } ?: throw RemoteError(400, "\"level\" (0-100) or \"delta\" is needed")
        app.setVolume(level.coerceIn(0, 100))
        return mapOf("volume" to player.state.value.volume)
    }

    private fun shuffle(on: Boolean?): Map<String, Any?> {
        if (on == null || on != player.state.value.queue.shuffle) player.toggleShuffle()
        return mapOf("shuffle" to player.state.value.queue.shuffle)
    }

    private fun setRepeat(mode: String?): Map<String, Any?> {
        if (mode == null) {
            player.cycleRepeat()
        } else {
            val wanted = RepeatMode.entries.firstOrNull { it.name.equals(mode, ignoreCase = true) }
                ?: throw RemoteError(400, "\"mode\" is off, all or one")
            repeat(RepeatMode.entries.size) { if (player.state.value.queue.repeat != wanted) player.cycleRepeat() }
        }
        return mapOf("repeat" to player.state.value.queue.repeat.name.lowercase())
    }

    private fun queueIndex(a: Args): Int {
        val i = a.int("index") ?: throw RemoteError(400, "\"index\" (from 0) is needed")
        val size = player.state.value.queue.songs.size
        if (i !in 0 until size) throw RemoteError(400, "The queue has $size songs; index $i is not one of them")
        return i
    }

    // --- finding and playing ----------------------------------------------------------

    private fun client(): SubsonicClient = app.client ?: throw RemoteError(409, "Platter is not signed in to a server")

    private suspend fun search(query: String, limit: Int): Map<String, Any?> {
        client()
        val r = app.search(query)
        val wanted = norm(query)
        val lists = playlists().filter { norm(it.name).let { name -> name.isNotEmpty() && (wanted in name || name in wanted) } }
        return mapOf(
            "top" to r.top?.let(::topJson),
            "songs" to r.songs.take(limit).map(::songJson),
            "albums" to r.albums.take(limit).map(::albumJson),
            "artists" to r.artists.take(limit).map(::artistJson),
            "playlists" to lists.take(limit).map(::playlistJson),
        )
    }

    private suspend fun playlists(): List<Playlist> {
        client()
        if (app.playlists.isEmpty()) app.refreshPlaylists()
        return app.playlists
    }

    private suspend fun playItem(a: Args): Map<String, Any?> {
        val c = client()
        val kind = a.string("kind")?.lowercase() ?: "song"
        val mode = a.string("mode")?.lowercase() ?: "now"
        if (mode !in MODES) throw RemoteError(400, "\"mode\" is one of ${MODES.joinToString()}")
        val (name, found) = when (kind) {
            "song", "track" -> {
                val song = c.song(a.need("id"))
                if (mode == "now" && a.bool("radio") == true) {
                    app.startRadio(song)
                    settle()
                    return mapOf("kind" to "song", "name" to song.title, "radio" to true, "first" to songJson(song)) + status()
                }
                song.title to listOf(song)
            }
            "album" -> c.album(a.need("id")).let { it.name to it.songs.orEmpty() }
            "artist" -> c.artist(a.need("id")).let { it.name to app.artistSongs(it.name.orEmpty(), it.albums.orEmpty()) }
            "playlist" -> c.playlist(a.need("id")).let { it.name to it.entries.orEmpty() }
            "genre" -> a.need("id").let { it to c.songsByGenre(it, GENRE_SONGS) }
            "liked" -> "liked" to app.liked().songs.orEmpty()
            else -> throw RemoteError(400, "\"kind\" is one of song, album, artist, playlist, genre, liked")
        }
        if (found.isEmpty()) throw RemoteError(404, "There is nothing to play in \"${name.orEmpty()}\"")
        val songs = if (mode == "shuffle") found.shuffled() else found
        when (mode) {
            "now", "shuffle" -> player.play(songs)
            "next" -> app.playNext(songs)
            "last" -> app.addToQueue(songs)
        }
        settle()
        return mapOf("kind" to kind, "name" to name, "songs" to songs.size, "first" to songJson(songs.first())) + status()
    }

    private suspend fun radio(a: Args): Map<String, Any?> {
        val c = client()
        when (val kind = a.string("kind")?.lowercase() ?: "song") {
            "song", "track" -> {
                val seed = a.string("id")?.let { c.song(it) } ?: playing()
                app.startRadio(seed)
                settle()
                return mapOf("seed" to songJson(seed)) + status()
            }
            "album", "artist" -> {
                app.startRadioFor(a.need("id"), kind)
                return mapOf("started" to true)
            }
            else -> throw RemoteError(400, "\"kind\" is song, album or artist")
        }
    }

    private suspend fun wave(): Map<String, Any?> {
        client()
        if (!app.waveAvailable) throw RemoteError(409, "My Wave is not available on this server")
        val now = player.state.value
        if (app.waveState.isWaveTrack(now.current?.id)) {
            if (!now.isPlaying) player.toggle()
        } else {
            app.onWaveTapped()
            withTimeoutOrNull(WAVE_START_MS) { while (app.waveStarting) delay(100) }
            app.waveError?.let { throw RemoteError(502, it) }
        }
        settle()
        return status()
    }

    // --- likes, playlists, words ------------------------------------------------------

    /** The song named by `id`, or the one playing. */
    private suspend fun target(a: Args): Song = a.string("id")?.let { client().song(it) } ?: playing()

    private fun playing(): Song = player.state.value.current?.takeIf { it.isMusic }
        ?: throw RemoteError(409, "No song is playing")

    private suspend fun like(a: Args): Map<String, Any?> {
        client()
        val song = target(a)
        val on = a.bool("on") ?: true
        if (app.isLiked(song.id, song.starred) != on) app.toggleLike(song)
        return mapOf("song" to songJson(song), "liked" to on)
    }

    private suspend fun dislike(a: Args): Map<String, Any?> {
        client()
        val song = target(a)
        val on = a.bool("on") ?: true
        if (app.isDisliked(song) != on) app.toggleDislike(song)
        return mapOf("song" to songJson(song), "disliked" to on)
    }

    private suspend fun rate(a: Args): Map<String, Any?> {
        client()
        val stars = a.int("stars")?.takeIf { it in 0..5 } ?: throw RemoteError(400, "\"stars\" (0-5) is needed")
        val song = target(a)
        app.rate(song, stars)
        return mapOf("song" to songJson(song), "stars" to stars)
    }

    private suspend fun addToPlaylist(a: Args): Map<String, Any?> {
        val id = a.need("playlistId")
        val playlist = playlists().firstOrNull { it.id == id } ?: throw RemoteError(404, "No playlist $id")
        val songs = a.strings("songIds").map { songId -> Song().apply { this.id = songId } }.ifEmpty { listOf(playing()) }
        app.addToPlaylist(playlist, songs)
        return mapOf("playlist" to playlist.name, "songs" to songs.size, "song" to songs.singleOrNull()?.takeIf { it.title != null }?.let(::songJson))
    }

    private fun createPlaylist(a: Args): Map<String, Any?> {
        client()
        val name = a.need("name")
        val songs = a.strings("songIds").map { songId -> Song().apply { this.id = songId } }
        app.createPlaylist(name, songs)
        return mapOf("name" to name, "songs" to songs.size)
    }

    private suspend fun lyrics(a: Args): Map<String, Any?> {
        client()
        val song = target(a)
        val words = app.lyricsFor(song)
        return mapOf("song" to songJson(song), "synced" to words.isSynced, "lines" to words.lines)
    }

    // --- what goes out ------------------------------------------------------------------

    private fun songJson(song: Song): Map<String, Any?> = mapOf(
        "id" to song.id,
        "title" to song.title,
        "artist" to song.artistLine(),
        "artistId" to song.artistId,
        "album" to song.album,
        "albumId" to song.albumId,
        "year" to song.year,
        "durationSec" to song.duration,
        "liked" to app.isLiked(song.id, song.starred),
        "rating" to app.ratingOf(song).takeIf { it > 0 },
        "kind" to song.kind,
    )

    private fun albumJson(album: Album): Map<String, Any?> = mapOf(
        "id" to album.id,
        "name" to album.name,
        "artist" to album.artistLine(),
        "artistId" to album.artistId,
        "year" to album.year,
        "songCount" to album.songCount,
    )

    private fun artistJson(artist: Artist): Map<String, Any?> = mapOf(
        "id" to artist.id,
        "name" to artist.name,
        "albumCount" to artist.albumCount,
    )

    private fun playlistJson(playlist: Playlist): Map<String, Any?> = mapOf(
        "id" to playlist.id,
        "name" to playlist.name,
        "songCount" to playlist.songCount,
        "owner" to playlist.owner,
    )

    private fun topJson(top: RankedResults.Top): Map<String, Any?> = when (top) {
        is RankedResults.Top.OfArtist -> mapOf("kind" to "artist") + artistJson(top.artist)
        is RankedResults.Top.OfAlbum -> mapOf("kind" to "album") + albumJson(top.album)
        is RankedResults.Top.OfSong -> mapOf("kind" to "song") + songJson(top.song)
    }

    private fun norm(text: String?): String = text.orEmpty().lowercase().replace('ё', 'е').trim()

    private companion object {
        val MODES = listOf("now", "next", "last", "shuffle")

        /** How long the player's worker is given to act on a command before its state is read back. */
        const val SETTLE_MS = 350L
        const val UPCOMING = 5
        const val GENRE_SONGS = 100
        const val WAVE_START_MS = 15_000L
    }
}
