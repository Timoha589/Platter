@file:OptIn(ExperimentalTestApi::class)

package com.platter.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.platter.desktop.api.Auth
import com.platter.desktop.api.Credentials
import com.platter.desktop.api.Song
import com.platter.desktop.data.SettingsStore
import com.platter.desktop.ui.PlatterColors
import com.platter.desktop.ui.PlatterTheme
import com.platter.desktop.ui.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real window, clicked and typed into: what a listener does, rather than what the controller can do. The test
 * clock is stopped (the wave and spinners animate for ever), so [step] pushes frames by hand while the network and
 * the image loads, which run on real threads, catch up.
 */
class InteractionTest {
    private fun app(fake: FakeSubsonic, configure: com.platter.desktop.data.Settings.() -> Unit = {}): AppController {
        val store = SettingsStore(Files.createTempDirectory("platter-ui-test").resolve("settings.json"))
        store.update {
            withCredentials(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt"))
            configure()
        }
        return AppController(store, CoroutineScope(SupervisorJob() + Dispatchers.Main), vlcArgs = listOf("--aout=dummy"))
    }

    private fun ComposeUiTest.step(frames: Int = 10) {
        repeat(frames) {
            Thread.sleep(60)
            mainClock.advanceTimeByFrame()
            waitForIdle()
        }
    }

    private fun ComposeUiTest.open(app: AppController) {
        mainClock.autoAdvance = false
        setContent { PlatterTheme { Box(Modifier.fillMaxSize().background(PlatterColors.Black)) { Shell(app) } } }
        step()
    }

    @Test
    fun `typing a search, pressing Enter, and opening the top result`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(1280, 800) {
            val app = app(fake)
            open(app)

            onNode(hasSetTextAction()).performTextInput("abbey")
            step(16)
            onNodeWithText("Top result").assertIsDisplayed()
            assertEquals(Screen.Search, app.screen)
            assertTrue(app.recentSearches.isEmpty(), "typing alone is not worth remembering")

            onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
            step()
            assertEquals(listOf("abbey"), app.recentSearches)

            // The card says "Album"; pressing it opens the album.
            onNodeWithText("Album").performClick()
            step()
            assertEquals(Screen.Album("a2"), app.screen)
            app.shutdown()
        }
    }

    @Test
    fun `the wheel over the volume control changes the volume`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(1280, 800) {
            val app = app(fake)
            open(app)
            app.setVolume(50)
            step()

            // A turn of the wheel towards the listener (down) is quieter, away from them louder; a notch is five percent.
            onNodeWithContentDescription("Mute").performMouseInput { moveTo(center); scroll(1f) }
            step()
            assertEquals(45, app.player.state.value.volume)
            onNodeWithContentDescription("Mute").performMouseInput { moveTo(center); scroll(-2f) }
            step()
            assertEquals(55, app.player.state.value.volume)

            // It stops at the ends.
            repeat(30) { onNodeWithContentDescription("Mute").performMouseInput { moveTo(center); scroll(-1f) } }
            step()
            assertEquals(100, app.player.state.value.volume)
            app.shutdown()
        }
    }

    @Test
    fun `the side buttons of the mouse go back and forward`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(1280, 800) {
            val app = app(fake)
            open(app)
            app.navigate(Screen.Album("a2"))
            step()
            app.navigate(Screen.Artist("ar1"))
            step()
            assertEquals(Screen.Artist("ar1"), app.screen)

            onRoot().performMouseInput { moveTo(center); press(MouseButton(3)); release(MouseButton(3)) }
            step()
            assertEquals(Screen.Album("a2"), app.screen)

            onRoot().performMouseInput { moveTo(center); press(MouseButton(4)); release(MouseButton(4)) }
            step()
            assertEquals(Screen.Artist("ar1"), app.screen)
            app.shutdown()
        }
    }

    @Test
    fun `an album goes into a playlist from its menu`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(1280, 800) {
            val app = app(fake)
            open(app)
            app.navigate(Screen.Album("a2"))
            step()

            // The first "More" is the page's own; its menu is what can be done with the whole album.
            onAllNodesWithContentDescription("More")[0].performClick()
            step()
            onNodeWithText("Add to playlist…").performClick()
            step()
            // "Focus" is in the sidebar as well; the dialog is the last of the two.
            onAllNodesWithText("Focus").onLast().performClick()
            step()

            assertEquals(listOf("s2-1", "s2-2", "s2-1", "s2-2", "s2-3", "s2-4", "s2-5"), fake.playlistSongs("p2"))
            assertNull(app.dialog, "the dialog closes once a playlist is chosen")
            app.shutdown()
        }
    }

    @Test
    fun `a song's menu rates it and a new playlist can be started from the dialog`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(1280, 800) {
            val app = app(fake)
            open(app)
            app.navigate(Screen.Album("a2"))
            step()

            onAllNodesWithContentDescription("More")[1].performClick() // the first song's dots
            step()
            onNodeWithText("Rate…").performClick()
            step()
            onNodeWithContentDescription("4 stars").performClick()
            onNodeWithText("Save").performClick()
            step()
            waitUntil("the rating to reach the server") { fake.requestsTo("setRating").any { it.queryParameter("rating") == "4" } }
            assertEquals("s2-1", fake.requestsTo("setRating").single().queryParameter("id"))

            onAllNodesWithContentDescription("More")[1].performClick()
            step()
            onNodeWithText("Add to playlist…").performClick()
            step()
            onNodeWithText("New playlist").performClick()
            step()
            // The search field is still on the page behind; the dialog's is the last.
            onAllNodes(hasSetTextAction()).onLast().performTextInput("Road trip")
            onNodeWithText("Create").performClick()
            step()
            waitUntil("the playlist to be made") { "Road trip" in fake.playlistNames() }
            assertEquals(listOf("s2-1"), fake.playlistSongs("p3"))
            app.shutdown()
        }
    }

    @Test
    fun `settings end with a link to the repository`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(1280, 2600) {
            val app = app(fake)
            open(app)
            app.navigate(Screen.Settings)
            step()
            onNodeWithText("Source code").assertIsDisplayed()
            onNodeWithText("https://github.com/Timoha589/Platter").assertIsDisplayed()
            onNodeWithText("Open on GitHub").assertIsDisplayed()
            app.shutdown()
        }
    }

    @Test
    fun `settings change what is streamed and what is reported`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(1280, 1500) {
            val app = app(fake)
            open(app)
            app.navigate(Screen.Settings)
            step()

            // Each choice is a closed list showing the value in use: press it, then press the one wanted. Stream quality
            // and the format are the first two that read "Original" (once the first has changed, the format is the first
            // left); downloads have a quality of their own further down.
            onAllNodesWithText("Original")[0].performClick()
            step()
            onNodeWithText("128 kbps").performClick()
            step()
            onAllNodesWithText("Original")[0].performClick()
            step()
            onNodeWithText("MP3").performClick()
            step()
            onNodeWithText("Track").performClick() // volume levelling, which now starts on Track
            step()
            onNodeWithText("Album").performClick()
            step()
            assertEquals(128, app.maxBitrate)
            assertEquals("mp3", app.streamFormat)
            assertEquals("album", app.replayGain)

            assertTrue(app.scrobbling)
            // The equalizer button is the first switch on the page, scrobbling the second.
            onAllNodes(isToggleable())[1].performClick()
            step()
            assertFalse(app.scrobbling)
            app.shutdown()
        }
    }

    @Test
    fun `home can be rearranged from its own button`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(1280, 800) {
            val app = app(fake)
            open(app)
            step()
            assertTrue(HomeSection.RecentlyAdded in app.homeSections)

            // The button is at the foot of a long page, and scrolling to it never settles with the clock stopped:
            // what the button does is open the dialog, so that is what is done here.
            app.dialog = AppDialog.ArrangeHome
            step()
            // Switch "Recently added" off and "Discovery" on, then save.
            onAllNodesWithContentDescription("Shown").let { shown ->
                // Visible sections come in order: Playlists, Made for you, Liked songs, Last played, New releases, Recently added.
                shown[5].performClick()
            }
            onAllNodesWithContentDescription("Hidden")[0].performClick() // Discovery, the first hidden one
            step()
            onNodeWithText("Save").performClick()
            step()

            assertFalse(HomeSection.RecentlyAdded in app.homeSections)
            assertTrue(HomeSection.Discovery in app.homeSections)
            assertNull(app.dialog)
            app.shutdown()
        }
    }

    @Test
    fun `a podcast episode is played from its page and one not fetched yet is asked for`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(1280, 800) {
            val app = app(fake)
            open(app)
            app.navigate(Screen.Podcast("c1"))
            step(14)

            onNodeWithText("Download").performClick()
            step()
            waitUntil("the download to be asked for") { fake.requestsTo("downloadPodcastEpisode").isNotEmpty() }
            assertEquals("e3", fake.requestsTo("downloadPodcastEpisode").single().queryParameter("id"))

            // The first "Play" is the page's own; the next is the first episode's.
            onAllNodesWithContentDescription("Play")[1].performClick()
            step()
            waitUntil("the episode to play") { app.player.state.value.current?.id == "s0-1" }
            assertEquals(Song.KIND_PODCAST, app.player.state.value.current?.kind)
            app.shutdown()
        }
    }

    @Test
    fun `the offer to resume is taken from the window`() = FakeSubsonic().use { fake ->
        kotlinx.coroutines.runBlocking {
            com.platter.desktop.api.SubsonicClient(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt"))
                .savePlayQueue(listOf("s1-1", "s1-2", "s1-3"), 1, 1_000)
        }
        runDesktopComposeUiTest(1280, 800) {
            val app = app(fake) { syncQueue = true }
            open(app)
            waitUntil("the offer") { app.resumeOffer != null }
            step()
            onNodeWithText("Pick up where you left off").assertIsDisplayed()
            onNodeWithText("Resume").performClick()
            step()
            waitUntil("the queue to be picked up") { app.player.state.value.current?.id == "s1-2" }
            assertEquals(3, app.player.state.value.queue.songs.size)
            assertNull(app.resumeOffer)
            app.shutdown()
        }
    }

    @Test
    fun `the offer can be dismissed, and syncing is switched on from settings`() = FakeSubsonic().use { fake ->
        kotlinx.coroutines.runBlocking {
            com.platter.desktop.api.SubsonicClient(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt"))
                .savePlayQueue(listOf("s1-1"), 0, 0)
        }
        runDesktopComposeUiTest(1280, 1500) {
            val app = app(fake) { syncQueue = true }
            open(app)
            waitUntil("the offer") { app.resumeOffer != null }
            step()
            onNodeWithContentDescription("Dismiss").performClick()
            step()
            assertNull(app.resumeOffer)
            assertTrue(app.player.state.value.queue.songs.isEmpty(), "dismissing is not accepting")

            app.navigate(Screen.Settings)
            step()
            assertTrue(app.syncQueue)
            // The equalizer button is the first switch, scrobbling the second, syncing the third.
            onAllNodes(isToggleable())[2].performClick()
            step()
            assertFalse(app.syncQueue)
            app.shutdown()
        }
    }

    private fun deemixApp(fake: FakeSubsonic, configure: com.platter.desktop.data.Settings.() -> Unit = {}): AppController {
        val folder = Files.createTempDirectory("platter-ui-dl").toString()
        val store = SettingsStore(Files.createTempDirectory("platter-ui-test").resolve("settings.json"))
        store.update {
            withCredentials(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt"))
            downloadFolder = folder
            deemixUrl = fake.url
            sealedDeemixPassword = com.platter.desktop.data.Secrets.seal("deemixpw")
            configure()
        }
        return AppController(store, CoroutineScope(SupervisorJob() + Dispatchers.Main), vlcArgs = listOf("--aout=dummy"), deezerCatalogUrl = fake.url + "deezer/")
    }

    @Test
    fun `an album is downloaded from its menu, marked, and removed again from the same menu`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(1280, 800) {
            val app = deemixApp(fake)
            open(app)
            app.navigate(Screen.Album("a2"))
            step(14)

            onAllNodesWithContentDescription("More")[0].performClick()
            step()
            onNodeWithText("Download album").performClick()
            step()
            com.platter.desktop.waitUntil("the album to be saved") { (0..4).all { app.isDownloaded("s2-${it + 1}") } }
            step(14)
            assertEquals(5, onAllNodesWithContentDescription("Downloaded").fetchSemanticsNodes().size, "every row says it is saved")

            onAllNodesWithContentDescription("More")[0].performClick()
            step()
            onNodeWithText("Remove downloads").performClick()
            step()
            assertTrue(app.downloadedSongs().isEmpty())
            app.shutdown()
        }
    }

    @Test
    fun `the downloads screen lists what is saved and plays it`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(1280, 800) {
            val app = deemixApp(fake)
            open(app)
            val songs = kotlinx.coroutines.runBlocking { app.client!!.album("a2") }.songs!!.take(2)
            app.download(songs)
            com.platter.desktop.waitUntil("both to be saved") { songs.all { app.isDownloaded(it.id) } }

            onNodeWithText("Downloads").performClick() // the sidebar
            step(14)
            assertEquals(Screen.Downloads, app.screen)
            onNodeWithText("Track 1 of Abbey Road").assertIsDisplayed()
            onNodeWithText("Track 2 of Abbey Road").assertIsDisplayed()

            onAllNodesWithContentDescription("Play")[0].performClick()
            step()
            com.platter.desktop.waitUntil("playback from the disk") { app.player.state.value.isPlaying }
            assertTrue(fake.requestsTo("stream").isEmpty())

            onNodeWithText("Kept for likes").performClick()
            step()
            // The player bar still names the song that plays; the list's rows are the ones marked as downloaded.
            assertTrue(onAllNodesWithContentDescription("Downloaded").fetchSemanticsNodes().isEmpty(), "the filter hides what was not kept for likes")
            app.shutdown()
        }
    }

    @Test
    fun `Deezer in search - preview a track, download one, and open an album to download it after being asked`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(1280, 1800) {
            val app = deemixApp(fake)
            open(app)

            onNode(hasSetTextAction()).performTextInput("beatles")
            step(20)
            onNodeWithText("Get from Deezer").assertIsDisplayed()

            // The first "Preview" is the first row's: Come Together, which the library already has.
            onAllNodesWithContentDescription("Preview")[0].performClick()
            step()
            assertEquals("TRACK:101", app.previewKey)
            com.platter.desktop.waitUntil("the preview to play") { app.previewState == AppController.PreviewState.PLAYING }
            onAllNodesWithContentDescription("Stop the preview")[0].performClick()
            step()
            assertNull(app.previewKey)

            // Come Together is "In your library"; of the arrows the first is Here Comes the Sun, the second Octopus's Garden.
            onNodeWithText("In your library").assertIsDisplayed()
            onAllNodesWithContentDescription("Download")[1].performClick()
            step()
            com.platter.desktop.waitUntil("the track to be queued") { app.deezerStates["TRACK:103"] == com.platter.desktop.deemix.DeezerHit.State.QUEUED }

            // An album asks first.
            onNodeWithText("Abbey Road").performClick() // the album row opens its page
            step(14)
            assertEquals(Screen.Deezer(com.platter.desktop.deemix.DeezerHit.Kind.ALBUM, 201), app.screen)
            onNodeWithText("Download album").performClick()
            step()
            onNodeWithText("Download “Abbey Road”?").assertIsDisplayed()
            onAllNodesWithText("Download").onLast().performClick()
            step()
            com.platter.desktop.waitUntil("the album to be queued") { app.deezerStates["ALBUM:201"] == com.platter.desktop.deemix.DeezerHit.State.QUEUED }
            assertEquals("Added 8 tracks, 2 already in your library", app.toast)
            app.shutdown()
        }
    }

    @Test
    fun `home sections are dragged by their handle and the order is kept on save`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(1280, 1000) {
            val app = app(fake)
            open(app)
            val before = app.homeOrder
            app.dialog = AppDialog.ArrangeHome
            step()

            // The first row goes two places down: the pointer takes it by the handle, moves, lets go.
            val rowPx = 40f * density.density
            onAllNodesWithContentDescription("Drag to reorder", useUnmergedTree = true)[0].performMouseInput {
                // The gesture runs on the test's own clock, which is stopped: a frame after each event lets it see the event.
                moveTo(center)
                mainClock.advanceTimeByFrame()
                press()
                mainClock.advanceTimeByFrame()
                repeat(9) { moveBy(Offset(0f, rowPx * 2.1f / 9)); mainClock.advanceTimeByFrame() }
                release()
                mainClock.advanceTimeByFrame()
            }
            step()
            assertEquals(before, app.homeOrder, "nothing is kept until Save")
            onNodeWithText("Save").performClick()
            step()

            val expected = before.toMutableList().apply { add(2, removeAt(0)) }
            assertEquals(expected, app.homeOrder)
            app.shutdown()
        }
    }

    @Test
    fun `a song's menu opens where it was asked for, not at the left edge of its row`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(1280, 800) {
            val app = app(fake)
            open(app)
            app.navigate(Screen.Playlist("p1"))
            step(20)

            fun menuLeft(): Float = onNodeWithText("Play radio").fetchSemanticsNode().boundsInWindow.left

            // A right-click well along the row: the menu starts at the pointer.
            val row = onAllNodesWithText("Blue Train").onFirst()
            val bounds = row.fetchSemanticsNode().boundsInWindow
            val clickX = 900f
            val clickY = bounds.center.y
            onRoot().performMouseInput { rightClick(Offset(clickX, clickY)) }
            step()
            val atClick = menuLeft()
            assertTrue(kotlin.math.abs(atClick - clickX) < 40f, "menu at x=$atClick for a click at x=$clickX")
            onNodeWithText("Play radio").performKeyInput { pressKey(Key.Escape) }
            app.dialog = null
            step()
        }
    }

    @Test
    fun `the equalizer button is in the player bar only once settings ask for it`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(1280, 800) {
            val app = deemixApp(fake)
            open(app)
            onAllNodesWithContentDescription("Equalizer").assertCountEquals(0)

            app.changeEqualizerButton(true)
            step()
            onNodeWithContentDescription("Equalizer").assertExists()

            app.changeEqualizerButton(false)
            step()
            onAllNodesWithContentDescription("Equalizer").assertCountEquals(0)
            app.shutdown()
        }
    }

    @Test
    fun `the equalizer is opened from the player bar, shaped by a preset, reset and closed`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(1280, 800) {
            val app = deemixApp(fake) { equalizerButton = true }
            open(app)
            onNodeWithContentDescription("Equalizer").performClick()
            step()
            assertEquals(AppDialog.Equalizer, app.dialog)

            onNodeWithText("Rock").performClick()
            step()
            assertTrue(app.equalizer.enabled)
            assertEquals(com.platter.desktop.player.EqPresets.all.getValue("Rock"), app.equalizer.bands)

            onNodeWithText("Reset").performClick()
            step()
            assertTrue(app.equalizer.gains().all { it == 0f })
            assertTrue(app.equalizer.enabled, "reset levels the bands, it does not switch the equalizer off")

            onNode(isToggleable()).performClick()
            step()
            assertFalse(app.equalizer.enabled)

            onNodeWithText("Done").performClick()
            step()
            assertNull(app.dialog)
            app.shutdown()
        }
    }

    @Test
    fun `the sidebar and the side panel are dragged to a new width, kept, and put back by a double press`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(1280, 800) {
            val store = SettingsStore(Files.createTempDirectory("platter-ui-test").resolve("settings.json"))
            store.update { withCredentials(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt")) }
            val app = AppController(store, CoroutineScope(SupervisorJob() + Dispatchers.Main), vlcArgs = listOf("--aout=dummy"))
            open(app)
            assertEquals(SIDEBAR_DEFAULT, app.sidebarWidth)

            // Dragging right widens the sidebar; the pointer has to get past the touch slop first, so it moves a good way.
            onNodeWithTag("resize-sidebar").performMouseInput { moveTo(center); press(); moveBy(Offset(100f, 0f)); release() }
            step()
            assertTrue(app.sidebarWidth > SIDEBAR_DEFAULT + 40, "dragged right: ${app.sidebarWidth}")
            assertEquals(app.sidebarWidth, store.load().sidebarWidth, "the width is saved when the drag ends")

            // Far past the limit, the width stops at it.
            onNodeWithTag("resize-sidebar").performMouseInput { moveTo(center); press(); moveBy(Offset(800f, 0f)); release() }
            step()
            assertEquals(SIDEBAR_RANGE.last, app.sidebarWidth)

            // The right panel has a handle only while it is open, and dragging left widens it.
            app.sidePanel = SidePanel.Queue
            step()
            mainClock.advanceTimeBy(500) // the panel slides in; its handle is at rest only once it has
            waitForIdle()
            onNodeWithTag("resize-panel").performMouseInput { moveTo(center); press(); moveBy(Offset(-60f, 0f)); release() }
            step()
            assertTrue(app.panelWidth > PANEL_DEFAULT + 20, "dragged left: ${app.panelWidth}")

            onNodeWithTag("resize-panel").performMouseInput { doubleClick() }
            step()
            assertEquals(PANEL_DEFAULT, app.panelWidth)
            app.shutdown()
        }
    }

    @Test
    fun `the panel under the page of an upright window is dragged taller, kept, and put back by a double press`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(900, 1200) {
            val store = SettingsStore(Files.createTempDirectory("platter-ui-test").resolve("settings.json"))
            store.update { withCredentials(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt")) }
            val app = AppController(store, CoroutineScope(SupervisorJob() + Dispatchers.Main), vlcArgs = listOf("--aout=dummy"))
            open(app)
            assertEquals(PANEL_HEIGHT_DEFAULT, app.panelHeight)

            app.sidePanel = SidePanel.Queue
            step()
            mainClock.advanceTimeBy(500)
            waitForIdle()
            // Dragging up makes it taller.
            onNodeWithTag("resize-panel").performMouseInput { moveTo(center); press(); moveBy(Offset(0f, -80f)); release() }
            step()
            assertTrue(app.panelHeight > PANEL_HEIGHT_DEFAULT + 30, "dragged up: ${app.panelHeight}")
            assertEquals(app.panelHeight, store.load().panelHeight, "the height is saved when the drag ends")

            // Far past the limit, it stops at it.
            onNodeWithTag("resize-panel").performMouseInput { moveTo(center); press(); moveBy(Offset(0f, -2000f)); release() }
            step()
            assertEquals(PANEL_HEIGHT_RANGE.last, app.panelHeight)

            onNodeWithTag("resize-panel").performMouseInput { doubleClick() }
            step()
            assertEquals(PANEL_HEIGHT_DEFAULT, app.panelHeight)
            app.shutdown()
        }
    }

    @Test
    fun `the words in the full-screen player of an upright window are dragged taller, kept, and put back by a double press`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(900, 1200) {
            val store = SettingsStore(Files.createTempDirectory("platter-ui-test").resolve("settings.json"))
            store.update { withCredentials(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt")) }
            val app = AppController(store, CoroutineScope(SupervisorJob() + Dispatchers.Main), vlcArgs = listOf("--aout=dummy"))
            open(app)
            val songs = kotlinx.coroutines.runBlocking { app.client!!.album("a2") }.songs!!
            app.player.play(songs, 0)
            Thread.sleep(500)
            app.fullPlayer = true
            app.fullPlayerLyrics = true
            step()
            mainClock.advanceTimeBy(900) // the words come in, and only then has the handle anything to hold
            waitForIdle()
            assertEquals(0, app.wordsHeight, "the window's own split to begin with")

            // Dragging up makes the words taller (the cover above them gives way).
            onNodeWithTag("resize-words").performMouseInput { moveTo(center); press(); moveBy(Offset(0f, -80f)); release() }
            step()
            val dragged = app.wordsHeight
            assertTrue(dragged > 0, "dragged up: $dragged")
            assertEquals(dragged, store.load().wordsHeight, "the height is saved when the drag ends")

            // Far past the limit, it stops short of squeezing the cover away.
            onNodeWithTag("resize-words").performMouseInput { moveTo(center); press(); moveBy(Offset(0f, -2000f)); release() }
            step()
            assertTrue(app.wordsHeight > dragged && app.wordsHeight < 1200, "stopped at the limit: ${app.wordsHeight}")

            // Dragged down it goes the other way: shorter words, a larger cover, down to the few lines the words keep.
            onNodeWithTag("resize-words").performMouseInput { moveTo(center); press(); moveBy(Offset(0f, 2000f)); release() }
            step()
            assertTrue(app.wordsHeight in 1 until dragged, "dragged down: ${app.wordsHeight}")
            assertTrue(app.wordsHeight >= 140, "the words keep their least height: ${app.wordsHeight}")

            onNodeWithTag("resize-words").performMouseInput { doubleClick() }
            step()
            assertEquals(0, app.wordsHeight)
            assertEquals(0, store.load().wordsHeight)
            app.shutdown()
        }
    }

    @Test
    fun `the cover in the player bar opens the full-screen player, and Escape closes it`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(1280, 800) {
            val app = app(fake)
            open(app)
            val songs = kotlinx.coroutines.runBlocking { app.client!!.album("a2") }.songs!!
            app.player.play(songs, 0)
            Thread.sleep(500)
            step()
            assertFalse(app.fullPlayer)

            onNodeWithTag("open-full-player").performClick()
            step()
            mainClock.advanceTimeBy(600)
            waitForIdle()
            assertTrue(app.fullPlayer)
            onNodeWithText("Now playing").assertIsDisplayed()

            // The lyrics button puts the words where the cover was, and takes them away again.
            assertFalse(app.fullPlayerLyrics)
            onNodeWithTag("full-player-lyrics").performClick()
            step()
            assertTrue(app.fullPlayerLyrics)
            // The button travels with the title while the words come in; a press has to find it where it stays.
            step(60)
            onNodeWithTag("full-player-lyrics").performClick()
            step()
            assertFalse(app.fullPlayerLyrics)

            onRoot().performKeyInput { pressKey(Key.Escape) }
            step()
            assertFalse(app.fullPlayer)
            app.shutdown()
        }
    }
}
