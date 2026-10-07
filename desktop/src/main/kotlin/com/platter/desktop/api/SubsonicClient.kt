package com.platter.desktop.api

import com.platter.desktop.i18n.t
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * What identifies the user to the server. Only the token and salt are kept -
 * `t = md5(password + salt)` - so the password itself is never stored. Same
 * scheme as the Android app's SubsonicPreferences.
 */
data class Credentials(
    val serverUrl: String,
    val username: String,
    val token: String,
    val salt: String,
)

/** Subsonic answered with `status=failed`. */
class SubsonicException(val code: Int, message: String) : Exception(message) {
    val isWrongCredentials get() = code == WRONG_USERNAME_OR_PASSWORD

    companion object {
        const val GENERIC = 0
        const val WRONG_USERNAME_OR_PASSWORD = 40
        const val TOKEN_AUTH_NOT_SUPPORTED = 41
    }
}

object Auth {
    fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun newSalt(): String = UUID.randomUUID().toString()

    fun token(password: String, salt: String): String = md5(password + salt)

    /** `host:4533` and `http://host` both come out as a base URL ending in `/rest/`. */
    fun restBase(serverUrl: String): String {
        var url = serverUrl.trim()
        if (!url.contains("://")) url = "https://$url"
        return url.trimEnd('/') + "/rest/"
    }
}

class SubsonicClient(val credentials: Credentials, http: OkHttpClient = defaultHttp) {
    /*
     * Subsonic API 1.16.1 - the version Navidrome implements and the one
     * OpenSubsonic asks clients to declare. "c" names the client: Navidrome
     * hangs a player entry and its transcoding profile off (client, user), so
     * it is the same "Platter" the Android app sends.
     */
    private val baseUrl: HttpUrl = Auth.restBase(credentials.serverUrl).toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Bad server address: ${credentials.serverUrl}")

    /** The u/t/s/v/c/f parameters every Subsonic call carries; the wave service takes the same signature. */
    val authParams: Map<String, String> get() = params

    private val params: Map<String, String> = mapOf(
        "u" to credentials.username,
        "t" to credentials.token,
        "s" to credentials.salt,
        "v" to API_VERSION,
        "c" to CLIENT_NAME,
        "f" to "json",
    )

    private val service: SubsonicService = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(GsonConverterFactory.create())
        .client(http)
        .build()
        .create(SubsonicService::class.java)

    private suspend fun call(block: suspend (SubsonicService) -> ApiResponse): SubsonicResponse {
        val response = block(service).subsonicResponse
            ?: throw SubsonicException(SubsonicException.GENERIC, t("The server did not answer with a Subsonic response"))
        if (response.status != "ok") {
            val error = response.error
            throw SubsonicException(error?.code ?: SubsonicException.GENERIC, error?.message ?: t("The server reported an error"))
        }
        return response
    }

    suspend fun ping(): SubsonicResponse = call { it.ping(params) }

    suspend fun artists(): List<Artist> =
        call { it.getArtists(params) }.artists?.indices.orEmpty().flatMap { it.artists.orEmpty() }

    suspend fun artist(id: String): Artist =
        call { it.getArtist(params, id) }.artist ?: throw SubsonicException(SubsonicException.GENERIC, t("No such artist"))

    suspend fun album(id: String): Album =
        call { it.getAlbum(params, id) }.album ?: throw SubsonicException(SubsonicException.GENERIC, t("No such album"))

    /** [type] is `newest`, `recent`, `frequent`, `random`, `starred`, `alphabeticalByName`, ... */
    suspend fun albumList(type: String, size: Int = 20, offset: Int = 0, genre: String? = null, fromYear: Int? = null, toYear: Int? = null): List<Album> =
        call { it.getAlbumList2(params, type, size, offset, genre, fromYear, toYear) }.albumList2?.albums.orEmpty()

    /** Every album, alphabetically, paged until a short page or [maxPages]: a huge library is not worth endless paging. */
    suspend fun allAlbums(pageSize: Int = 500, maxPages: Int = 20): List<Album> {
        val all = ArrayList<Album>()
        for (page in 0 until maxPages) {
            val batch = albumList("alphabeticalByName", pageSize, page * pageSize)
            all += batch
            if (batch.size < pageSize) break
        }
        return all
    }

    /** What the server's agents know about [artistId]: biography, Last.fm link, similar artists. Null where there is none. */
    suspend fun artistInfo(artistId: String): ArtistInfo? = call { it.getArtistInfo2(params, artistId) }.artistInfo2

    /** The artist's best-known songs (server-side, usually from Last.fm). Empty on a server with no agent for it. */
    suspend fun topSongs(artistName: String, count: Int = 10): List<Song> =
        call { it.getTopSongs(params, artistName, count) }.topSongs?.songs.orEmpty()

    /** Songs like [id] - a song, album or artist id. The "radio" behind the instant mix. */
    suspend fun similarSongs(id: String, count: Int = 50): List<Song> =
        call { it.getSimilarSongs2(params, id, count) }.similarSongs2?.songs.orEmpty()

    suspend fun genres(): List<Genre> = call { it.getGenres(params) }.genres?.genres.orEmpty()

    suspend fun songsByGenre(genre: String, count: Int = 100, offset: Int = 0): List<Song> =
        call { it.getSongsByGenre(params, genre, count, offset) }.songsByGenre?.songs.orEmpty()

    /** Starts the server scanning its folders; progress is [scanStatus]. */
    suspend fun startScan(): ScanStatus = call { it.startScan(params) }.scanStatus ?: ScanStatus()

    suspend fun scanStatus(): ScanStatus = call { it.getScanStatus(params) }.scanStatus ?: ScanStatus()

    /** A public link to an album, playlist or song, or null where the server has sharing off. */
    suspend fun createShare(id: String): String? = call { it.createShare(params, id) }.shares?.shares?.firstOrNull()?.url

    /**
     * Makes a playlist and returns it. The songs go in batches: a long album would otherwise put one `songId` per
     * track into the query string, and past about 8 KB every common reverse proxy refuses the request.
     */
    suspend fun createPlaylist(name: String, songIds: List<String> = emptyList()): Playlist? {
        val batches = songIds.chunked(PLAYLIST_BATCH)
        val created = call { it.createPlaylist(params, null, name, batches.firstOrNull().orEmpty()) }.playlist
        // Older servers answer with nothing; the playlist is then looked up by name.
        val id = created?.id ?: playlists().lastOrNull { it.name == name }?.id ?: return created
        batches.drop(1).forEach { addToPlaylist(id, it) }
        return created ?: playlist(id)
    }

    suspend fun addToPlaylist(playlistId: String, songIds: List<String>) {
        songIds.chunked(PLAYLIST_BATCH).forEach { batch ->
            call { it.updatePlaylist(params, playlistId, null, null, batch, emptyList()) }
        }
    }

    /** [indexes] are positions in the playlist as the server holds it, not song ids: one song may be there twice. */
    suspend fun removeFromPlaylist(playlistId: String, indexes: List<Int>) {
        if (indexes.isEmpty()) return
        call { it.updatePlaylist(params, playlistId, null, null, emptyList(), indexes) }
    }

    suspend fun renamePlaylist(playlistId: String, name: String) {
        call { it.updatePlaylist(params, playlistId, name, null, emptyList(), emptyList()) }
    }

    suspend fun deletePlaylist(playlistId: String) {
        call { it.deletePlaylist(params, playlistId) }
    }

    /**
     * Puts the playlist's songs in a new order. Subsonic cannot move one entry, but `createPlaylist` with an existing
     * `playlistId` replaces its songs outright, so the whole list is sent again, in batches like [createPlaylist].
     */
    suspend fun reorderPlaylist(playlistId: String, songIds: List<String>) {
        val batches = songIds.chunked(PLAYLIST_BATCH)
        call { it.createPlaylist(params, playlistId, null, batches.firstOrNull().orEmpty()) }
        batches.drop(1).forEach { addToPlaylist(playlistId, it) }
    }

    suspend fun randomSongs(size: Int = 30): List<Song> =
        call { it.getRandomSongs(params, size) }.randomSongs?.songs.orEmpty()

    suspend fun starred(): SearchResult = call { it.getStarred2(params) }.starred2 ?: SearchResult()

    suspend fun search(query: String, songs: Int = 20, albums: Int = 20, artists: Int = 20): SearchResult =
        call { it.search3(params, query, songs, albums, artists) }.searchResult3 ?: SearchResult()

    suspend fun playlists(): List<Playlist> = call { it.getPlaylists(params) }.playlists?.playlists.orEmpty()

    suspend fun playlist(id: String): Playlist =
        call { it.getPlaylist(params, id) }.playlist ?: throw SubsonicException(SubsonicException.GENERIC, t("No such playlist"))

    suspend fun star(songId: String? = null, albumId: String? = null, artistId: String? = null) {
        call { it.star(params, songId, albumId, artistId) }
    }

    suspend fun unstar(songId: String? = null, albumId: String? = null, artistId: String? = null) {
        call { it.unstar(params, songId, albumId, artistId) }
    }

    /** [time] is when the play started, in epoch milliseconds; the server files it there however late it arrives. */
    suspend fun scrobble(songId: String, submission: Boolean, time: Long? = null) {
        call { it.scrobble(params, songId, submission, time) }
    }

    /**
     * The words of [song]. The layered `getLyricsBySongId` first; a server
     * without it, or a track it has nothing for, falls back to the old
     * plain-text `getLyrics` by artist and title.
     */
    suspend fun lyrics(song: Song): SongLyrics {
        val id = song.id ?: return SongLyrics(null, null)
        val layer = try {
            LyricsPicker.pick(call { it.getLyricsBySongId(params, id) }.lyricsList)
        } catch (e: SubsonicException) {
            null // an older server: it has no such endpoint
        }
        if (layer != null) return SongLyrics(layer, null)

        val plain = try {
            call { it.getLyrics(params, song.artist, song.title) }.lyrics?.value?.takeIf { it.isNotBlank() }
        } catch (e: SubsonicException) {
            null
        }
        return SongLyrics(null, plain)
    }

    // --- the saved play queue -------------------------------------------------------

    private var knownExtensions: Set<String>? = null

    /** What the server says it can do beyond Subsonic 1.16; empty on a server that does not know the question. */
    suspend fun extensions(): Set<String> {
        knownExtensions?.let { return it }
        val found = try {
            call { it.getOpenSubsonicExtensions(params) }.openSubsonicExtensions.orEmpty().mapNotNull { it.name }.toSet()
        } catch (e: SubsonicException) {
            emptySet()
        }
        return found.also { knownExtensions = it }
    }

    /** The queue another device left on the server, or null where nothing was saved. */
    suspend fun playQueue(): SavedQueue? {
        val response = if ("indexBasedQueue" in extensions()) call { it.getPlayQueueByIndex(params) } else call { it.getPlayQueue(params) }
        // Whichever endpoint was asked, take the key that came back.
        return response.playQueueByIndex ?: response.playQueue
    }

    /**
     * Saves the queue. [index] says which song is playing; a server with the indexBasedQueue extension takes it as it
     * is (a queue may hold a song twice), one without takes the song's id. A long queue goes as a form post where the
     * server allows it, and otherwise as a window around the playing song, so the request stays under what proxies accept.
     */
    suspend fun savePlayQueue(ids: List<String>, index: Int, positionMs: Long) {
        if (ids.isEmpty()) return
        val extensions = extensions()
        val post = "formPost" in extensions
        var sent = ids
        var at = index.coerceIn(0, ids.lastIndex)
        if (!post && ids.size > GET_QUEUE_LIMIT) {
            val from = (at - GET_QUEUE_LIMIT / 4).coerceIn(0, ids.size - GET_QUEUE_LIMIT)
            sent = ids.subList(from, from + GET_QUEUE_LIMIT)
            at -= from
        }
        val position = positionMs.coerceAtLeast(0)
        val current = sent[at]
        call {
            when {
                "indexBasedQueue" in extensions && post -> it.savePlayQueueByIndexPost(params, sent, at, position)
                "indexBasedQueue" in extensions -> it.savePlayQueueByIndex(params, sent, at, position)
                post -> it.savePlayQueuePost(params, sent, current, position)
                else -> it.savePlayQueue(params, sent, current, position)
            }
        }
    }

    // --- podcasts -------------------------------------------------------------------

    suspend fun podcastChannels(): List<PodcastChannel> = call { it.getPodcasts(params, false, null) }.podcasts?.channels.orEmpty()

    /** One channel with its episodes. */
    suspend fun podcastChannel(id: String): PodcastChannel =
        call { it.getPodcasts(params, true, id) }.podcasts?.channels?.firstOrNull() ?: throw SubsonicException(SubsonicException.GENERIC, t("No such podcast"))

    suspend fun newestEpisodes(count: Int = 20): List<PodcastEpisode> = call { it.getNewestPodcasts(params, count) }.newestPodcasts?.episodes.orEmpty()

    suspend fun refreshPodcasts() {
        call { it.refreshPodcasts(params) }
    }

    suspend fun addPodcast(feedUrl: String) {
        call { it.createPodcastChannel(params, feedUrl) }
    }

    suspend fun deletePodcast(channelId: String) {
        call { it.deletePodcastChannel(params, channelId) }
    }

    suspend fun deleteEpisode(episodeId: String) {
        call { it.deletePodcastEpisode(params, episodeId) }
    }

    /** Asks the server to fetch the episode; it can be played once its status is `completed`. */
    suspend fun downloadEpisode(episodeId: String) {
        call { it.downloadPodcastEpisode(params, episodeId) }
    }

    // --- internet radio -------------------------------------------------------------

    suspend fun radioStations(): List<RadioStation> = call { it.getInternetRadioStations(params) }.internetRadioStations?.stations.orEmpty()

    suspend fun addStation(name: String, streamUrl: String, homePageUrl: String?) {
        call { it.createInternetRadioStation(params, streamUrl, name, homePageUrl?.ifBlank { null }) }
    }

    suspend fun updateStation(id: String, name: String, streamUrl: String, homePageUrl: String?) {
        call { it.updateInternetRadioStation(params, id, streamUrl, name, homePageUrl?.ifBlank { null }) }
    }

    suspend fun deleteStation(id: String) {
        call { it.deleteInternetRadioStation(params, id) }
    }

    /** Subsonic has no dislike, so rating 1 stands in for one and 0 clears it, as in the Android app. */
    suspend fun setRating(songId: String, rating: Int) {
        call { it.setRating(params, songId, rating) }
    }

    /** A plain URL the player can open. Credentials ride in the query string, as everywhere in Subsonic. */
    fun streamUrl(songId: String, maxBitRate: Int? = null, format: String? = null): String =
        endpoint("stream") {
            addQueryParameter("id", songId)
            maxBitRate?.let { addQueryParameter("maxBitRate", it.toString()) }
            format?.let { addQueryParameter("format", it) }
        }

    /** The file as stored on the server, whole: what an offline copy is made from. */
    fun downloadUrl(songId: String): String = endpoint("download") { addQueryParameter("id", songId) }

    fun coverArtUrl(coverArtId: String, size: Int? = null): String =
        endpoint("getCoverArt") {
            size?.let { addQueryParameter("size", it.toString()) }
            addQueryParameter("id", coverArtId)
        }

    private fun endpoint(name: String, extra: HttpUrl.Builder.() -> Unit): String =
        baseUrl.newBuilder().addPathSegment(name).apply {
            // Same order as the Android app's Util.authenticationQuery: u, s, t, v, c.
            for (key in listOf("u", "s", "t", "v", "c")) addQueryParameter(key, params.getValue(key))
            extra()
        }.build().toString()

    companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT_NAME = "Platter"
        private const val PLAYLIST_BATCH = 100

        /** The most ids a GET save sends: about 8 KB of query string at the length of a Navidrome id. */
        private const val GET_QUEUE_LIMIT = 250

        val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .callTimeout(2, TimeUnit.MINUTES)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /**
         * Signs in: makes a fresh salt, derives the token, and pings. Throws
         * [SubsonicException] (code 40 for a wrong user or password) or an
         * IOException when the server cannot be reached. The password is not
         * kept past this call.
         */
        suspend fun login(serverUrl: String, username: String, password: String, http: OkHttpClient = defaultHttp): SubsonicClient {
            val salt = Auth.newSalt()
            val client = SubsonicClient(Credentials(serverUrl.trim(), username.trim(), Auth.token(password, salt), salt), http)
            client.ping()
            return client
        }
    }
}
