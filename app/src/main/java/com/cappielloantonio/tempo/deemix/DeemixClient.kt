package com.cappielloantonio.tempo.deemix

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.helper.search.QueryVariants
import com.cappielloantonio.tempo.subsonic.models.Child
import com.cappielloantonio.tempo.util.NetworkAddress
import com.cappielloantonio.tempo.util.Preferences
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What a call to Deemix plus came to. Built for Java callers as much as
 * Kotlin ones, so it is a plain class rather than a sealed one.
 */
class DeemixResult<T> private constructor(
        val value: T?,
        val failure: Failure?,
        /** The server's own explanation, when it gave one - already in the user's language. */
        val message: String?
) {
    enum class Failure {
        /** No address to try, or nothing answered there. */
        UNREACHABLE,
        /** The service answered, but neither password opened this account. */
        NO_ACCOUNT,
        /** The daily limit - the account's or the server's - has run out. */
        QUOTA,
        ERROR
    }

    val isOk: Boolean get() = failure == null

    companion object {
        fun <T> ok(value: T) = DeemixResult(value, null, null)
        fun <T> failed(failure: Failure, message: String? = null) = DeemixResult<T>(null, failure, message)
    }
}

fun interface DeemixCallback<T> {
    fun onResult(result: DeemixResult<T>)
}

/** A request the caller may give up on; its callback then never runs. */
class DeemixRequest internal constructor() {
    @Volatile
    var isCancelled = false
        private set

    @Volatile
    internal var call: Call<*>? = null

    fun cancel() {
        isCancelled = true
        call?.cancel()
    }
}

/**
 * Talks to Deemix plus as the signed-in listener.
 *
 * The site has its own accounts, but they are meant to mirror the Navidrome
 * ones, so the app signs in with the name and password it already holds and
 * asks for nothing more. Where the two passwords have drifted apart, the one
 * entered in the settings is tried as well. The token that comes back lasts a
 * day; it lives in memory only and is fetched again on the first 401.
 *
 * Every call runs on a background thread and reports back on the main one.
 */
object DeemixClient {
    private const val TAG = "DeemixClient"

    /** The port docker-compose publishes the site on, next to Navidrome. */
    private const val DEFAULT_PORT = 8088

    /** The name the site is published under beside the server's, behind a reverse proxy. */
    private const val SUBDOMAIN = "deemix"

    /* A search is superseded by the next keystroke long before this. */
    private const val SEARCH_TIMEOUT_S = 30L

    /*
     * Queuing an artist reads every album of the discography from Deezer and
     * checks each track against the library before anything is queued. On a
     * long discography that is minutes, not seconds.
     */
    private const val DOWNLOAD_TIMEOUT_S = 300L

    private const val MAX_TOP_TRACKS = 50
    private const val MAX_RELEASES = 500
    private const val MAX_ALBUM_TRACKS = 500

    /* Enough for a whole discography, joint credits included. */
    private const val LIBRARY_SONGS_PER_ARTIST = 500

    private val CREDIT_JOINS = Regex("\\s*(?:/|,|;|&|\\s(?:feat|ft)\\.?\\s)\\s*")
    private val FEATURING = Regex("\\s*[(\\[](?:feat|ft|with)\\.?\\s[^)\\]]*[)\\]]")

    /* After a failed connection, how long the search stops knocking on every keystroke. */
    private const val UNREACHABLE_BACKOFF_MS = 60 * 1000L

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(DOWNLOAD_TIMEOUT_S, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
    }

    /* Only asks whether the site is there: an address that is not must not hold a search up. */
    private val probe: OkHttpClient by lazy {
        http.newBuilder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(6, TimeUnit.SECONDS)
                .build()
    }

    private val executor = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())

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

    private class Session(val key: String, val token: String, val username: String, val role: String)

    /**
     * Where the site is, for showing: the address found to answer, or the
     * first one that would be tried.
     */
    @JvmStatic
    fun baseUrl(): String? {
        val candidates = candidates()
        return found?.takeIf { it.first == candidates }?.second ?: candidates.firstOrNull()
    }

    /** Every address the site is looked for at, in the order they are tried. */
    @JvmStatic
    fun candidates(): List<String> {
        val custom = Preferences.getDeemixUrl()?.trim()
        if (!custom.isNullOrEmpty()) {
            val withScheme = if ("://" in custom) custom else "http://$custom"
            return listOf(if (withScheme.endsWith("/")) withScheme else "$withScheme/")
        }

        /*
         * The address in use first: at home that is the local one, and the
         * site is next to Navidrome on the same machine. Away from home the
         * server's public name is usually a reverse proxy that passes on
         * Navidrome alone, with the site on a name of its own beside it -
         * deemix.example.com next to music.example.com.
         */
        val servers = listOfNotNull(Preferences.getInUseServerAddress(), Preferences.getServer())
                .mapNotNull { it.trim().toHttpUrlOrNull() }
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

    /*
     * The address to call: the first candidate where the site answers. Found
     * once for each set of candidates - they change as the phone leaves home
     * or comes back - and looked for again after a connection fails. A
     * redirect is followed here, once, so that http:// written in the
     * settings still reaches a site served over https: calls that send a body
     * would not follow it.
     */
    private fun resolveBase(): String? {
        val candidates = candidates()
        found?.takeIf { it.first == candidates }?.let { return it.second }
        if (candidates.isEmpty()) return null
        if (synchronized(lock) { SystemClock.elapsedRealtime() < unreachableUntil }) return candidates.first()

        val base = candidates.firstNotNullOfOrNull(::siteAt)
        if (base == null) {
            markUnreachable()
            return candidates.first()
        }
        found = candidates to base
        return base
    }

    /*
     * Where [base] leads, if the site answers there. Asking who is signed in
     * without a token is refused, but refused by FastAPI, in JSON - Navidrome
     * or a proxy's error page at the same address answers otherwise.
     */
    private fun siteAt(base: String): String? = try {
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

    /** The account the site knows this listener as, once signed in. */
    @JvmStatic
    fun account(): Pair<String, String>? = synchronized(lock) {
        session?.takeIf { it.key == credentialKey(baseUrl()) }?.let { it.username to it.role }
    }

    /** Forgets a failed sign-in, so the next call tries again. */
    @JvmStatic
    fun retry() = synchronized(lock) {
        rejectedKey = null
        unreachableUntil = 0L
        found = null
    }

    @JvmStatic
    fun usage(callback: DeemixCallback<DeemixUsage>): DeemixRequest =
            run(callback) { request -> authorized(request, SEARCH_TIMEOUT_S) { usage(it) } }

    /**
     * Searches Deezer, and marks which of the tracks found the library already
     * has - the same check the site runs before offering a download.
     */
    @JvmStatic
    fun search(query: String, callback: DeemixCallback<DeezerResults>): DeemixRequest =
            run(callback) { request ->
                val found = authorized(request, SEARCH_TIMEOUT_S) { search(it, query) }
                if (!found.isOk) return@run DeemixResult.failed(found.failure!!, found.message)

                val search = found.value!!
                DeemixResult.ok(DeezerResults(search, libraryMarks(request, search.tracks.orEmpty())))
            }

    /**
     * An artist's page: who they are, their most played tracks, and every
     * release - read from Deezer's own catalogue, the tracks marked against
     * the library through Deemix plus.
     */
    @JvmStatic
    fun artistPage(id: Long, callback: DeemixCallback<DeezerArtistPage>): DeemixRequest =
            run(callback) { request ->
                // Three independent reads of about a second each: side by side, not in a row.
                val releases = executor.submit<List<DeezerAlbum>?> { catalog { albums(id, MAX_RELEASES) }?.data }
                val popular = executor.submit<List<DeezerTrack>?> { catalog { top(id, MAX_TOP_TRACKS) }?.data }

                val artist = catalog { artist(id) }?.takeIf { it.id != null }
                        ?: return@run DeemixResult.failed(DeemixResult.Failure.UNREACHABLE)
                val top = popular.get().orEmpty()
                val albums = releases.get().orEmpty()

                DeemixResult.ok(DeezerArtistPage(artist, top, albums, libraryMarks(request, top)))
            }

    /** An album's page: the record and its tracklist, marked against the library. */
    @JvmStatic
    fun albumPage(id: Long, callback: DeemixCallback<DeezerAlbumPage>): DeemixRequest =
            run(callback) { request ->
                val tracklist = executor.submit<List<DeezerTrack>?> { catalog { albumTracks(id, MAX_ALBUM_TRACKS) }?.data }

                val album = catalog { album(id) }?.takeIf { it.id != null }
                        ?: return@run DeemixResult.failed(DeemixResult.Failure.UNREACHABLE)
                val tracks = tracklist.get().orEmpty()

                DeemixResult.ok(DeezerAlbumPage(album, tracks, libraryMarks(request, tracks)))
            }

    /* A catalogue call's body, or null when Deezer could not be reached or said no. */
    private fun <T> catalog(make: DeezerCatalogService.() -> Call<T>): T? = try {
        DeezerCatalog.service.make().execute().takeIf { it.isSuccessful }?.body()
    } catch (e: IOException) {
        Log.w(TAG, "deezer catalogue call failed", e)
        null
    }

    /**
     * Which of [tracks] the library already holds - the same check the site
     * runs before offering a download. An answer without the marks is still an
     * answer: every track then simply offers a download, and the server skips
     * the ones it already has.
     */
    private fun libraryMarks(request: DeemixRequest, tracks: List<DeezerTrack>): Map<String, Boolean> {
        val checked = tracks.filter { it.id != null && it.link != null }.map {
            LibraryCheckItem(it.id.toString(), it.artist?.name.orEmpty(), it.title.orEmpty())
        }
        if (checked.isEmpty()) return emptyMap()

        val marks = authorized(request, SEARCH_TIMEOUT_S) { checkLibrary(it, LibraryCheckBody(checked)) }
                .value?.results.orEmpty()

        val unmarked = tracks.filter { it.id != null && it.link != null && marks[it.id.toString()] != true }
        return marks + creditedInLibrary(request, unmarked)
    }

    /*
     * The site matches a track on its artist exactly, so a song the library
     * tags with every performer - "ivycomb/Stephanafro" where Deezer says
     * "ivycomb" - reads as missing, and search offered to download the very
     * song it listed above. What the site leaves unmarked is looked up in the
     * library itself, one search per artist, and the artist then only has to
     * be one of the credits.
     */
    private fun creditedInLibrary(request: DeemixRequest, tracks: List<DeezerTrack>): Map<String, Boolean> {
        if (tracks.isEmpty() || request.isCancelled) return emptyMap()

        val byArtist = tracks.filter { !it.artist?.name.isNullOrBlank() }.groupBy { it.artist!!.name!! }
        val lookups = byArtist.keys.associateWith { artist -> executor.submit(Callable { librarySongsBy(artist) }) }

        val found = mutableMapOf<String, Boolean>()
        for ((artist, lookup) in lookups) {
            val songs = runCatching { lookup.get(SEARCH_TIMEOUT_S, TimeUnit.SECONDS) }.getOrNull() ?: continue
            val credits = credits(artist)

            for (track in byArtist.getValue(artist)) {
                val title = comparableTitle(track.title)
                val held = songs.any { song ->
                    comparableTitle(song.title) == title && credits(song.artistLine()).any { it in credits }
                }
                if (held) found[track.id.toString()] = true
            }
        }
        return found
    }

    /* The server's full-text search finds an artist inside a joint credit too. */
    private fun librarySongsBy(artist: String): List<Child> =
            App.getSubsonicClientInstance(false)
                    .searchingClient
                    .search3(artist, LIBRARY_SONGS_PER_ARTIST, 0, 0)
                    .execute()
                    .body()?.subsonicResponse?.searchResult3?.songs.orEmpty()

    private fun credits(artists: String?): Set<String> =
            QueryVariants.normalize(artists).split(CREDIT_JOINS).map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /* "Song (feat. Someone)" on one side and "Song" on the other are the same recording. */
    private fun comparableTitle(title: String?): String =
            QueryVariants.normalize(title).replace(FEATURING, "").trim()

    /**
     * Where to stream a track's 30-second preview from, and the header that
     * opens it. The site's own proxy, not Deezer's link from the search: that
     * one is signed and expires, the proxy fetches a fresh one each time.
     */
    @JvmStatic
    fun preview(trackId: Long, callback: DeemixCallback<DeemixPreview>): DeemixRequest =
            run(callback) {
                val signedIn = signIn()
                val token = signedIn.value ?: return@run DeemixResult.failed(signedIn.failure!!, signedIn.message)
                val base = resolveBase() ?: return@run DeemixResult.failed(DeemixResult.Failure.UNREACHABLE)

                DeemixResult.ok(DeemixPreview("${base}api/preview?track_id=$trackId", "Bearer $token"))
            }

    @JvmStatic
    fun downloadTrack(link: String, callback: DeemixCallback<DownloadMany>): DeemixRequest =
            run(callback) { request ->
                val result = authorized(request, DOWNLOAD_TIMEOUT_S) { downloadTrack(it, DownloadUrlBody(link)) }
                val one = result.value ?: return@run DeemixResult.failed(result.failure!!, result.message)

                /*
                 * A single link is queued as it is, without the library check:
                 * "already in the queue" and "already downloaded" come back as a
                 * refusal, which to the listener is the same thing as a skip.
                 */
                if (one.success == true) {
                    DeemixResult.ok(DownloadMany(true, 1, 0, 1, null, null))
                } else {
                    DeemixResult.ok(DownloadMany(true, 0, 1, 1, one.error, null))
                }
            }

    @JvmStatic
    fun downloadAlbum(id: Long, title: String, callback: DeemixCallback<DownloadMany>): DeemixRequest =
            run(callback) { request ->
                many(authorized(request, DOWNLOAD_TIMEOUT_S) { downloadAlbum(it, DownloadAlbumBody(id, title)) })
            }

    @JvmStatic
    fun downloadArtist(id: Long, name: String, callback: DeemixCallback<DownloadMany>): DeemixRequest =
            run(callback) { request ->
                many(authorized(request, DOWNLOAD_TIMEOUT_S) { downloadArtist(it, DownloadArtistBody(id, name)) })
            }

    /* The bulk routes report "could not read the album" in the body, with a 200. */
    private fun many(result: DeemixResult<DownloadMany>): DeemixResult<DownloadMany> {
        val many = result.value ?: return result
        return if (many.success == false) DeemixResult.failed(DeemixResult.Failure.ERROR, many.error) else result
    }

    private fun <T> run(callback: DeemixCallback<T>, work: (DeemixRequest) -> DeemixResult<T>): DeemixRequest {
        val request = DeemixRequest()
        executor.execute {
            val result = try {
                work(request)
            } catch (e: Exception) {
                Log.w(TAG, "deemix call failed", e)
                DeemixResult.failed(DeemixResult.Failure.ERROR, e.message)
            }
            main.post { if (!request.isCancelled) callback.onResult(result) }
        }
        return request
    }

    /**
     * Runs one call with a token, signing in first if there is none yet and
     * once more if the server has stopped accepting the one there is.
     */
    private fun <T> authorized(
            request: DeemixRequest,
            timeoutS: Long,
            make: DeemixService.(String) -> Call<T>
    ): DeemixResult<T> {
        for (attempt in 0..1) {
            if (request.isCancelled) return DeemixResult.failed(DeemixResult.Failure.ERROR)

            val signedIn = signIn()
            val token = signedIn.value ?: return DeemixResult.failed(signedIn.failure!!, signedIn.message)
            val api = service() ?: return DeemixResult.failed(DeemixResult.Failure.UNREACHABLE)

            val call = api.make("Bearer $token")
            call.timeout().timeout(timeoutS, TimeUnit.SECONDS)
            request.call = call

            val response = try {
                call.execute()
            } catch (e: IOException) {
                if (!request.isCancelled) markUnreachable()
                return DeemixResult.failed(DeemixResult.Failure.UNREACHABLE, e.message)
            }

            if (response.code() == 401 && attempt == 0) {
                synchronized(lock) { if (session?.token == token) session = null }
                continue
            }
            return toResult(response)
        }
        return DeemixResult.failed(DeemixResult.Failure.NO_ACCOUNT)
    }

    private fun <T> toResult(response: Response<T>): DeemixResult<T> {
        val body = response.body()
        if (response.isSuccessful && body != null) return DeemixResult.ok(body)

        val detail = detail(response.errorBody()?.use { it.string() })
        return when (response.code()) {
            401, 403 -> DeemixResult.failed(DeemixResult.Failure.NO_ACCOUNT, detail)
            429 -> DeemixResult.failed(DeemixResult.Failure.QUOTA, detail)
            else -> DeemixResult.failed(DeemixResult.Failure.ERROR, detail ?: "HTTP ${response.code()}")
        }
    }

    /* FastAPI puts its reason under "detail" - a string for everything this app calls. */
    private fun detail(body: String?): String? = try {
        val detail = JsonParser.parseString(body ?: "").asJsonObject.get("detail")
        if (detail != null && detail.isJsonPrimitive) detail.asString else null
    } catch (e: Exception) {
        null
    }

    private fun signIn(): DeemixResult<String> {
        val key = credentialKey(resolveBase()) ?: return DeemixResult.failed(DeemixResult.Failure.UNREACHABLE)

        synchronized(lock) {
            session?.takeIf { it.key == key }?.let { return DeemixResult.ok(it.token) }
            if (rejectedKey == key) return DeemixResult.failed(DeemixResult.Failure.NO_ACCOUNT)
            if (SystemClock.elapsedRealtime() < unreachableUntil) {
                return DeemixResult.failed(DeemixResult.Failure.UNREACHABLE)
            }
        }

        val api = service() ?: return DeemixResult.failed(DeemixResult.Failure.UNREACHABLE)
        val user = Preferences.getUser().orEmpty()

        for (password in passwords()) {
            val response = try {
                api.login(DeemixLoginBody(user, password)).execute()
            } catch (e: IOException) {
                markUnreachable()
                return DeemixResult.failed(DeemixResult.Failure.UNREACHABLE, e.message)
            }

            val token = response.body()?.token
            if (response.isSuccessful && token != null) {
                val login = response.body()!!
                synchronized(lock) {
                    session = Session(key, token, login.username ?: user, login.role ?: "user")
                }
                return DeemixResult.ok(token)
            }
            if (response.code() != 401) {
                return DeemixResult.failed(DeemixResult.Failure.ERROR, detail(response.errorBody()?.use { it.string() }))
            }
        }

        synchronized(lock) { rejectedKey = key }
        return DeemixResult.failed(DeemixResult.Failure.NO_ACCOUNT)
    }

    /**
     * The passwords worth trying, the one set for the site first: it is only
     * ever entered because the Navidrome one did not work there.
     *
     * The Navidrome password goes only where it cannot be read on the way -
     * over HTTPS, or to an address on this network. It was given to the
     * music server, not to whatever the site's address happens to point at.
     */
    private fun passwords(): List<String> {
        val own = Preferences.getDeemixPassword()?.takeIf { it.isNotEmpty() }
        val server = Preferences.getPassword()?.takeIf { it.isNotEmpty() }
        val url = resolveBase()?.toHttpUrlOrNull()
        val serverAllowed = url != null && NetworkAddress.isPrivateOrEncrypted(url)

        return listOfNotNull(own, server?.takeIf { serverAllowed }).distinct()
    }

    /* Anything that changes who signs in, or where, makes the old token someone else's. */
    private fun credentialKey(base: String?): String? {
        base ?: return null
        val user = Preferences.getUser()?.takeIf { it.isNotEmpty() } ?: return null
        return listOf(base, user, Preferences.getDeemixPassword().orEmpty(), Preferences.getPassword().orEmpty())
                .joinToString("\u0000")
                .hashCode()
                .toString()
    }

    /* The address may be the one that went away: look again on the next try. */
    private fun markUnreachable() = synchronized(lock) {
        unreachableUntil = SystemClock.elapsedRealtime() + UNREACHABLE_BACKOFF_MS
        found = null
    }

    private fun service(): DeemixService? {
        val url = resolveBase() ?: return null
        service?.takeIf { it.first == url }?.let { return it.second }

        val created = try {
            Retrofit.Builder()
                    .baseUrl(url)
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(http)
                    .build()
                    .create(DeemixService::class.java)
        } catch (e: IllegalArgumentException) {
            // A malformed address in the settings.
            Log.w(TAG, "bad deemix address $url", e)
            return null
        }
        service = url to created
        return created
    }
}

class DeemixPreview(val url: String, val authorization: String)

class DeezerArtistPage(
        val artist: DeezerArtist,
        val top: List<DeezerTrack>,
        val albums: List<DeezerAlbum>,
        val inLibrary: Map<String, Boolean>
)

class DeezerAlbumPage(val album: DeezerAlbum, val tracks: List<DeezerTrack>, val inLibrary: Map<String, Boolean>)

/** A Deezer search, with the tracks the library already holds marked by id. */
class DeezerResults(val search: DeezerSearch, val inLibrary: Map<String, Boolean>) {
    fun isInLibrary(track: DeezerTrack): Boolean = inLibrary[track.id.toString()] == true
}
