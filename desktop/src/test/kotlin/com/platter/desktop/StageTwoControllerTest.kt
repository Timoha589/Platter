package com.platter.desktop

import com.platter.desktop.api.Auth
import com.platter.desktop.api.Credentials
import com.platter.desktop.api.PodcastEpisode
import com.platter.desktop.api.RadioStation
import com.platter.desktop.api.Song
import com.platter.desktop.data.Settings
import com.platter.desktop.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The queue kept on the server, podcasts, radio, plays that wait for a server, and home-or-away addressing. */
class StageTwoControllerTest {
    private val apps = ArrayList<AppController>()
    private val dir = Files.createTempDirectory("platter-stage2")

    @AfterTest
    fun tearDown() = apps.forEach { it.shutdown() }

    private fun store() = SettingsStore(dir.resolve("settings.json"))

    /** [address] is where the listener "signed in"; it defaults to the fake server. */
    private fun app(fake: FakeSubsonic, address: String = fake.url, store: SettingsStore = store(), wave: String? = null, configure: Settings.() -> Unit = {}): AppController {
        store.update {
            withCredentials(Credentials(address, "tim", Auth.token("pw", "salt"), "salt"))
            configure()
        }
        return AppController(store, CoroutineScope(SupervisorJob() + Dispatchers.IO), vlcArgs = listOf("--aout=dummy"), waveUrl = wave).also { apps += it }
    }

    private fun song(id: String) = Song().apply { this.id = id; title = "Song $id"; artist = "Someone"; duration = 180 }

    private val dead = "http://127.0.0.1:1/"

    // --- the queue on the server ------------------------------------------------------------

    @Test
    fun `with syncing off nothing is saved`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        app.player.play(listOf(song("s1-1"), song("s1-2")))
        waitUntil("playback") { app.player.state.value.isPlaying }
        Thread.sleep(2_800)
        assertTrue(fake.requests.none { it.pathSegments.last().startsWith("savePlayQueue") })
    }

    @Test
    fun `with syncing on the queue is saved a moment after it changes, with the song and the place in it`() = FakeSubsonic().use { fake ->
        val app = app(fake) { syncQueue = true }
        app.player.play(listOf(song("s1-1"), song("s1-2"), song("s1-3")), start = 1)
        waitUntil("the queue to reach the server", 12_000) { fake.savedQueue != null }
        val saved = fake.savedQueue!!
        assertEquals(listOf("s1-1", "s1-2", "s1-3"), saved.ids)
        assertEquals(1, saved.currentIndex)
    }

    @Test
    fun `turning syncing on saves what is playing straight away`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        app.player.play(listOf(song("s2-1"), song("s2-2")))
        waitUntil("playback") { app.player.state.value.isPlaying }
        app.changeSyncQueue(true)
        waitUntil("the save") { fake.savedQueue != null }
        assertEquals(listOf("s2-1", "s2-2"), fake.savedQueue!!.ids)
        assertTrue(app.syncQueue)
    }

    @Test
    fun `a queue with a podcast or a station in it is not saved - those ids are not songs`() = FakeSubsonic().use { fake ->
        val app = app(fake) { syncQueue = true }
        app.playStation(RadioStation().apply { id = "r1"; name = "Jazz FM"; streamUrl = "${fake.url}radio/jazz.wav" })
        waitUntil("the station to play") { app.player.state.value.isPlaying }
        Thread.sleep(2_800)
        assertNull(fake.savedQueue)
    }

    @Test
    fun `a queue another device left is offered on start, and picking it up resumes where it stopped`() = FakeSubsonic().use { fake ->
        runBlocking {
            com.platter.desktop.api.SubsonicClient(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt")).savePlayQueue(listOf("s1-1", "s1-2", "s1-3"), 2, 2_000)
        }
        val app = app(fake) { syncQueue = true }
        waitUntil("the offer") { app.resumeOffer != null }
        val offer = app.resumeOffer!!
        assertEquals("s1-3", offer.song?.id)
        assertEquals(2_000L, offer.positionMs)

        app.resume()
        assertNull(app.resumeOffer)
        waitUntil("the queue to play") { app.player.state.value.current?.id == "s1-3" && app.player.state.value.isPlaying }
        assertEquals(3, app.player.state.value.queue.songs.size)
        assertTrue(app.player.state.value.positionMs >= 2_000, "it starts part-way in, not from the top")
        waitUntil("the stream to be asked for") { fake.requestsTo("stream").any { it.queryParameter("id") == "s1-3" } }
    }

    @Test
    fun `no offer when nothing was saved, when syncing is off, or when something is already playing`() = FakeSubsonic().use { fake ->
        val empty = app(fake) { syncQueue = true }
        Thread.sleep(600)
        assertNull(empty.resumeOffer, "nothing was saved")

        runBlocking {
            com.platter.desktop.api.SubsonicClient(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt")).savePlayQueue(listOf("s1-1"), 0, 0)
        }
        val off = app(fake, store = SettingsStore(dir.resolve("off.json")))
        Thread.sleep(600)
        assertNull(off.resumeOffer, "syncing is off")

        val busy = app(fake, store = SettingsStore(dir.resolve("busy.json"))) { syncQueue = true }
        busy.player.play(listOf(song("s2-1")))
        waitUntil("the queue") { busy.player.state.value.queue.songs.isNotEmpty() }
        busy.checkSavedQueue()
        Thread.sleep(600)
        // The offer made at start-up may have come before the song did; asking again with something playing offers nothing.
        busy.dismissResume()
        busy.checkSavedQueue()
        Thread.sleep(600)
        assertNull(busy.resumeOffer)
    }

    @Test
    fun `the offer goes away by itself after the time set`() = FakeSubsonic().use { fake ->
        runBlocking {
            com.platter.desktop.api.SubsonicClient(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt")).savePlayQueue(listOf("s1-1"), 0, 0)
        }
        val app = app(fake) { syncQueue = true; syncSeconds = 1 }
        waitUntil("the offer") { app.resumeOffer != null }
        waitUntil("the offer to lapse", 6_000) { app.resumeOffer == null }
    }

    @Test
    fun `closing the app leaves the queue and the place in it on the server`() = FakeSubsonic().use { fake ->
        val app = app(fake) { syncQueue = true }
        app.player.play(listOf(song("s1-1"), song("s1-2")), start = 1)
        waitUntil("playback") { app.player.state.value.isPlaying }
        app.shutdown()
        assertEquals(listOf("s1-1", "s1-2"), fake.savedQueue?.ids)
        assertEquals(1, fake.savedQueue?.currentIndex)
    }

    // --- podcasts ----------------------------------------------------------------------------------

    private fun episodes(fake: FakeSubsonic, app: AppController): List<PodcastEpisode> =
        runBlocking { app.client!!.podcastChannel("c1") }.episodes!!

    @Test
    fun `an episode becomes something the player can queue, marked as a podcast`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        val channel = runBlocking { app.client!!.podcastChannel("c1") }
        val song = app.songOf(channel.episodes!!.first(), channel)
        assertEquals("s0-1", song.id)
        assertEquals("Tech Talk", song.artist)
        assertEquals(1800, song.duration)
        assertEquals(Song.KIND_PODCAST, song.kind)
        assertFalse(song.isMusic)
        assertFalse(song.isLive)
    }

    @Test
    fun `playing episodes queues those the server has, from the one pressed, and scrobbles nothing`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        val all = episodes(fake, app)
        app.playEpisodes(all, 0)
        waitUntil("the queue") { app.player.state.value.queue.songs.size == 2 }
        assertEquals(listOf("s0-1", "s0-2"), app.player.state.value.queue.songs.map { it.id }, "the episode not fetched yet is left out")
        waitUntil("playback") { app.player.state.value.isPlaying }
        Thread.sleep(1_500)
        assertTrue(fake.requestsTo("scrobble").isEmpty(), "an episode is not a play the server counts")

        app.playEpisodes(all, 2)
        assertEquals("The server has not downloaded this episode yet", app.toast)
    }

    @Test
    fun `podcasts are added from a feed address, and only from one`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        app.addPodcast("not an address")
        assertEquals("A podcast address starts with http:// or https://", app.toast)
        assertTrue(fake.requestsTo("createPodcastChannel").isEmpty())

        val before = app.podcastVersion
        app.addPodcast("  http://feeds.test/new  ")
        waitUntil("the feed to be sent") { fake.requestsTo("createPodcastChannel").isNotEmpty() }
        assertEquals("http://feeds.test/new", fake.requestsTo("createPodcastChannel").single().queryParameter("url"))
        waitUntil("the screens to be told") { app.podcastVersion > before }
    }

    @Test
    fun `a podcast and its episodes can be refreshed, downloaded and deleted`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        val channel = runBlocking { app.client!!.podcastChannel("c1") }
        app.refreshPodcasts()
        app.downloadEpisode(channel.episodes!![2])
        app.deleteEpisode(channel.episodes!![1])
        waitUntil("all three") { fake.requestsTo("refreshPodcasts").isNotEmpty() && fake.requestsTo("downloadPodcastEpisode").isNotEmpty() && fake.requestsTo("deletePodcastEpisode").isNotEmpty() }
        assertEquals("e3", fake.requestsTo("downloadPodcastEpisode").single().queryParameter("id"))

        app.navigate(Screen.Podcast("c1"))
        app.deletePodcast(channel)
        waitUntil("the delete") { fake.requestsTo("deletePodcastChannel").isNotEmpty() }
        waitUntil("the page to be left") { app.screen == Screen.Home }
        assertEquals("c1", fake.requestsTo("deletePodcastChannel").single().queryParameter("id"))
    }

    // --- internet radio -------------------------------------------------------------------------------

    @Test
    fun `a station plays at its own address, is live, and is not a song`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        val station = runBlocking { app.client!!.radioStations() }.single()
        app.playStation(station)
        waitUntil("the station to play") { app.player.state.value.isPlaying }
        val playing = app.player.state.value.current!!
        assertTrue(playing.isLive)
        assertFalse(playing.isMusic)
        assertEquals("Jazz FM", playing.title)
        assertTrue(fake.requests.any { it.encodedPath == "/radio/jazz.wav" }, "it was fetched from the station's own address, not through stream")
        assertTrue(fake.requestsTo("stream").isEmpty())
        Thread.sleep(1_200)
        assertTrue(fake.requestsTo("scrobble").isEmpty())
    }

    @Test
    fun `stations are added and changed only with a name and a web address`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        app.saveStation(null, "  ", "http://x.test/s", "")
        assertEquals("A station needs a name and a stream address starting with http:// or https://", app.toast)
        app.saveStation(null, "No scheme", "x.test/s", "")
        assertTrue(fake.requestsTo("createInternetRadioStation").isEmpty())

        app.saveStation(null, "Rock FM", "http://rock.test/s", "http://rock.test")
        waitUntil("the station to be added") { fake.requestsTo("createInternetRadioStation").isNotEmpty() }
        val added = runBlocking { app.client!!.radioStations() }.first { it.name == "Rock FM" }

        app.saveStation(added, "Rock FM Two", "http://rock.test/two", "")
        waitUntil("the change") { fake.requestsTo("updateInternetRadioStation").isNotEmpty() }
        assertEquals(added.id, fake.requestsTo("updateInternetRadioStation").single().queryParameter("id"))

        app.deleteStation(added)
        waitUntil("the delete") { fake.requestsTo("deleteInternetRadioStation").isNotEmpty() }
        assertEquals(listOf("Jazz FM"), runBlocking { app.client!!.radioStations() }.map { it.name })
    }

    // --- plays that could not be sent -------------------------------------------------------------------

    @Test
    fun `a play that counted while the server was down is kept and sent when it is back`() = FakeSubsonic().use { fake ->
        fake.scrobblesFail = true
        val app = app(fake)
        app.player.play(listOf(Song().apply { id = "s0-1"; title = "Short"; artist = "A"; duration = 4 }))
        // Half of four seconds counts; the server refuses.
        waitUntil("the play to be written down", 15_000) { app.pendingPlays == 1 }
        assertTrue(fake.requestsTo("scrobble").any { it.queryParameter("submission") == "true" })

        fake.scrobblesFail = false
        app.flushPending()
        waitUntil("the play to be sent") { app.pendingPlays == 0 }
        val sent = fake.requestsTo("scrobble").last { it.queryParameter("submission") == "true" }
        assertEquals("s0-1", sent.queryParameter("id"))
        assertTrue(sent.queryParameter("time")!!.toLong() > 0, "it keeps the time it was heard, however late it arrives")
    }

    @Test
    fun `waiting plays are still waiting after a restart, and go out when the server answers`() = FakeSubsonic().use { fake ->
        val store = store()
        fake.scrobblesFail = true
        val first = app(fake, store = store)
        first.player.play(listOf(Song().apply { id = "s0-2"; title = "Short"; artist = "A"; duration = 4 }))
        waitUntil("the play to be written down", 15_000) { first.pendingPlays == 1 }
        first.shutdown()

        // Reopened while the server is still down: the play is still there.
        val second = app(fake, store = store)
        assertEquals(1, second.pendingPlays)
        Thread.sleep(500)
        assertEquals(1, second.pendingPlays)

        // Once the server answers, the next try sends it.
        fake.scrobblesFail = false
        second.flushPending()
        waitUntil("the play to be sent") { second.pendingPlays == 0 }
    }

    @Test
    fun `with scrobbling off nothing is written down either`() = FakeSubsonic().use { fake ->
        fake.scrobblesFail = true
        val app = app(fake) { scrobbling = false }
        app.player.play(listOf(Song().apply { id = "s0-1"; title = "Short"; artist = "A"; duration = 4 }))
        waitUntil("playback") { app.player.state.value.isPlaying }
        Thread.sleep(3_000)
        assertEquals(0, app.pendingPlays)
        assertTrue(fake.requestsTo("scrobble").isEmpty())
    }

    // --- home or away --------------------------------------------------------------------------------------

    @Test
    fun `away from home the main address is used, and the home one when it answers`() = FakeSubsonic().use { fake ->
        // Signed in at an address that is not there; the server is "at home".
        val app = app(fake, address = dead)
        assertFalse(app.usingLocal)
        app.changeLocalAddress(fake.url)
        waitUntil("the move to the home address") { app.usingLocal }
        assertTrue(com.platter.desktop.api.ServerAddress.same(app.client!!.credentials.serverUrl, fake.url))
        // Everything asked for afterwards goes there.
        assertEquals(8, runBlocking { app.catalogue() }.albums.size)
        app.player.play(listOf(song("s1-1")))
        waitUntil("the stream to come from the home address") { fake.requestsTo("stream").isNotEmpty() }
    }

    @Test
    fun `when the home address stops answering the app goes back to the main one`() = FakeSubsonic().use { fake ->
        val app = app(fake) // main address is the fake
        app.changeLocalAddress(dead)
        Thread.sleep(800)
        assertFalse(app.usingLocal, "a home address that does not answer is not used")
        assertTrue(com.platter.desktop.api.ServerAddress.same(app.client!!.credentials.serverUrl, fake.url))

        app.changeLocalAddress("")
        assertNull(app.localAddress)
    }

    @Test
    fun `moving between addresses is noticed when asked, and says whether it moved`() = FakeSubsonic().use { fake ->
        val app = app(fake, address = dead) { localAddress = fake.url }
        waitUntil("start-up to settle on the home address") { app.usingLocal }
        assertFalse(runBlocking { app.checkAddress() }, "already there: nothing to move")

        // The home address goes away; the main one (a dead port too) is all that is left, and it is a move.
        app.changeLocalAddress(dead)
        waitUntil("the move back") { !app.usingLocal }
        assertTrue(com.platter.desktop.api.ServerAddress.same(app.client!!.credentials.serverUrl, dead))
    }

    @Test
    fun `the saved sign-in and the wave stay with the address the listener signed in with`() = FakeSubsonic().use { fake ->
        val store = store()
        val app = app(fake, address = dead, store = store, wave = fake.waveUrl)
        app.changeLocalAddress(fake.url)
        waitUntil("the move") { app.usingLocal }
        assertEquals(dead, store.load().serverUrl, "what is saved is the main address, not whichever is in use")
        assertEquals(fake.url, store.load().localAddress)
        assertNotNull(app.client)
    }
}
