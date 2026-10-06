package com.platter.desktop.deemix

import com.google.gson.JsonParser
import com.platter.desktop.api.Song
import com.platter.desktop.search.QueryVariants
import com.platter.desktop.wave.NetworkAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What a call to Deemix plus came to. */
sealed interface DeemixResult<out T> {
    data class Ok<T>(override val value: T) : DeemixResult<T>

    /** [message] is the server's own explanation, when it gave one - already in the user's language. */
    data class Failed(val failure: Failure, val message: String? = null) : DeemixResult<Nothing> {
        override val value: Nothing? get() = null
    }

    enum class Failure {
        /** No address to try, or nothing answered there. */
        UNREACHABLE,

        /** The service answered, but neither password opened this account. */
        NO_ACCOUNT,

        /** The daily limit - the account's or the server's - has run out. */
        QUOTA,
        ERROR,
    }

    /** The answer, or null when the call failed. */
    val value: T?
}

/** Everything the client needs to know about the listener, asked for afresh each time since it can change. */
class DeemixConfig(
    /** An address written in settings; empty to have the site looked for. */
    val customUrl: () -> String?,
    /** The music server's addresses, the one in use first. */
    val servers: () -> List<String>,
    val user: () -> String?,
    /** The password entered for the site, which is only needed when it differs from the server's. */
    val ownPassword: () -> String?,
    val serverPassword: () -> String?,
    /** The library's songs by an artist, for matching what Deezer lists against what is already held. */
    val searchLibrary: suspend (artist: String) -> List<Song>,
)

class DeemixPreview(val url: String, val authorization: String)

class DeezerArtistPage(val artist: DeezerArtist, val top: List<DeezerTrack>, val albums: List<DeezerAlbum>, val inLibrary: Map<String, Boolean>)

class DeezerAlbumPage(val album: DeezerAlbum, val tracks: List<DeezerTrack>, val inLibrary: Map<String, Boolean>)

/** A Deezer search, with the tracks the library already holds marked by id. */
class DeezerResults(val search: DeezerSearch, val inLibrary: Map<String, Boolean>) {
    fun isInLibrary(track: DeezerTrack): Boolean = inLibrary[track.id.toString()] == true
}

/**
 * Talks to Deemix plus as the signed-in listener - the Android app's `DeemixClient` on coroutines.
 *
 * The site has its own accounts, meant to mirror the Navidrome ones, so the app signs in with the name and password
 * it already holds and asks for nothing more. Where the two passwords have drifted apart, the one entered in
 * settings is tried as well. The token that comes back lasts a day; it lives in memory only and is fetched again on
 * the first 401.
 */
class DeemixClient(
    private val config: DeemixConfig,
    catalogUrl: String = "https://api.deezer.com/",
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(DOWNLOAD_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /* Only asks whether the site is there: an address that is not must not hold a search up. */
    private val probe: OkHttpClient = http.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private val catalogService: DeezerCatalogService = Retrofit.Builder()
        .baseUrl(catalogUrl)
        .addConverterFactory(GsonConverterFactory.create())
        .client(OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build())
        .build()
        .create(DeezerCatalogService::class.java)

    @Volatile
    private var service: Pair<String, DeemixService>? = null

    /* The candidates last looked through, and the address among them that answered. */
    @Volatile
    private var found: Pair<List<String>, String>? = null

    private val lock = Any()

    /* Guarded by lock. */
    private var session: Session? = null
    private var rejectedKey: String? = null
    private var unreachableUntil = 0L

    /* The addresses the back-off is for: a listener who has changed the address is not made to wait for the old one. */
    private var unreachableFor: List<String>? = null

    private class Session(val key: String, val token: String, val username: String, val role: String)

    /** Where the site is, for showing: the address found to answer, or the first one that would be tried. */
    fun baseUrl(): String? {
        val candidates = candidates()
        return found?.takeIf { it.first == candidates }?.second ?: candidates.firstOrNull()
    }

    /** Every address the site is looked for at, in the order they are tried. */
    fun candidates(): List<String> {
        val custom = config.customUrl()?.trim()
        if (!custom.isNullOrEmpty()) {
            val withScheme = if ("://" in custom) custom else "http://$custom"
            return listOf(if (withScheme.endsWith("/")) withScheme else "$withScheme/")
        }

        /*
         * The address in use first: at home that is the local one, and the site is next to Navidrome on the same
         * machine. Away from home the server's public name is usually a reverse proxy that passes on Navidrome
         * alone, with the site on a name of its own beside it - deemix.example.com next to music.example.com.
         */
        val servers = config.servers().mapNotNull { address ->
            val trimmed = address.trim()
            (if ("://" in trimmed) trimmed else "https://$trimmed").toHttpUrlOrNull()
        }
        return servers.flatMap { server ->
            val onPort = HttpUrl.Builder().scheme(server.scheme).host(server.host).port(DEFAULT_PORT).build().toString()
            val labels = server.host.split('.')
            val isName = labels.size >= 2 && labels.last().toIntOrNull() == null && ':' !in server.host
            if (!isName || labels.first() == SUBDOMAIN) return@flatMap listOf(onPort)

            val parent = if (labels.size >= 3) labels.drop(1) else labels
            val sibling = "https://" + (listOf(SUBDOMAIN) + parent).joinToString(".") + "/"
            listOf(sibling, onPort)
        }.distinct()
    }

    /**
     * The address to call: the first candidate where the site answers. Found once for each set of candidates - they
     * change as the listener leaves home or comes back - and looked for again after a connection fails. A redirect
     * is followed here, once, so that http:// written in settings still reaches a site served over https: calls
     * that send a body would not follow it.
     */
    private suspend fun resolveBase(): String? {
        val candidates = candidates()
        found?.takeIf { it.first == candidates }?.let { return it.second }
        if (candidates.isEmpty()) return null
        if (backedOff(candidates)) return candidates.first()

        var base: String? = null
        for (candidate in candidates) {
            base = siteAt(candidate)
            if (base != null) break
        }
        if (base == null) {
            markUnreachable()
            return candidates.first()
        }
        found = candidates to base
        return base
    }

    /**
     * Where [base] leads, if the site answers there. Asking who is signed in without a token is refused, but
     * refused by FastAPI, in JSON - Navidrome or a proxy's error page at the same address answers otherwise.
     */
    private suspend fun siteAt(base: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("${base}api/auth/me").build()
            probe.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val isSite = response.code == 401 && "\"detail\"" in body || response.isSuccessful && "\"username\"" in body
                if (!isSite) return@use null
                val url = response.request.url.toString()
                url.removeSuffix("api/auth/me").takeIf { it != url }
            }
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** The account the site knows this listener as, once signed in. */
    fun account(): Pair<String, String>? = synchronized(lock) {
        session?.takeIf { it.key == credentialKey(baseUrl()) }?.let { it.username to it.role }
    }

    /** Forgets a failed sign-in, so the next call tries again. */
    fun retry() = synchronized(lock) {
        rejectedKey = null
        unreachableUntil = 0L
        unreachableFor = null
        found = null
    }

    suspend fun usage(): DeemixResult<DeemixUsage> = authorized(SEARCH_TIMEOUT_S) { usage(it) }

    /** Searches Deezer, and marks which of the tracks found the library already has - the check the site runs before offering a download. */
    suspend fun search(query: String): DeemixResult<DeezerResults> {
        val found = authorized(SEARCH_TIMEOUT_S) { search(it, query) }
        val search = found.value ?: return found as DeemixResult.Failed
        return DeemixResult.Ok(DeezerResults(search, libraryMarks(search.tracks.orEmpty())))
    }

    /** An artist's page: who they are, their most played tracks, and every release - from Deezer's own catalogue, the tracks marked against the library. */
    suspend fun artistPage(id: Long): DeemixResult<DeezerArtistPage> = coroutineScope {
        // Three independent reads of about a second each: side by side, not in a row.
        val releases = async { catalog { albums(id, MAX_RELEASES) }?.data }
        val popular = async { catalog { top(id, MAX_TOP_TRACKS) }?.data }

        val artist = catalog { artist(id) }?.takeIf { it.id != null } ?: return@coroutineScope DeemixResult.Failed(DeemixResult.Failure.UNREACHABLE)
        val top = popular.await().orEmpty()
        val albums = releases.await().orEmpty()
        DeemixResult.Ok(DeezerArtistPage(artist, top, albums, libraryMarks(top)))
    }

    /** An album's page: the record and its tracklist, marked against the library. */
    suspend fun albumPage(id: Long): DeemixResult<DeezerAlbumPage> = coroutineScope {
        val tracklist = async { catalog { albumTracks(id, MAX_ALBUM_TRACKS) }?.data }

        val album = catalog { album(id) }?.takeIf { it.id != null } ?: return@coroutineScope DeemixResult.Failed(DeemixResult.Failure.UNREACHABLE)
        val tracks = tracklist.await().orEmpty()
        DeemixResult.Ok(DeezerAlbumPage(album, tracks, libraryMarks(tracks)))
    }

    /* A catalogue call's body, or null when Deezer could not be reached or said no. */
    private suspend fun <T> catalog(make: suspend DeezerCatalogService.() -> Response<T>): T? = try {
        catalogService.make().takeIf { it.isSuccessful }?.body()
    } catch (e: IOException) {
        null
    }

    /**
     * Which of [tracks] the library already holds. An answer without the marks is still an answer: every track
     * then simply offers a download, and the server skips the ones it already has.
     */
    private suspend fun libraryMarks(tracks: List<DeezerTrack>): Map<String, Boolean> {
        val checked = tracks.filter { it.id != null && it.link != null }.map { LibraryCheckItem(it.id.toString(), it.artist?.name.orEmpty(), it.title.orEmpty()) }
        if (checked.isEmpty()) return emptyMap()

        val marks = authorized(SEARCH_TIMEOUT_S) { checkLibrary(it, LibraryCheckBody(checked)) }.value?.results.orEmpty()
        val unmarked = tracks.filter { it.id != null && it.link != null && marks[it.id.toString()] != true }
        return marks + creditedInLibrary(unmarked)
    }

    /*
     * The site matches a track on its artist exactly, so a song the library tags with every performer -
     * "ivycomb/Stephanafro" where Deezer says "ivycomb" - reads as missing, and search offered to download the very
     * song it listed above. What the site leaves unmarked is looked up in the library itself, one search per
     * artist, and the artist then only has to be one of the credits.
     */
    private suspend fun creditedInLibrary(tracks: List<DeezerTrack>): Map<String, Boolean> = coroutineScope {
        if (tracks.isEmpty()) return@coroutineScope emptyMap()

        val byArtist = tracks.filter { !it.artist?.name.isNullOrBlank() }.groupBy { it.artist!!.name!! }
        val lookups = byArtist.keys.associateWith { artist ->
            async {
                try {
                    withTimeoutOrNull(SEARCH_TIMEOUT_S * 1000) { config.searchLibrary(artist) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }

        val found = mutableMapOf<String, Boolean>()
        for ((artist, lookup) in lookups) {
            val songs = lookup.await() ?: continue
            val credits = credits(artist)
            for (track in byArtist.getValue(artist)) {
                val title = comparableTitle(track.title)
                val held = songs.any { song -> comparableTitle(song.title) == title && credits(song.artistLine()).any { it in credits } }
                if (held) found[track.id.toString()] = true
            }
        }
        found
    }

    private fun credits(artists: String?): Set<String> =
        QueryVariants.normalize(artists).split(CREDIT_JOINS).map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /* "Song (feat. Someone)" on one side and "Song" on the other are the same recording. */
    private fun comparableTitle(title: String?): String = QueryVariants.normalize(title).replace(FEATURING, "").trim()

    /**
     * Where to stream a track's 30-second preview from, and the header that opens it. The site's own proxy, not
     * Deezer's link from the search: that one is signed and expires, the proxy fetches a fresh one each time.
     */
    suspend fun preview(trackId: Long): DeemixResult<DeemixPreview> {
        val signedIn = signIn()
        val token = signedIn.value ?: return signedIn as DeemixResult.Failed
        val base = resolveBase() ?: return DeemixResult.Failed(DeemixResult.Failure.UNREACHABLE)
        return DeemixResult.Ok(DeemixPreview("${base}api/preview?track_id=$trackId", "Bearer $token"))
    }

    /**
     * A single link is queued as it is, without the library check: "already in the queue" and "already downloaded"
     * come back as a refusal, which to the listener is the same thing as a skip.
     */
    suspend fun downloadTrack(link: String): DeemixResult<DownloadMany> {
        val result = authorized(DOWNLOAD_TIMEOUT_S) { downloadTrack(it, DownloadUrlBody(link)) }
        val one = result.value ?: return result as DeemixResult.Failed
        return DeemixResult.Ok(if (one.success == true) DownloadMany(true, 1, 0, 1, null, null) else DownloadMany(true, 0, 1, 1, one.error, null))
    }

    suspend fun downloadAlbum(id: Long, title: String): DeemixResult<DownloadMany> =
        many(authorized(DOWNLOAD_TIMEOUT_S) { downloadAlbum(it, DownloadAlbumBody(id, title)) })

    suspend fun downloadArtist(id: Long, name: String): DeemixResult<DownloadMany> =
        many(authorized(DOWNLOAD_TIMEOUT_S) { downloadArtist(it, DownloadArtistBody(id, name)) })

    /* The bulk routes report "could not read the album" in the body, with a 200. */
    private fun many(result: DeemixResult<DownloadMany>): DeemixResult<DownloadMany> {
        val many = result.value ?: return result
        return if (many.success == false) DeemixResult.Failed(DeemixResult.Failure.ERROR, many.error) else result
    }

    /** Runs one call with a token, signing in first if there is none yet and once more if the server has stopped accepting the one there is. */
    private suspend fun <T> authorized(timeoutS: Long, make: suspend DeemixService.(String) -> Response<T>): DeemixResult<T> {
        for (attempt in 0..1) {
            val signedIn = signIn()
            val token = signedIn.value ?: return signedIn as DeemixResult.Failed
            val api = service() ?: return DeemixResult.Failed(DeemixResult.Failure.UNREACHABLE)

            val response = try {
                withTimeoutOrNull(timeoutS * 1000) { api.make("Bearer $token") }
                    ?: return DeemixResult.Failed(DeemixResult.Failure.UNREACHABLE, "timed out").also { markUnreachable() }
            } catch (e: IOException) {
                markUnreachable()
                return DeemixResult.Failed(DeemixResult.Failure.UNREACHABLE, e.message)
            }

            if (response.code() == 401 && attempt == 0) {
                synchronized(lock) { if (session?.token == token) session = null }
                continue
            }
            return toResult(response)
        }
        return DeemixResult.Failed(DeemixResult.Failure.NO_ACCOUNT)
    }

    private fun <T> toResult(response: Response<T>): DeemixResult<T> {
        val body = response.body()
        if (response.isSuccessful && body != null) return DeemixResult.Ok(body)

        val detail = detail(response.errorBody()?.use { it.string() })
        return when (response.code()) {
            401, 403 -> DeemixResult.Failed(DeemixResult.Failure.NO_ACCOUNT, detail)
            429 -> DeemixResult.Failed(DeemixResult.Failure.QUOTA, detail)
            else -> DeemixResult.Failed(DeemixResult.Failure.ERROR, detail ?: "HTTP ${response.code()}")
        }
    }

    /* FastAPI puts its reason under "detail" - a string for everything this app calls. */
    private fun detail(body: String?): String? = try {
        val detail = JsonParser.parseString(body ?: "").asJsonObject.get("detail")
        if (detail != null && detail.isJsonPrimitive) detail.asString else null
    } catch (e: Exception) {
        null
    }

    private suspend fun signIn(): DeemixResult<String> {
        val key = credentialKey(resolveBase()) ?: return DeemixResult.Failed(DeemixResult.Failure.UNREACHABLE)

        synchronized(lock) {
            session?.takeIf { it.key == key }?.let { return DeemixResult.Ok(it.token) }
            if (rejectedKey == key) return DeemixResult.Failed(DeemixResult.Failure.NO_ACCOUNT)
            if (backedOff(candidates())) return DeemixResult.Failed(DeemixResult.Failure.UNREACHABLE)
        }

        val api = service() ?: return DeemixResult.Failed(DeemixResult.Failure.UNREACHABLE)
        val user = config.user().orEmpty()

        for (password in passwords()) {
            val response = try {
                api.login(DeemixLoginBody(user, password))
            } catch (e: IOException) {
                markUnreachable()
                return DeemixResult.Failed(DeemixResult.Failure.UNREACHABLE, e.message)
            }

            val token = response.body()?.token
            if (response.isSuccessful && token != null) {
                val login = response.body()!!
                synchronized(lock) { session = Session(key, token, login.username ?: user, login.role ?: "user") }
                return DeemixResult.Ok(token)
            }
            if (response.code() != 401) {
                return DeemixResult.Failed(DeemixResult.Failure.ERROR, detail(response.errorBody()?.use { it.string() }))
            }
        }

        synchronized(lock) { rejectedKey = key }
        return DeemixResult.Failed(DeemixResult.Failure.NO_ACCOUNT)
    }

    /**
     * The passwords worth trying, the one set for the site first: it is only ever entered because the Navidrome one
     * did not work there. The Navidrome password goes only where it cannot be read on the way - over HTTPS, or to an
     * address on this network. It was given to the music server, not to whatever the site's address points at.
     */
    private suspend fun passwords(): List<String> {
        val own = config.ownPassword()?.takeIf { it.isNotEmpty() }
        val server = config.serverPassword()?.takeIf { it.isNotEmpty() }
        val url = resolveBase()?.toHttpUrlOrNull()
        val serverAllowed = url != null && NetworkAddress.isPrivateOrEncrypted(url)
        return listOfNotNull(own, server?.takeIf { serverAllowed }).distinct()
    }

    /* Anything that changes who signs in, or where, makes the old token someone else's. */
    private fun credentialKey(base: String?): String? {
        base ?: return null
        val user = config.user()?.takeIf { it.isNotEmpty() } ?: return null
        return listOf(base, user, config.ownPassword().orEmpty(), config.serverPassword().orEmpty()).joinToString("\u0000").hashCode().toString()
    }

    /* The address may be the one that went away: look again on the next try. */
    private fun markUnreachable() = synchronized(lock) {
        unreachableUntil = now() + UNREACHABLE_BACKOFF_MS
        unreachableFor = candidates()
        found = null
    }

    private fun backedOff(candidates: List<String>): Boolean = synchronized(lock) { now() < unreachableUntil && unreachableFor == candidates }

    private suspend fun service(): DeemixService? {
        val url = resolveBase() ?: return null
        service?.takeIf { it.first == url }?.let { return it.second }

        val created = try {
            Retrofit.Builder().baseUrl(url).addConverterFactory(GsonConverterFactory.create()).client(http).build().create(DeemixService::class.java)
        } catch (e: IllegalArgumentException) {
            // A malformed address in the settings.
            return null
        }
        service = url to created
        return created
    }

    private companion object {
        /** The port docker-compose publishes the site on, next to Navidrome. */
        const val DEFAULT_PORT = 8088

        /** The name the site is published under beside the server's, behind a reverse proxy. */
        const val SUBDOMAIN = "deemix"

        /* A search is superseded by the next keystroke long before this. */
        const val SEARCH_TIMEOUT_S = 30L

        /* Queuing an artist reads every album of the discography from Deezer and checks each track against the library first: minutes on a long one. */
        const val DOWNLOAD_TIMEOUT_S = 300L

        const val MAX_TOP_TRACKS = 50
        const val MAX_RELEASES = 500
        const val MAX_ALBUM_TRACKS = 500

        /* After a failed connection, how long the search stops knocking on every keystroke. */
        const val UNREACHABLE_BACKOFF_MS = 60 * 1000L

        val CREDIT_JOINS = Regex("\\s*(?:/|,|;|&|\\s(?:feat|ft)\\.?\\s)\\s*")
        val FEATURING = Regex("\\s*[(\\[](?:feat|ft|with)\\.?\\s[^)\\]]*[)\\]]")
    }
}
