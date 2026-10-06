package com.platter.desktop

import com.platter.desktop.api.Auth
import com.platter.desktop.api.Credentials
import com.platter.desktop.api.Song
import com.platter.desktop.api.SubsonicClient
import com.platter.desktop.offline.DownloadStore
import com.platter.desktop.offline.DownloadedSong
import com.platter.desktop.offline.Downloader
import com.platter.desktop.offline.Source
import com.platter.desktop.offline.Transfer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloaderTest {
    private val dir = Files.createTempDirectory("platter-dl")
    private val root = dir.resolve("Downloads")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = DownloadStore(dir.resolve("index.json"))
    private var changes = 0
    private val downloader = Downloader(scope, store, { root }, dir.resolve("covers"), onChange = { changes++ })
    private val server = "home|tim"

    @AfterTest
    fun tearDown() = downloader.cancelAll()

    private fun client(fake: FakeSubsonic) = SubsonicClient(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt"))

    private fun song(id: String, title: String = "Song $id", artist: String = "Artist", album: String = "Album", track: Int? = 1) = Song().apply {
        this.id = id; this.title = title; this.artist = artist; this.album = album; this.track = track
        suffix = "mp3"; coverArtId = "al-0"; duration = 200
    }

    private fun idle() = downloader.transfers.isEmpty()

    @Test
    fun `a song is filed under artist and album, whole, with its tags and cover`() = FakeSubsonic().use { fake ->
        val s = song("s1", title = "Come Together", artist = "The Beatles", album = "Abbey Road", track = 1)
        downloader.enqueue(client(fake), server, listOf(s), Source.MANUAL)
        waitUntil("the download to finish") { store.find(server, "s1") != null }

        val file = root.resolve("The Beatles").resolve("Abbey Road").resolve("01 - Come Together.mp3")
        assertTrue(Files.isRegularFile(file), "expected $file")
        assertContentEquals(fake.audioBytes("s1"), Files.readAllBytes(file))

        val record = store.find(server, "s1")!!
        assertEquals(file.toString(), record.path)
        assertEquals(Files.size(file), record.bytes)
        assertEquals(Source.MANUAL, record.source)
        assertEquals("Come Together", record.toSong().title)
        assertEquals(file, store.playablePath(server, "s1"))
        assertTrue(Files.isRegularFile(downloader.coverFile("al-0")), "the cover is kept for the Downloads screen")
        assertTrue(idle())
        assertTrue(Files.list(file.parent).use { list -> list.noneMatch { it.toString().endsWith(".part") } })
        assertTrue(changes > 0)
    }

    @Test
    fun `the original file is asked for, a transcode only when a cap or format is set`() = FakeSubsonic().use { fake ->
        downloader.enqueue(client(fake), server, listOf(song("s1")), Source.MANUAL)
        waitUntil("the original") { store.find(server, "s1") != null }
        assertEquals(1, fake.requestsTo("download").size)
        assertTrue(fake.requestsTo("stream").isEmpty())

        downloader.enqueue(client(fake), server, listOf(song("s2")), Source.MANUAL, maxBitRate = 128, format = "mp3")
        waitUntil("the transcode") { store.find(server, "s2") != null }
        val request = fake.requestsTo("stream").single()
        assertEquals("128", request.queryParameter("maxBitRate"))
        assertEquals("mp3", request.queryParameter("format"))
        assertTrue(store.find(server, "s2")!!.path.endsWith(".mp3"))
    }

    @Test
    fun `a podcast episode or a station is not a download`() = FakeSubsonic().use { fake ->
        val episode = song("e1").apply { kind = Song.KIND_PODCAST }
        downloader.enqueue(client(fake), server, listOf(episode), Source.MANUAL)
        Thread.sleep(300)
        assertTrue(fake.requestsTo("download").isEmpty())
        assertNull(store.find(server, "e1"))
    }

    @Test
    fun `asking again does not fetch again, and a stronger reason is kept`() = FakeSubsonic().use { fake ->
        downloader.enqueue(client(fake), server, listOf(song("s1")), Source.SMART)
        waitUntil("the song") { store.find(server, "s1") != null }
        assertEquals(Source.SMART, store.find(server, "s1")!!.source)

        // Asked for by hand afterwards: it stays, and it is now the listener's, not the cache's.
        downloader.enqueue(client(fake), server, listOf(song("s1")), Source.MANUAL)
        assertEquals(Source.MANUAL, store.find(server, "s1")!!.source)
        // And the cache asking later cannot take that back.
        downloader.enqueue(client(fake), server, listOf(song("s1")), Source.SMART)
        assertEquals(Source.MANUAL, store.find(server, "s1")!!.source)
        assertEquals(1, fake.requestsTo("download").size)
    }

    @Test
    fun `a refusal from the server is shown as the reason, and leaves nothing behind`() = FakeSubsonic().use { fake ->
        fake.downloadFails += "gone"
        downloader.enqueue(client(fake), server, listOf(song("gone")), Source.MANUAL)
        waitUntil("the failure") { downloader.transfers["gone"] is Transfer.Failed }
        assertEquals("Song not found", (downloader.transfers["gone"] as Transfer.Failed).message)
        assertNull(store.find(server, "gone"))
        assertTrue(!Files.exists(root) || Files.walk(root).use { walk -> walk.noneMatch { Files.isRegularFile(it) } }, "no file, not even a .part")
    }

    @Test
    fun `names Windows would refuse are made safe`() {
        assertEquals("AC_DC_ Back In Black_", Downloader.clean("AC/DC: Back In Black?"))
        assertEquals("_CON", Downloader.clean("CON"))
        assertEquals("_nul.txt", Downloader.clean("nul.txt"))
        assertEquals("Mr. Brightside", Downloader.clean("Mr. Brightside"))
        assertEquals("Wait", Downloader.clean("Wait... "))
        assertEquals("_", Downloader.clean("..."))
        assertEquals(100, Downloader.clean("x".repeat(300)).length)
    }

    @Test
    fun `a disc number comes before the track number, and a missing track has none`() {
        val d = downloader.targetFor(server, song("a", track = 3).apply { discNumber = 2 }, "flac")
        assertEquals("2-03 - Song a.flac", d.fileName.toString())
        assertEquals("Song b.mp3", downloader.targetFor(server, song("b", track = null), "mp3").fileName.toString())
    }

    @Test
    fun `two songs that would share a file name do not overwrite each other`() = FakeSubsonic().use { fake ->
        val first = song("s1", title = "Intro", track = 1)
        val second = song("s2", title = "Intro", track = 1)
        downloader.enqueue(client(fake), server, listOf(first, second), Source.MANUAL)
        waitUntil("both") { store.find(server, "s1") != null && store.find(server, "s2") != null }
        assertEquals(2, store.all(server).map { it.path }.toSet().size)
        assertContentEquals(fake.audioBytes("s1"), Files.readAllBytes(store.find(server, "s1")!!.file))
        assertContentEquals(fake.audioBytes("s2"), Files.readAllBytes(store.find(server, "s2")!!.file))
    }

    @Test
    fun `removing takes the file and the folders it leaves empty, but not the folder of its neighbours`() = FakeSubsonic().use { fake ->
        downloader.enqueue(client(fake), server, listOf(song("s1", track = 1), song("s2", track = 2), song("s3", album = "Other", track = 1)), Source.MANUAL)
        waitUntil("all three") { store.all(server).size == 3 }
        val first = store.find(server, "s1")!!.file
        val other = store.find(server, "s3")!!.file

        downloader.remove(server, "s1")
        assertFalse(Files.exists(first))
        assertTrue(Files.isDirectory(first.parent), "s2 is still in the album folder")
        assertNull(store.find(server, "s1"))

        downloader.remove(server, "s3")
        assertFalse(Files.exists(other.parent), "the empty album folder went")
        assertTrue(Files.isDirectory(root), "the download folder itself stays")
    }

    @Test
    fun `everything can be removed at once`() = FakeSubsonic().use { fake ->
        downloader.enqueue(client(fake), server, listOf(song("s1"), song("s2", track = 2)), Source.MANUAL)
        waitUntil("both") { store.all(server).size == 2 }
        downloader.removeAll(server)
        assertEquals(0, store.all(server).size)
        assertTrue(Files.walk(root).use { walk -> walk.noneMatch { Files.isRegularFile(it) } })
    }

    @Test
    fun `cancelling stops what is running and leaves no part of a file`() = FakeSubsonic().use { fake ->
        downloader.enqueue(client(fake), server, listOf(song("slow1")), Source.MANUAL)
        waitUntil("the download to be under way") { (downloader.transfers["slow1"] as? Transfer.Active)?.fraction?.let { it > 0f } == true }
        downloader.cancelAll()
        Thread.sleep(500)
        assertTrue(idle())
        assertNull(store.find(server, "slow1"))
        assertTrue(Files.walk(root).use { walk -> walk.noneMatch { Files.isRegularFile(it) } }, "not even a .part")
    }

    @Test
    fun `no more than two are fetched at once, and the rest wait their turn`() = FakeSubsonic().use { fake ->
        downloader.enqueue(client(fake), server, (1..5).map { song("slow$it", track = it) }, Source.MANUAL)
        var mostAtOnce = 0
        val end = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < end) {
            mostAtOnce = maxOf(mostAtOnce, downloader.transfers.values.count { it is Transfer.Active })
            Thread.sleep(20)
        }
        assertTrue(mostAtOnce in 1..Downloader.PARALLEL, "saw $mostAtOnce at once")
        assertTrue(downloader.transfers.values.any { it is Transfer.Queued })
    }

    @Test
    fun `the index survives a restart, and a file the listener deleted is no longer playable`() = FakeSubsonic().use { fake ->
        downloader.enqueue(client(fake), server, listOf(song("s1")), Source.LIKED)
        waitUntil("the song") { store.find(server, "s1") != null }

        val reopened = DownloadStore(dir.resolve("index.json"))
        val record = reopened.find(server, "s1")!!
        assertEquals(Source.LIKED, record.source)
        assertNotNull(reopened.playablePath(server, "s1"))

        Files.delete(record.file)
        assertNull(reopened.playablePath(server, "s1"), "the entry is there, the file is not")
        assertNotNull(reopened.find(server, "s1"))
    }

    @Test
    fun `songs of another server are not mixed in`() {
        val a = DownloadedSong().apply { this.server = "one|tim"; id = "7"; path = "a" }
        val b = DownloadedSong().apply { this.server = "two|tim"; id = "7"; path = "b" }
        store.put(a)
        store.put(b)
        assertEquals(listOf("a"), store.all("one|tim").map { it.path })
        assertEquals("b", store.find("two|tim", "7")!!.path)
        assertEquals(1, store.all("two|tim").size)
    }

    @Test
    fun `a damaged index loses the list, not the app`() {
        val file: Path = dir.resolve("broken.json")
        Files.writeString(file, "[ not json")
        assertEquals(0, DownloadStore(file).all(server).size)
    }

    @Test
    fun `sources rank - asked for beats liked beats saved while listening`() {
        assertTrue(Source.rank(Source.MANUAL) > Source.rank(Source.LIKED))
        assertTrue(Source.rank(Source.LIKED) > Source.rank(Source.SMART))
    }
}
