package com.platter.desktop

import com.platter.desktop.api.Auth
import com.platter.desktop.api.Credentials
import com.platter.desktop.api.SubsonicClient
import com.platter.desktop.ui.screens.cleanBiography
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The endpoints added for search, the artist page, genres and playlists, read through the real client. */
class MoreApiTest {
    private fun client(fake: FakeSubsonic) = SubsonicClient(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt"))

    @Test
    fun `top songs are asked for by the artist's name`() = FakeSubsonic().use { fake ->
        val songs = runBlocking { client(fake).topSongs("Miles Davis", 5) }
        assertEquals(3, songs.size)
        val request = fake.requestsTo("getTopSongs").single()
        assertEquals("Miles Davis", request.queryParameter("artist"))
        assertEquals("5", request.queryParameter("count"))
    }

    @Test
    fun `artist info carries the biography, the Last-fm link and similar artists`() = FakeSubsonic().use { fake ->
        val info = runBlocking { client(fake).artistInfo("ar0") }!!
        assertTrue(info.biography!!.startsWith("Trumpeter"))
        assertEquals("https://www.last.fm/music/Miles+Davis", info.lastFmUrl)
        assertEquals(listOf("John Coltrane", "The Beatles"), info.similarArtists!!.map { it.name })
    }

    @Test
    fun `genres, and the songs of one`() = FakeSubsonic().use { fake ->
        val client = client(fake)
        val genres = runBlocking { client.genres() }
        assertEquals(listOf("Jazz", "Rock"), genres.map { it.value })
        assertEquals(2, genres.first().albumCount)

        assertEquals(4, runBlocking { client.songsByGenre("Jazz", 50, 10) }.size)
        val request = fake.requestsTo("getSongsByGenre").single()
        assertEquals("Jazz", request.queryParameter("genre"))
        assertEquals("50", request.queryParameter("count"))
        assertEquals("10", request.queryParameter("offset"))
    }

    @Test
    fun `an album list can be asked for by year, newest first when the years are given backwards`() = FakeSubsonic().use { fake ->
        val client = client(fake)
        val newest = runBlocking { client.albumList("byYear", 1, 0, fromYear = 2026, toYear = 1900) }.single()
        assertEquals(2008, newest.year)
        val oldest = runBlocking { client.albumList("byYear", 1, 0, fromYear = 1900, toYear = 2026) }.single()
        assertEquals(1959, oldest.year)

        val request = fake.requestsTo("getAlbumList2").first()
        assertEquals("byYear", request.queryParameter("type"))
        assertEquals("2026", request.queryParameter("fromYear"))
        assertEquals("1900", request.queryParameter("toYear"))

        // Without a year or a genre the parameters are not sent at all.
        runBlocking { client.albumList("newest", 5) }
        val plain = fake.requestsTo("getAlbumList2").last()
        assertNull(plain.queryParameter("fromYear"))
        assertNull(plain.queryParameter("genre"))
    }

    @Test
    fun `every album is paged through until a short page`() = FakeSubsonic().use { fake ->
        val all = runBlocking { client(fake).allAlbums(pageSize = 3) }
        assertEquals(8, all.size)
        assertEquals(listOf("0", "3", "6"), fake.requestsTo("getAlbumList2").map { it.queryParameter("offset") })
    }

    @Test
    fun `a share is a link`() = FakeSubsonic().use { fake ->
        assertEquals("http://example.test/share/sh1", runBlocking { client(fake).createShare("a2") })
    }

    @Test
    fun `songs leave a playlist by position`() = FakeSubsonic().use { fake ->
        runBlocking { client(fake).removeFromPlaylist("p1", listOf(0, 2)) }
        val request = fake.requestsTo("updatePlaylist").single()
        assertEquals(listOf("0", "2"), request.queryParameterValues("songIndexToRemove"))
        assertEquals(listOf("s4-2"), fake.playlistSongs("p1"))

        // Nothing to remove, nothing to ask.
        runBlocking { client(fake).removeFromPlaylist("p1", emptyList()) }
        assertEquals(1, fake.requestsTo("updatePlaylist").size)
    }

    @Test
    fun `a new playlist is found by name on a server that answers with nothing`() = FakeSubsonic().use { fake ->
        val made = runBlocking { client(fake).createPlaylist("Fresh", listOf("s1-1")) }
        assertEquals("Fresh", made?.name)
        assertEquals(listOf("s1-1"), fake.playlistSongs(made!!.id!!))
    }

    @Test
    fun `a biography loses its markup and the read-more link`() {
        assertEquals(
            "Trumpeter and bandleader.",
            cleanBiography("Trumpeter and bandleader. <a target=\"_blank\" href=\"https://x\">Read more on Last.fm</a>"),
        )
        assertEquals("Rock & Roll \"live\"\n\nSecond.", cleanBiography("Rock &amp; Roll &quot;live&quot;<br><br>Second."))
        assertNull(cleanBiography("   "))
        assertNull(cleanBiography(null))
        assertNull(cleanBiography("<a href=\"x\">only a link</a>"))
    }
}
