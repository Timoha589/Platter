@file:OptIn(ExperimentalTestApi::class)

package com.platter.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.platter.desktop.api.Auth
import com.platter.desktop.api.Credentials
import com.platter.desktop.data.SettingsStore
import com.platter.desktop.ui.PlatterColors
import com.platter.desktop.ui.PlatterTheme
import com.platter.desktop.ui.Shell
import com.platter.desktop.ui.screens.LoginScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renders the real screens against the fake server and writes PNGs to
 * build/screenshots, so the design can be looked at without a display session.
 */
class ScreenshotTest {
    private val outDir = File("build/screenshots").apply { mkdirs() }

    private fun app(fake: FakeSubsonic?, name: String, wave: Boolean = false, configure: com.platter.desktop.data.Settings.() -> Unit = {}): AppController {
        val store = SettingsStore(Files.createTempDirectory("platter-test").resolve("settings.json"))
        if (fake != null) store.update {
            withCredentials(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt"))
            configure()
        }
        return AppController(store, CoroutineScope(SupervisorJob() + Dispatchers.Main), vlcArgs = listOf("--aout=dummy"), deezerCatalogUrl = (fake?.url ?: "http://127.0.0.1:1/") + "deezer/", waveUrl = if (wave) fake?.waveUrl else null)
    }

    /** Lets network and image loads finish: they run on real threads, outside the test's virtual clock. */
    private fun ComposeUiTest.settle() {
        repeat(25) {
            Thread.sleep(120)
            // With the clock stopped (see [stopClock]) a frame is pushed by hand, so loads still land on screen.
            if (!mainClock.autoAdvance) mainClock.advanceTimeByFrame()
            waitForIdle()
        }
    }

    /**
     * The wave card animates for as long as the app is open, so a test clock that advances by itself never finds the
     * screen idle. Stopping it makes `setContent` return; [settle] then steps frames itself.
     */
    private fun ComposeUiTest.stopClock() {
        mainClock.autoAdvance = false
    }

    private fun ComposeUiTest.shoot(name: String) {
        settle()
        val file = File(outDir, "$name.png")
        // An open dropdown is a second root of its own; it is saved beside the page as <name>-popup.png.
        val roots = onAllNodes(isRoot())
        ImageIO.write(roots[0].captureToImage().toAwtImage(), "png", file)
        assertTrue(file.length() > 2_000, "$name rendered something")
        for (i in 1 until roots.fetchSemanticsNodes().size) {
            ImageIO.write(roots[i].captureToImage().toAwtImage(), "png", File(outDir, "$name-popup$i.png"))
        }
    }

    @Test
    fun `wave card, lyrics panel and lyric search`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(1280, 800) {
            val app = app(fake, "wave", wave = true)
            stopClock()
            setContent { PlatterTheme { Box(Modifier.fillMaxSize().background(PlatterColors.Black)) { Shell(app) } } }
            shoot("8-home-wave-idle")

            app.onWaveTapped()
            Thread.sleep(1200)
            shoot("9-home-wave-playing")

            // An album whose songs carry timed lyrics, a few seconds in so a middle line is the sung one.
            val songs = kotlinx.coroutines.runBlocking { app.client!!.album("a2") }.songs!!
            app.player.play(songs, 0)
            app.sidePanel = SidePanel.Lyrics
            Thread.sleep(2500)
            shoot("10-lyrics-synced")

            app.player.play(listOf(songs[0].also { }), 0)
            app.searchQuery = "come together"
            app.navigate(Screen.Search)
            app.sidePanel = null
            shoot("11-search-in-lyrics")
            app.shutdown()
        }
    }

    @Test
    fun `search, artist, liked, genre and settings`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(1280, 800) {
            val app = app(fake, "pages")
            stopClock()
            setContent { PlatterTheme { Box(Modifier.fillMaxSize().background(PlatterColors.Black)) { Shell(app) } } }
            app.commitSearch("Abbey Road")
            app.commitSearch("Radiohead")

            app.navigate(Screen.Search)
            shoot("12-search-recent")

            app.searchQuery = "abbey"
            shoot("13-search-results")

            // Nothing in the fake matches this spelling; the library's own names put it right.
            app.searchQuery = "Abbey Raod"
            shoot("14-search-typo")

            app.navigate(Screen.Artist("ar0"))
            shoot("15-artist")

            app.navigate(Screen.Liked)
            shoot("16-liked")

            app.navigate(Screen.Genre("Jazz"))
            shoot("17-genre")

            app.navigate(Screen.AlbumList("Most played", "frequent"))
            shoot("18-album-list")

            app.navigate(Screen.Settings)
            shoot("19-settings")
            app.shutdown()
        }
    }

    @Test
    fun `home sections, library tabs and the song menu`() = FakeSubsonic().use { fake ->
        // Someone else's playlist, so the sidebar has its "Public playlists" section under the listener's own.
        fake.addPublicPlaylist("p9", "Sunday jazz", owner = "anna")
        runDesktopComposeUiTest(1280, 800) {
            val app = app(fake, "home")
            stopClock()
            setContent { PlatterTheme { Box(Modifier.fillMaxSize().background(PlatterColors.Black)) { Shell(app) } } }
            app.togglePin("p1")
            shoot("20-home-default")

            app.saveHomeLayout(HomeSection.entries, emptySet())
            shoot("21-home-everything")

            app.navigate(Screen.Library)
            // The clock is stopped, so a screen reaches the tree only when a frame is pushed.
            settle()
            onNodeWithText("Genres").performClick()
            shoot("22-library-genres")
            // The sidebar has a "Playlists" heading too; the chip is the second.
            onAllNodesWithText("Playlists")[1].performClick()
            shoot("23-library-playlists")

            app.navigate(Screen.Playlist("p1"))
            settle()
            shoot("24-playlist")
            onAllNodesWithContentDescription("More")[1].performClick()
            shoot("25-song-menu")
            app.shutdown()
        }
    }

    @Test
    fun `dialogs and the toast`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(1280, 800) {
            val app = app(fake, "dialogs")
            stopClock()
            setContent { PlatterTheme { Box(Modifier.fillMaxSize().background(PlatterColors.Black)) { Shell(app) } } }
            val songs = kotlinx.coroutines.runBlocking { app.client!!.album("a2") }.songs!!
            app.navigate(Screen.Album("a2"))
            app.player.play(songs, 1)
            Thread.sleep(800)

            app.dialog = AppDialog.AddToPlaylist(listOf(songs[1]))
            shoot("26-dialog-add-to-playlist")
            app.dialog = AppDialog.NewPlaylist(emptyList())
            shoot("27-dialog-new-playlist")
            app.dialog = AppDialog.TrackInfo(songs[1])
            shoot("29-dialog-track-info")
            app.dialog = AppDialog.ArrangeHome
            shoot("30-dialog-arrange-home")
            app.dialog = null
            app.showToast("Added to Late night")
            shoot("31-toast")
            app.shutdown()
        }
    }

    @Test
    fun `update dialog and the version in settings`() = FakeSubsonic().use { fake ->
        val github = okhttp3.mockwebserver.MockWebServer()
        val body = ByteArray(400_000)
        github.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse =
                if (request.path!!.startsWith("/releases")) okhttp3.mockwebserver.MockResponse().setBody(
                    """[{"tag_name":"v1.1.0","body":"- Platter updates itself from GitHub
- Remembers the window, the page and the queue
- Eight languages","draft":false,"prerelease":false,
                        "assets":[{"name":"Platter-1.1.0.msi","browser_download_url":"${github.url("/Platter-1.1.0.msi")}","size":${body.size}}]}]"""
                ) else okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(body)).throttleBody(40_000, 1, java.util.concurrent.TimeUnit.SECONDS)
        }
        github.start()
        try {
            runDesktopComposeUiTest(1280, 800) {
                val store = SettingsStore(Files.createTempDirectory("platter-test").resolve("settings.json"))
                store.update { withCredentials(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt")) }
                val updater = com.platter.desktop.update.Updater(
                    Files.createTempDirectory("platter-update"), "1.0.0", github.url("/releases").toString(), launch = { _, _ -> },
                )
                val app = AppController(store, CoroutineScope(SupervisorJob() + Dispatchers.Main), vlcArgs = listOf("--aout=dummy"), deezerCatalogUrl = fake.url + "deezer/", updater = updater)
                stopClock()
                setContent { PlatterTheme { Box(Modifier.fillMaxSize().background(PlatterColors.Black)) { Shell(app) } } }
                app.navigate(Screen.Settings)
                // The look at start has found the release and put its dialog up.
                shoot("update-1-offer")
                assertEquals(AppDialog.Update, app.dialog)

                app.installUpdate()
                shoot("update-2-downloading")
                app.shutdown()
            }
        } finally {
            github.shutdown()
        }
    }

    @Test
    fun `podcasts, radio, the resume offer and the player on an episode and a station`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(1280, 800) {
            // Something another device left on the server, to be offered on start.
            kotlinx.coroutines.runBlocking {
                com.platter.desktop.api.SubsonicClient(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt"))
                    .savePlayQueue(listOf("s2-1", "s2-2", "s2-3"), 1, 83_000)
            }
            val app = app(fake, "stage2") { syncQueue = true; syncSeconds = 60; localAddress = fake.url }
            stopClock()
            setContent { PlatterTheme { Box(Modifier.fillMaxSize().background(PlatterColors.Black)) { Shell(app) } } }
            settle()
            shoot("32-resume-offer")

            app.dismissResume()
            app.navigate(Screen.Library)
            settle()
            shoot("33-library")

            app.navigate(Screen.Podcast("c1"))
            shoot("35-podcast-page")

            app.dialog = AppDialog.AddPodcast
            shoot("36-dialog-add-podcast")
            app.dialog = AppDialog.EditStation(null)
            shoot("37-dialog-add-station")
            app.dialog = null

            val channel = kotlinx.coroutines.runBlocking { app.client!!.podcastChannel("c1") }
            app.playEpisodes(channel.episodes!!, 0, channel)
            Thread.sleep(1_200)
            shoot("38-player-on-an-episode")

            app.playStation(kotlinx.coroutines.runBlocking { app.client!!.radioStations() }.first())
            Thread.sleep(1_200)
            shoot("39-player-on-a-station")

            app.navigate(Screen.Settings)
            settle()
            shoot("40-settings")
            // A dropdown over a settings card: the menu is a step above the card, so it does not melt into it.
            onAllNodesWithText("Original")[0].performClick()
            shoot("40b-settings-dropdown")
            app.shutdown()
        }
    }

    @Test
    fun `downloads, Deezer in search and on its pages, and the equalizer`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(1280, 800) {
            val folder = Files.createTempDirectory("platter-shots-dl").toString()
            val app = app(fake, "stage3") {
                downloadFolder = folder
                deemixUrl = fake.url
                sealedDeemixPassword = com.platter.desktop.data.Secrets.seal("deemixpw")
            }
            stopClock()
            setContent { PlatterTheme { Box(Modifier.fillMaxSize().background(PlatterColors.Black)) { Shell(app) } } }

            val songs = kotlinx.coroutines.runBlocking { app.client!!.album("a2") }.songs!!
            // One song of another album too: it stands alone in the list, with no album header.
            val lone = kotlinx.coroutines.runBlocking { app.client!!.album("a1") }.songs!!.take(1)
            app.download(songs + lone)
            val end = System.currentTimeMillis() + 15_000
            while ((songs + lone).any { !app.isDownloaded(it.id) } && System.currentTimeMillis() < end) Thread.sleep(100)
            app.navigate(Screen.Album("a2"))
            shoot("41-album-with-downloads")

            app.navigate(Screen.Downloads)
            shoot("42-downloads")

            // Something still on its way, so the progress rows are drawn.
            app.download((1..3).map { n -> songs.first().let { s -> com.platter.desktop.api.Song().apply { id = "slow$n"; title = "Slow song $n"; artist = s.artist; album = "Slow album"; track = n; suffix = "wav" } } })
            Thread.sleep(1_500)
            shoot("43-downloads-in-progress")
            app.downloader.cancelAll()

            app.searchQuery = "beatles"
            app.navigate(Screen.Search)
            Thread.sleep(1_500)
            shoot("44-search-with-deezer")

            app.navigate(Screen.Deezer(com.platter.desktop.deemix.DeezerHit.Kind.ARTIST, 1))
            shoot("45-deezer-artist")
            app.navigate(Screen.Deezer(com.platter.desktop.deemix.DeezerHit.Kind.ALBUM, 201))
            shoot("46-deezer-album")
            app.dialog = AppDialog.ConfirmDeezer(com.platter.desktop.deemix.DeezerHit(com.platter.desktop.deemix.DeezerHit.Kind.ARTIST, 1, "Beatles", "", "", null, null, false))
            shoot("47-dialog-download-artist")

            app.dialog = AppDialog.Equalizer
            app.chooseEqPreset("Rock")
            shoot("48-equalizer")
            app.dialog = null
            app.shutdown()
        }
    }

    @Test
    fun `login, home, album, queue`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(1280, 800) {
            val signedOut = app(null, "out")
            setContent { PlatterTheme { Box(Modifier.fillMaxSize().background(PlatterColors.Black)) { LoginScreen(signedOut) } } }
            shoot("1-login")
        }

        runDesktopComposeUiTest(1280, 800) {
            val signedOut = app(null, "out")
            setContent { PlatterTheme { Box(Modifier.fillMaxSize().background(PlatterColors.Black)) { LoginScreen(signedOut) } } }
            settle()
            onNodeWithTag("premium-offer").performClick()
            shoot("1-login-premium")
        }

        // Signed out with servers kept: the list to pick from.
        runDesktopComposeUiTest(1280, 800) {
            val kept = app(fake, "kept") {
                servers = listOf(
                    com.platter.desktop.data.SavedServer("a", "Timoha Premium", "https://music.timoha.top", "tim", null, null),
                    com.platter.desktop.data.SavedServer("b", null, "http://192.168.1.5:4533", "anna", null, null),
                )
                withCredentials(null)
            }
            setContent { PlatterTheme { Box(Modifier.fillMaxSize().background(PlatterColors.Black)) { LoginScreen(kept) } } }
            shoot("1-login-servers")
        }

        runDesktopComposeUiTest(1280, 800) {
            val app = app(fake, "in")
            setContent { PlatterTheme { Box(Modifier.fillMaxSize().background(PlatterColors.Black)) { Shell(app) } } }
            shoot("2-home")

            app.navigate(Screen.Album("a2"))
            shoot("3-album")

            app.navigate(Screen.Library)
            shoot("4-library")

            app.navigate(Screen.Artist("ar0"))
            shoot("5-artist")

            app.searchQuery = "abbey"
            app.navigate(Screen.Search)
            shoot("6-search")

            app.navigate(Screen.Playlist("p1"))
            app.sidePanel = SidePanel.Queue
            val songs = kotlinx.coroutines.runBlocking { app.client!!.album("a2") }.songs!!
            app.player.play(songs, 1)
            Thread.sleep(1500)
            shoot("7-playlist-queue-playing")
            app.shutdown()
        }
    }
    @Test
    fun `lyrics pour into place and wait in dots`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(1280, 800) {
            val app = app(fake, "lyrics-motion")
            stopClock()
            setContent { PlatterTheme { Box(Modifier.fillMaxSize().background(PlatterColors.Black)) { Shell(app) } } }
            // The fake's tracks are four seconds long, so everything here has to happen quickly in real time.
            fun frames(n: Int) = repeat(n) { mainClock.advanceTimeByFrame(); waitForIdle() }
            fun capture(name: String) = ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", File(outDir, "$name.png"))
            val songs = kotlinx.coroutines.runBlocking { app.client!!.album("a2") }.songs!!
            app.player.play(songs, 0)
            app.sidePanel = SidePanel.Lyrics
            Thread.sleep(500)
            frames(40)

            // A step to line 2: the column has jumped and each line is walking back to where it was.
            app.player.seekMs(1600)
            Thread.sleep(250)
            // Frames a tenth of a second apart: the lines under the sung one set off one after another.
            for (step in 1..5) {
                frames(8)
                capture("18-lyrics-pouring-$step")
            }

            // The second song opens with nine seconds of nothing.
            app.player.play(songs, 1)
            Thread.sleep(700)
            frames(40)
            capture("18-lyrics-dots")
            app.shutdown()
        }
    }

    @Test
    fun `an upright window puts the lyrics and the queue under the page`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(900, 1200) {
            val app = app(fake, "portrait")
            stopClock()
            setContent { PlatterTheme { Box(Modifier.fillMaxSize().background(PlatterColors.Black)) { Shell(app) } } }
            val songs = kotlinx.coroutines.runBlocking { app.client!!.album("a2") }.songs!!
            app.player.play(songs, 0)
            app.sidePanel = SidePanel.Lyrics
            Thread.sleep(2500)
            shoot("12-portrait-lyrics")
            app.sidePanel = SidePanel.Queue
            shoot("13-portrait-queue")
            app.sidePanel = null
            shoot("14-portrait-closed")
            app.shutdown()
        }
    }

    @Test
    fun `the full-screen player, wide and upright, and a track change half way`() = FakeSubsonic().use { fake ->
        for ((w, h, name) in listOf(Triple(1280, 800, "wide"), Triple(900, 1200, "upright"))) {
            runDesktopComposeUiTest(w, h) {
                val app = app(fake, "full-$name")
                stopClock()
                setContent { PlatterTheme { Box(Modifier.fillMaxSize().background(PlatterColors.Black)) { Shell(app) } } }
                val songs = kotlinx.coroutines.runBlocking { app.client!!.album("a2") }.songs!!
                app.player.play(songs, 1)
                Thread.sleep(800)
                app.player.toggle() // the fake's tracks are 4 s long; held still, the picture is not a track change
                Thread.sleep(300)
                app.fullPlayer = true
                shoot("15-full-$name")

                // The words where the cover was.
                app.fullPlayerLyrics = true
                repeat(18) { mainClock.advanceTimeByFrame(); waitForIdle() }
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "17-full-$name-lyrics-moving.png"))
                shoot("17-full-$name-lyrics")
                // Dragged: an upright window's words made taller, the cover above them smaller.
                if (name == "upright") {
                    app.resizeWords(800)
                    shoot("17-full-upright-lyrics-taller")
                    app.resizeWords(1)
                    shoot("17-full-upright-lyrics-shorter")
                    app.resizeWords(0)
                }
                app.fullPlayerLyrics = false
                shoot("17-full-$name-lyrics-closed")

                // The next track, caught while the cover is still on its way.
                app.player.next()
                repeat(14) {
                    Thread.sleep(20)
                    mainClock.advanceTimeByFrame()
                    waitForIdle()
                }
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "16-full-$name-turning.png"))

                app.shutdown()
            }
        }
    }
}
