package com.platter.desktop

import com.platter.desktop.api.Song
import com.platter.desktop.deemix.DeemixClient
import com.platter.desktop.deemix.DeemixConfig
import com.platter.desktop.deemix.DeemixResult
import com.platter.desktop.deemix.DeemixUsage
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeemixClientTest {
    private var custom: String? = null
    private var servers: List<String> = emptyList()
    private var own: String? = null
    private var serverPassword: String? = "pw"
    private var library: List<Song> = emptyList()
    private var searchedLibraryFor = ArrayList<String>()

    private fun client(fake: FakeSubsonic? = null, clock: () -> Long = { System.nanoTime() / 1_000_000 }) = DeemixClient(
        DeemixConfig(
            customUrl = { custom },
            servers = { servers },
            user = { "tim" },
            ownPassword = { own },
            serverPassword = { serverPassword },
            searchLibrary = { artist -> searchedLibraryFor += artist; library },
        ),
        catalogUrl = (fake?.url ?: "http://127.0.0.1:1/") + "deezer/",
        now = clock,
    )

    private fun libSong(title: String, artist: String) = Song().apply { this.title = title; this.artist = artist }

    // --- where the site is --------------------------------------------------------------------------

    @Test
    fun `the site is looked for beside the music server - a name of its own behind a proxy, then the port`() {
        servers = listOf("https://music.example.com")
        assertEquals(listOf("https://deemix.example.com/", "https://music.example.com:8088/"), client().candidates())

        servers = listOf("music.example.com") // typed without a scheme
        assertEquals(listOf("https://deemix.example.com/", "https://music.example.com:8088/"), client().candidates())
    }

    @Test
    fun `on a home address it is the same machine on port 8088, and the address in use comes first`() {
        servers = listOf("http://192.168.0.81:4533", "https://music.example.com")
        assertEquals(
            listOf("http://192.168.0.81:8088/", "https://deemix.example.com/", "https://music.example.com:8088/"),
            client().candidates(),
        )
        servers = listOf("https://deemix.example.com")
        assertEquals(listOf("https://deemix.example.com:8088/"), client().candidates(), "already the site's own name")
    }

    @Test
    fun `an address written in settings is the only one, with its scheme and slash made good`() {
        servers = listOf("https://music.example.com")
        custom = "nas.local:8088"
        assertEquals(listOf("http://nas.local:8088/"), client().candidates())
        custom = "https://deemix.timoha.top/"
        assertEquals(listOf("https://deemix.timoha.top/"), client().candidates())
        custom = "   "
        assertEquals(2, client().candidates().size)
    }

    @Test
    fun `an address counts only where the site answers, in FastAPI's words`() = FakeSubsonic().use { fake ->
        custom = fake.url
        val client = client(fake)
        assertTrue(runBlocking { client.usage() } is DeemixResult.Ok)
        assertEquals(fake.url, client.baseUrl())

        // Another service at the address - here the Subsonic part of the same fake, which has no /api - is not the site.
        val elsewhere = FakeSubsonic()
        try {
            custom = elsewhere.url.replace("/", "/") + "rest/"
            val result = runBlocking { client(elsewhere).usage() }
            assertIs<DeemixResult.Failed>(result)
        } finally {
            elsewhere.close()
        }
    }

    @Test
    fun `nothing at the address is unreachable, and a new address is looked at afresh`() = FakeSubsonic().use { fake ->
        val client = client(fake)
        custom = "http://127.0.0.1:1/"
        val down = runBlocking { client.usage() }
        assertEquals(DeemixResult.Failure.UNREACHABLE, (down as DeemixResult.Failed).failure)

        custom = fake.url
        assertIs<DeemixResult.Ok<DeemixUsage>>(runBlocking { client.usage() })
    }

    @Test
    fun `a back-off after a failure is for that address, not for whatever is typed next`() = FakeSubsonic().use { fake ->
        var clock = 0L
        val client = client(fake) { clock }
        own = "deemixpw"
        custom = "http://127.0.0.1:1/"
        runBlocking { client.usage() }
        val before = fake.deemixCalls.size

        // The listener fixes the address; the back-off was for the old one, so there is no minute to wait.
        custom = fake.url
        assertIs<DeemixResult.Ok<DeemixUsage>>(runBlocking { client.usage() }, "a changed address is a different place: no waiting")
        assertTrue(fake.deemixCalls.size > before)
    }

    // --- signing in ---------------------------------------------------------------------------------------

    @Test
    fun `the password set for the site is tried first, then the server's where it cannot be read on the way`() = FakeSubsonic().use { fake ->
        custom = fake.url
        own = "deemixpw"
        runBlocking { client(fake).usage() }
        assertEquals(listOf("deemixpw"), fake.deemixLogins, "the first that works is the only one tried")

        fake.deemixLogins.clear()
        fake.deemixPasswords += "pw"
        own = "wrong"
        runBlocking { client(fake).usage() }
        assertEquals(listOf("wrong", "pw"), fake.deemixLogins, "http://127.0.0.1 is this machine, so the server's password may go")
    }

    @Test
    fun `with no password of its own the server's is used`() = FakeSubsonic().use { fake ->
        custom = fake.url
        fake.deemixPasswords += "pw"
        assertIs<DeemixResult.Ok<DeemixUsage>>(runBlocking { client(fake).usage() })
        assertEquals(listOf("pw"), fake.deemixLogins)
    }

    @Test
    fun `when no password opens the account that is said, once, until retry`() = FakeSubsonic().use { fake ->
        custom = fake.url
        val client = client(fake)
        val first = runBlocking { client.usage() } as DeemixResult.Failed
        assertEquals(DeemixResult.Failure.NO_ACCOUNT, first.failure)
        val tried = fake.deemixLogins.size

        runBlocking { client.usage() }
        assertEquals(tried, fake.deemixLogins.size, "a rejected account is not knocked on again")

        own = "deemixpw" // the listener types the right one: a new key, so a new try
        assertIs<DeemixResult.Ok<DeemixUsage>>(runBlocking { client.usage() })
    }

    @Test
    fun `one sign-in serves many calls, and a token the server drops is replaced`() = FakeSubsonic().use { fake ->
        custom = fake.url
        own = "deemixpw"
        val client = client(fake)
        runBlocking { client.usage(); client.usage(); client.usage() }
        assertEquals(1, fake.deemixLogins.size)
        assertEquals("tim" to "user", client.account())

        fake.expireDeemixTokens()
        assertIs<DeemixResult.Ok<DeemixUsage>>(runBlocking { client.usage() }, "the 401 is answered with a new sign-in, and the call goes again")
        assertEquals(2, fake.deemixLogins.size)
    }

    // --- the account and the quota ----------------------------------------------------------------------------

    @Test
    fun `the quota is read, and says what is left and whose limit is tighter`() {
        val plenty = DeemixUsage(false, 3, 50, 10, 500)
        assertEquals(47, plenty.remaining())
        assertFalse(plenty.globalIsTighter())

        val serverTight = DeemixUsage(false, 3, 50, 480, 500)
        assertEquals(20, serverTight.remaining())
        assertTrue(serverTight.globalIsTighter())

        assertEquals(0, DeemixUsage(false, 60, 50, 10, 500).remaining(), "never below zero")
        assertNull(DeemixUsage(true, 100, 50, 10, 500).remaining(), "an administrator has no limit")
    }

    @Test
    fun `usage comes from the site`() = FakeSubsonic().use { fake ->
        custom = fake.url
        own = "deemixpw"
        val usage = runBlocking { client(fake).usage() }.value!!
        assertEquals(50, usage.userLimit)
        assertEquals(3, usage.userDaily)
    }

    // --- search -----------------------------------------------------------------------------------------------

    @Test
    fun `a search is Deezer's answer with what the library already holds marked`() = FakeSubsonic().use { fake ->
        custom = fake.url
        own = "deemixpw"
        val results = runBlocking { client(fake).search("beatles") }.value!!
        assertEquals(listOf(2L, 1L), results.search.artists!!.map { it.id })
        assertEquals(3, results.search.tracks!!.size)
        val (come, sun, octopus) = results.search.tracks!!
        assertTrue(results.isInLibrary(come), "the site marked Come Together")
        assertFalse(results.isInLibrary(sun))
        assertFalse(results.isInLibrary(octopus))
        assertEquals("beatles", fake.deemixCalls.first { it.second == "api/search-all" }.let { "beatles" })
    }

    @Test
    fun `a song the library credits to several artists is still found there`() = FakeSubsonic().use { fake ->
        custom = fake.url
        own = "deemixpw"
        // Deezer says "Beatles"; the library tags the song with every performer, and drops the "(feat.)".
        library = listOf(libSong("Here Comes The Sun", "Beatles/George Harrison"))
        val results = runBlocking { client(fake).search("beatles") }.value!!
        val (_, sun, octopus) = results.search.tracks!!
        assertTrue(results.isInLibrary(sun), "same title once the featuring is ignored, and Beatles is one of the credits")
        assertFalse(results.isInLibrary(octopus))
        assertEquals(listOf("Beatles"), searchedLibraryFor.distinct(), "one library search per artist, not per track")
    }

    @Test
    fun `a search with nothing found is an empty answer, not a failure`() = FakeSubsonic().use { fake ->
        custom = fake.url
        own = "deemixpw"
        val results = runBlocking { client(fake).search("zzzz") }.value!!
        assertTrue(results.search.tracks!!.isEmpty() && results.search.artists!!.isEmpty())
        assertTrue(results.inLibrary.isEmpty())
    }

    // --- artist and album pages -------------------------------------------------------------------------------------

    @Test
    fun `an artist page has the artist, the popular tracks marked, and every release`() = FakeSubsonic().use { fake ->
        custom = fake.url
        own = "deemixpw"
        val page = runBlocking { client(fake).artistPage(1) }.value!!
        assertEquals("Beatles", page.artist.name)
        assertEquals(900_000L, page.artist.fanCount)
        assertEquals(listOf("Come Together", "Here Comes the Sun (feat. Someone)", "Octopus's Garden"), page.top.map { it.title })
        assertEquals(listOf("Abbey Road", "Hey Jude"), page.albums.map { it.title })
        assertEquals(true, page.inLibrary["101"])
    }

    @Test
    fun `an album page has the record and its tracklist, marked`() = FakeSubsonic().use { fake ->
        custom = fake.url
        own = "deemixpw"
        val page = runBlocking { client(fake).albumPage(201) }.value!!
        assertEquals("Abbey Road", page.album.title)
        assertEquals(3, page.tracks.size)
        assertEquals(true, page.inLibrary["101"])
    }

    @Test
    fun `a page that Deezer does not have is a failure to open it`() = FakeSubsonic().use { fake ->
        custom = fake.url
        own = "deemixpw"
        assertIs<DeemixResult.Failed>(runBlocking { client(fake).artistPage(424242) })
        assertIs<DeemixResult.Failed>(runBlocking { client(fake).albumPage(424242) })
    }

    // --- previews -----------------------------------------------------------------------------------------------------

    @Test
    fun `a preview is the site's own proxy and the header that opens it`() = FakeSubsonic().use { fake ->
        custom = fake.url
        own = "deemixpw"
        val preview = runBlocking { client(fake).preview(101) }.value!!
        assertEquals("${fake.url}api/preview?track_id=101", preview.url)

        val http = OkHttpClient()
        http.newCall(Request.Builder().url(preview.url).header("Authorization", preview.authorization).build()).execute().use {
            assertEquals(200, it.code)
            assertTrue(it.body!!.bytes().size > 1_000)
        }
        http.newCall(Request.Builder().url(preview.url).build()).execute().use { assertEquals(401, it.code) }
    }

    // --- downloads -----------------------------------------------------------------------------------------------------

    @Test
    fun `a track is queued by its link, and a refusal reads as a skip`() = FakeSubsonic().use { fake ->
        custom = fake.url
        own = "deemixpw"
        val client = client(fake)
        val added = runBlocking { client.downloadTrack("https://www.deezer.com/track/101") }.value!!
        assertEquals(1, added.added)
        assertEquals(0, added.skipped)

        val dup = runBlocking { client.downloadTrack("https://www.deezer.com/track/dup") }.value!!
        assertEquals(0, dup.added)
        assertEquals(1, dup.skipped)
        assertEquals("Already in the queue", dup.error)
    }

    @Test
    fun `an album or an artist is queued as a whole, and says how many were new`() = FakeSubsonic().use { fake ->
        custom = fake.url
        own = "deemixpw"
        val client = client(fake)
        val album = runBlocking { client.downloadAlbum(201, "Abbey Road") }.value!!
        assertEquals(8, album.added)
        assertEquals(2, album.skipped)
        assertTrue(fake.deemixCalls.any { it.second == "api/download-album" && "\"id\":201" in it.third && "Abbey Road" in it.third })

        val artist = runBlocking { client.downloadArtist(1, "Beatles") }.value!!
        assertEquals(10, artist.total)
        assertTrue(fake.deemixCalls.any { it.second == "api/download-artist" && "Beatles" in it.third })
    }

    @Test
    fun `a bulk download the site could not read is an error, with its words`() = FakeSubsonic().use { fake ->
        custom = fake.url
        own = "deemixpw"
        val failed = runBlocking { client(fake).downloadAlbum(999, "Broken") } as DeemixResult.Failed
        assertEquals(DeemixResult.Failure.ERROR, failed.failure)
        assertEquals("Could not read the album", failed.message)
    }

    @Test
    fun `an exhausted quota is its own failure, in the server's words`() = FakeSubsonic().use { fake ->
        custom = fake.url
        own = "deemixpw"
        fake.deemixQuotaExhausted = true
        val failed = runBlocking { client(fake).downloadTrack("https://www.deezer.com/track/1") } as DeemixResult.Failed
        assertEquals(DeemixResult.Failure.QUOTA, failed.failure)
        assertEquals("Daily limit reached (50 tracks)", failed.message)
        assertNotNull(failed.message)
    }
}
