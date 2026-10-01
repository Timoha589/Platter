package com.cappielloantonio.tempo.lyricsearch

import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.deemix.DeezerCatalog
import com.cappielloantonio.tempo.helper.search.QueryVariants
import com.cappielloantonio.tempo.subsonic.models.Child
import com.cappielloantonio.tempo.viewmodel.DeezerHit
import com.cappielloantonio.tempo.wave.WaveClient
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/*
 * Finding a song by a line of it, from two places at once.
 *
 * The library's own lyrics - the ones embedded in the files. Neither the
 * Subsonic API nor Navidrome can search them (search3 reads titles, artists
 * and albums, and lyrics come one song at a time), so the wave service on the
 * server keeps an index of them; see lyrics.py in the Daylist project. What it
 * finds is exactly the file that holds the line.
 *
 * And Genius, for the songs whose files carry no lyrics and the songs the
 * library does not have. The search its own site runs has a lyrics index,
 * needs no account, and is as good on Russian as on English. Genius says which
 * song a line is from; each one it names is looked up by title in the library
 * and kept as a library track when an artist agrees - otherwise it is still
 * worth showing, as the answer to "what song is this", with the way to get it.
 */

@Keep
data class GeniusResponse(val response: GeniusSearch?)

@Keep
data class GeniusSearch(val sections: List<GeniusSection>?)

@Keep
data class GeniusSection(val type: String?, val hits: List<GeniusHit>?)

@Keep
data class GeniusHit(val highlights: List<GeniusHighlight>?, val result: GeniusSong?)

@Keep
data class GeniusHighlight(val property: String?, val value: String?)

@Keep
data class GeniusSong(
        val id: Long?,
        val title: String?,
        @SerializedName("artist_names") val artistNames: String?,
        @SerializedName("primary_artist") val primaryArtist: GeniusArtist?,
        @SerializedName("song_art_image_thumbnail_url") val thumbnail: String?
)

@Keep
data class GeniusArtist(val name: String?)

interface GeniusService {
    @GET("api/search/lyric")
    fun searchLyrics(@Query("q") query: String, @Query("per_page") perPage: Int): Call<GeniusResponse>
}

/**
 * A song a line was found in: in the library when [song] is set, otherwise on
 * Deezer as [deezer] - ready to preview and download like any row of the
 * Deezer section. A song neither has is never one of these.
 */
data class LyricMatch(
        val title: String,
        val artist: String,
        /** The lines the query was found in, joined with " / ". */
        val snippet: String,
        val song: Child?,
        val image: String?,
        val deezer: DeezerHit? = null,
        /** Every spelling Genius gives the artist, folded - for finding the song on Deezer. */
        internal val artistNames: List<String> = emptyList()
) {
    val inLibrary: Boolean get() = song != null
}

class LyricSearchRequest internal constructor() {
    @Volatile
    var isCancelled = false
        private set

    @Volatile
    internal var future: Future<*>? = null

    fun cancel() {
        isCancelled = true
        future?.cancel(true)
    }
}

@UnstableApi
object LyricSearch {
    /*
     * Genius sits behind Cloudflare, which turns away OkHttp's own user agent
     * with a 401. A browser's is let through.
     */
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    private const val GENIUS_HITS = 10

    /* One library lookup per distinct title Genius names; past this the hits are noise. */
    private const val MAX_LOOKUPS = 8
    private const val LIBRARY_SONGS_PER_LOOKUP = 20

    /* A line runs over a line break as often as not - "ждут / отпечатков". */
    private const val MOST_SNIPPET_LINES = 3

    /* The library's own lyrics, through the wave service; see WaveClient.searchLyrics. */
    private const val EMBEDDED_RESULTS = 10
    private const val EMBEDDED_WAIT_S = 10L

    /* How much of the query a Genius hit's own lines must hold to count. */
    private const val MIN_GENIUS_COVERAGE = 0.6

    /*
     * Songs the library lacks: a few at most, and only the ones Deezer has.
     * More are looked up than are kept, so the ones Deezer does not have do
     * not cost the ones it does their place.
     */
    private const val MAX_ELSEWHERE = 4
    private const val MAX_DEEZER_LOOKUPS = 8

    /* Deezer's own search, for the songs the library lacks: about half a second a query. */
    private const val DEEZER_CANDIDATES = 10
    private const val DEEZER_WAIT_S = 8L

    private val service: GeniusService by lazy {
        Retrofit.Builder()
                .baseUrl("https://genius.com/")
                .addConverterFactory(GsonConverterFactory.create())
                .client(OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .addInterceptor { chain ->
                            chain.proceed(chain.request().newBuilder()
                                    .header("User-Agent", USER_AGENT)
                                    .header("Accept", "application/json")
                                    .build())
                        }
                        .build())
                .build()
                .create(GeniusService::class.java)
    }

    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Songs whose lyrics hold [query], library tracks first. The callback runs
     * on the main thread, with an empty list when Genius could not be reached -
     * and not at all once the request is cancelled.
     */
    @JvmStatic
    fun search(query: String, callback: (List<LyricMatch>) -> Unit): LyricSearchRequest {
        val request = LyricSearchRequest()

        request.future = executor.submit {
            val matches = try {
                find(query, request)
            } catch (e: Exception) {
                emptyList()
            }

            mainHandler.post { if (!request.isCancelled) callback(matches) }
        }

        return request
    }

    private fun find(query: String, request: LyricSearchRequest): List<LyricMatch> {
        // The two sources are independent, and the slower one sets the pace: ask both at once.
        val embedded = executor.submit(Callable { WaveClient.searchLyrics(query, EMBEDDED_RESULTS) })
        val genius = try {
            fromGenius(query, request)
        } catch (e: Exception) {
            GeniusFound(emptyList(), emptyList())
        }

        val own = runCatching { embedded.get(EMBEDDED_WAIT_S, TimeUnit.SECONDS) }.getOrNull()
                ?.results.orEmpty()
                .mapNotNull { found ->
                    val song = found.song ?: return@mapNotNull null
                    LyricMatch(song.title.orEmpty(), song.artistLine().orEmpty(), found.snippet.orEmpty(), song, null)
                }

        /*
         * The library's own lyrics come first: they are the words of exactly
         * that file. Genius adds what they miss - a song whose file carries no
         * lyrics - and, last, the songs the library does not have at all,
         * unless it has one by that title already.
         */
        val seenSongs = mutableSetOf<String>()
        val seenTitles = mutableSetOf<String>()
        val inLibrary = (own + genius.inLibrary).filter { match ->
            val song = match.song!!
            val same = comparable(match.title) + "\u0000" + comparable(match.artist)
            seenTitles += comparable(stripTrailingBrackets(match.title))
            seenSongs.add(song.id) and seenSongs.add(same)
        }

        val missing = genius.elsewhere
                .filter { comparable(it.title) !in seenTitles }
                .distinctBy { comparable(it.title) + "\u0000" + comparable(it.artist) }
                .take(MAX_DEEZER_LOOKUPS)

        /*
         * What the library lacks is only worth a row with a way to get it:
         * each is looked for on Deezer, side by side, and the ones Deezer does
         * not have are dropped - a song that can be neither played nor
         * downloaded answers nothing the user can act on.
         */
        val onDeezer = missing.map { match -> executor.submit(Callable { deezerTrackFor(match) }) }

        val downloadable = missing.mapIndexedNotNull { index, match ->
            runCatching { onDeezer[index].get(DEEZER_WAIT_S, TimeUnit.SECONDS) }.getOrNull()
                    ?.let { match.copy(deezer = it) }
        }

        return inLibrary + downloadable.take(MAX_ELSEWHERE)
    }

    /**
     * The same song in Deezer's catalogue, or null when Deezer does not have it.
     *
     * Deezer's search wants plain words: its artist:"" track:"" syntax found
     * none of the songs tried, "Аквариум Мама, я не могу больше пить" finds
     * nothing either - Deezer files that band as Aquarium - and the title on its
     * own does. So the artist and title are tried together, then the title
     * alone, and a result only counts when its title is the song's and its
     * artist is one of the names Genius gives.
     */
    private fun deezerTrackFor(match: LyricMatch): DeezerHit? {
        val queries = listOf("${match.artist} ${match.title}", match.title)
                .map { it.replace(QUOTES, "").trim() }
                .distinct()

        for (query in queries) {
            val tracks = try {
                DeezerCatalog.service.searchTracks(query, DEEZER_CANDIDATES).execute().body()?.data.orEmpty()
            } catch (e: Exception) {
                return null
            }

            val track = tracks.firstOrNull {
                sameTitle(it.title, match.title) && sameArtist(it.artist?.name, match.artistNames)
            }
            if (track != null) return DeezerHit.track(track, false)
        }

        return null
    }

    private class GeniusFound(val inLibrary: List<LyricMatch>, val elsewhere: List<LyricMatch>)

    private fun fromGenius(query: String, request: LyricSearchRequest): GeniusFound {
        val hits = service.searchLyrics(query, GENIUS_HITS).execute().body()
                ?.response?.sections.orEmpty()
                .flatMap { it.hits.orEmpty() }
                .filter { it.result?.title != null }

        if (hits.isEmpty() || request.isCancelled) return GeniusFound(emptyList(), emptyList())

        // Every version of a song Genius holds is a separate hit; the library is asked once per song.
        val songs = hits.map { lookupKey(it.result!!) }.distinct().take(MAX_LOOKUPS)
        val lookups = songs.associateWith { (title, artist) -> executor.submit(Callable { librarySongs(title, artist) }) }

        val inLibrary = mutableListOf<LyricMatch>()
        val elsewhere = mutableListOf<LyricMatch>()

        for (hit in hits) {
            val genius = hit.result!!
            val title = plainTitle(genius.title!!)
            val artists = artistNames(genius)
            val (snippet, coverage) = snippet(hit.highlights?.firstOrNull { it.property == "lyrics" }?.value, query)

            /*
             * Genius answers every query with something, down to songs that
             * share two words with it. A hit whose own lines do not hold most
             * of what was typed is not the song the line came from.
             */
            if (coverage < MIN_GENIUS_COVERAGE) continue

            val candidates = lookups[lookupKey(genius)]?.let { runCatching { it.get(15, TimeUnit.SECONDS) }.getOrNull() }.orEmpty()
            val song = candidates
                    .filter { sameTitle(it.title, title) && sameArtist(it.artistLine(), artists) }
                    .minByOrNull { otherVersion(it.title, genius.title) }

            if (song != null) {
                inLibrary += LyricMatch(song.title ?: title, song.artistLine().orEmpty(), snippet, song, null)
            } else {
                /*
                 * A song the library has is not also listed as missing because
                 * Genius holds a cover of it too - "Группа крови" by GSPD under
                 * Кино's own. find() drops those by title.
                 */
                elsewhere += LyricMatch(title, plainName(genius.primaryArtist?.name ?: genius.artistNames.orEmpty()),
                        snippet, null, genius.thumbnail, artistNames = artists)
            }
        }

        return GeniusFound(inLibrary, elsewhere)
    }

    private fun lookupKey(song: GeniusSong): Pair<String, String> =
            plainTitle(song.title!!) to plainName(song.primaryArtist?.name ?: song.artistNames.orEmpty())

    /*
     * The title alone is not enough to find a song by: "Enemy" is the title
     * of twenty-odd songs in a big library, and search3 hands back the first
     * twenty - Imagine Dragons' was not among them, and a song the library
     * had was offered as a download. Title and artist together find it; the
     * title alone is still asked, side by side, for a library that spells the
     * artist otherwise - Кино where Genius says Kino.
     */
    private fun librarySongs(title: String, artist: String): List<Child> {
        val queries = listOf("$title $artist", title).map { it.replace(QUOTES, "").trim() }.filter { it.isNotEmpty() }.distinct()
        val asked = queries.map { query -> executor.submit(Callable { search3(query) }) }
        return asked.flatMap { runCatching { it.get(15, TimeUnit.SECONDS) }.getOrNull().orEmpty() }.distinctBy { it.id }
    }

    private fun search3(query: String): List<Child> {
        val response = App.getSubsonicClientInstance(false)
                .searchingClient
                .search3(query, LIBRARY_SONGS_PER_LOOKUP, 0, 0)
                .execute()

        return response.body()?.subsonicResponse?.searchResult3?.songs.orEmpty()
    }

    /*
     * Genius appends a translation or a romanisation to a title in another
     * script - "Группа крови (Blood Type)", "КИНО (KINO)" - which no tag in the
     * library carries. A bracket that is part of the real title, "(Remix)", is
     * dropped too; the library title is compared with its own brackets dropped,
     * so the two meet in the middle.
     */
    private fun plainTitle(title: String): String = stripTrailingBrackets(title.replace(' ', ' ')).ifEmpty { title }

    private fun plainName(name: String): String = stripTrailingBrackets(name).ifEmpty { name }

    private fun stripTrailingBrackets(text: String): String {
        var result = text.trim()
        while (result.endsWith(")") || result.endsWith("]")) {
            val open = result.lastIndexOf(if (result.endsWith(")")) '(' else '[')
            if (open <= 0) break
            result = result.substring(0, open).trim()
        }
        return result
    }

    /*
     * How far a library title is from the version Genius names, when the two
     * agree once their brackets are dropped: "Enemy (Live in Vegas)" is the
     * same title as "Enemy", but not the song a line of the studio "Enemy"
     * should lead to while the library has that too. 0 is the very title;
     * past that, one for every version word the library title has and
     * Genius's does not.
     */
    private fun otherVersion(libraryTitle: String?, geniusTitle: String): Int {
        val library = comparable(libraryTitle.orEmpty())
        val genius = comparable(geniusTitle)
        if (library == genius) return 0

        val geniusWords = genius.split(" ").toSet()
        return 1 + library.split(" ").count { it in VERSION_WORDS && it !in geniusWords }
    }

    private val VERSION_WORDS = setOf("live", "remix", "mix", "acoustic", "instrumental", "demo", "karaoke",
            "cover", "slowed", "sped", "reverb", "nightcore", "edit", "version", "remastered", "remaster", "mono")

    private fun sameTitle(libraryTitle: String?, geniusTitle: String): Boolean {
        if (libraryTitle == null) return false
        return comparable(stripTrailingBrackets(libraryTitle)) == comparable(geniusTitle)
    }

    /*
     * Every way Genius names who performed it: the primary artist with and
     * without the bracket (either half may be how the library spells them), and
     * the full credit split at its joins.
     */
    private fun artistNames(song: GeniusSong): List<String> {
        val names = mutableListOf<String>()

        song.primaryArtist?.name?.let { name ->
            names += name
            names += stripTrailingBrackets(name)
            Regex("\\(([^)]*)\\)").findAll(name).forEach { names += it.groupValues[1] }
        }

        song.artistNames?.split(Regex(",|&|\\(Ft\\.|\\(feat\\.|\\)", RegexOption.IGNORE_CASE))?.forEach { names += it }

        return names.map { comparable(it) }.filter { it.length >= 2 }.distinct()
    }

    private fun sameArtist(libraryArtists: String?, geniusArtists: List<String>): Boolean {
        val library = comparable(libraryArtists ?: return false)
        if (library.isEmpty()) return false

        return geniusArtists.any { library.contains(it) || it.contains(library) }
    }

    /** Folded, with punctuation and runs of spaces gone: "Can’t" and "Cant" are the same word here. */
    private fun comparable(text: String): String =
            QueryVariants.normalize(text).replace(APOSTROPHES, "").replace(NOT_A_WORD, " ").trim()

    /* "I'd" is typed "id" as often as not; the server's index folds it the same way. */
    private val APOSTROPHES = Regex("['’ʼ`]")
    private val QUOTES = Regex("[\"“”«»]")
    private val NOT_A_WORD = Regex("[^\\p{L}\\p{N}]+")

    /**
     * The lines the query is in, out of the few Genius sends around it. The
     * window of up to three consecutive lines that holds the most words of the
     * query wins; between two that hold as many, the shorter. Returned with the
     * share of the query's words it holds.
     */
    private fun snippet(highlight: String?, query: String): Pair<String, Double> {
        val lines = highlight.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return "" to 0.0

        val wanted = comparable(query).split(" ").filter { it.isNotEmpty() }.toSet()

        var best = lines.subList(0, 1)
        var bestScore = -1

        for (start in lines.indices) {
            for (size in 1..MOST_SNIPPET_LINES) {
                if (start + size > lines.size) break

                val window = lines.subList(start, start + size)
                val words = comparable(window.joinToString(" ")).split(" ").toSet()
                val score = wanted.count { it in words }

                if (score > bestScore) {
                    best = window
                    bestScore = score
                }
            }
        }

        return best.joinToString(" / ") to if (wanted.isEmpty()) 0.0 else bestScore.toDouble() / wanted.size
    }
}
