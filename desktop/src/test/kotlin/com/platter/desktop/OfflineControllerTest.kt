package com.platter.desktop

import com.platter.desktop.api.Auth
import com.platter.desktop.api.Credentials
import com.platter.desktop.api.Song
import com.platter.desktop.data.Settings
import com.platter.desktop.data.SettingsStore
import com.platter.desktop.offline.Source
import com.platter.desktop.offline.Transfer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Songs saved for offline play: asked for, played from the disk, kept by listening or by liking, and used with no server. */
class OfflineControllerTest {
    private val apps = ArrayList<AppController>()
    private val dir = Files.createTempDirectory("platter-offline")
    private val folder: Path = dir.resolve("my-downloads")

    @AfterTest
    fun tearDown() = apps.forEach { it.shutdown() }

    private fun store() = SettingsStore(dir.resolve("settings.json"))

    private fun app(fake: FakeSubsonic, store: SettingsStore = store(), configure: Settings.() -> Unit = {}): AppController {
        store.update {
            withCredentials(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt"))
            downloadFolder = folder.toString()
            configure()
        }
        return AppController(store, CoroutineScope(SupervisorJob() + Dispatchers.IO), vlcArgs = listOf("--aout=dummy")).also { apps += it }
    }

    private fun song(id: String, title: String = "Song $id", album: String = "Album", track: Int = 1) = Song().apply {
        this.id = id; this.title = title; artist = "Artist"; this.album = album; this.track = track; suffix = "wav"; duration = 4; coverArtId = "al-0"
    }

    private fun savedIds(app: AppController) = app.downloadedSongs().map { it.id }.toSet()

    // --- asking for songs ---------------------------------------------------------------------

    @Test
    fun `a song asked for is saved in the folder chosen, and says so`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        val s = song("s1-1", "Come Together")
        assertFalse(app.isDownloaded("s1-1"))
        app.download(listOf(s))
        assertEquals("Downloading “Come Together”", app.toast)
        waitUntil("the song to be saved") { app.isDownloaded("s1-1") }

        val saved = app.downloadedSongs().single()
        assertTrue(saved.path.startsWith(folder.toString()), saved.path)
        assertEquals(Source.MANUAL, saved.source)
        assertNotNull(app.localCover("al-0"), "its cover came with it")
        assertTrue(app.downloadedBytes > 0)
    }

    @Test
    fun `a podcast or a station cannot be downloaded`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        app.download(listOf(song("e1").apply { kind = Song.KIND_PODCAST }))
        Thread.sleep(300)
        assertTrue(fake.requestsTo("download").isEmpty())
        assertNull(app.toast)
    }

    @Test
    fun `the download quality asks the server to convert`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        app.changeDownloadBitrate(128)
        app.download(listOf(song("s1-1")))
        waitUntil("the song") { app.isDownloaded("s1-1") }
        val request = fake.requestsTo("stream").single()
        assertEquals("128", request.queryParameter("maxBitRate"))
        assertEquals("mp3", request.queryParameter("format"))
        assertTrue(fake.requestsTo("download").isEmpty())
    }

    @Test
    fun `a failed download is shown, and tried again on request`() = FakeSubsonic().use { fake ->
        fake.downloadFails += "s1-1"
        val app = app(fake)
        app.download(listOf(song("s1-1")))
        waitUntil("the failure") { app.downloader.transfers["s1-1"] is Transfer.Failed }
        assertEquals("s1-1", app.downloader.songFor("s1-1")?.id)

        fake.downloadFails -= "s1-1"
        app.retryFailedDownloads()
        waitUntil("the retry to work") { app.isDownloaded("s1-1") }
        assertTrue(app.downloader.transfers.isEmpty())
    }

    // --- playing from the disk ------------------------------------------------------------------

    @Test
    fun `a saved song plays from the disk, not from the server`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        app.download(listOf(song("s1-1")))
        waitUntil("the song") { app.isDownloaded("s1-1") }

        app.player.play(listOf(song("s1-1")))
        waitUntil("playback") { app.player.state.value.isPlaying }
        Thread.sleep(500)
        assertTrue(fake.requestsTo("stream").isEmpty(), "nothing was streamed")

        // A song that was not saved still streams.
        app.player.play(listOf(song("s1-2", track = 2)))
        waitUntil("the stream") { fake.requestsTo("stream").any { it.queryParameter("id") == "s1-2" } }
    }

    @Test
    fun `a saved song whose file the listener deleted is streamed again`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        app.download(listOf(song("s1-1")))
        waitUntil("the song") { app.isDownloaded("s1-1") }
        Files.delete(app.downloadedSongs().single().file)

        app.player.play(listOf(song("s1-1")))
        waitUntil("the stream") { fake.requestsTo("stream").isNotEmpty() }
    }

    @Test
    fun `with the server gone the saved songs are listed and play, and the play is kept for later`() {
        val fake = FakeSubsonic()
        val store = store()
        val first = app(fake, store)
        first.download(listOf(song("s1-1", "Offline song")))
        waitUntil("the song") { first.isDownloaded("s1-1") }
        first.shutdown()
        fake.close() // the train goes into the tunnel

        val offline = app(fake, store)
        assertEquals(listOf("Offline song"), offline.downloadedSongs().map { it.title })
        assertNotNull(offline.localCover("al-0"), "the cover is there with no server")
        offline.player.play(offline.downloadedSongs().map { it.toSong() })
        waitUntil("playback with no server") { offline.player.state.value.isPlaying }
        // Half of four seconds: the play counts, and with nobody to tell it is written down for later.
        waitUntil("the play to be kept", 15_000) { offline.pendingPlays == 1 }
    }

    // --- removing -----------------------------------------------------------------------------------

    @Test
    fun `downloads are removed one by one or all at once, the files with them`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        app.download(listOf(song("s1-1"), song("s1-2", track = 2), song("s1-3", track = 3)))
        waitUntil("all three") { savedIds(app).size == 3 }
        val files = app.downloadedSongs().map { it.file }
        assertTrue(files.all { Files.exists(it) })

        app.removeDownloads(listOf(song("s1-1")))
        assertEquals(setOf("s1-2", "s1-3"), savedIds(app))
        assertEquals(2, files.count { Files.exists(it) })

        app.removeAllDownloads()
        assertTrue(savedIds(app).isEmpty())
        assertTrue(files.none { Files.exists(it) })
        assertEquals("Deleted all downloads", app.toast)
    }

    @Test
    fun `new downloads go to the new folder, and the old ones stay where they were`() = FakeSubsonic().use { fake ->
        val store = store()
        val app = app(fake, store)
        app.download(listOf(song("s1-1")))
        waitUntil("the first") { app.isDownloaded("s1-1") }
        val old = app.downloadedSongs().single().file

        val elsewhere = dir.resolve("elsewhere")
        app.changeDownloadFolder(elsewhere.toString())
        assertTrue(Files.isDirectory(elsewhere))
        assertEquals(elsewhere.toString(), store.load().downloadFolder)

        app.download(listOf(song("s1-2", track = 2)))
        waitUntil("the second") { app.isDownloaded("s1-2") }
        assertTrue(app.downloadedSongs().first { it.id == "s1-2" }.path.startsWith(elsewhere.toString()))
        assertTrue(Files.exists(old))
        app.player.play(listOf(song("s1-1")))
        waitUntil("the old one to play") { app.player.state.value.isPlaying }
    }

    // --- saving as you listen -----------------------------------------------------------------------

    @Test
    fun `with smart download off, listening saves nothing`() = FakeSubsonic().use { fake ->
        val app = app(fake)
        app.player.play(listOf(song("s1-1")))
        waitUntil("playback") { app.player.state.value.isPlaying }
        Thread.sleep(800)
        assertTrue(fake.requestsTo("download").isEmpty())
    }

    @Test
    fun `with smart download on, what is played and liked is saved, marked as saved by listening`() = FakeSubsonic().use { fake ->
        val app = app(fake) { smartDownload = true }
        app.player.play(listOf(song("s1-1"))) // liked on the server
        waitUntil("the song to be saved as it plays") { app.isDownloaded("s1-1") }
        assertEquals(Source.SMART, app.downloadedSongs().single().source)
    }

    @Test
    fun `a song that is listened to but not liked is not saved, until it is liked while it plays`() = FakeSubsonic().use { fake ->
        val app = app(fake) { smartDownload = true }
        app.player.play(listOf(song("s1-2", track = 2)))
        waitUntil("playback") { app.player.state.value.isPlaying }
        Thread.sleep(800)
        assertFalse(app.isDownloaded("s1-2"), "listening alone saves nothing")

        app.toggleLike(song("s1-2", track = 2))
        waitUntil("the like to save it") { app.isDownloaded("s1-2") }
        assertEquals(Source.SMART, app.downloadedSongs().single().source)
    }

    @Test
    fun `a like on a song that is not the one playing saves nothing`() = FakeSubsonic().use { fake ->
        val app = app(fake) { smartDownload = true }
        app.player.play(listOf(song("s1-2", track = 2)))
        waitUntil("playback") { app.player.state.value.isPlaying }
        app.toggleLike(song("s1-3", track = 3))
        Thread.sleep(800)
        assertTrue(savedIds(app).isEmpty())
    }

    @Test
    fun `the songs played longest ago make room, and what the listener asked for is never let go`() = FakeSubsonic().use { fake ->
        fake.starredSongs += setOf("s1-2", "s1-3")
        val app = app(fake) { smartDownload = true; smartCount = 2 }
        app.download(listOf(song("mine", "Mine", track = 9)))
        waitUntil("the one asked for") { app.isDownloaded("mine") }

        for ((i, id) in listOf("s1-1", "s1-2", "s1-3").withIndex()) {
            app.player.play(listOf(song(id, track = i + 1)))
            waitUntil("$id to be saved") { app.isDownloaded(id) }
            Thread.sleep(50)
        }
        waitUntil("the oldest to make room") { !app.isDownloaded("s1-1") }
        assertEquals(setOf("mine", "s1-2", "s1-3"), savedIds(app), "two saved by listening, plus the one asked for")
    }

    @Test
    fun `a song saved by listening and then asked for by hand is not let go`() = FakeSubsonic().use { fake ->
        fake.starredSongs += "s1-2"
        val app = app(fake) { smartDownload = true; smartCount = 1 }
        app.player.play(listOf(song("s1-1")))
        waitUntil("saved by listening") { app.isDownloaded("s1-1") }
        app.download(listOf(song("s1-1")))
        waitUntil("promoted") { app.downloadedSongs().single().source == Source.MANUAL }

        app.player.play(listOf(song("s1-2", track = 2)))
        waitUntil("the next") { app.isDownloaded("s1-2") }
        app.changeSmartCount(1)
        assertTrue(app.isDownloaded("s1-1"), "it is the listener's now")
    }

    @Test
    fun `turning smart download off keeps what it saved`() = FakeSubsonic().use { fake ->
        val app = app(fake) { smartDownload = true; smartCount = 1 }
        app.player.play(listOf(song("s1-1")))
        waitUntil("saved") { app.isDownloaded("s1-1") }
        app.changeSmartDownload(false)
        app.changeSmartCount(25)
        app.player.play(listOf(song("s1-2", track = 2)))
        Thread.sleep(800)
        assertTrue(app.isDownloaded("s1-1"))
        assertFalse(app.isDownloaded("s1-2"), "nothing new is saved")
    }

    @Test
    fun `settings for downloads are kept across a restart`() = FakeSubsonic().use { fake ->
        val store = store()
        val app = app(fake, store)
        app.changeDownloadBitrate(192)
        app.changeSmartDownload(true)
        app.changeSmartCount(50)
        val saved = store.load()
        assertEquals(192, saved.downloadBitrate)
        assertTrue(saved.smartDownload)
        assertEquals(50, saved.smartCount)

        val again = app(fake, store)
        assertEquals(50, again.smartCount)
    }
}
