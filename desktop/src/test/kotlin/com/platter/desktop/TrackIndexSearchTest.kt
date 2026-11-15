package com.platter.desktop

import com.platter.desktop.api.SearchResult
import com.platter.desktop.api.Song
import com.platter.desktop.api.Credentials
import com.platter.desktop.api.SubsonicClient
import com.platter.desktop.search.EditDistance
import com.platter.desktop.search.FuzzyIndex
import com.platter.desktop.search.LibrarySignals
import com.platter.desktop.search.RankedResults
import com.platter.desktop.search.SearchEngine
import com.platter.desktop.search.TrackCatalogue
import com.platter.search.TrackIndex
import com.platter.search.TrackSnapshot
import com.platter.search.TrackChanges
import com.platter.search.TrackStore
import com.platter.search.TrackSearch
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TrackIndexSearchTest {
    private val original = song("original", "La La La", "Naughty Boy/Sam Smith", "In The Lonely Hour")
    private val remix = song("remix", "La La La (Kaos Remix)", "Naughty Boy/Kaos", "La La La")
    private val short = song("short", "T.N.T.", "AC/DC", "High Voltage")
    private val cyrillic = song("cyrillic", "Всё идёт по плану", "Гражданская Оборона", "Всё идёт по плану")
    private val index = index(listOf(original, remix, short, cyrillic))

    @Test
    fun `the whole title is corrected with or without a credited artist`() {
        for (query in listOf("Ka La La", "La Al La", "La Al La Sam Smith", "Naughty Boy Ka La La")) {
            val hit = index.find(query, EditDistance::within).first()
            assertEquals("original", hit.song.id, query)
            assertEquals(1, hit.edits, query)
        }
        assertEquals(0, index.find("La La L", EditDistance::within).first().edits)
        assertEquals("short", index.find("T.N.", EditDistance::within).first().song.id)
        assertEquals("cyrillic", index.find("Все идет по плену", EditDistance::within).first().song.id)
    }

    @Test
    fun `the index also retrieves an artist or an album and a literal query stays literal`() {
        assertEquals(listOf("original"), index.find("Sam Smith", EditDistance::within).map { it.song.id })
        assertEquals(listOf("original"), index.find("In The Lonely", EditDistance::within).map { it.song.id })
        assertTrue(index.find("\"Ka La La\"", EditDistance::within).isEmpty())
    }

    @Test
    fun `index uses whole credits and keeps exact pairs ahead of the candidate limit`() {
        val correct = song("correct", "Hello", "Adele", "25")
        val tribute = song("tribute", "Hello", "Adele Tribute Band", "Covers")
        val namesakes = (1..100).map { song("other-$it", "Hello Adele", "Other Band", "Other Album") }
        val query = "Hello Adele"
        assertEquals(0, TrackSearch(query).match(tribute.title, tribute.artistLine(), tribute.artist))
        assertTrue(index(listOf(tribute)).find(query, EditDistance::within).isEmpty())
        val hits = index(namesakes + correct + tribute).find(query, EditDistance::within)
        assertEquals(100, hits.size)
        assertEquals("correct", hits.first().song.id)
    }

    @Test
    fun `literal words and an artist typo can retrieve a track from the index`() {
        val linkin = index(listOf(song("linkin", "In the End", "Linkin Park", "Hybrid Theory")))
        assertEquals("linkin", linkin.find("End", EditDistance::within).single().song.id)
        val correction = linkin.find("In the End Linkin Prak", EditDistance::within).single()
        assertEquals("linkin", correction.song.id)
        assertEquals(1, correction.edits)
        assertTrue(linkin.find("In the End Linkin Prak", null).isEmpty())
        assertEquals("7", index(listOf(song("7", "7", "Artist", "Album"))).find("7", null).single().song.id)
    }

    @Test
    fun `a single character is an exact title in the index even if it is a symbol`() {
        val catalogue = index(listOf(
            song("prefix", "7 Days", "Artist", "Album"),
            song("suffix", "Part 7", "Artist", "Album"),
            song("punctuated", "7!", "Artist", "Album"),
            song("credited", "Other Song", "7", "Album"),
            song("number", "7", "Artist", "Album"),
            song("symbol", "!", "Artist", "Album"),
            song("emoji", "🎵", "Artist", "Album"),
            song("accented", "É", "Artist", "Album"),
        ))
        for ((query, wanted) in listOf(" 7 " to "number", "!" to "symbol", "🎵" to "emoji", "e\u0301" to "accented")) {
            assertEquals(listOf(wanted), catalogue.find(query, EditDistance::within).map { it.song.id }, query)
        }
    }

    @Test
    fun `a literal title prefix outranks a correction even with many similar titles`() = runBlocking {
        val literal = song("wanted", "Halo Theme", "Martin O'Donnell", "Halo")
        val corrections = (1..100).map { song("typo-$it", "Halt", "Other Artist", "Other Album") }
        val catalogue = index(corrections + literal)
        val engine = SearchEngine(
            source = { SearchResult().apply { songs = corrections } },
            signals = { LibrarySignals.Empty },
            fuzzy = { FuzzyIndex.empty() },
            localTracks = { query, allow -> catalogue.find(query, if (allow) EditDistance::within else null) },
        )
        val ranked = engine.search("Halo")
        assertEquals("wanted", ranked.songs.first().id)
        assertEquals("wanted", assertIs<RankedResults.Top.OfSong>(ranked.top).song.id)
    }

    @Test
    fun `layout corrections find already indexed tracks when the server is down`() = runBlocking {
        val catalogue = index(listOf(song("wanted", "Ёлка", "Artist", "Album")))
        val engine = SearchEngine(
            source = { throw IOException("server down") },
            signals = { LibrarySignals.Empty },
            fuzzy = { FuzzyIndex.empty() },
            localTracks = { query, allow -> catalogue.find(query, if (allow) EditDistance::within else null) },
        )
        assertEquals("wanted", assertIs<RankedResults.Top.OfSong>(engine.search("tkrf").top).song.id)
    }

    @Test
    fun `a missing last letter prefers the title that begins with the query`() = runBlocking {
        val lovely = song("correct", "Lovely", "Billie Eilish", "13 Reasons Why")
        val lover = song("other", "Lover", "Someone Else", "Singles").apply { starred = "2025-01-01T00:00:00Z" }
        val names = index(listOf(lovely, lover))
        val engine = SearchEngine(
            source = { SearchResult() },
            signals = { LibrarySignals.Empty },
            fuzzy = { FuzzyIndex.empty() },
            localTracks = { query, allow -> names.find(query, if (allow) EditDistance::within else null) },
        )
        assertEquals("correct", engine.search("lovel").songs.first().id)
    }

    @Test
    fun `corrections survive a noisy or unavailable server and an exact track still wins`() = runBlocking {
        var fromDisk = false
        var fail = false
        val exact = song("exact", "Ka La La", "Someone", "Other Album")
        val engine = SearchEngine(
            source = { query ->
                assertTrue(fromDisk)
                if (fail) throw IOException("unavailable")
                SearchResult().apply { songs = if (query == "Ka La La") listOf(remix, exact) else emptyList() }
            },
            signals = { LibrarySignals.Empty },
            fuzzy = { FuzzyIndex.empty() },
            localTracks = { q, allow -> index.find(q, if (allow) EditDistance::within else null) },
        )
        val online = engine.search("Ka La La") { fromDisk = true }
        assertEquals(listOf("exact", "original", "remix"), online.songs.map { it.id })
        fail = true
        val offline = engine.search("Ka La La")
        assertEquals("original", assertIs<RankedResults.Top.OfSong>(offline.top).song.id)
    }

    @Test
    fun `paged snapshots reopen with metadata and repeated pages abort the refresh`() {
        assertNotEquals(TrackSnapshot.filename("server", "a"), TrackSnapshot.filename("server", "b"))
        val snapshot = TrackSnapshot(Song::class.java, Song::id, Song::title, Song::artistLine, Song::artist, Song::album)
        val file = createTempFile().toFile()
        try {
            snapshot.writer(file).use { pages ->
                assertTrue(pages.add(listOf(original, remix)))
                assertTrue(pages.add(listOf(remix, short)))
                assertEquals(4, pages.offset())
                assertFailsWith<IOException> { pages.add(listOf(original, remix)) }
                assertFalse(pages.add(emptyList()))
                pages.finish()
            }
            val index = snapshot.read(file)
            val hits = index.find(file, "Ka La La Sam Smith", EditDistance::within)
            assertEquals("In The Lonely Hour", hits.first().song.album)
            assertEquals(3, TrackSnapshot.trackCount(file))
            snapshot.writer(file).use { it.finish() }
            assertFailsWith<IOException> { index.find(file, "Ka La La Sam Smith", EditDistance::within) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `journal changes update the searchable index and survive restart without rewriting the snapshot`() {
        val snapshot = TrackSnapshot(Song::class.java, Song::id, Song::title, Song::artistLine, Song::artist, Song::album)
        val dir = createTempDirectory().toFile()
        val base = dir.resolve("catalogue.bin")
        fun store() = TrackStore(base, snapshot) { source, target ->
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        val initial = store()
        try {
            val temp = initial.temporary()
            val index = initial.writer(temp).use { writer ->
                writer.add(listOf(original, remix))
                writer.finish()
            }
            initial.commitBaseline(temp, index, "0")
            val unchanged = base.readBytes().toList()
            val newSong = song("added", "Unrelated New Song", "Someone", "New Album")
            val renamed = song("original", "Completely Retitled", "Naughty Boy/Sam Smith", "In The Lonely Hour")
            val json = Gson()
            val removed = TrackChanges.Change().apply { id = "remix"; deleted = true }
            fun upsert(payload: Song) = TrackChanges.Change().apply {
                id = payload.id
                song = json.toJsonTree(payload).asJsonObject
            }
            val page = TrackChanges().apply { cursor = "3"; changes = listOf(removed, upsert(newSong), upsert(renamed)) }
            assertTrue(initial.apply(page))
            assertEquals(unchanged, base.readBytes().toList())

            val reopened = store().apply { load() }
            assertEquals("3", reopened.cursor())
            assertEquals("added", reopened.find("Unrelated New Song", EditDistance::within).first().song.id)
            assertEquals("original", reopened.find("Completely Retitled", EditDistance::within).first().song.id)
            assertTrue(reopened.find("La La La (Kaos Remix)", EditDistance::within).isEmpty())
            assertTrue(reopened.find("La La La", EditDistance::within).isEmpty())

            val invalid = TrackChanges().apply { cursor = "4"; changes = listOf(upsert(newSong).apply { id = "other" }) }
            assertFailsWith<IOException> { reopened.apply(invalid) }
            assertEquals("3", store().apply { load() }.cursor())
        } finally {
            dir.listFiles().orEmpty().forEach { it.delete() }
            dir.delete()
        }
    }

    @Test
    fun `revocation on a later journal page discards the baseline even if rebuilding fails`() = runBlocking {
        val directory = createTempDirectory().toFile()
        val file = directory.resolve(TrackSnapshot.filename("server", "user"))
        val store = store(file)
        val temporary = store.temporary()
        val saved = store.writer(temporary).use { writer ->
            writer.add(listOf(original))
            writer.finish()
        }
        store.commitBaseline(temporary, saved, "0")
        val catalogue = TrackCatalogue(this, directory.toPath(), "server", "user")
        val discarded = CompletableDeferred<Unit>()
        val client = catalogueClient { url ->
            when (url.queryParameter("cursor")) {
                "0" -> journal("1", more = true)
                "1" -> journal("2", resetReason = "scope")
                else -> throw IOException("baseline temporarily unavailable")
            }
        }
        try {
            catalogue.refresh(client) { if (!file.exists()) discarded.complete(Unit) }
            withTimeout(5_000) { discarded.await() }
            assertTrue(catalogue.find("La La La", true).isEmpty())
            assertFalse(store(file).exists())
            assertFalse(directory.resolve(file.name + ".changes").exists())
        } finally {
            catalogue.cancel()
            directory.listFiles().orEmpty().forEach { it.delete() }
            directory.delete()
        }
    }

    @Test
    fun `baseline commits at the newer checkpoint if no accessible tracks changed`() = runBlocking {
        val directory = createTempDirectory().toFile()
        val file = directory.resolve(TrackSnapshot.filename("server", "user"))
        val catalogue = TrackCatalogue(this, directory.toPath(), "server", "user")
        val rebuilt = CompletableDeferred<Unit>()
        var heads = 0
        val client = catalogueClient { url ->
            if (url.encodedPath.endsWith("search3")) {
                if (url.queryParameter("songOffset") == "0") {
                    """"searchResult3":{"song":[{"id":"wanted","title":"Hello","artist":"Adele"}]}"""
                } else """"searchResult3":{"song":[]}"""
            } else if (url.queryParameter("cursor") == "1") {
                journal("2")
            } else {
                journal((++heads).toString())
            }
        }
        try {
            catalogue.refresh(client) { rebuilt.complete(Unit) }
            withTimeout(5_000) { rebuilt.await() }
            assertEquals("wanted", catalogue.find("Hello Adele", true).single().song.id)
            assertEquals("2", store(file).apply { load() }.cursor())
        } finally {
            catalogue.cancel()
            directory.listFiles().orEmpty().forEach { it.delete() }
            directory.delete()
        }
    }

    @Test
    fun `history reset can keep the baseline but a scope or legacy reset cannot`() {
        val reset = TrackChanges().apply { this.reset = true }
        assertTrue(reset.discardsBaseline())
        reset.resetReason = "scope"
        assertTrue(reset.discardsBaseline())
        reset.resetReason = "history"
        assertFalse(reset.discardsBaseline())
    }

    private fun store(file: java.io.File): TrackStore<Song> {
        val snapshot = TrackSnapshot(Song::class.java, Song::id, Song::title, Song::artistLine, Song::artist, Song::album)
        return TrackStore(file, snapshot) { source, destination ->
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun journal(cursor: String, more: Boolean = false, resetReason: String? = null): String {
        val reason = resetReason?.let { ""","resetReason":"$it"""" }.orEmpty()
        return """"platterTrackChanges":{"cursor":"$cursor","hasMore":$more,"reset":${resetReason != null},"changes":[]$reason}"""
    }

    private fun catalogueClient(response: (HttpUrl) -> String): SubsonicClient {
        val dispatcher = Dispatcher(Executors.newCachedThreadPool { task ->
            Thread(task, "track-journal-test").apply { isDaemon = true }
        })
        val http = OkHttpClient.Builder().dispatcher(dispatcher).addInterceptor { chain ->
            val request = chain.request()
            val body = """{"subsonic-response":{"status":"ok",${response(request.url)}}}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return SubsonicClient(Credentials("https://library.invalid", "user", "token", "salt"), http)
    }

    private fun index(songs: List<Song>) = TrackIndex(songs, Song::id, Song::title, Song::artistLine, Song::artist, Song::album)

    private fun song(id: String, title: String, artist: String, album: String) = Song().apply {
        this.id = id
        this.title = title
        this.artist = artist
        this.album = album
    }
}
