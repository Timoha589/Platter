package com.platter.desktop

import com.platter.desktop.api.Auth
import com.platter.desktop.api.Credentials
import com.platter.desktop.api.Song
import com.platter.desktop.data.Settings
import com.platter.desktop.data.SettingsStore
import com.platter.desktop.search.RankedResults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What the screens lean on, driven against the fake server without drawing anything. */
class AppControllerTest {
    private val apps = ArrayList<AppController>()
    private val dir = Files.createTempDirectory("platter-app-test")

    @AfterTest
    fun tearDown() = apps.forEach { it.shutdown() }

    private fun store() = SettingsStore(dir.resolve("settings.json"))

    private fun app(fake: FakeSubsonic, store: SettingsStore = store(), configure: Settings.() -> Unit = {}): AppController {
        store.update {
            withCredentials(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt"))
            configure()
        }
        return AppController(store, CoroutineScope(SupervisorJob() + Dispatchers.IO), vlcArgs = listOf("--aout=dummy")).also { apps += it }
    }

    private fun song(id: String) = Song().apply { this.id = id; title = "Song $id"; artist = "Someone" }

    // --- search -----------------------------------------------------------------------

    @Test
    fun `search goes through the ranker and leads with the best hit`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        val result = runBlocking { app.search("abbey") }
        assertEquals("Abbey Road", result.albums.first().name)
        assertEquals("Track 1 of Abbey Road", result.songs.first().title)
        assertIs<RankedResults.Top.OfAlbum>(result.top)
    }

    @Test
    fun `a misspelt name is corrected against the library and still found`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        // search3 on the fake is a plain substring match, as on a real server: "Abbey Raod" is found by nothing.
        val result = runBlocking { app.search("Abbey Raod") }
        assertEquals("Abbey Road", result.top?.title)
        assertTrue(fake.requestsTo("getAlbumList2").isNotEmpty(), "the library's names were fetched to measure the typo against")
    }

    @Test
    fun `the library is fetched once and kept`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        val first = runBlocking { app.catalogue() }
        val second = runBlocking { app.catalogue() }
        assertTrue(first === second)
        assertEquals(8, first.albums.size)
        assertEquals(2, first.artists.size)
        assertEquals(1, fake.requestsTo("getArtists").size)
    }

    @Test
    fun `liked songs are asked for once a minute, and again after a like`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        runBlocking { app.liked(); app.liked() }
        assertEquals(1, fake.requestsTo("getStarred2").size)
        app.toggleLike(song("s9-1"))
        runBlocking { app.liked() }
        assertEquals(2, fake.requestsTo("getStarred2").size)
        waitUntil("the star to reach the server") { fake.requestsTo("star").isNotEmpty() }
    }

    @Test
    fun `recent searches keep what was acted on, newest first, without repeats`() = FakeSubsonic().use { fake ->
        val store = store()
        val app = app(fake, store)
        app.commitSearch("ab") // too short to be about anything
        app.commitSearch("Abbey")
        app.commitSearch("Nirvana")
        app.commitSearch("abbey") // the same search again, in another case
        assertEquals(listOf("abbey", "Nirvana"), app.recentSearches)

        app.forgetSearch("Nirvana")
        assertEquals(listOf("abbey"), app.recentSearches)

        // They are there after a restart.
        val reopened = app(fake, store)
        assertEquals(listOf("abbey"), reopened.recentSearches)
    }

    @Test
    fun `only the latest dozen searches are kept`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        (1..20).forEach { app.commitSearch("query $it") }
        assertEquals(12, app.recentSearches.size)
        assertEquals("query 20", app.recentSearches.first())
    }

    // --- playlists -----------------------------------------------------------------------

    @Test
    fun `songs are added to a playlist, taken out by position and moved`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        runBlocking { app.refreshPlaylists() }
        val late = app.playlists.first { it.id == "p1" }

        app.addToPlaylist(late, listOf(song("s2-1"), song("s2-2")))
        waitUntil("songs to be added") { fake.playlistSongs("p1")?.size == 5 }
        assertEquals(listOf("s1-1", "s4-2", "s5-3", "s2-1", "s2-2"), fake.playlistSongs("p1"))
        assertEquals("Added to Late night", app.toast)

        app.removeFromPlaylist("p1", 1)
        waitUntil("a song to be removed") { fake.playlistSongs("p1")?.size == 4 }
        assertEquals(listOf("s1-1", "s5-3", "s2-1", "s2-2"), fake.playlistSongs("p1"))

        val songs = fake.playlistSongs("p1")!!.map(::song)
        app.moveInPlaylist("p1", songs, from = 0, to = 2)
        waitUntil("the order to change") { fake.playlistSongs("p1")?.first() == "s5-3" }
        assertEquals(listOf("s5-3", "s2-1", "s1-1", "s2-2"), fake.playlistSongs("p1"))
    }

    @Test
    fun `a playlist can be made, renamed and deleted`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        app.createPlaylist("  Road trip  ", listOf(song("s1-1"), song("s1-2")))
        waitUntil("the playlist to exist") { "Road trip" in fake.playlistNames() }
        assertEquals(listOf("s1-1", "s1-2"), fake.playlistSongs("p3"))
        waitUntil("the sidebar list to refresh") { app.playlists.any { it.name == "Road trip" } }

        val made = app.playlists.first { it.name == "Road trip" }
        app.renamePlaylist(made, "Long drive")
        waitUntil("the rename") { "Long drive" in fake.playlistNames() }

        app.togglePin("p3")
        assertEquals(listOf("p3"), app.pinned)
        app.deletePlaylist(app.playlists.first { it.name == "Long drive" })
        waitUntil("the delete") { "Long drive" !in fake.playlistNames() }
        assertFalse("p3" in app.pinned, "a deleted playlist is not pinned any more")
    }

    @Test
    fun `other people's public playlists are kept apart from the listener's own`() = FakeSubsonic().use { fake ->
        fake.addPublicPlaylist("p9", "Sunday jazz", owner = "anna")
        val store = store()
        val app = app(fake, store)
        runBlocking { app.refreshPlaylists() }
        assertEquals(listOf("Late night", "Focus"), app.ownPlaylists.map { it.name })
        assertEquals(listOf("Sunday jazz"), app.publicPlaylists.map { it.name })

        assertFalse(app.publicCollapsed)
        app.togglePublicCollapsed()
        assertTrue(app(fake, store).publicCollapsed, "a folded list stays folded after a restart")
    }

    @Test
    fun `a blank name makes nothing`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        app.createPlaylist("   ")
        Thread.sleep(300)
        assertEquals(2, fake.playlistNames().size)
        assertTrue(fake.requestsTo("createPlaylist").isEmpty())
    }

    @Test
    fun `a long list goes up in batches the proxy will take`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        app.createPlaylist("Everything", (1..250).map { song("s9-$it") })
        waitUntil("every batch to land") { fake.playlistSongs("p3")?.size == 250 }
        assertEquals(1, fake.requestsTo("createPlaylist").size)
        assertEquals(2, fake.requestsTo("updatePlaylist").size)
        assertTrue(fake.requests.filter { it.pathSegments.last() in listOf("createPlaylist", "updatePlaylist") }.all { it.toString().length < 4_000 })
    }

    @Test
    fun `pinned playlists survive a restart`() = FakeSubsonic().use { fake ->
        val store = store()
        app(fake, store).togglePin("p2")
        assertEquals(listOf("p2"), app(fake, store).pinned)
    }

    // --- home ----------------------------------------------------------------------------------

    @Test
    fun `home shows the Android app's default sections until the listener arranges it`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        assertEquals(
            listOf(HomeSection.Pinned, HomeSection.MadeForYou, HomeSection.StarredTracks, HomeSection.LastPlayed, HomeSection.NewReleases, HomeSection.RecentlyAdded),
            app.homeSections,
        )
    }

    @Test
    fun `an arranged home comes back as it was, and a section added later stays off`() = FakeSubsonic().use { fake ->
        val store = store()
        val app = app(fake, store)
        val order = listOf(HomeSection.RecentlyAdded, HomeSection.Discovery, HomeSection.Pinned)
        app.saveHomeLayout(order + HomeSection.entries.filterNot { it in order }, hidden = setOf(HomeSection.Pinned) + HomeSection.entries.filterNot { it in order })

        val reopened = app(fake, store)
        assertEquals(listOf(HomeSection.RecentlyAdded, HomeSection.Discovery), reopened.homeSections)

        // A build with a section the saved order has never seen: it is in the order, and off.
        val older = AppController.resolveHomeOrder(listOf("RecentlyAdded", "Pinned"))
        assertEquals(HomeSection.RecentlyAdded, older.first())
        assertEquals(HomeSection.entries.size, older.size)
        val hidden = AppController.resolveHomeHidden(listOf("RecentlyAdded", "Pinned"), emptyList())
        assertTrue(HomeSection.Flashback in hidden)
        assertFalse(HomeSection.Pinned in hidden)
    }

    // --- ratings and settings ----------------------------------------------------------------

    @Test
    fun `stars are sent as rated and one star is a dislike`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        val s = song("s2-1")
        app.rate(s, 4)
        waitUntil("the rating") { fake.requestsTo("setRating").any { it.queryParameter("rating") == "4" } }
        assertEquals(4, app.ratingOf(s))
        assertFalse(app.isDisliked(s))

        app.toggleDislike(s)
        assertTrue(app.isDisliked(s))
        waitUntil("the dislike") { fake.requestsTo("setRating").any { it.queryParameter("rating") == "1" } }

        app.toggleDislike(s)
        assertEquals(0, app.ratingOf(s))
    }

    @Test
    fun `stream quality and format are asked of the server`() = FakeSubsonic().use { fake ->
        val store = store()
        val app = app(fake, store)
        app.changeMaxBitrate(128)
        app.changeStreamFormat("mp3")
        app.player.play(listOf(song("s0-1")))
        waitUntil("the stream request") { fake.requestsTo("stream").isNotEmpty() }
        val request = fake.requestsTo("stream").first()
        assertEquals("128", request.queryParameter("maxBitRate"))
        assertEquals("mp3", request.queryParameter("format"))

        val saved = store.load()
        assertEquals(128, saved.maxBitrate)
        assertEquals("mp3", saved.streamFormat)
    }

    @Test
    fun `scrobbling can be switched off`() = FakeSubsonic().use { fake ->
        val app = app(fake) { scrobbling = false }
        app.player.play(listOf(song("s0-1")))
        waitUntil("the track to start") { fake.requestsTo("stream").isNotEmpty() && app.player.state.value.isPlaying }
        Thread.sleep(2_500) // past the point where a play would have been counted
        assertTrue(fake.requestsTo("scrobble").isEmpty(), "nothing was reported")
    }

    @Test
    fun `volume levelling is handed to the audio engine`() {
        assertEquals(emptyList(), AppController.replayGainArgs("off"))
        assertEquals(listOf("--audio-replay-gain-mode=track"), AppController.replayGainArgs("track"))
        assertEquals(listOf("--audio-replay-gain-mode=album"), AppController.replayGainArgs("album"))
        assertEquals(emptyList(), AppController.replayGainArgs("nonsense"))
    }

    @Test
    fun `settings are kept across a restart`() = FakeSubsonic().use { fake ->
        val store = store()
        val app = app(fake, store)
        app.changeScrobbling(false)
        app.changeReplayGain("album")

        val reopened = app(fake, store)
        assertFalse(reopened.scrobbling)
        assertEquals("album", reopened.replayGain)
    }

    @Test
    fun `volume levelling is on track by default, and the wave only on the premium server`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        assertEquals("track", app.replayGain)
        assertEquals(listOf("--audio-replay-gain-mode=track"), AppController.replayGainArgs(com.platter.desktop.data.Settings().replayGain))
        // The fake is not music.timoha.top and there is no address to set by hand: no wave.
        assertFalse(app.waveAvailable)
    }

    @Test
    fun `a library scan is followed until the server is done`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        app.scanLibrary()
        assertTrue(app.scanning)
        waitUntil("the scan to finish") { !app.scanning }
        assertEquals("Library scan finished", app.toast)
        assertEquals(1, fake.requestsTo("startScan").size)
        assertTrue(fake.requestsTo("getScanStatus").isNotEmpty())
    }

    // --- radio, queue, sharing --------------------------------------------------------------------

    @Test
    fun `radio plays the song and lets similar ones follow`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        val seed = song("s2-1")
        app.startRadio(seed)
        waitUntil("the mix to join the queue") { app.player.state.value.queue.songs.size == 5 }
        val queue = app.player.state.value.queue.songs
        assertEquals("s2-1", queue.first().id)
        assertTrue(queue.drop(1).none { it.id == "s2-1" })
    }

    @Test
    fun `an instant mix goes in after the song that is playing and says so`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        app.player.play(listOf(song("s2-1"), song("s2-2")))
        waitUntil("the queue") { app.player.state.value.queue.songs.size == 2 }
        app.addInstantMix(song("s2-1"))
        waitUntil("the mix") { app.player.state.value.queue.songs.size == 6 }
        val queue = app.player.state.value.queue.songs.map { it.id }
        assertEquals("s2-1", queue[0])
        assertEquals("s2-2", queue.last(), "the mix sits between the current song and what was already queued")
        assertEquals("Added 4 similar songs to the queue", app.toast)
    }

    @Test
    fun `an artist plays by its best-known songs, a genre by a handful of its own`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        app.playArtist("Miles Davis", emptyList(), shuffle = false)
        waitUntil("the artist's songs") { app.player.state.value.queue.songs.size == 3 }
        assertEquals(listOf("s0-1", "s0-2", "s0-3"), app.player.state.value.queue.songs.map { it.id })

        app.playGenre("Jazz", shuffle = true)
        waitUntil("the genre's songs") { app.player.state.value.queue.songs.size == 4 }
    }

    @Test
    fun `sharing makes a link and either copies it or shows it`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        app.share("a2")
        waitUntil("the toast") { app.toast != null }
        assertEquals("a2", fake.requestsTo("createShare").single().queryParameter("id"))
        assertTrue(app.toast == "Link copied" || app.toast == "http://example.test/share/sh1")
    }

    @Test
    fun `play next and add to queue are confirmed`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        app.player.play(listOf(song("s1-1")))
        waitUntil("the queue") { app.player.state.value.queue.songs.isNotEmpty() }
        app.playNext(listOf(song("s1-2"), song("s1-3")))
        assertEquals("2 songs will play next", app.toast)
        app.addToQueue(listOf(song("s1-4")))
        assertEquals("Added to queue", app.toast)
        waitUntil("the queue to fill") { app.player.state.value.queue.songs.size == 4 }
        assertEquals(listOf("s1-1", "s1-2", "s1-3", "s1-4"), app.player.state.value.queue.songs.map { it.id })
    }

    // --- places ------------------------------------------------------------------------------------

    @Test
    fun `top-level places replace the trail, pages stack on it`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        app.navigate(Screen.Genre("Jazz"))
        app.navigate(Screen.Album("a1"))
        assertEquals(3, app.stack.size)
        app.back()
        assertEquals(Screen.Genre("Jazz"), app.screen)
        app.navigate(Screen.Settings)
        assertEquals(listOf<Screen>(Screen.Settings), app.stack)
        assertNotNull(app.client)
    }
}
