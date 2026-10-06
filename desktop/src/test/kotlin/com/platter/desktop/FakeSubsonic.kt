package com.platter.desktop

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.sin

/**
 * A small Subsonic server for tests: eight albums, a few artists and playlists,
 * generated cover art, and a four second WAV tone for every stream. Album
 * "a0" has four-second songs so playback and scrobbling can be watched in
 * real time; the others carry realistic lengths for screenshots.
 */
class FakeSubsonic : AutoCloseable {
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<HttpUrl>()
    /* By IP: MockWebServer names itself after the machine (Docker maps "localhost" to a *.docker.internal name), and the wave sends the password only to addresses it can tell are private. */
    val url: String get() = "http://127.0.0.1:${server.port}/"

    /** Where the wave service answers: the server's own address with /wave/. */
    val waveUrl: String get() = url + "wave/"

    /** Each `X-Wave-Password` header seen, and the JSON body of each wave `next`. */
    val wavePasswords = CopyOnWriteArrayList<String?>()
    val waveNextBodies = CopyOnWriteArrayList<String>()

    /** When set, `next` answers 503 the way the service does when it cannot build a batch. */
    @Volatile
    var waveNextFails = false
    private var waveBatch = 0

    private val albumNames = listOf("Kind of Blue", "Blue Train", "Abbey Road", "Rumours", "Nevermind", "OK Computer", "Discovery", "Random Access Memories")
    private val artistNames = listOf("Miles Davis", "John Coltrane", "The Beatles", "Fleetwood Mac", "Nirvana", "Radiohead", "Daft Punk", "Daft Punk")

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                requests += url
                // A form post carries its parameters in the body; read them as if they had come in the query.
                // Only the REST api: the wave service reads its own bodies.
                val form = if (request.method == "POST" && url.pathSegments.firstOrNull() == "rest") request.body.readUtf8().also { formBodies += url.pathSegments.last() to it }.let { "http://form.invalid/?$it".toHttpUrl() } else url
                if (url.pathSegments.firstOrNull() == "wave") return wave(request, url)
                if (url.pathSegments.firstOrNull() == "api") return deemix(request, url)
                if (url.pathSegments.firstOrNull() == "deezer") return deezerCatalog(url)
                if (url.pathSegments.firstOrNull() == "radio") return MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wav(seconds = 30)))
                // Real servers answer both /rest/ping and /rest/ping.view.
                return when (url.pathSegments.lastOrNull()?.removeSuffix(".view")) {
                    "getLyricsBySongId" -> ok(""""lyricsList":{"structuredLyrics":${lyricsFor(url.queryParameter("id").orEmpty())}}""")
                    "setRating" -> ok("")
                    "getLyrics" -> ok(""""lyrics":{"value":"${if (url.queryParameter("artist") == "Fleetwood Mac") "First plain line\\nSecond plain line" else ""}"}""")
                    "ping" -> ok("")
                    "getAlbumList2" -> ok(""""albumList2":{"album":[${albumList(url).joinToString(",") { albumJson(it, withSongs = false) }}]}""")
                    "getAlbum" -> ok(""""album":${albumJson(url.queryParameter("id")!!.removePrefix("a").toInt(), withSongs = true)}""")
                    "getArtists" -> ok(""""artists":{"index":[{"name":"D","artist":[${artistJson(6)}]},{"name":"M","artist":[${artistJson(0)}]}]}""")
                    "getArtist" -> ok(""""artist":{"id":"ar0","name":"Miles Davis","coverArt":"ar-0","albumCount":1,"album":[${albumJson(0, withSongs = false)}]}""")
                    "search3" -> ok(""""searchResult3":${search(url.queryParameter("query").orEmpty())}""")
                    "getStarred2" -> ok(""""starred2":{"song":[${starredSongs.joinToString(",") { songJsonById(it) }}],"album":[${albumJson(3, false)}],"artist":[${artistJson(6)}]}""")
                    "getPlaylists" -> synchronized(playlistsState) {
                        ok(""""playlists":{"playlist":[${playlistsState.entries.joinToString(",") { playlistJson(it.key, withSongs = false) }}]}""")
                    }
                    "getPlaylist" -> synchronized(playlistsState) { ok(""""playlist":${playlistJson(url.queryParameter("id")!!, withSongs = true)}""") }
                    "createPlaylist" -> synchronized(playlistsState) { createPlaylist(url) }
                    "updatePlaylist" -> synchronized(playlistsState) { updatePlaylist(url) }
                    "deletePlaylist" -> synchronized(playlistsState) { playlistsState.remove(url.queryParameter("id")); ok("") }
                    "getTopSongs" -> ok(""""topSongs":{"song":[${songJson(0, 1)},${songJson(0, 2)},${songJson(0, 3)}]}""")
                    "getSimilarSongs2" -> {
                        val seed = url.queryParameter("id").orEmpty().filter { it.isDigit() }.take(1).ifEmpty { "0" }.toInt()
                        ok(""""similarSongs2":{"song":[${(1..4).joinToString(",") { songJson((seed + 1) % albumNames.size, it) }}]}""")
                    }
                    "getArtistInfo2" -> ok(
                        """"artistInfo2":{"biography":"Trumpeter and bandleader. <a target=\"_blank\" href=\"https://www.last.fm/music/Miles+Davis\">Read more on Last.fm</a>","lastFmUrl":"https://www.last.fm/music/Miles+Davis","similarArtist":[${artistJson(1)},${artistJson(2)}]}""",
                    )
                    "getGenres" -> ok(""""genres":{"genre":[{"value":"Jazz","songCount":10,"albumCount":2},{"value":"Rock","songCount":25,"albumCount":5}]}""")
                    "getSongsByGenre" -> ok(""""songsByGenre":{"song":[${(1..4).joinToString(",") { songJson(1, it) }}]}""")
                    "createShare" -> ok(""""shares":{"share":[{"id":"sh1","url":"http://example.test/share/sh1"}]}""")
                    "startScan" -> { scansLeft = 2; ok(""""scanStatus":{"scanning":true,"count":10}""") }
                    "getScanStatus" -> ok(""""scanStatus":{"scanning":${if (scansLeft-- > 0) "true" else "false"},"count":20}""")
                    "scrobble" -> if (scrobblesFail) MockResponse().setResponseCode(503) else ok("")
                    "star" -> { url.queryParameter("id")?.let { starredSongs += it }; ok("") }
                    "unstar" -> { url.queryParameter("id")?.let { starredSongs -= it }; ok("") }
                    "getOpenSubsonicExtensions" -> ok(""""openSubsonicExtensions":[${extensions.joinToString(",") { """{"name":"$it","versions":[1]}""" }}]""")
                    "getPlayQueue" -> ok(""""playQueue":${savedQueueJson(byIndex = false)}""")
                    "getPlayQueueByIndex" -> ok(""""playQueueByIndex":${savedQueueJson(byIndex = true)}""")
                    "savePlayQueue", "savePlayQueueByIndex" -> {
                        val ids = form.queryParameterValues("id").filterNotNull()
                        savedQueue = SavedQueue(ids, form.queryParameter("currentIndex")?.toInt(), form.queryParameter("current"), form.queryParameter("position")?.toLong() ?: 0)
                        ok("")
                    }
                    "getPodcasts" -> synchronized(channels) {
                        val withEpisodes = url.queryParameter("includeEpisodes") == "true"
                        val wanted = url.queryParameter("id")
                        ok(""""podcasts":{"channel":[${channels.filter { wanted == null || it.id == wanted }.joinToString(",") { channelJson(it, withEpisodes) }}]}""")
                    }
                    "getNewestPodcasts" -> synchronized(channels) {
                        ok(""""newestPodcasts":{"episode":[${channels.flatMap { c -> c.episodes.filter { it.status == "completed" }.map { episodeJson(c, it) } }.joinToString(",")}]}""")
                    }
                    "refreshPodcasts" -> ok("")
                    "createPodcastChannel" -> synchronized(channels) {
                        channels += FakeChannel("c${nextChannel++}", url.queryParameter("url").orEmpty(), url.queryParameter("url").orEmpty(), "new", mutableListOf())
                        ok("")
                    }
                    "deletePodcastChannel" -> synchronized(channels) { channels.removeAll { it.id == url.queryParameter("id") }; ok("") }
                    "deletePodcastEpisode" -> synchronized(channels) { channels.forEach { c -> c.episodes.removeAll { it.id == url.queryParameter("id") } }; ok("") }
                    "downloadPodcastEpisode" -> synchronized(channels) {
                        channels.flatMap { it.episodes }.firstOrNull { it.id == url.queryParameter("id") }?.status = "downloading"
                        ok("")
                    }
                    "getInternetRadioStations" -> synchronized(stations) {
                        ok(""""internetRadioStations":{"internetRadioStation":[${stations.joinToString(",") { stationJson(it) }}]}""")
                    }
                    "createInternetRadioStation" -> synchronized(stations) {
                        stations += FakeStation("r${nextStation++}", url.queryParameter("name").orEmpty(), url.queryParameter("streamUrl").orEmpty(), url.queryParameter("homepageUrl"))
                        ok("")
                    }
                    "updateInternetRadioStation" -> synchronized(stations) {
                        val i = stations.indexOfFirst { it.id == url.queryParameter("id") }
                        if (i >= 0) stations[i] = FakeStation(stations[i].id, url.queryParameter("name").orEmpty(), url.queryParameter("streamUrl").orEmpty(), url.queryParameter("homepageUrl"))
                        ok("")
                    }
                    "deleteInternetRadioStation" -> synchronized(stations) { stations.removeAll { it.id == url.queryParameter("id") }; ok("") }
                    "download" -> download(url)
                    "getCoverArt" -> MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png(url.queryParameter("id").orEmpty())))
                    "stream" -> MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wav(seconds = 4)))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }


    /** Which OpenSubsonic extensions the server claims; the saved queue and form posts depend on them. */
    @Volatile
    var extensions: List<String> = listOf("indexBasedQueue", "formPost")

    /** When set, `scrobble` answers 503 the way a server that is down does. */
    @Volatile
    var scrobblesFail = false

    /** The endpoint and body of every form post, since the URL of one carries nothing. */
    val formBodies = CopyOnWriteArrayList<Pair<String, String>>()

    class SavedQueue(val ids: List<String>, val currentIndex: Int?, val current: String?, val position: Long)

    @Volatile
    var savedQueue: SavedQueue? = null

    private fun savedQueueJson(byIndex: Boolean): String {
        val q = savedQueue ?: return "{}"
        val entries = q.ids.joinToString(",") { songJsonById(it) }
        val which = if (byIndex) """"currentIndex":${q.currentIndex ?: 0}""" else """"current":"${q.current ?: q.ids.firstOrNull().orEmpty()}""""
        return """{"entry":[$entries],$which,"position":${q.position},"username":"tim","changedBy":"Platter"}"""
    }

    class FakeEpisode(val id: String, val streamId: String?, val title: String, var status: String)
    class FakeChannel(val id: String, val url: String, val title: String, val status: String, val episodes: MutableList<FakeEpisode>)

    val channels = mutableListOf(
        FakeChannel(
            "c1", "http://feeds.test/tech", "Tech Talk", "completed",
            mutableListOf(
                FakeEpisode("e1", "s0-1", "Episode one: how it started", "completed"),
                FakeEpisode("e2", "s0-2", "Episode two: how it went", "completed"),
                FakeEpisode("e3", null, "Episode three: not fetched yet", "new"),
            ),
        ),
        FakeChannel("c2", "http://feeds.test/broken", "Broken feed", "error", mutableListOf()),
    )
    private var nextChannel = 3

    private fun channelJson(c: FakeChannel, withEpisodes: Boolean): String {
        val episodes = if (withEpisodes) ""","episode":[${c.episodes.joinToString(",") { episodeJson(c, it) }}]""" else ""
        val error = if (c.status == "error") ""","errorMessage":"404 Not Found"""" else ""
        return """{"id":"${c.id}","url":"${c.url}","title":"${c.title}","description":"A show about ${c.title}","coverArt":"pl-${c.id}","status":"${c.status}"$error$episodes}"""
    }

    private fun episodeJson(c: FakeChannel, e: FakeEpisode): String {
        val stream = e.streamId?.let { ""","streamId":"$it"""" }.orEmpty()
        return """{"id":"${e.id}","channelId":"${c.id}","title":"${e.title}","description":"Notes for ${e.title}","coverArt":"pl-${c.id}","duration":1800,"publishDate":"2026-03-0${e.id.last()}T08:00:00Z","status":"${e.status}"$stream}"""
    }

    class FakeStation(val id: String, val name: String, val streamUrl: String, val homePageUrl: String?)

    val stations = mutableListOf(FakeStation("r1", "Jazz FM", "http://127.0.0.1:0/radio/jazz.wav", "http://jazz.test"))
    private var nextStation = 2

    private fun stationJson(st: FakeStation): String {
        val home = st.homePageUrl?.let { ""","homePageUrl":"$it"""" }.orEmpty()
        return """{"id":"${st.id}","name":"${st.name}","streamUrl":"${st.streamUrl.replace("127.0.0.1:0", "127.0.0.1:${server.port}")}"$home}"""
    }

    /** Playlists as the server holds them: ids of songs, in order. `p1` and `p2` to begin with. */
    private class FakePlaylist(var name: String, val songs: MutableList<String>, val coverArt: String?, val owner: String = "tim")

    /** Another user's public playlist, which a real server lists beside the listener's own. */
    fun addPublicPlaylist(id: String, name: String, owner: String) {
        synchronized(playlistsState) { playlistsState[id] = FakePlaylist(name, mutableListOf("s3-1"), null, owner) }
    }

    private val playlistsState = linkedMapOf(
        "p1" to FakePlaylist("Late night", mutableListOf("s1-1", "s4-2", "s5-3"), "pl-1"),
        "p2" to FakePlaylist("Focus", mutableListOf("s2-1", "s2-2"), null),
    )
    private var nextPlaylist = 3

    @Volatile
    private var scansLeft = 0

    /** The songs of a playlist by id, for assertions. */
    fun playlistSongs(id: String): List<String>? = synchronized(playlistsState) { playlistsState[id]?.songs?.toList() }

    fun playlistNames(): List<String> = synchronized(playlistsState) { playlistsState.values.map { it.name } }

    private fun playlistJson(id: String, withSongs: Boolean): String {
        val p = playlistsState[id] ?: return """{"id":"$id","name":"gone","songCount":0,"duration":0}"""
        val cover = p.coverArt?.let { ""","coverArt":"$it"""" }.orEmpty()
        val entries = if (withSongs) ""","entry":[${p.songs.joinToString(",") { songJsonById(it) }}]""" else ""
        return """{"id":"$id","name":"${p.name}","owner":"${p.owner}","songCount":${p.songs.size},"duration":${p.songs.size * 200}$cover$entries}"""
    }

    private fun createPlaylist(url: HttpUrl): MockResponse {
        val ids = url.queryParameterValues("songId").filterNotNull()
        val existing = url.queryParameter("playlistId")
        val id = existing?.takeIf { it in playlistsState } ?: "p${nextPlaylist++}"
        val playlist = playlistsState.getOrPut(id) { FakePlaylist(url.queryParameter("name").orEmpty(), mutableListOf(), null) }
        url.queryParameter("name")?.let { playlist.name = it }
        // With an existing playlistId the songs are replaced outright - which is how a reorder is sent.
        playlist.songs.clear()
        playlist.songs += ids
        return ok(""""playlist":${playlistJson(id, withSongs = false)}""")
    }

    private fun updatePlaylist(url: HttpUrl): MockResponse {
        val playlist = playlistsState[url.queryParameter("playlistId")] ?: return MockResponse().setResponseCode(404)
        url.queryParameter("name")?.let { playlist.name = it }
        url.queryParameterValues("songIndexToRemove").mapNotNull { it?.toInt() }.sortedDescending().forEach { if (it in playlist.songs.indices) playlist.songs.removeAt(it) }
        playlist.songs += url.queryParameterValues("songIdToAdd").filterNotNull()
        return ok("")
    }

    /** Anything whose name holds the query, as search3 would: albums, artists and the songs "Track n of <album>". */
    private fun search(query: String): String {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return "{}"
        val albums = albumNames.indices.filter { albumNames[it].lowercase().contains(q) }
        val artists = artistNames.indices.filter { artistNames[it].lowercase().contains(q) }.distinctBy { artistNames[it] }
        val songs = albumNames.indices.flatMap { a -> (1..2).map { n -> a to n } }.filter { (a, n) -> "track $n of ${albumNames[a]}".lowercase().contains(q) }
        return """{"song":[${songs.joinToString(",") { (a, n) -> songJson(a, n) }}],"album":[${albums.joinToString(",") { albumJson(it, false) }}],"artist":[${artists.joinToString(",") { artistJson(it) }}]}"""
    }

    /** `getAlbumList2` for the types the client uses: paging, byYear (either direction), byGenre; the rest is everything. */
    private fun albumList(url: HttpUrl): List<Int> {
        val size = url.queryParameter("size")?.toInt() ?: 10
        val offset = url.queryParameter("offset")?.toInt() ?: 0
        var all = albumNames.indices.toList()
        if (url.queryParameter("type") == "byYear") {
            val from = url.queryParameter("fromYear")!!.toInt()
            val to = url.queryParameter("toYear")!!.toInt()
            all = all.filter { (1959 + it * 7) in minOf(from, to)..maxOf(from, to) }.let { if (from <= to) it else it.reversed() }
        }
        if (url.queryParameter("type") == "byGenre") all = all.take(3)
        return all.drop(offset).take(size)
    }

    private fun songJsonById(id: String): String {
        val (album, n) = id.removePrefix("s").split("-").map { it.toInt() }
        return songJson(album, n)
    }

    // --- Deemix plus and Deezer's catalogue ----------------------------------------------------------

    /** Passwords the site's login takes; the Navidrome one is not among them by default, as for the user's own account. */
    val deemixPasswords: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply { add("deemixpw") }

    /** Each login attempt, as the password tried. */
    val deemixLogins = CopyOnWriteArrayList<String>()

    /** Each call to the site's api: method, path and body. */
    val deemixCalls = CopyOnWriteArrayList<Triple<String, String, String>>()

    @Volatile
    var deemixTokenGeneration = 1

    /** When set, `download*` answers 429 with the site's own words, the way an exhausted quota does. */
    @Volatile
    var deemixQuotaExhausted = false

    @Volatile
    var deemixUsageJson = """{"is_admin":false,"user_daily":3,"user_limit":50,"global_daily":10,"global_limit":500}"""

    /** Makes every token handed out so far stale: the next call with one is refused, as when the day is up. */
    fun expireDeemixTokens() {
        deemixTokenGeneration++
    }

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun validToken(request: RecordedRequest) = request.getHeader("Authorization") == "Bearer tok$deemixTokenGeneration"

    private fun deemix(request: RecordedRequest, url: HttpUrl): MockResponse {
        val path = url.encodedPath.removePrefix("/")
        val body = if (request.method == "POST") request.body.readUtf8() else ""
        deemixCalls += Triple(request.method.orEmpty(), path, body)
        if (path == "api/auth/login") {
            val password = Regex("\"password\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1).orEmpty()
            deemixLogins += password
            return if (password in deemixPasswords) json(200, """{"token":"tok$deemixTokenGeneration","username":"tim","role":"user"}""")
            else json(401, """{"detail":"Invalid username or password"}""")
        }
        if (!validToken(request)) return json(401, """{"detail":"Not authenticated"}""")
        return when (path) {
            "api/auth/me" -> json(200, """{"username":"tim"}""")
            "api/auth/usage" -> json(200, deemixUsageJson)
            "api/search-all" -> json(200, deezerSearchJson(url.queryParameter("q").orEmpty()))
            "api/navidrome/check-bulk" -> {
                val ids = Regex("\"id\"\\s*:\\s*\"(\\d+)\"[^}]*\"title\"\\s*:\\s*\"([^\"]*)\"").findAll(body)
                json(200, """{"results":{${ids.joinToString(",") { """"${it.groupValues[1]}":${it.groupValues[2] == "Come Together"}""" }}}}""")
            }
            "api/download" -> when {
                deemixQuotaExhausted -> json(429, """{"detail":"Daily limit reached (50 tracks)"}""")
                "dup" in body -> json(200, """{"success":false,"error":"Already in the queue"}""")
                else -> json(200, """{"success":true}""")
            }
            "api/download-album", "api/download-artist" -> when {
                deemixQuotaExhausted -> json(429, """{"detail":"Daily limit reached (50 tracks)"}""")
                "\"id\":999" in body -> json(200, """{"success":false,"error":"Could not read the album"}""")
                else -> json(200, """{"success":true,"added":8,"skipped":2,"total_tracks":10}""")
            }
            "api/preview" -> MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wav(seconds = 3)))
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun deezerSearchJson(query: String): String {
        if (!query.lowercase().contains("beatles")) return """{"success":true,"tracks":[],"artists":[],"albums":[]}"""
        fun track(id: Int, title: String) = """{"id":$id,"title":"$title","link":"https://www.deezer.com/track/$id","duration":200,"rank":1000,"artist":{"id":1,"name":"Beatles"},"album":{"id":201,"title":"Abbey Road","cover_medium":"https://img.test/abbey.jpg"}}"""
        return """{"success":true,
            "artists":[{"id":2,"name":"Beatles Tribute","picture_medium":"https://img.test/t.jpg","nb_fan":5},{"id":1,"name":"Beatles","picture_medium":"https://img.test/b.jpg","picture_xl":"https://img.test/bx.jpg","nb_fan":900000}],
            "tracks":[${track(101, "Come Together")},${track(102, "Here Comes the Sun (feat. Someone)")},${track(103, "Octopus's Garden")}],
            "albums":[{"id":201,"title":"Abbey Road","cover_medium":"https://img.test/abbey.jpg","record_type":"album","release_date":"1969-09-26","artist":{"id":1,"name":"Beatles"}}]}"""
    }

    private fun deezerCatalog(url: HttpUrl): MockResponse {
        val path = url.encodedPath.removePrefix("/deezer/")
        fun track(id: Int, title: String, pos: Int) = """{"id":$id,"title":"$title","link":"https://www.deezer.com/track/$id","duration":${180 + pos},"track_position":$pos,"artist":{"id":1,"name":"Beatles"},"album":{"id":201,"title":"Abbey Road"}}"""
        return when (path) {
            "artist/1" -> json(200, """{"id":1,"name":"Beatles","picture_medium":"https://img.test/b.jpg","picture_xl":"https://img.test/bx.jpg","nb_album":12,"nb_fan":900000}""")
            "artist/1/top" -> json(200, """{"data":[${track(101, "Come Together", 1)},${track(102, "Here Comes the Sun (feat. Someone)", 2)},${track(103, "Octopus's Garden", 3)}]}""")
            "artist/1/albums" -> json(200, """{"data":[{"id":201,"title":"Abbey Road","cover_medium":"https://img.test/abbey.jpg","record_type":"album","release_date":"1969-09-26"},{"id":202,"title":"Hey Jude","record_type":"single","release_date":"1968-08-30"}]}""")
            "album/201" -> json(200, """{"id":201,"title":"Abbey Road","cover_medium":"https://img.test/abbey.jpg","cover_xl":"https://img.test/abbeyx.jpg","record_type":"album","release_date":"1969-09-26","nb_tracks":3,"artist":{"id":1,"name":"Beatles"}}""")
            "album/201/tracks" -> json(200, """{"data":[${track(101, "Come Together", 1)},${track(102, "Here Comes the Sun (feat. Someone)", 2)},${track(103, "Octopus's Garden", 3)}]}""")
            else -> json(404, """{"error":{"message":"no data"}}""")
        }
    }

    /** The songs the listener has liked, as the server holds them; `star` and `unstar` move them. */
    val starredSongs: MutableSet<String> = java.util.Collections.synchronizedSet(linkedSetOf("s1-1", "s4-2"))

    /** Songs whose download the server refuses the Subsonic way: a JSON error under a 200. */
    val downloadFails: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** The bytes `download` serves for a song: recognisable, and a different length for the "slow" ones. */
    fun audioBytes(id: String): ByteArray = wav(seconds = if (id.startsWith("slow")) 40 else 4)

    private fun download(url: HttpUrl): MockResponse {
        val id = url.queryParameter("id").orEmpty()
        if (id in downloadFails) {
            return MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":70,"message":"Song not found"}}}""")
        }
        val response = MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(Buffer().write(audioBytes(id)))
        // A slow one takes seconds, so it can be cancelled or watched.
        return if (id.startsWith("slow")) response.throttleBody(16_384, 200, java.util.concurrent.TimeUnit.MILLISECONDS) else response
    }

    fun requestsTo(endpoint: String): List<HttpUrl> = requests.filter { it.pathSegments.lastOrNull() == endpoint }

    /** Songs of album a2 carry a timed main layer and a translation; a3's come as plain text; the rest have none. */
    private fun lyricsFor(songId: String): String {
        if (!songId.startsWith("s2-")) return "[]"
        // The second song waits nine seconds before the first word, so the panel's dots can be seen.
        val lead = if (songId == "s2-2") 9000 else 0
        val main = (0 until 8).joinToString(",") { """{"start":${lead + it * 1500},"value":"Line ${it + 1} of the song"}""" }
        val translation = (0 until 8).joinToString(",") { """{"start":${it * 1500},"value":"Translated line ${it + 1}"}""" }
        // The translation is listed first on purpose: taking element zero would show it.
        return """[{"kind":"translation","lang":"ru","synced":true,"offset":0,"line":[$translation]},{"kind":"main","lang":"und","synced":true,"offset":0,"line":[$main]}]"""
    }

    private fun wave(request: RecordedRequest, url: HttpUrl): MockResponse {
        wavePasswords += request.getHeader("X-Wave-Password")
        fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
        return when (url.pathSegments.getOrNull(1)) {
            "ping" -> json("""{"status":"ok"}""")
            "prepare" -> json("""{"state":"ready"}""")
            "start" -> json(waveBatchJson("electronic"))
            "next" -> {
                waveNextBodies += request.body.readUtf8()
                if (waveNextFails) MockResponse().setResponseCode(503).setBody("""{"detail":"audiomuse_down"}""") else json(waveBatchJson("jazz"))
            }
            "lyrics" -> json(
                """{"indexing":false,"indexed":3,"results":[{"id":"s2-1","snippet":"come together / right now","song":${songJson(2, 1)}}]}""",
            )
            else -> MockResponse().setResponseCode(404)
        }
    }

    /** Five four-second tracks, so a wave can be heard turning over in real time. */
    private fun waveBatchJson(vibe: String): String {
        val n = waveBatch++
        val tracks = (1..5).joinToString(",") { k ->
            val id = "w$n-$k"
            val song = """{"id":"$id","title":"Wave track $n-$k","album":"Wave","albumId":"a0","artist":"Wave Artist","artistId":"ar0","duration":4,"coverArt":"al-0"}"""
            """{"id":"$id","song":$song,"energy":0.7,"tempo":128.0,"source":"alchemy"}"""
        }
        return """{"session":"sess1","batch":$n,"slot":"evening","vibe":{"label":"$vibe","next":null,"transition":false},"tracks":[$tracks]}"""
    }

    private fun ok(body: String) =
        MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"subsonic-response":{"status":"ok","version":"1.16.1","type":"fake","openSubsonic":true${if (body.isEmpty()) "" else ",$body"}}}""")

    private fun artistJson(i: Int) = """{"id":"ar$i","name":"${artistNames[i]}","coverArt":"ar-$i","albumCount":1}"""

    private fun songJson(album: Int, n: Int): String {
        val seconds = if (album == 0) 4 else 180 + n * 7
        return """{"id":"s$album-$n","title":"Track $n of ${albumNames[album]}","album":"${albumNames[album]}","albumId":"a$album","artist":"${artistNames[album]}","artistId":"ar$album","track":$n,"duration":$seconds,"coverArt":"al-$album","suffix":"wav"}"""
    }

    private fun albumJson(i: Int, withSongs: Boolean): String {
        val songs = if (withSongs) ""","song":[${(1..5).joinToString(",") { songJson(i, it) }}]""" else ""
        return """{"id":"a$i","name":"${albumNames[i]}","artist":"${artistNames[i]}","artistId":"ar$i","coverArt":"al-$i","songCount":5,"duration":${if (i == 0) 20 else 1000},"year":${1959 + i * 7},"created":"2026-0${i + 1}-01T00:00:00Z"$songs}"""
    }

    private fun png(seed: String): ByteArray {
        val hue = (seed.hashCode().mod(360)) / 360f
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color.getHSBColor(hue, 0.55f, 0.75f)
        g.fillRect(0, 0, 64, 64)
        g.color = Color.getHSBColor(hue, 0.4f, 0.4f)
        g.fillOval(12, 12, 40, 40)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun wav(seconds: Int, rate: Int = 8000): ByteArray {
        val samples = seconds * rate
        val data = ByteArray(samples) { i -> (128 + 40 * sin(2 * PI * 440 * i / rate)).toInt().toByte() }
        val out = ByteArrayOutputStream()
        fun int(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun short(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        out.write("RIFF".toByteArray()); int(36 + samples); out.write("WAVEfmt ".toByteArray())
        int(16); short(1); short(1); int(rate); int(rate); short(1); short(8)
        out.write("data".toByteArray()); int(samples); out.write(data)
        return out.toByteArray()
    }

    override fun close() = server.shutdown()
}
