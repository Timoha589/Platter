package com.platter.desktop

import com.platter.desktop.api.Auth
import com.platter.desktop.api.Credentials
import com.platter.desktop.api.Song
import com.platter.desktop.data.SavedQueue
import com.platter.desktop.data.SettingsStore
import com.platter.desktop.deemix.DeezerHit
import com.platter.desktop.player.PlayQueue
import com.platter.desktop.player.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the window keeps across a restart: where the listener was, which panels were open, the queue, the window. */
class RememberedStateTest {
    private val apps = ArrayList<AppController>()
    private val dir = Files.createTempDirectory("platter-remember-test")

    @AfterTest
    fun tearDown() = apps.forEach { it.shutdown() }

    private fun store() = SettingsStore(dir.resolve("settings.json"))

    private fun open(fake: FakeSubsonic, store: SettingsStore = store()): AppController {
        store.update { if (serverUrl == null) withCredentials(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt")) }
        return AppController(store, CoroutineScope(SupervisorJob() + Dispatchers.IO), vlcArgs = listOf("--aout=dummy")).also { apps += it }
    }

    private fun song(id: String) = Song().apply { this.id = id; title = "Song $id"; artist = "Someone"; duration = 4 }

    /** Closes the app as a listener would and starts it again on the same files. */
    private fun restart(fake: FakeSubsonic, app: AppController): AppController {
        app.shutdown()
        return open(fake)
    }

    @Test
    fun `the page, its history and the open panel come back`() = FakeSubsonic().use { fake ->
        val first = open(fake)
        first.navigate(Screen.Library)
        first.navigate(Screen.Album("al-1"))
        first.navigate(Screen.Deezer(DeezerHit.Kind.ALBUM, 42L))
        first.navigate(Screen.AlbumList("Recent", "recent", 2000, 2010))
        first.sidePanel = SidePanel.Lyrics
        first.fullPlayerLyrics = true

        val second = restart(fake, first)
        assertEquals(Screen.AlbumList("Recent", "recent", 2000, 2010), second.screen)
        assertEquals(SidePanel.Lyrics, second.sidePanel)
        assertTrue(second.fullPlayerLyrics)
        // Back leads where it did.
        second.back()
        assertEquals(Screen.Deezer(DeezerHit.Kind.ALBUM, 42L), second.screen)
        second.back()
        assertEquals(Screen.Album("al-1"), second.screen)
        second.back()
        assertEquals(Screen.Library, second.screen)
    }

    @Test
    fun `a closed panel stays closed`() = FakeSubsonic().use { fake ->
        val first = open(fake)
        first.sidePanel = SidePanel.Queue
        first.sidePanel = null
        assertNull(restart(fake, first).sidePanel)
    }

    @Test
    fun `signing out sends the next start home`() = FakeSubsonic().use { fake ->
        val first = open(fake)
        first.navigate(Screen.Settings)
        first.signOut()
        assertEquals(Screen.Home, restart(fake, first).screen)
    }

    @Test
    fun `an unknown page in the file is skipped, not fatal`() = FakeSubsonic().use { fake ->
        val store = store()
        store.update {
            screens = listOf(
                com.platter.desktop.data.SavedScreen("from-the-future"),
                com.platter.desktop.data.SavedScreen("album"), // no id
                com.platter.desktop.data.SavedScreen("downloads"),
            )
        }
        assertEquals(Screen.Downloads, open(fake, store).screen)
    }

    @Test
    fun `the library tab, the sortings and the downloads filter come back`() = FakeSubsonic().use { fake ->
        val first = open(fake)
        first.choose("library.tab", "Artists")
        first.choose("library.artistSort", "Albums")
        first.choose("downloads.filter", "Liked")
        val second = restart(fake, first)
        assertEquals("Artists", second.choice("library.tab"))
        assertEquals("Albums", second.choice("library.artistSort"))
        assertEquals("Liked", second.choice("downloads.filter"))
        assertNull(second.choice("library.albumSort"))
    }

    @Test
    fun `the window is remembered, and only the normal size of it`() = FakeSubsonic().use { fake ->
        val first = open(fake)
        assertNull(first.savedWindow)
        first.saveWindow(1100, 720, 80, 60, maximized = false)
        // Maximising does not lose where the window sits when it is not maximised.
        first.saveWindow(null, null, null, null, maximized = true)
        val window = assertNotNull(restart(fake, first).savedWindow)
        assertEquals(listOf(1100, 720, 80, 60), listOf(window.width, window.height, window.x, window.y))
        assertTrue(window.maximized)
    }

    @Test
    fun `the queue comes back stopped, where it was, with shuffle and repeat`() = FakeSubsonic().use { fake ->
        val first = open(fake)
        val songs = listOf(song("s1-1"), song("s1-2"), song("s1-3"))
        val shuffled = PlayQueue(songs, 1, RepeatMode.All).toggleShuffle()
        first.player.restore(shuffled, 2500)
        val before = first.player.state.value.queue

        val second = restart(fake, first)
        val state = second.player.state.value
        assertEquals(before.songs.map { it.id }, state.queue.songs.map { it.id })
        assertEquals(before.index, state.queue.index)
        assertEquals(RepeatMode.All, state.queue.repeat)
        assertTrue(state.queue.shuffle)
        assertEquals(songs.map { it.id }, state.queue.original?.map { it.id })
        assertEquals(2500L, state.positionMs)
        assertFalse(state.isPlaying, "nothing may start by itself")

        // Switching shuffle off puts the order back, as it would have before the restart.
        second.player.toggleShuffle()
        assertEquals(songs.map { it.id }, second.player.state.value.queue.songs.map { it.id })
    }

    @Test
    fun `pressing play on a restored queue begins at the remembered place`() = FakeSubsonic().use { fake ->
        val first = open(fake)
        first.player.restore(PlayQueue(listOf(song("s1-1"), song("s1-2")), 0), 1500)
        val second = restart(fake, first)
        second.player.toggle()
        waitUntil("the song to play") { second.player.state.value.isPlaying }
        waitUntil("the time to run on from the remembered place") { second.player.state.value.positionMs >= 1500 }
        assertEquals(0, second.player.state.value.queue.index)
    }

    @Test
    fun `a podcast and a station keep what makes them one`() = FakeSubsonic().use { fake ->
        val first = open(fake)
        val episode = song("ep-1").apply { kind = Song.KIND_PODCAST }
        val station = song("st-1").apply { kind = Song.KIND_RADIO; streamUrl = "http://radio.example/live" }
        first.player.restore(PlayQueue(listOf(episode, station), 1), 0)
        val queue = restart(fake, first).player.state.value.queue
        assertEquals(Song.KIND_PODCAST, queue.songs[0].kind)
        assertEquals(Song.KIND_RADIO, queue.songs[1].kind)
        assertEquals("http://radio.example/live", queue.songs[1].streamUrl)
        assertFalse(queue.songs[0].isMusic)
    }

    @Test
    fun `the queue of one account is not shown to another, and a damaged file costs only the queue`() {
        val file = dir.resolve("queue-direct.json")
        val saved = SavedQueue(file)
        saved.write("a|tim", PlayQueue(listOf(song("x")), 0), 10)
        assertNotNull(saved.read("a|tim"))
        assertNull(saved.read("a|someone-else"))

        Files.writeString(file, "{ not json")
        assertNull(saved.read("a|tim"))

        saved.write("a|tim", PlayQueue(), 0) // an empty queue removes the file
        assertFalse(Files.exists(file))
    }
}
