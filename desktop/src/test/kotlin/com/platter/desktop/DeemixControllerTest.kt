package com.platter.desktop

import com.platter.desktop.api.Auth
import com.platter.desktop.api.Credentials
import com.platter.desktop.api.Song
import com.platter.desktop.data.Secrets
import com.platter.desktop.data.Settings
import com.platter.desktop.data.SettingsStore
import com.platter.desktop.deemix.DeezerHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Deezer through Deemix plus, as the app uses it: the account, search, pages, downloads and previews. */
class DeemixControllerTest {
    private val apps = ArrayList<AppController>()
    private val dir = Files.createTempDirectory("platter-deemix")

    @AfterTest
    fun tearDown() = apps.forEach { it.shutdown() }

    private fun store() = SettingsStore(dir.resolve("settings.json"))

    /** Signed in to the fake server, with the site found at the same address and the right site password sealed away. */
    private fun app(fake: FakeSubsonic, store: SettingsStore = store(), password: String? = "deemixpw", configure: Settings.() -> Unit = {}): AppController {
        store.update {
            withCredentials(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt"))
            deemixUrl = fake.url
            if (password != null) sealedDeemixPassword = Secrets.seal(password)
            configure()
        }
        return AppController(store, CoroutineScope(SupervisorJob() + Dispatchers.IO), vlcArgs = listOf("--aout=dummy"), deezerCatalogUrl = fake.url + "deezer/").also { apps += it }
    }

    private fun windows() = assumeTrue(System.getProperty("os.name").startsWith("Windows"), "the site password is sealed with DPAPI, which is Windows-only")

    private fun hit(id: Long, kind: DeezerHit.Kind = DeezerHit.Kind.TRACK, link: String? = "https://www.deezer.com/track/$id") =
        DeezerHit(kind, id, "Title $id", "Artist", "", null, if (kind == DeezerHit.Kind.TRACK) link else null, false)

    // --- the account ------------------------------------------------------------------------------------

    @Test
    fun `settings show the account and what is left of the quota`() = FakeSubsonic().use { fake ->
        windows()
        val app = app(fake)
        app.refreshDeemixAccount()
        waitUntil("the account") { app.deemixStatus is DeemixStatus.Ready }
        val ready = app.deemixStatus as DeemixStatus.Ready
        assertEquals("tim", ready.user)
        assertEquals("user", ready.role)
        assertEquals("47 of 50 left today", app.deemixQuotaText())
    }

    @Test
    fun `a password the site does not take is said so, and the right one fixes it and is kept sealed`() = FakeSubsonic().use { fake ->
        windows()
        val store = store()
        val app = app(fake, store, password = "wrong")
        app.refreshDeemixAccount()
        waitUntil("the refusal") { app.deemixStatus is DeemixStatus.Rejected }
        assertEquals("tim", (app.deemixStatus as DeemixStatus.Rejected).user)

        app.changeDeemixPassword("deemixpw")
        assertTrue(app.hasDeemixPassword)
        waitUntil("the account") { app.deemixStatus is DeemixStatus.Ready }

        val sealed = store.load().sealedDeemixPassword
        assertNotNull(sealed)
        assertNotEquals("deemixpw", sealed, "never written in the clear")
        assertFalse(Files.readString(dir.resolve("settings.json")).contains("deemixpw"))

        // After a restart the password is still there.
        val again = app(fake, store, password = null)
        assertTrue(again.hasDeemixPassword)
        again.refreshDeemixAccount()
        waitUntil("the account after a restart") { again.deemixStatus is DeemixStatus.Ready }

        again.changeDeemixPassword("")
        assertFalse(again.hasDeemixPassword)
        assertNull(store.load().sealedDeemixPassword)
    }

    @Test
    fun `an address with nothing at it is reported with the address`() = FakeSubsonic().use { fake ->
        windows()
        val app = app(fake) { deemixUrl = "http://127.0.0.1:1/" }
        app.refreshDeemixAccount()
        waitUntil("the report") { app.deemixStatus is DeemixStatus.Unreachable }
        assertEquals("http://127.0.0.1:1/", (app.deemixStatus as DeemixStatus.Unreachable).address)

        app.changeDeemixUrl(fake.url)
        assertEquals(fake.url, app.deemixUrl)
        waitUntil("the account at the new address") { app.deemixStatus is DeemixStatus.Ready }
    }

    // --- search and pages -------------------------------------------------------------------------------------

    @Test
    fun `a search gives artists by fans, then tracks marked against the library, then albums`() = FakeSubsonic().use { fake ->
        windows()
        val app = app(fake)
        val hits = runBlocking { app.deezerSearch("beatles") }!!
        assertEquals(listOf("Beatles", "Beatles Tribute"), hits.artists.map { it.title }, "the one with fans first")
        assertEquals(listOf(DeezerHit.Kind.TRACK, DeezerHit.Kind.TRACK, DeezerHit.Kind.TRACK, DeezerHit.Kind.ALBUM), hits.rows.map { it.kind })
        assertTrue(hits.rows.first { it.title == "Come Together" }.inLibrary)
        assertFalse(hits.rows.first { it.title == "Octopus's Garden" }.inLibrary)
        assertEquals("Abbey Road", hits.rows.last().title)
        assertEquals("https://www.deezer.com/track/101", hits.rows.first().link)
    }

    @Test
    fun `no account means no section, not an error`() = FakeSubsonic().use { fake ->
        windows()
        val app = app(fake, password = "wrong")
        assertNull(runBlocking { app.deezerSearch("beatles") })
        assertNull(app.toast)
    }

    @Test
    fun `an artist page says who, how loved, and what is on it`() = FakeSubsonic().use { fake ->
        windows()
        val app = app(fake)
        val page = runBlocking { app.deezerPage(DeezerHit.Kind.ARTIST, 1) }!!
        assertEquals("Beatles", page.title)
        assertEquals("Artist · 900K fans", page.caption)
        assertEquals("https://img.test/bx.jpg", page.image)
        assertEquals(2, page.albums.size)
        assertEquals(listOf("Album · 1969", "Single · 1968"), page.albums.map { it.caption })
        assertEquals("Abbey Road", page.tracks.first().caption)
        assertTrue(page.tracks.first().inLibrary)
    }

    @Test
    fun `an album page lists its tracks, each placed by artist and length`() = FakeSubsonic().use { fake ->
        windows()
        val app = app(fake)
        val page = runBlocking { app.deezerPage(DeezerHit.Kind.ALBUM, 201) }!!
        assertEquals("Abbey Road", page.title)
        assertEquals("Album · Beatles · 1969", page.caption)
        assertEquals("https://img.test/abbeyx.jpg", page.image)
        assertEquals(listOf("Beatles · 3:01", "Beatles · 3:02", "Beatles · 3:03"), page.tracks.map { it.caption })
        assertTrue(page.albums.isEmpty())
        assertNull(runBlocking { app.deezerPage(DeezerHit.Kind.ALBUM, 424242) })
    }

    // --- downloads ------------------------------------------------------------------------------------------------

    @Test
    fun `a track is queued, and says so`() = FakeSubsonic().use { fake ->
        windows()
        val app = app(fake)
        val track = hit(101)
        app.deezerDownload(track)
        waitUntil("the track to be queued") { app.deezerStates[track.key] == DeezerHit.State.QUEUED }
        assertEquals("Added to the download queue", app.toast)
        assertTrue(fake.deemixCalls.any { it.second == "api/download" && "https://www.deezer.com/track/101" in it.third })
    }

    @Test
    fun `a track the site already has or queued is a skip, not a failure`() = FakeSubsonic().use { fake ->
        windows()
        val app = app(fake)
        val dup = hit(7, link = "https://www.deezer.com/track/dup")
        app.deezerDownload(dup)
        waitUntil("the answer") { app.toast == "Already downloaded or queued" }
        assertEquals(DeezerHit.State.IDLE, app.deezerStates[dup.key], "it can be tried again")
    }

    @Test
    fun `an album is queued as a whole and the toast counts what was new`() = FakeSubsonic().use { fake ->
        windows()
        val app = app(fake)
        val album = hit(201, DeezerHit.Kind.ALBUM)
        app.deezerDownload(album)
        waitUntil("the album to be queued") { app.deezerStates[album.key] == DeezerHit.State.QUEUED }
        assertEquals("Added 8 tracks, 2 already in your library", app.toast)

        val artist = hit(1, DeezerHit.Kind.ARTIST)
        app.deezerDownload(artist)
        waitUntil("the artist to be queued") { app.deezerStates[artist.key] == DeezerHit.State.QUEUED }
    }

    @Test
    fun `pressing again while it is being asked for does not ask twice`() = FakeSubsonic().use { fake ->
        windows()
        val app = app(fake)
        val album = hit(201, DeezerHit.Kind.ALBUM)
        app.deezerDownload(album)
        app.deezerDownload(album)
        waitUntil("queued") { app.deezerStates[album.key] == DeezerHit.State.QUEUED }
        assertEquals(1, fake.deemixCalls.count { it.second == "api/download-album" })
    }

    @Test
    fun `an exhausted quota is shown in the server's words, and the row can be tried again`() = FakeSubsonic().use { fake ->
        windows()
        fake.deemixQuotaExhausted = true
        val app = app(fake)
        val track = hit(101)
        app.deezerDownload(track)
        waitUntil("the refusal") { app.toast == "Daily limit reached (50 tracks)" }
        assertEquals(DeezerHit.State.IDLE, app.deezerStates[track.key])
    }

    @Test
    fun `a site that cannot be reached is said so`() = FakeSubsonic().use { fake ->
        windows()
        val app = app(fake) { deemixUrl = "http://127.0.0.1:1/" }
        app.deezerDownload(hit(101))
        waitUntil("the message") { app.toast == "Deemix plus is not reachable" }
    }

    @Test
    fun `the quota is read again after a download`() = FakeSubsonic().use { fake ->
        windows()
        val app = app(fake)
        app.deezerDownload(hit(101))
        waitUntil("the quota to be read") { app.deemixQuotaText() == "47 of 50 left today" }

        fake.deemixUsageJson = """{"is_admin":false,"user_daily":50,"user_limit":50,"global_daily":10,"global_limit":500}"""
        app.deezerDownload(hit(102))
        waitUntil("the new quota") { app.deemixQuotaText() == "Daily limit reached" }
    }

    // --- previews -------------------------------------------------------------------------------------------------------

    @Test
    fun `a preview is fetched with the site's token, plays, and ends by itself`() = FakeSubsonic().use { fake ->
        windows()
        val app = app(fake)
        val track = hit(101)
        app.togglePreview(track)
        assertEquals(track.key, app.previewKey)
        waitUntil("the preview to play") { app.previewState == AppController.PreviewState.PLAYING }
        assertTrue(fake.deemixCalls.any { it.second == "api/preview" })

        // Three seconds of tone, and then it stops on its own and clears up after itself.
        waitUntil("the preview to end", 15_000) { app.previewState == AppController.PreviewState.STOPPED }
        assertNull(app.previewKey)
    }

    @Test
    fun `pressing a preview again stops it, and another one takes over`() = FakeSubsonic().use { fake ->
        windows()
        val app = app(fake)
        val first = hit(101)
        val second = hit(102)
        app.togglePreview(first)
        waitUntil("playing") { app.previewState == AppController.PreviewState.PLAYING }
        app.togglePreview(first)
        assertEquals(AppController.PreviewState.STOPPED, app.previewState)

        app.togglePreview(first)
        app.togglePreview(second)
        assertEquals(second.key, app.previewKey)
        waitUntil("the second") { app.previewState == AppController.PreviewState.PLAYING }
        app.stopPreview()
    }

    @Test
    fun `a preview pauses the music, and the music taking over stops the preview`() = FakeSubsonic().use { fake ->
        windows()
        val app = app(fake)
        app.player.play(listOf(Song().apply { id = "slow-music"; title = "Long"; artist = "A"; duration = 100 }))
        waitUntil("the music") { app.player.state.value.isPlaying }

        app.togglePreview(hit(101))
        waitUntil("the preview") { app.previewState == AppController.PreviewState.PLAYING }
        waitUntil("the music to give way") { !app.player.state.value.isPlaying }

        app.player.toggle()
        waitUntil("the preview to give way") { app.previewState == AppController.PreviewState.STOPPED }
    }

    @Test
    fun `a preview the site refuses is reported, and leaves no preview playing`() = FakeSubsonic().use { fake ->
        windows()
        val app = app(fake, password = "wrong")
        app.togglePreview(hit(101))
        waitUntil("the message") { app.toast == "Could not play the preview" }
        assertEquals(AppController.PreviewState.STOPPED, app.previewState)
        assertIs<DeemixStatus>(app.deemixStatus)
    }

    @Test
    fun `only a track can be previewed`() = FakeSubsonic().use { fake ->
        windows()
        val app = app(fake)
        app.togglePreview(hit(201, DeezerHit.Kind.ALBUM))
        assertNull(app.previewKey)
    }
}
