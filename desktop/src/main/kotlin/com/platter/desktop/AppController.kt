package com.platter.desktop

import com.platter.desktop.i18n.t
import com.platter.desktop.i18n.tn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.platter.desktop.api.Album
import com.platter.desktop.api.Artist
import com.platter.desktop.api.Playlist
import com.platter.desktop.api.PodcastChannel
import com.platter.desktop.api.PodcastEpisode
import com.platter.desktop.api.RadioStation
import com.platter.desktop.api.ServerAddress
import com.platter.desktop.api.SubsonicException
import com.platter.desktop.api.SearchResult
import com.platter.desktop.api.Song
import com.platter.desktop.api.SongLyrics
import com.platter.desktop.api.SubsonicClient
import com.platter.desktop.data.PendingScrobbles
import com.platter.desktop.data.SavedQueue
import com.platter.desktop.data.SavedScreen
import com.platter.desktop.data.SavedServer
import com.platter.desktop.i18n.I18n
import com.platter.desktop.deemix.DeemixClient
import com.platter.desktop.deemix.DeemixConfig
import com.platter.desktop.deemix.DeemixResult
import com.platter.desktop.deemix.DeemixUsage
import com.platter.desktop.deemix.DeemixWords
import com.platter.desktop.deemix.DeezerHit
import com.platter.desktop.deemix.DeezerHits
import com.platter.desktop.deemix.DeezerPage
import com.platter.desktop.player.EqPresets
import com.platter.desktop.player.EqSettings
import com.platter.desktop.player.PreviewPlayer
import com.platter.desktop.update.UpdateInfo
import com.platter.desktop.update.Updater
import com.platter.desktop.offline.DownloadStore
import com.platter.desktop.offline.DownloadedSong
import com.platter.desktop.offline.Downloader
import com.platter.desktop.offline.Source
import com.platter.desktop.data.Secrets
import com.platter.desktop.data.SettingsStore
import com.platter.desktop.player.PlayerController
import com.platter.desktop.search.FuzzyIndex
import com.platter.desktop.search.LibrarySignals
import com.platter.desktop.search.RankedResults
import com.platter.desktop.search.SearchEngine
import com.platter.desktop.wave.WaveClient
import com.platter.desktop.wave.WaveController
import com.platter.desktop.wave.WaveLyrics
import com.platter.desktop.wave.WaveResult
import com.platter.desktop.wave.WaveState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.awt.Toolkit
import java.nio.file.Files
import java.nio.file.Path
import java.awt.datatransfer.StringSelection

sealed interface Screen {
    data object Home : Screen
    data object Library : Screen
    data object Search : Screen
    data object Settings : Screen

    /** The songs, albums and artists the listener has liked. */
    data object Liked : Screen
    data object LikedArtists : Screen
    data class Album(val id: String) : Screen
    data class Artist(val id: String) : Screen
    data class Playlist(val id: String) : Screen
    data class Genre(val name: String) : Screen
    data class Podcast(val id: String) : Screen

    /** Everything saved for offline play. */
    data object Downloads : Screen

    /** An artist's or an album's page from Deezer's catalogue, with what can be downloaded from it. */
    data class Deezer(val kind: DeezerHit.Kind, val id: Long) : Screen

    /** A rail's "See all": [type] is a `getAlbumList2` type, with a year span for `byYear`. */
    data class AlbumList(val title: String, val type: String, val fromYear: Int? = null, val toYear: Int? = null) : Screen
}

private const val SAVED_TRAIL = 40

/** The page written down for the settings file. */
internal fun Screen.toSaved(): SavedScreen = when (this) {
    Screen.Home -> SavedScreen("home")
    Screen.Library -> SavedScreen("library")
    Screen.Search -> SavedScreen("search")
    Screen.Settings -> SavedScreen("settings")
    Screen.Liked -> SavedScreen("liked")
    Screen.LikedArtists -> SavedScreen("liked-artists")
    Screen.Downloads -> SavedScreen("downloads")
    is Screen.Album -> SavedScreen("album", id = id)
    is Screen.Artist -> SavedScreen("artist", id = id)
    is Screen.Playlist -> SavedScreen("playlist", id = id)
    is Screen.Genre -> SavedScreen("genre", title = name)
    is Screen.Podcast -> SavedScreen("podcast", id = id)
    is Screen.Deezer -> SavedScreen("deezer", id = id.toString(), type = kind.name)
    is Screen.AlbumList -> SavedScreen("album-list", title = title, type = type, fromYear = fromYear, toYear = toYear)
}

/** The page a [toSaved] note stands for; null when it is not one this version knows, so an odd file never blocks the start. */
internal fun SavedScreen.toScreen(): Screen? = when (kind) {
    "home" -> Screen.Home
    "library" -> Screen.Library
    "search" -> Screen.Search
    "settings" -> Screen.Settings
    "liked" -> Screen.Liked
    "liked-artists" -> Screen.LikedArtists
    "downloads" -> Screen.Downloads
    "album" -> id?.let { Screen.Album(it) }
    "artist" -> id?.let { Screen.Artist(it) }
    "playlist" -> id?.let { Screen.Playlist(it) }
    "genre" -> title?.let { Screen.Genre(it) }
    "podcast" -> id?.let { Screen.Podcast(it) }
    "deezer" -> {
        val deezerKind = DeezerHit.Kind.entries.firstOrNull { it.name == type }
        val deezerId = id?.toLongOrNull()
        if (deezerKind != null && deezerId != null) Screen.Deezer(deezerKind, deezerId) else null
    }
    "album-list" -> {
        val listTitle = title
        val listType = type
        if (listTitle != null && listType != null) Screen.AlbumList(listTitle, listType, fromYear, toYear) else null
    }
    else -> null
}

/** What the right-hand panel shows, if anything. */
enum class SidePanel { Queue, Lyrics }

/** A question or a form laid over the window. At most one at a time. */
sealed interface AppDialog {
    /** Pick a playlist for [songs], or start a new one with them. */
    data class AddToPlaylist(val songs: List<Song>) : AppDialog
    data class NewPlaylist(val songs: List<Song>) : AppDialog
    data class RenamePlaylist(val playlist: Playlist) : AppDialog
    data class DeletePlaylist(val playlist: Playlist) : AppDialog
    data class TrackInfo(val song: Song) : AppDialog
    data object ArrangeHome : AppDialog
    data object AddPodcast : AppDialog
    data object DeleteAllDownloads : AppDialog
    data object Equalizer : AppDialog

    /** A newer Platter has been found; the dialog offers it and follows its download. */
    data object Update : AppDialog

    /** An album or a whole artist can spend much of the day's quota in one press, so those ask first. */
    data class ConfirmDeezer(val hit: DeezerHit) : AppDialog
    data class DeletePodcast(val channel: PodcastChannel) : AppDialog

    /** A station to change, or null to add one. */
    data class EditStation(val station: RadioStation?) : AppDialog
    data class DeleteStation(val station: RadioStation) : AppDialog
}

/** Where the download and the installation of a new version stand, for the update dialog. */
sealed interface UpdateState {
    data object Idle : UpdateState

    /** [fraction] is 0..1. */
    data class Downloading(val fraction: Float) : UpdateState
    data object Installing : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** A queue another device saved on the server, and where in it to pick up. */
class ResumeOffer(val songs: List<Song>, val index: Int, val positionMs: Long) {
    val song: Song? get() = songs.getOrNull(index)
}

/** How the Deemix plus account stands, as settings shows it. */
sealed interface DeemixStatus {
    data object NotChecked : DeemixStatus
    data object Checking : DeemixStatus
    data class Ready(val user: String, val role: String, val usage: DeemixUsage?) : DeemixStatus
    data class Unreachable(val address: String?) : DeemixStatus
    data class Rejected(val user: String?) : DeemixStatus
    data class Failed(val message: String?) : DeemixStatus
}

/** Widths in dp the side panels start at, and how far a drag may take them. */
const val SIDEBAR_DEFAULT = 340
const val PANEL_DEFAULT = 360
val SIDEBAR_RANGE = 220..520
val PANEL_RANGE = 280..600

/** The same panel's height when it sits under the page, in a window held upright. */
const val PANEL_HEIGHT_DEFAULT = 360
val PANEL_HEIGHT_RANGE = 160..800

/** The sections home can show, in the Android app's default order, with the ones it shows by default. */
enum class HomeSection(val title: String, val shownByDefault: Boolean) {
    Discovery("Discovery", false),
    Pinned("Playlists", true),
    MadeForYou("Made for you", true),
    StarredTracks("Liked songs", true),
    StarredAlbums("Liked albums", false),
    StarredArtists("Liked artists", false),
    Flashback("Flashback", false),
    MostPlayed("Most played", false),
    LastPlayed("Last played", true),
    NewReleases("New releases", true),
    RecentlyAdded("Recently added", true),
    NewestPodcasts("New episodes", false),
}

/** The artists and albums of the whole library: what the library screen lists and what a misspelling is measured against. */
class Catalogue(val artists: List<Artist>, val albums: List<Album>) {
    val fuzzy: FuzzyIndex by lazy { FuzzyIndex.of(artists.mapNotNull { it.name } + albums.mapNotNull { it.name }) }
}

/** Everything the screens share: who is signed in, where we are, what is playing. */
class AppController(
    private val store: SettingsStore,
    val scope: CoroutineScope,
    private val vlcArgs: List<String> = emptyList(),
    /** Deezer's public catalogue; tests point it at a fake. */
    deezerCatalogUrl: String = "https://api.deezer.com/",
    /** Where the wave answers when the server is not the one that has it; only tests set this, to a fake. The app itself has no such setting. */
    private val waveUrl: String? = null,
    /** Finds and fetches new versions; tests point it at a fake release list. */
    private val updater: Updater = Updater(store.directory.resolve("update")),
) {
    private val saved = store.load().also { I18n.choose(it.language) }

    var client by mutableStateOf(saved.credentials()?.let { SubsonicClient(it) })
        private set

    /** The servers the listener has signed in to, offered again on the sign-in screen after signing out. */
    var servers by mutableStateOf(rememberedServers())
        private set
    private var activeServer: String? = saved.activeServer

    /** A listener who was signed in before servers were kept is not asked to sign in again: the account in use becomes the first of them. */
    private fun rememberedServers(): List<SavedServer> {
        if (saved.servers.isNotEmpty()) return saved.servers
        val c = saved.credentials() ?: return emptyList()
        val entry = SavedServer(java.util.UUID.randomUUID().toString(), null, c.serverUrl, c.username, saved.sealedPassword, saved.localAddress)
        saved.activeServer = entry.id
        store.update { servers = listOf(entry); activeServer = entry.id }
        return listOf(entry)
    }

    /** The password of a saved server, or null where it cannot be read back (no DPAPI, or another Windows user). */
    private fun passwordOf(server: SavedServer): String? = Secrets.open(server.sealedPassword)

    private fun saveServers(list: List<SavedServer>, active: String?) {
        servers = list
        activeServer = active
        store.update { servers = list; activeServer = active }
    }

    var stack by mutableStateOf(saved.screens.mapNotNull { it.toScreen() }.ifEmpty { listOf(Screen.Home) })
        private set
    val screen: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    /** Pages Back has stepped off, newest last; any new navigation forgets them. */
    private var ahead by mutableStateOf<List<Screen>>(emptyList())
    val canGoForward: Boolean get() = ahead.isNotEmpty()

    /** Where the pages of the trail were scrolled to, so Back and Forward land where a page was left. */
    val scrollMemory = com.platter.desktop.ui.ScrollMemory()

    var searchQuery by mutableStateOf("")

    /** The panel on the right; what was open when the app was closed is open again. */
    private var shownPanel by mutableStateOf(SidePanel.entries.firstOrNull { it.name == saved.sidePanel })
    var sidePanel: SidePanel?
        get() = shownPanel
        set(value) {
            if (value == shownPanel) return
            shownPanel = value
            val name = value?.name
            store.update { sidePanel = name }
        }

    /** The full-screen player is open over everything; opened from the cover in the player bar. */
    var fullPlayer by mutableStateOf(false)

    /** The full-screen player shows the words of the song where the cover would be. */
    private var wordsInFullPlayer by mutableStateOf(saved.fullPlayerLyrics)
    var fullPlayerLyrics: Boolean
        get() = wordsInFullPlayer
        set(value) {
            if (value == wordsInFullPlayer) return
            wordsInFullPlayer = value
            store.update { fullPlayerLyrics = value }
        }
    var playlists by mutableStateOf<List<Playlist>>(emptyList())
        private set

    /** The listener's own playlists: the server also lists other people's public ones, which carry another owner. */
    val ownPlaylists: List<Playlist> get() = playlists.filter { !isOthers(it) }

    /** Public playlists made by someone else. */
    val publicPlaylists: List<Playlist> get() = playlists.filter { isOthers(it) }

    private fun isOthers(playlist: Playlist): Boolean {
        val owner = playlist.owner?.takeIf { it.isNotBlank() } ?: return false
        return !owner.equals(client?.credentials?.username, ignoreCase = true)
    }

    /** Whether the sidebar's public list is folded away; remembered across restarts. */
    var publicCollapsed by mutableStateOf(saved.publicPlaylistsCollapsed)
        private set

    fun togglePublicCollapsed() {
        publicCollapsed = !publicCollapsed
        val value = publicCollapsed
        store.update { publicPlaylistsCollapsed = value }
    }

    /** Bumped whenever a playlist's songs or name change, so the page showing it loads again. */
    var playlistVersion by mutableStateOf(0)
        private set

    /** Likes toggled this session, over whatever the server last sent. Ratings likewise (1 stands for a dislike). */
    private val likes = mutableStateMapOf<String, Boolean>()
    private val ratings = mutableStateMapOf<String, Int>()

    // --- settings the listener can change ------------------------------------

    /** The interface language's tag, or null to follow the system, as the phone does by default. */
    var language by mutableStateOf(saved.language)
        private set
    var scrobbling by mutableStateOf(saved.scrobbling)
        private set
    var equalizerButton by mutableStateOf(saved.equalizerButton)
        private set
    var crossfade by mutableStateOf(saved.crossfade)
        private set
    var maxBitrate by mutableStateOf(saved.maxBitrate)
        private set
    var streamFormat by mutableStateOf(saved.streamFormat)
        private set
    var replayGain by mutableStateOf(saved.replayGain)
        private set
    var syncQueue by mutableStateOf(saved.syncQueue)
        private set
    var syncSeconds by mutableStateOf(saved.syncSeconds)
        private set

    /** The widths of the side panels in dp; dragged by the handles beside them, saved when the drag ends. */
    var sidebarWidth by mutableStateOf(saved.sidebarWidth.coerceIn(SIDEBAR_RANGE))
        private set
    var panelWidth by mutableStateOf(saved.panelWidth.coerceIn(PANEL_RANGE))
        private set
    var panelHeight by mutableStateOf(saved.panelHeight.coerceIn(PANEL_HEIGHT_RANGE))
        private set

    /** How tall the words stand in the full-screen player of an upright window, in dp; 0 is the automatic split. */
    var wordsHeight by mutableStateOf(saved.wordsHeight.coerceAtLeast(0))
        private set

    fun resizeSidebar(width: Int) { sidebarWidth = width.coerceIn(SIDEBAR_RANGE) }

    fun resizePanel(width: Int) { panelWidth = width.coerceIn(PANEL_RANGE) }

    fun resizePanelHeight(height: Int) { panelHeight = height.coerceIn(PANEL_HEIGHT_RANGE) }

    fun resizeWords(height: Int) { wordsHeight = height.coerceAtLeast(0) }

    fun saveLayout() {
        val sidebar = sidebarWidth
        val panel = panelWidth
        val height = panelHeight
        val words = wordsHeight
        store.update { sidebarWidth = sidebar; panelWidth = panel; panelHeight = height; wordsHeight = words }
    }

    // --- small picks that outlive the window ------------------------------------

    private val choices = mutableStateMapOf<String, String>().apply { putAll(saved.choices) }

    /** What the listener last picked under [key] - the library's tab, a sorting - or null if nothing yet. */
    fun choice(key: String): String? = choices[key]

    fun choose(key: String, value: String) {
        if (choices[key] == value) return
        choices[key] = value
        val all = choices.toMap()
        store.update { this.choices = all }
    }

    // --- the window ---------------------------------------------------------------

    /** Where the window was left. [x] and [y] are null until it has been placed; the size is that of the window not maximised. */
    class WindowMemory(val width: Int, val height: Int, val x: Int?, val y: Int?, val maximized: Boolean)

    val savedWindow: WindowMemory? = if (saved.windowWidth > 0 && saved.windowHeight > 0) {
        WindowMemory(saved.windowWidth, saved.windowHeight, saved.windowX, saved.windowY, saved.windowMaximized)
    } else {
        null
    }

    /** [width] and [height] with [x] and [y] are those of the normal window; null while it is maximised, when only the flag is news. */
    fun saveWindow(width: Int?, height: Int?, x: Int?, y: Int?, maximized: Boolean) {
        store.update {
            if (width != null && height != null) {
                windowWidth = width
                windowHeight = height
                windowX = x
                windowY = y
            }
            windowMaximized = maximized
        }
    }

    /** The address the listener signed in with; the one the wave and the saved sign-in belong to. */
    private var serverUrl: String? = saved.serverUrl

    /** The address on the home network, if one was set, and whether it is what the app is talking to now. */
    var localAddress by mutableStateOf(saved.localAddress)
        private set
    var usingLocal by mutableStateOf(false)
        private set

    /** The equalizer; the audio engine has the same, and is told whenever it changes. */
    var equalizer by mutableStateOf(EqSettings(saved.eqEnabled, saved.eqPreamp, saved.eqBands))
        private set

    /** The songs saved for offline play, and what is being fetched. */
    private val downloads = DownloadStore(store.directory.resolve("downloads.json"))
    var downloadFolder by mutableStateOf(saved.downloadFolder)
        private set
    var downloadBitrate by mutableStateOf(saved.downloadBitrate)
        private set
    var smartDownload by mutableStateOf(saved.smartDownload)
        private set
    var smartCount by mutableStateOf(saved.smartCount)
        private set

    /** Bumped whenever something is saved, changed or removed, so what lists downloads loads again. */
    var downloadsVersion by mutableStateOf(0)
        private set

    val downloader = Downloader(
        scope = scope,
        store = downloads,
        root = { downloadRoot() },
        coversDir = store.directory.resolve("covers"),
        onChange = { downloadsVersion++; trimSmartDownloads() },
    )

    /** Plays that counted while the server could not be reached, still to be sent. */
    private val savedQueue = SavedQueue(store.directory.resolve("queue.json"))

    private val pending = PendingScrobbles(store.directory.resolve("pending-scrobbles.json"))
    var pendingPlays by mutableStateOf(0)
        private set

    val player = PlayerController(
        scope = scope,
        urlFor = { song -> localFile(song)?.toUri()?.toString() ?: song.streamUrl ?: song.id?.let { client?.streamUrl(it, maxBitrate.takeIf { b -> b > 0 }, streamFormat) } },
        scrobble = { id, submission, time ->
            if (scrobbling) {
                // "Now playing" is best effort; a counted play is written down first and sent when the server answers.
                if (submission) submitPlay(id, time ?: System.currentTimeMillis()) else client?.scrobble(id, false, null)
            }
        },
        initialVolume = saved.volume,
        initialEqualizer = EqSettings(saved.eqEnabled, saved.eqPreamp, saved.eqBands),
        initialCrossfade = saved.crossfade,
        vlcArgs = vlcArgs + replayGainArgs(saved.replayGain),
    )

    // --- messages and dialogs ---------------------------------------------------

    var dialog by mutableStateOf<AppDialog?>(null)

    var toast by mutableStateOf<String?>(null)
        private set
    private var toastSerial = 0

    /** A line at the bottom of the window that goes away by itself. */
    fun showToast(message: String) {
        val mine = ++toastSerial
        toast = message
        scope.launch {
            delay(TOAST_MS)
            if (mine == toastSerial) toast = null
        }
    }

    /** Runs [block]; a failure becomes a toast instead of a silent nothing. */
    private fun act(failure: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast("$failure: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // --- updates ----------------------------------------------------------------

    /** The newer release the update dialog is about; set by every look that finds one. */
    var updateOffer by mutableStateOf<UpdateInfo?>(null)
        private set

    var updateState by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    /** A look for a new version is under way. */
    var checkingUpdate by mutableStateOf(false)
        private set

    /** The version this build is, or null when it runs from the sources. */
    val version: String? get() = updater.currentVersion

    /** Closes the window and ends the program; Main sets it, because only the application scope can. */
    var onQuit: () -> Unit = {}

    /**
     * Asks GitHub for a newer Platter. At start ([manual] false) it is silent unless something is found, and it keeps
     * quiet for a day after "Later"; from Settings it always answers.
     */
    fun checkForUpdate(manual: Boolean) {
        if (checkingUpdate || updateState is UpdateState.Downloading) return
        if (!manual && System.currentTimeMillis() < saved.nextUpdateCheck) return
        checkingUpdate = true
        scope.launch {
            try {
                val found = updater.newer()
                if (found != null) {
                    updateOffer = found
                    if (updateState is UpdateState.Failed) updateState = UpdateState.Idle
                    if (dialog == null) dialog = AppDialog.Update
                } else if (manual) {
                    showToast(t("Platter is up to date"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (manual) showToast("${t("Could not check for updates")}: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                checkingUpdate = false
            }
        }
    }

    /** "Later": the offer is left alone for a day. */
    fun postponeUpdate() {
        saved.nextUpdateCheck = System.currentTimeMillis() + DAY_MS
        store.update { nextUpdateCheck = saved.nextUpdateCheck }
        dialog = null
    }

    /** Downloads the offered installer, then hands it to Windows Installer and quits so it can replace the program. */
    fun installUpdate() {
        val offer = updateOffer ?: return
        if (updateState is UpdateState.Downloading || updateState is UpdateState.Installing) return
        updateState = UpdateState.Downloading(0f)
        scope.launch {
            try {
                val msi = updater.download(offer) { updateState = UpdateState.Downloading(it) }
                updateState = UpdateState.Installing
                updater.install(msi)
                // The installer waits for this process to end before it touches the files.
                delay(QUIT_AFTER_INSTALL_MS)
                onQuit()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateState = UpdateState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // --- wave -----------------------------------------------------------------

    val waveState = WaveState()

    /** The password the wave service needs, read back from its sealed copy. */
    private var password: String? = Secrets.open(saved.sealedPassword)
    private var waveClient: WaveClient? = null

    private val waveController = WaveController(scope, player, waveState, client = { wave() })

    var waveStarting by mutableStateOf(false)
        private set
    var waveError by mutableStateOf<String?>(null)
        private set

    /** The wave client for the signed-in server, or null where there is no wave to ask. */
    private fun wave(): WaveClient? {
        val c = client ?: return null
        // The wave lives where the listener signed in, whichever address the rest of the app is using.
        val main = serverUrl ?: c.credentials.serverUrl
        if (!WaveClient.isAvailable(main, waveUrl)) return null
        val base = WaveClient.baseUrlFor(main, waveUrl)
        waveClient?.takeIf { it.baseUrl == base }?.let { return it }
        return WaveClient(base, c.authParams, password = { password }).also { waveClient = it }
    }

    val waveAvailable: Boolean get() = wave() != null

    /** Asks the server to have a first batch ready, so the press of the card is quick. Best effort. */
    fun prepareWave() {
        val w = wave() ?: return
        // Opening the home screen may ask only every so often.
        val now = System.currentTimeMillis()
        if (lastPrepare != 0L && now - lastPrepare < PREPARE_EVERY_MS) return
        lastPrepare = now
        scope.launch { w.prepare() }
    }

    private var lastPrepare = 0L

    /** The card is the wave's play/pause while it is what is playing, and its start otherwise. */
    fun onWaveTapped() {
        if (waveState.isWaveTrack(player.state.value.current?.id)) {
            player.toggle()
            return
        }
        val w = wave() ?: return
        if (waveStarting) return
        waveStarting = true
        waveError = null
        scope.launch {
            try {
                when (val result = w.start()) {
                    is WaveResult.Ok -> {
                        waveState.begin(result.batch)
                        player.play(result.batch.songs())
                    }
                    is WaveResult.Failed -> waveError = when (result.reason) {
                        WaveResult.Reason.UNREACHABLE -> t("My Wave is not set up on your server")
                        WaveResult.Reason.NOT_CONFIGURED -> t("My Wave needs your password, which is only sent over HTTPS or on your home network")
                        WaveResult.Reason.UNAVAILABLE -> result.detail?.let { t("My Wave could not start: %s", it) } ?: t("My Wave could not start")
                    }
                }
            } finally {
                waveStarting = false
            }
        }
    }

    /** The library searched by its embedded lyrics; null when the service could not be asked. */
    suspend fun searchLyrics(query: String): WaveLyrics? = wave()?.searchLyrics(query, 10)

    // --- navigation and sign-in -------------------------------------------------

    fun navigate(to: Screen) {
        // Going somewhere that is not a result of the search leaves the search behind, field and all.
        if (to == Screen.Home || to == Screen.Library || to == Screen.Settings) searchQuery = ""
        ahead = emptyList()
        val before = stack
        stack = when (to) {
            // The top-level places replace the trail; pages stack on it so Back works.
            Screen.Home, Screen.Library, Screen.Search, Screen.Settings -> listOf(to)
            else -> if (stack.last() == to) stack else stack + to
        }
        // A page reached anew starts at the top; the ones before it on the trail keep the place they were left at.
        if (stack !== before) scrollMemory.forgetFrom(stack.size - 1)
        rememberScreens()
    }

    /** The trail is written down, so the app opens on the page it was closed on, with Back leading where it did. */
    private fun rememberScreens() {
        val trail = stack.takeLast(SAVED_TRAIL).map { it.toSaved() }
        store.update { screens = trail }
    }

    fun back() {
        if (canGoBack) {
            ahead = ahead + stack.last()
            stack = stack.dropLast(1)
            rememberScreens()
        }
    }

    fun forward() {
        if (canGoForward) {
            stack = stack + ahead.last()
            ahead = ahead.dropLast(1)
            rememberScreens()
        }
    }

    /** Throws [com.platter.desktop.api.SubsonicException] or an IOException; the caller shows the message. */
    suspend fun signIn(server: String, user: String, pass: String, name: String? = null) {
        val signedIn = SubsonicClient.login(server, user, pass)
        val sealed = Secrets.seal(pass)
        val creds = signedIn.credentials
        // The same account signed in to again is the same entry, with the newest password and name.
        val known = servers.firstOrNull { ServerAddress.same(it.address, creds.serverUrl) && it.username.equals(creds.username, ignoreCase = true) }
        val entry = SavedServer(
            known?.id ?: java.util.UUID.randomUUID().toString(),
            name?.trim()?.takeIf { it.isNotEmpty() } ?: known?.name,
            creds.serverUrl, creds.username, sealed, known?.localAddress,
        )
        saveServers(servers.filter { it !== known } + entry, entry.id)
        localAddress = entry.localAddress
        store.update {
            withCredentials(creds)
            sealedPassword = sealed
            this.localAddress = entry.localAddress
        }
        password = pass
        serverUrl = signedIn.credentials.serverUrl
        usingLocal = false
        client = signedIn
        ahead = emptyList()
        stack = listOf(Screen.Home)
        scrollMemory.forgetFrom(0)
        rememberScreens()
        refreshPlaylists()
        afterSignIn()
    }

    /**
     * Signs in to a server kept from before, with the password kept for it. False when that password cannot be read
     * back, so the screen can ask for it; throws as [signIn] does when the server refuses or does not answer.
     */
    suspend fun signInTo(server: SavedServer): Boolean {
        val address = server.address ?: return false
        val user = server.username ?: return false
        val pass = passwordOf(server) ?: return false
        signIn(address, user, pass)
        return true
    }

    /** Takes a server off the sign-in screen. If it is the one in use, it comes back at the next sign-in. */
    fun forgetServer(id: String?) {
        saveServers(servers.filter { it.id != id }, activeServer.takeIf { it != id })
    }

    fun signOut() {
        player.play(emptyList())
        // The server stays in the list, password and all: signing out ends the session, it does not forget the account.
        saveServers(servers, null)
        localAddress = null
        store.update {
            withCredentials(null)
            sealedPassword = null
            this.localAddress = null
        }
        password = null
        client = null
        savedQueue.clear()
        waveClient = null
        waveError = null
        playlists = emptyList()
        likes.clear()
        ratings.clear()
        likedCache = null
        lyricsCache.clear()
        resetLibraryCaches()
        serverUrl = null
        usingLocal = false
        pendingPlays = 0
        resumeOffer = null
        dialog = null
        ahead = emptyList()
        stack = listOf(Screen.Home)
        scrollMemory.forgetFrom(0)
        rememberScreens()
    }

    suspend fun refreshPlaylists() {
        playlists = runCatching { client?.playlists().orEmpty() }.getOrDefault(playlists)
    }

    fun setVolume(volume: Int) {
        player.setVolume(volume)
        store.update { this.volume = volume }
    }

    // --- likes and ratings ------------------------------------------------------

    fun isLiked(id: String?, starred: String?): Boolean = id?.let { likes[id] } ?: (starred != null)

    fun ratingOf(song: Song): Int = song.id?.let { ratings[it] } ?: (song.userRating ?: 0)

    /** Subsonic has no dislike, so the lowest star rating stands in for one, as in the Android app. */
    fun isDisliked(song: Song): Boolean = ratingOf(song) == DISLIKE_RATING

    private var likedCache: SearchResult? = null
    private var likedAt = 0L

    /** What the listener has liked, asked of the server at most once a minute and again after any like. */
    suspend fun liked(): SearchResult {
        val c = client ?: return SearchResult()
        likedCache?.takeIf { System.currentTimeMillis() - likedAt < LIKED_FRESH_MS }?.let { return it }
        return c.starred().also { likedCache = it; likedAt = System.currentTimeMillis() }
    }

    private fun likesChanged() {
        signalsStale = true
        likedCache = null
    }

    fun toggleLike(song: Song) {
        val id = song.id ?: return
        val now = !isLiked(id, song.starred)
        likes[id] = now
        likesChanged()
        waveState.onFavoriteToggled(id, now)
        scope.launch {
            // Put the heart back if the server did not take it.
            runCatching { if (now) client?.star(songId = id) else client?.unstar(songId = id) }
                .onFailure { likes[id] = !now }
                .onSuccess { if (now) saveIfListening(song) }
        }
    }

    /** Disliking drops the like: the two exclude each other. */
    fun toggleDislike(song: Song) {
        val now = !isDisliked(song)
        rate(song, if (now) DISLIKE_RATING else 0)
    }

    /** [stars] is 0 to clear, 1..5 otherwise; one star is also what marks a dislike. */
    fun rate(song: Song, stars: Int) {
        val id = song.id ?: return
        val before = ratingOf(song)
        val now = stars.coerceIn(0, 5)
        ratings[id] = now
        scope.launch {
            runCatching { client?.setRating(id, now) }.onFailure { ratings[id] = before }
        }
        if (now == DISLIKE_RATING) {
            // On a wave track this steers the wave away straight away, not only once the server rereads ratings.
            waveState.onDisliked(id)
            if (isLiked(id, song.starred)) toggleLike(song)
        }
    }

    fun toggleLikeAlbum(id: String, starred: String?) {
        val now = !isLiked(id, starred)
        likes[id] = now
        likesChanged()
        scope.launch {
            runCatching { if (now) client?.star(albumId = id) else client?.unstar(albumId = id) }
                .onFailure { likes[id] = !now }
        }
    }

    fun toggleLikeArtist(id: String, starred: String?) {
        val key = "artist:$id"
        val now = !isLiked(key, starred)
        likes[key] = now
        likesChanged()
        scope.launch {
            runCatching { if (now) client?.star(artistId = id) else client?.unstar(artistId = id) }
                .onFailure { likes[key] = !now }
        }
    }

    fun isArtistLiked(id: String, starred: String?): Boolean = isLiked("artist:$id", starred)

    // --- lyrics ---------------------------------------------------------------

    private val lyricsCache = HashMap<String, SongLyrics>()

    /** The words of [song], asked of the server once per track. */
    suspend fun lyricsFor(song: Song): SongLyrics {
        val id = song.id ?: return SongLyrics(null, null)
        lyricsCache[id]?.let { return it }
        val fetched = client?.lyrics(song) ?: SongLyrics(null, null)
        // An empty answer is not kept: it may be a hiccup, and asking again is cheap.
        if (!fetched.isEmpty) lyricsCache[id] = fetched
        return fetched
    }

    // --- the library, for search and the library screen --------------------------

    private var catalogueJob: Deferred<Catalogue>? = null
    private var engine: SearchEngine? = null
    private var signals = LibrarySignals.Empty
    private var signalsAt = 0L
    private var signalsStale = true

    /** Every artist and album, fetched once per sign-in and kept. A failure is not kept: the next ask tries again. */
    suspend fun catalogue(): Catalogue {
        val c = client ?: throw IllegalStateException("Not signed in")
        val job = catalogueJob ?: scope.async { Catalogue(c.artists(), c.allAlbums()) }.also { catalogueJob = it }
        return try {
            job.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (catalogueJob === job) catalogueJob = null
            throw e
        }
    }

    private fun resetLibraryCaches() {
        catalogueJob?.cancel()
        catalogueJob = null
        engine = null
        signals = LibrarySignals.Empty
        signalsStale = true
    }

    /** Starred and recently played, as ids; read again a few minutes later or after a like. */
    private suspend fun librarySignals(): LibrarySignals {
        val c = client ?: return LibrarySignals.Empty
        val now = System.currentTimeMillis()
        if (!signalsStale && now - signalsAt < SIGNALS_FRESH_MS) return signals
        signalsStale = false
        signalsAt = now
        try {
            val starred = liked()
            val recent = runCatching { c.albumList("recent", 50) }.getOrDefault(emptyList())
            signals = LibrarySignals(
                starred = starred.songs.orEmpty().mapNotNull { it.id?.let { id -> LibrarySignals.Kind.SONG to id } } +
                    starred.albums.orEmpty().mapNotNull { it.id?.let { id -> LibrarySignals.Kind.ALBUM to id } } +
                    starred.artists.orEmpty().mapNotNull { it.id?.let { id -> LibrarySignals.Kind.ARTIST to id } },
                played = recent.mapNotNull { it.id?.let { id -> LibrarySignals.Kind.ALBUM to id } } +
                    recent.mapNotNull { it.artistId?.let { id -> LibrarySignals.Kind.ARTIST to id } },
            )
        } catch (e: CancellationException) {
            signalsStale = true
            throw e
        } catch (e: Exception) {
            // Ranking is better with what was known before than with nothing.
        }
        return signals
    }

    /** Warms what the first search will want, so typing does not wait for it. */
    fun prepareSearch() {
        if (client == null) return
        scope.launch { runCatching { librarySignals() } }
        scope.launch { runCatching { catalogue() } }
    }

    /** The query and its rewrites, ranked the way the Android app ranks them. */
    suspend fun search(query: String): RankedResults {
        val c = client ?: return RankedResults.Empty
        val e = engine ?: SearchEngine(
            source = { q -> c.search(q, songs = 20, albums = 20, artists = 20) },
            signals = { librarySignals() },
            fuzzy = { runCatching { catalogue().fuzzy }.getOrDefault(FuzzyIndex.empty()) },
        ).also { engine = it }
        return e.search(query)
    }

    var recentSearches by mutableStateOf(saved.recentSearches)
        private set

    /**
     * Remembers [query] once the listener acts on what came back - presses Enter, or opens a result. Not on every
     * search that runs: with results arriving as the query is typed, "e", "el", "elk" are not a history.
     */
    fun commitSearch(query: String = searchQuery) {
        val q = query.trim()
        if (q.length < SearchEngine.MIN_QUERY_LENGTH) return
        recentSearches = (listOf(q) + recentSearches.filterNot { it.equals(q, ignoreCase = true) }).take(MAX_RECENT)
        store.update { recentSearches = this@AppController.recentSearches }
    }

    fun forgetSearch(query: String) {
        recentSearches = recentSearches - query
        store.update { recentSearches = this@AppController.recentSearches }
    }

    // --- radio, queue and sharing ------------------------------------------------

    /** Plays [seed] and lets songs like it follow. */
    fun startRadio(seed: Song) {
        val id = seed.id ?: return
        player.play(listOf(seed))
        act(t("Could not start the radio")) {
            val mix = client?.similarSongs(id, MIX_SIZE).orEmpty().filter { it.id != id }
            if (mix.isEmpty()) showToast(t("Nothing similar found")) else player.append(mix)
        }
    }

    /**
     * Plays an artist: the best-known songs where the server has them, otherwise the albums in turn (the first few,
     * as in [albums]'s order - a long discography is not worth one request per record).
     */
    fun playArtist(name: String, albums: List<Album>, shuffle: Boolean = false) {
        act(t("Could not play the artist")) {
            val c = client ?: return@act
            val top = runCatching { c.topSongs(name, 20) }.getOrDefault(emptyList())
            val songs = top.ifEmpty {
                coroutineScope {
                    albums.take(DISCOGRAPHY_ALBUMS).mapNotNull { it.id }
                        .map { id -> async { runCatching { c.album(id).songs.orEmpty() }.getOrDefault(emptyList()) } }
                        .awaitAll().flatten()
                }
            }
            if (songs.isEmpty()) showToast(t("No songs found for %s", name)) else player.play(if (shuffle) songs.shuffled() else songs)
        }
    }

    /** A hundred songs from anywhere in the library, in a random order. */
    fun shuffleAll() {
        act(t("Could not shuffle the library")) {
            val songs = client?.randomSongs(SHUFFLE_ALL).orEmpty()
            if (songs.isEmpty()) showToast(t("The library has no songs to shuffle")) else player.play(songs)
        }
    }

    /** Plays a handful of songs of one genre; the server picks them at random, so Play and Shuffle differ only in order. */
    fun playGenre(genre: String, shuffle: Boolean) {
        act(t("Could not play the genre")) {
            val songs = client?.songsByGenre(genre, GENRE_SONGS).orEmpty()
            if (songs.isEmpty()) showToast(t("No songs found in %s", genre)) else player.play(if (shuffle) songs.shuffled() else songs)
        }
    }

    /** Plays songs like an artist or an album, by its id. */
    fun startRadioFor(id: String, what: String) {
        act(t("Could not start the radio")) {
            val mix = client?.similarSongs(id, MIX_SIZE).orEmpty()
            if (mix.isEmpty()) showToast(if (what == "album") t("Nothing similar to this album was found") else t("Nothing similar to this artist was found")) else player.play(mix)
        }
    }

    /** Puts songs like [song] right after it, and says so: the queue is not on screen, so the tap would look dead. */
    fun addInstantMix(song: Song) {
        val id = song.id ?: return
        act(t("Could not build a mix")) {
            val mix = client?.similarSongs(id, MIX_SIZE).orEmpty().filter { it.id != id }
            if (mix.isEmpty()) {
                showToast(t("Nothing similar found"))
            } else {
                player.playNext(mix)
                showToast(tn(mix.size, "Added %d similar song to the queue", "Added %d similar songs to the queue"))
            }
        }
    }

    fun playNext(songs: List<Song>) {
        player.playNext(songs)
        showToast(if (songs.size == 1) t("Will play next") else tn(songs.size, "%d song will play next", "%d songs will play next"))
    }

    fun addToQueue(songs: List<Song>) {
        if (songs.size == 1) player.enqueue(songs.single()) else player.append(songs)
        showToast(if (songs.size == 1) t("Added to queue") else tn(songs.size, "%d song added to queue", "%d songs added to queue"))
    }

    /** Makes a public link for an album, playlist or song and puts it on the clipboard. */
    fun share(id: String?) {
        id ?: return
        act(t("Could not share")) {
            val url = client?.createShare(id)
            if (url == null) {
                showToast(t("Sharing is switched off on your server"))
            } else {
                // Where the clipboard cannot be reached the link is shown instead, so it is not lost.
                val copied = runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null) }.isSuccess
                showToast(if (copied) t("Link copied") else url)
            }
        }
    }

    // --- playlists ----------------------------------------------------------------

    private fun playlistChanged() {
        playlistVersion++
        scope.launch { refreshPlaylists() }
    }

    fun addToPlaylist(playlist: Playlist, songs: List<Song>) {
        val id = playlist.id ?: return
        val ids = songs.mapNotNull { it.id }
        act(t("Could not add to the playlist")) {
            client?.addToPlaylist(id, ids)
            playlistChanged()
            showToast(t("Added to %s", playlist.name.orEmpty()))
        }
    }

    fun createPlaylist(name: String, songs: List<Song> = emptyList()) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        act(t("Could not create the playlist")) {
            val created = client?.createPlaylist(clean, songs.mapNotNull { it.id })
            playlistChanged()
            showToast(t("Created %s", clean))
            if (songs.isEmpty()) created?.id?.let { navigate(Screen.Playlist(it)) }
        }
    }

    fun renamePlaylist(playlist: Playlist, name: String) {
        val id = playlist.id ?: return
        val clean = name.trim()
        if (clean.isEmpty()) return
        act(t("Could not rename the playlist")) {
            client?.renamePlaylist(id, clean)
            playlistChanged()
        }
    }

    fun deletePlaylist(playlist: Playlist) {
        val id = playlist.id ?: return
        act(t("Could not delete the playlist")) {
            client?.deletePlaylist(id)
            if (id in pinned) togglePin(id)
            if ((screen as? Screen.Playlist)?.id == id) back()
            playlistChanged()
            showToast(t("Deleted %s", playlist.name.orEmpty()))
        }
    }

    /** [index] is the song's place in the playlist, so a song listed twice loses only the one that was picked. */
    fun removeFromPlaylist(playlistId: String, index: Int) {
        act(t("Could not remove the song")) {
            client?.removeFromPlaylist(playlistId, listOf(index))
            playlistChanged()
        }
    }

    fun moveInPlaylist(playlistId: String, songs: List<Song>, from: Int, to: Int) {
        if (from !in songs.indices || to !in songs.indices || from == to) return
        val reordered = songs.toMutableList().apply { add(to, removeAt(from)) }
        act(t("Could not reorder the playlist")) {
            client?.reorderPlaylist(playlistId, reordered.mapNotNull { it.id })
            playlistChanged()
        }
    }

    var pinned by mutableStateOf(saved.pinnedPlaylists)
        private set

    fun togglePin(id: String) {
        pinned = if (id in pinned) pinned - id else pinned + id
        store.update { pinnedPlaylists = pinned }
    }

    // --- home layout ------------------------------------------------------------------

    var homeOrder by mutableStateOf(resolveHomeOrder(saved.homeOrder))
        private set
    var homeHidden by mutableStateOf(resolveHomeHidden(saved.homeOrder, saved.homeHidden))
        private set

    val homeSections: List<HomeSection> get() = homeOrder.filter { it !in homeHidden }

    fun saveHomeLayout(order: List<HomeSection>, hidden: Set<HomeSection>) {
        homeOrder = order
        homeHidden = hidden
        store.update {
            homeOrder = order.map { it.name }
            homeHidden = hidden.map { it.name }
        }
    }

    // --- settings -----------------------------------------------------------------------

    fun changeLanguage(tag: String?) {
        language = tag
        I18n.choose(tag)
        store.update { language = tag }
    }

    fun changeScrobbling(on: Boolean) {
        scrobbling = on
        store.update { scrobbling = on }
    }

    fun changeMaxBitrate(kbps: Int) {
        maxBitrate = kbps
        store.update { maxBitrate = kbps }
    }

    fun changeStreamFormat(format: String?) {
        streamFormat = format
        store.update { streamFormat = format }
    }

    /** Read when the audio engine starts, so it takes effect the next time Platter is opened. */
    fun changeReplayGain(mode: String) {
        replayGain = mode
        store.update { replayGain = mode }
    }

    var scanning by mutableStateOf(false)
        private set
    var scanCount by mutableStateOf<Long?>(null)
        private set

    /** Asks the server to rescan its folders and follows it until it is done. */
    fun scanLibrary() {
        if (scanning) return
        scanning = true
        scanCount = null
        act(t("Could not scan the library")) {
            try {
                var status = client?.startScan()
                while (status?.scanning == true) {
                    scanCount = status.count
                    delay(1500)
                    status = client?.scanStatus()
                }
                resetLibraryCaches()
                showToast(t("Library scan finished"))
            } finally {
                scanning = false
            }
        }
    }

    /** Leaves the queue on the server, if asked to, and lets go of the audio engine. */
    fun shutdown() {
        background.forEach { it.cancel() }
        downloader.cancelAll()
        stopPreviewIfAny()
        // The last position is the one worth leaving; the wait is short so closing never hangs on a dead server.
        if (syncQueue) runBlocking { withTimeoutOrNull(SAVE_ON_EXIT_MS) { saveQueueNow() } }
        keepQueue()
        player.release()
    }

    // --- plays that could not be sent ------------------------------------------------

    private fun serverKey(): String? = client?.credentials?.let { "${serverUrl ?: it.serverUrl}|${it.username}" }

    private suspend fun submitPlay(id: String, time: Long) {
        val key = serverKey() ?: return
        pending.add(key, id, time)
        pendingPlays = pending.count(key)
        flushPending()
    }

    /** Sends what is waiting, oldest first. A refusal from the server counts as an answer; a dead line does not. */
    fun flushPending() {
        val key = serverKey() ?: return
        scope.launch {
            pending.flush(key) { id, time ->
                try {
                    client?.scrobble(id, true, time) ?: return@flush false
                    true
                } catch (e: SubsonicException) {
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            }
            pendingPlays = pending.count(key)
        }
    }

    // --- which address, home or away ------------------------------------------------------

    /**
     * Picks the local address while it answers and the main one otherwise, and moves the app over if that is not
     * where it is. True if it moved. Everything asked for after the move - streams, covers, lists - goes to the new
     * address, so a queue put together at home follows the listener out of the door.
     */
    suspend fun checkAddress(): Boolean {
        val c = client ?: return false
        val main = serverUrl ?: return false
        val target = ServerAddress.choose(main, localAddress)
        if (ServerAddress.same(target, c.credentials.serverUrl)) return false
        if (client !== c) return false // signed out, or moved by another check, while the probe ran
        client = SubsonicClient(c.credentials.copy(serverUrl = target))
        usingLocal = !ServerAddress.same(target, main)
        engine = null
        waveClient = null
        return true
    }

    /** Takes the home-network address from settings; empty clears it. Checked straight away. */
    fun changeLocalAddress(address: String) {
        val value = address.trim().ifEmpty { null }
        localAddress = value
        // The home address belongs to the server it was set for; another server signed in to later has its own.
        val id = activeServer
        val list = servers.map { if (it.id == id) SavedServer(it.id, it.name, it.address, it.username, it.sealedPassword, value) else it }
        servers = list
        store.update { localAddress = value; servers = list }
        scope.launch { checkAddress() }
    }

    /** A track that failed for want of a network may only need the other address: once it has changed, try it again. */
    private suspend fun retryAfterAddressChange() {
        if (localAddress.isNullOrBlank()) return
        if (checkAddress()) player.retryCurrent()
    }

    // --- the queue, kept on the server -----------------------------------------------------

    var resumeOffer by mutableStateOf<ResumeOffer?>(null)
        private set

    fun changeSyncQueue(on: Boolean) {
        syncQueue = on
        store.update { syncQueue = on }
        if (on) saveQueueSoon()
    }

    fun changeCrossfade(on: Boolean) {
        crossfade = on
        player.setCrossfade(on)
        store.update { crossfade = on }
    }

    fun changeEqualizerButton(on: Boolean) {
        equalizerButton = on
        store.update { equalizerButton = on }
    }

    fun changeSyncSeconds(seconds: Int) {
        syncSeconds = seconds
        store.update { syncSeconds = seconds }
    }

    /** The queue and the place in it go to the disk, so the next start finds them where they were left. */
    private fun keepQueue() {
        val key = serverKey() ?: return
        val s = player.state.value
        runCatching { savedQueue.write(key, s.queue, s.positionMs) }
    }

    private suspend fun saveQueueNow() {
        val c = client ?: return
        val s = player.state.value
        val songs = s.queue.songs
        // Only music goes up: an episode's id is not a song's, and a station is not on the server at all.
        if (songs.isEmpty() || s.queue.index !in songs.indices || songs.any { !it.isMusic }) return
        val ids = songs.mapNotNull { it.id }
        if (ids.size != songs.size) return
        runCatching { c.savePlayQueue(ids, s.queue.index, s.positionMs) }
    }

    private fun saveQueueSoon() {
        scope.launch { saveQueueNow() }
    }

    /** The queue another device left, offered for a few seconds if nothing is playing here yet. */
    fun checkSavedQueue() {
        val c = client ?: return
        if (!syncQueue) return
        scope.launch {
            val saved = runCatching { c.playQueue() }.getOrNull() ?: return@launch
            val index = saved.resolvedIndex()
            if (index < 0 || player.state.value.queue.songs.isNotEmpty()) return@launch
            val offer = ResumeOffer(saved.entries.orEmpty(), index, saved.position ?: 0)
            resumeOffer = offer
            delay(syncSeconds * 1000L)
            if (resumeOffer === offer) resumeOffer = null
        }
    }

    fun resume() {
        val offer = resumeOffer ?: return
        resumeOffer = null
        player.play(offer.songs, offer.index, offer.positionMs)
    }

    fun dismissResume() {
        resumeOffer = null
    }

    // --- podcasts ------------------------------------------------------------------------------

    /** Bumped when a channel or an episode changes, so the screens showing them load again. */
    var podcastVersion by mutableStateOf(0)
        private set

    /** An episode as something the player can queue; it is streamed by the id the server gave it once downloaded. */
    fun songOf(episode: PodcastEpisode, channel: PodcastChannel? = null): Song = Song().apply {
        id = episode.streamId
        title = episode.title
        artist = channel?.title ?: episode.artist
        album = channel?.title ?: episode.album
        coverArtId = episode.coverArtId ?: channel?.coverArtId
        duration = episode.duration
        kind = Song.KIND_PODCAST
    }

    /** Plays [episodes] from [from] onward - the ones the server has downloaded; the others cannot be heard yet. */
    fun playEpisodes(episodes: List<PodcastEpisode>, from: Int, channel: PodcastChannel? = null) {
        val songs = episodes.drop(from).filter { it.isDownloaded }.map { songOf(it, channel) }
        if (songs.isEmpty()) showToast(t("The server has not downloaded this episode yet")) else player.play(songs)
    }

    fun addPodcast(feedUrl: String) {
        val url = feedUrl.trim()
        if (!isWebAddress(url)) {
            showToast(t("A podcast address starts with http:// or https://"))
            return
        }
        act(t("Could not add the podcast")) {
            client?.addPodcast(url)
            podcastVersion++
            showToast(t("Podcast added; the server is fetching its episodes"))
        }
    }

    fun refreshPodcasts() {
        act(t("Could not look for new episodes")) {
            client?.refreshPodcasts()
            podcastVersion++
            showToast(t("Looking for new episodes"))
        }
    }

    fun deletePodcast(channel: PodcastChannel) {
        val id = channel.id ?: return
        act(t("Could not delete the podcast")) {
            client?.deletePodcast(id)
            if ((screen as? Screen.Podcast)?.id == id) back()
            podcastVersion++
            showToast(t("Deleted %s", channel.title.orEmpty()))
        }
    }

    fun downloadEpisode(episode: PodcastEpisode) {
        val id = episode.id ?: return
        act(t("Could not ask for the episode")) {
            client?.downloadEpisode(id)
            podcastVersion++
            showToast(t("The server is downloading the episode"))
        }
    }

    fun deleteEpisode(episode: PodcastEpisode) {
        val id = episode.id ?: return
        act(t("Could not delete the episode")) {
            client?.deleteEpisode(id)
            podcastVersion++
        }
    }

    // --- internet radio ------------------------------------------------------------------------

    var radioVersion by mutableStateOf(0)
        private set

    /** A station as something the player can queue: heard at its own address, with no end and no length. */
    fun songOf(station: RadioStation): Song = Song().apply {
        id = "radio:${station.id}"
        title = station.name
        artist = t("Internet radio")
        streamUrl = station.streamUrl
        kind = Song.KIND_RADIO
    }

    fun playStation(station: RadioStation) {
        if (station.streamUrl.isNullOrBlank()) return
        player.play(listOf(songOf(station)))
    }

    /** Adds a station, or - with [existing] - changes it. */
    fun saveStation(existing: RadioStation?, name: String, streamUrl: String, homePageUrl: String) {
        val label = name.trim()
        val stream = streamUrl.trim()
        if (label.isEmpty() || !isWebAddress(stream)) {
            showToast(t("A station needs a name and a stream address starting with http:// or https://"))
            return
        }
        act(t("Could not save the station")) {
            val id = existing?.id
            if (id == null) client?.addStation(label, stream, homePageUrl.trim()) else client?.updateStation(id, label, stream, homePageUrl.trim())
            radioVersion++
        }
    }

    fun deleteStation(station: RadioStation) {
        val id = station.id ?: return
        act(t("Could not delete the station")) {
            client?.deleteStation(id)
            radioVersion++
            showToast(t("Deleted %s", station.name.orEmpty()))
        }
    }

    private fun isWebAddress(text: String) = text.startsWith("http://", true) || text.startsWith("https://", true)

    // --- the equalizer ---------------------------------------------------------------------------------

    /** Applies and keeps the equalizer: heard at once, and as it was left the next time Platter starts. */
    fun changeEqualizer(settings: EqSettings) {
        val clamped = settings.copy(
            preamp = settings.preamp.coerceIn(-EqSettings.RANGE_DB, EqSettings.RANGE_DB),
            bands = settings.gains().map { it.coerceIn(-EqSettings.RANGE_DB, EqSettings.RANGE_DB) },
        )
        equalizer = clamped
        player.setEqualizer(clamped)
        store.update {
            eqEnabled = clamped.enabled
            eqPreamp = clamped.preamp
            eqBands = clamped.bands
        }
    }

    /** One of [EqPresets]; choosing it also switches the equalizer on, since that is plainly what was meant. */
    fun chooseEqPreset(name: String) {
        val gains = EqPresets.all[name] ?: return
        changeEqualizer(equalizer.copy(enabled = true, bands = gains))
    }

    // --- songs saved for offline play --------------------------------------------------------------

    fun downloadRoot(): Path = downloadFolder?.let { Path.of(it) } ?: store.directory.resolve("Downloads")

    /** The file of a song saved on this machine, or null: it was not, or the file has been deleted. */
    private fun localFile(song: Song): Path? {
        if (!song.isMusic) return null
        val id = song.id ?: return null
        return serverKey()?.let { downloads.playablePath(it, id) }
    }

    fun isDownloaded(id: String?): Boolean {
        // Read so that whatever draws a mark is drawn again when downloads change.
        @Suppress("UNUSED_EXPRESSION") downloadsVersion
        val key = serverKey() ?: return false
        return id != null && downloads.find(key, id) != null
    }

    /** What is saved, newest first. */
    fun downloadedSongs(): List<DownloadedSong> {
        @Suppress("UNUSED_EXPRESSION") downloadsVersion
        return serverKey()?.let { downloads.all(it) }.orEmpty().sortedByDescending { it.downloadedAt }
    }

    val downloadedBytes: Long
        get() {
            @Suppress("UNUSED_EXPRESSION") downloadsVersion
            return serverKey()?.let { downloads.totalBytes(it) } ?: 0
        }

    /** A cover saved with a download, if there is one: it shows with no server. */
    fun localCover(coverArtId: String?): java.io.File? =
        coverArtId?.let { downloader.coverFile(it).toFile() }?.takeIf { it.isFile }

    /** The download quality as the server wants it: a cap, and the format to convert to under it. */
    private fun downloadQuality(): Pair<Int?, String?> = if (downloadBitrate > 0) downloadBitrate to "mp3" else null to null

    /** Saves [songs] for offline play. They are asked for by hand: nothing lets them go until the listener does. */
    fun download(songs: List<Song>) {
        val c = client ?: return
        val key = serverKey() ?: return
        val music = songs.filter { it.isMusic && it.id != null }
        if (music.isEmpty()) return
        val (cap, format) = downloadQuality()
        downloader.enqueue(c, key, music, Source.MANUAL, cap, format)
        showToast(if (music.size == 1) t("Downloading “%s”", music.single().title.orEmpty()) else tn(music.size, "Downloading %d song", "Downloading %d songs"))
    }

    fun removeDownloads(songs: List<Song>) {
        val key = serverKey() ?: return
        songs.mapNotNull { it.id }.forEach { downloader.remove(key, it) }
    }

    fun removeAllDownloads() {
        val key = serverKey() ?: return
        downloader.cancelAll()
        downloader.removeAll(key)
        showToast(t("Deleted all downloads"))
    }

    fun retryFailedDownloads() {
        val c = client ?: return
        downloader.retryFailed(c, serverKey() ?: return)
    }

    fun changeDownloadFolder(path: String) {
        val value = path.trim().ifEmpty { null }
        downloadFolder = value
        store.update { downloadFolder = value }
        if (value != null) runCatching { Files.createDirectories(Path.of(value)) }
        showToast(t("New downloads go to %s", downloadRoot()))
    }

    fun changeDownloadBitrate(kbps: Int) {
        downloadBitrate = kbps
        store.update { downloadBitrate = kbps }
    }

    fun changeSmartDownload(on: Boolean) {
        smartDownload = on
        store.update { smartDownload = on }
    }

    fun changeSmartCount(count: Int) {
        smartCount = count
        store.update { smartCount = count }
        trimSmartDownloads()
    }

    private var lastListenedId: String? = null
    private var listening: Song? = null

    /**
     * A song has started: it is heard (so it is the last to be let go), and with smart download on it is kept - but only
     * if it is liked. Listening alone saves nothing; a like given while it plays saves it then ([saveIfListening]).
     */
    private suspend fun onListening(song: Song?) {
        if (song == null || !song.isMusic) return
        val id = song.id ?: return
        val key = serverKey() ?: return
        downloads.update(key, id) { lastPlayedAt = System.currentTimeMillis() }
        // Pausing and resuming is not listening again.
        if (!smartDownload || id == lastListenedId) return
        lastListenedId = id
        listening = song
        if (isLikedNow(song)) saveSmart(song)
    }

    /** What the heart says, and where this computer has not heard of the song yet, what the server says. */
    private suspend fun isLikedNow(song: Song): Boolean {
        val id = song.id ?: return false
        likes[id]?.let { return it }
        if (song.starred != null) return true
        return runCatching { liked().songs.orEmpty().any { it.id == id } }.getOrDefault(false)
    }

    /** A like on the song that is being listened to: it is listened to and liked now, so it is kept. */
    private fun saveIfListening(song: Song) {
        if (!smartDownload) return
        val heard = listening?.takeIf { it.id == song.id && it.id == lastListenedId } ?: return
        saveSmart(heard)
    }

    private fun saveSmart(song: Song) {
        val c = client ?: return
        val key = serverKey() ?: return
        val (cap, format) = downloadQuality()
        downloader.enqueue(c, key, listOf(song), Source.SMART, cap, format)
        trimSmartDownloads()
    }

    /** Lets go of the songs saved by listening that were played longest ago, down to the size chosen. */
    private fun trimSmartDownloads() {
        if (!smartDownload) return
        val key = serverKey() ?: return
        val saved = downloads.all(key).filter { it.source == Source.SMART }.sortedBy { it.lastPlayedAt }
        // What the listener asked for is not counted and is never let go.
        saved.take((saved.size - smartCount).coerceAtLeast(0)).forEach { downloader.remove(key, it.id) }
    }

    // --- Deezer, through Deemix plus -------------------------------------------------------------------

    var deemixUrl by mutableStateOf(saved.deemixUrl)
        private set

    /** Only needed where the site's password is not the server's. Kept sealed on disk, like the one the wave needs. */
    private var deemixPassword: String? = Secrets.open(saved.sealedDeemixPassword)
    var hasDeemixPassword by mutableStateOf(deemixPassword != null)
        private set

    var deemixStatus by mutableStateOf<DeemixStatus>(DeemixStatus.NotChecked)
        private set
    var deemixUsage by mutableStateOf<DeemixUsage?>(null)
        private set

    /** What each Deezer row is doing: being queued, or queued. */
    val deezerStates = mutableStateMapOf<String, DeezerHit.State>()

    val deemix = DeemixClient(
        DeemixConfig(
            customUrl = { deemixUrl },
            // The address in use first: at home that is the local one, and the site is beside it on the same machine.
            servers = { listOfNotNull(client?.credentials?.serverUrl, serverUrl).distinct() },
            user = { client?.credentials?.username },
            ownPassword = { deemixPassword },
            serverPassword = { password },
            searchLibrary = { artist -> client?.search(artist, songs = LIBRARY_SONGS_PER_ARTIST, albums = 0, artists = 0)?.songs.orEmpty() },
        ),
        catalogUrl = deezerCatalogUrl,
    )

    fun deemixQuotaText(): String = DeemixWords.quota(deemixUsage)

    fun changeDeemixUrl(address: String) {
        val value = address.trim().ifEmpty { null }
        deemixUrl = value
        store.update { deemixUrl = value }
        deemix.retry()
        refreshDeemixAccount()
    }

    fun changeDeemixPassword(entered: String) {
        deemixPassword = entered.ifEmpty { null }
        hasDeemixPassword = deemixPassword != null
        val sealed = deemixPassword?.let { Secrets.seal(it) }
        store.update { sealedDeemixPassword = sealed }
        deemix.retry()
        refreshDeemixAccount()
    }

    /** Signs in to the site and reads the quota, for settings to show. */
    fun refreshDeemixAccount() {
        if (client == null) return
        deemixStatus = DeemixStatus.Checking
        scope.launch {
            when (val result = deemix.usage()) {
                is DeemixResult.Ok -> {
                    deemixUsage = result.value
                    val account = deemix.account()
                    deemixStatus = DeemixStatus.Ready(account?.first ?: client?.credentials?.username.orEmpty(), DeemixWords.role(account?.second), result.value)
                }
                is DeemixResult.Failed -> deemixStatus = when (result.failure) {
                    DeemixResult.Failure.UNREACHABLE -> DeemixStatus.Unreachable(deemix.baseUrl())
                    DeemixResult.Failure.NO_ACCOUNT -> DeemixStatus.Rejected(client?.credentials?.username)
                    else -> DeemixStatus.Failed(result.message)
                }
            }
        }
    }

    private fun refreshQuota() {
        scope.launch { (deemix.usage() as? DeemixResult.Ok)?.let { deemixUsage = it.value } }
    }

    /** What Deezer has for [query], marked against the library; null where there is no account or no site, and then the section is simply not there. */
    suspend fun deezerSearch(query: String): DeezerHits? {
        if (client == null) return null
        val found = deemix.search(query).value ?: return null
        if (deemixUsage == null) refreshQuota()
        return DeezerHits.of(found)
    }

    /** An artist's or album's page from Deezer, or null when it could not be opened. */
    suspend fun deezerPage(kind: DeezerHit.Kind, id: Long): DeezerPage? =
        if (kind == DeezerHit.Kind.ALBUM) deemix.albumPage(id).value?.let { DeezerPage.ofAlbum(it) }
        else deemix.artistPage(id).value?.let { DeezerPage.ofArtist(it) }

    /** Asks Deemix plus to download [hit]. An album or an artist goes through [AppDialog.ConfirmDeezer] first. */
    fun deezerDownload(hit: DeezerHit) {
        if (deezerStates[hit.key] == DeezerHit.State.WORKING) return
        deezerStates[hit.key] = DeezerHit.State.WORKING
        scope.launch {
            val result = try {
                when (hit.kind) {
                    DeezerHit.Kind.TRACK -> deemix.downloadTrack(hit.link.orEmpty())
                    DeezerHit.Kind.ALBUM -> deemix.downloadAlbum(hit.id, hit.title)
                    DeezerHit.Kind.ARTIST -> deemix.downloadArtist(hit.id, hit.title)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DeemixResult.Failed(DeemixResult.Failure.ERROR, e.message)
            }
            finishedDeezerDownload(hit, result)
        }
    }

    private fun finishedDeezerDownload(hit: DeezerHit, result: DeemixResult<com.platter.desktop.deemix.DownloadMany>) {
        val outcome = result.value
        if (outcome == null) {
            deezerStates[hit.key] = DeezerHit.State.IDLE
            val failed = result as DeemixResult.Failed
            showToast(
                when (failed.failure) {
                    DeemixResult.Failure.UNREACHABLE -> t("Deemix plus is not reachable")
                    // The server words the quota refusal itself, with the limit in it.
                    DeemixResult.Failure.QUOTA -> failed.message ?: t("Daily limit reached")
                    else -> t("Download failed: %s", failed.message ?: "-")
                },
            )
            refreshQuota()
            return
        }

        val added = outcome.added ?: 0
        val skipped = outcome.skipped ?: 0
        deezerStates[hit.key] = if (added > 0 || hit.kind != DeezerHit.Kind.TRACK) DeezerHit.State.QUEUED else DeezerHit.State.IDLE
        showToast(
            when {
                hit.kind == DeezerHit.Kind.TRACK && added > 0 -> t("Added to the download queue")
                hit.kind == DeezerHit.Kind.TRACK -> t("Already downloaded or queued")
                added == 0 -> t("Everything is already in your library")
                else -> t("Added %s, %d already in your library", tn(added, "%d track", "%d tracks"), skipped)
            },
        )
        refreshQuota()
    }

    // --- previews of Deezer tracks --------------------------------------------------------------------

    enum class PreviewState { STOPPED, LOADING, PLAYING }

    /** The Deezer track being previewed, as [DeezerHit.key]. */
    var previewKey by mutableStateOf<String?>(null)
        private set
    var previewState by mutableStateOf(PreviewState.STOPPED)
        private set

    private var previewJob: Job? = null
    private var previewFile: Path? = null
    private var previewPlayerInstance: PreviewPlayer? = null

    /** Made on the first preview: most sessions never play one. */
    private fun previewPlayer(): PreviewPlayer = previewPlayerInstance ?: PreviewPlayer(vlcArgs) { finished ->
        if (!finished) showToast(t("Could not play the preview"))
        scope.launch { stopPreview() }
    }.also { previewPlayerInstance = it }

    /** Starts [hit]'s 30-second preview, or stops it if it is the one playing. It pauses the music, as a call would, and does not touch the queue. */
    fun togglePreview(hit: DeezerHit) {
        if (hit.kind != DeezerHit.Kind.TRACK) return
        if (previewKey == hit.key) {
            stopPreview()
            return
        }
        stopPreview()
        previewKey = hit.key
        previewState = PreviewState.LOADING
        previewJob = scope.launch {
            val preview = deemix.preview(hit.id).value
            val bytes = preview?.let { fetchPreview(it.url, it.authorization) }
            if (previewKey != hit.key) return@launch
            if (bytes == null) {
                stopPreview()
                showToast(t("Could not play the preview"))
                return@launch
            }
            val file = withContext(Dispatchers.IO) { Files.createTempFile("platter-preview", ".mp3").also { Files.write(it, bytes); it.toFile().deleteOnExit() } }
            if (previewKey != hit.key) {
                runCatching { Files.deleteIfExists(file) }
                return@launch
            }
            previewFile = file
            if (player.state.value.isPlaying) player.toggle()
            previewPlayer().play(file)
            previewState = PreviewState.PLAYING
        }
    }

    private suspend fun fetchPreview(url: String, authorization: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            SubsonicClient.defaultHttp.newCall(okhttp3.Request.Builder().url(url).header("Authorization", authorization).build()).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes() else null
            }
        } catch (e: java.io.IOException) {
            null
        }
    }

    private fun stopPreviewIfAny() {
        if (previewKey != null) stopPreview()
        previewPlayerInstance?.release()
    }

    fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        if (previewKey != null) previewPlayerInstance?.stop()
        previewKey = null
        previewState = PreviewState.STOPPED
        previewFile?.let { runCatching { Files.deleteIfExists(it) } }
        previewFile = null
    }

    // --- things that run in the background -------------------------------------------------------

    private val background = ArrayList<Job>()

    private fun afterSignIn() {
        flushPending()
        checkSavedQueue()
        scope.launch { checkAddress() }
    }

    @OptIn(FlowPreview::class)
    private fun startBackgroundWork() {
        // A newer Platter is looked for once at start (and offered); a dead line or a missing release says nothing.
        checkForUpdate(manual = false)
        // The queue is saved a moment after it changes, and every half minute while something plays.
        background += scope.launch {
            player.state
                .map { s -> Triple(s.queue.songs.map { it.id }, s.queue.index, s.isPlaying) }
                .distinctUntilChanged()
                .debounce(SAVE_QUEUE_AFTER_MS)
                .collect { if (syncQueue) saveQueueNow() }
        }
        background += scope.launch {
            while (true) {
                delay(SAVE_QUEUE_EVERY_MS)
                if (syncQueue && player.state.value.isPlaying) saveQueueNow()
            }
        }
        // The same queue is kept on this machine, whatever the server is asked to do: it comes back, stopped, on the next start.
        background += scope.launch {
            player.state
                .map { s -> Triple(s.queue, s.queue.index, s.isPlaying) }
                .distinctUntilChanged()
                .debounce(SAVE_QUEUE_AFTER_MS)
                .collect { withContext(Dispatchers.IO) { keepQueue() } }
        }
        background += scope.launch {
            while (true) {
                delay(KEEP_QUEUE_EVERY_MS)
                if (player.state.value.isPlaying) withContext(Dispatchers.IO) { keepQueue() }
            }
        }
        // Plays waiting to be sent go again every minute, and the address is looked at again every half minute.
        background += scope.launch {
            while (true) {
                delay(FLUSH_EVERY_MS)
                if (pendingPlays > 0) flushPending()
            }
        }
        background += scope.launch {
            while (true) {
                delay(ADDRESS_EVERY_MS)
                if (!localAddress.isNullOrBlank()) checkAddress()
            }
        }
        // Pressing play on the music takes the speaker back from a preview.
        background += scope.launch {
            player.state.map { it.isPlaying }.distinctUntilChanged().collect { if (it && previewState == PreviewState.PLAYING) stopPreview() }
        }
        // A song that starts playing is "listened to": it counts for the smart download, and for what is let go first.
        background += scope.launch {
            player.state.map { it.current }.distinctUntilChanged { a, b -> a?.id == b?.id }.collect { onListening(it) }
        }
        // A track that would not load, with a home address set, may only need the other address.
        background += scope.launch {
            player.state.map { it.error }.distinctUntilChanged().collect { if (it != null) retryAfterAddressChange() }
        }
    }

    init {
        if (client != null) {
            pendingPlays = serverKey()?.let { pending.count(it) } ?: 0
            // With the queue kept on the server, that one is the truth (another device may have moved it on): it is offered below.
            if (!syncQueue) serverKey()?.let { key -> savedQueue.read(key)?.let { player.restore(it.queue, it.positionMs) } }
            afterSignIn()
        }
        startBackgroundWork()
    }

    companion object {
        const val DISLIKE_RATING = 1
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val QUIT_AFTER_INSTALL_MS = 600L
        private const val SAVE_QUEUE_AFTER_MS = 2_000L
        private const val SAVE_QUEUE_EVERY_MS = 30_000L
        private const val KEEP_QUEUE_EVERY_MS = 10_000L
        private const val SAVE_ON_EXIT_MS = 3_000L
        private const val FLUSH_EVERY_MS = 60_000L
        private const val ADDRESS_EVERY_MS = 30_000L
        private const val PREPARE_EVERY_MS = 15 * 60 * 1000L
        private const val TOAST_MS = 3500L
        private const val SIGNALS_FRESH_MS = 5 * 60 * 1000L
        private const val LIKED_FRESH_MS = 60 * 1000L
        private const val MAX_RECENT = 12
        private const val MIX_SIZE = 50
        private const val GENRE_SONGS = 100
        private const val SHUFFLE_ALL = 100
        private const val DISCOGRAPHY_ALBUMS = 30
        private const val LIBRARY_SONGS_PER_ARTIST = 500

        /** Wanted order first; anything newer than what was saved is added at its default place, never lost. */
        fun resolveHomeOrder(saved: List<String>): List<HomeSection> {
            val known = saved.mapNotNull { name -> HomeSection.entries.firstOrNull { it.name == name } }
            return known + HomeSection.entries.filterNot { it in known }
        }

        /** With nothing saved the defaults apply; once an arrangement is saved a section it has not seen yet stays off. */
        fun resolveHomeHidden(savedOrder: List<String>, savedHidden: List<String>): Set<HomeSection> =
            if (savedOrder.isEmpty()) HomeSection.entries.filterNot { it.shownByDefault }.toSet()
            else HomeSection.entries.filter { it.name in savedHidden || it.name !in savedOrder }.toSet()

        /** libvlc's own option; `off` leaves the files as they are. */
        fun replayGainArgs(mode: String): List<String> =
            if (mode == "track" || mode == "album") listOf("--audio-replay-gain-mode=$mode") else emptyList()
    }
}

/** The enum value picked under [key], or [default]. */
inline fun <reified E : Enum<E>> AppController.enumChoice(key: String, default: E): E =
    enumValues<E>().firstOrNull { it.name == choice(key) } ?: default

/** An address as people say it: without the scheme or a trailing slash. */
fun addressLabel(address: String?): String = address.orEmpty().replaceFirst(Regex("^https?://"), "").trimEnd('/')

/** What a saved server is called on the sign-in screen: the name it was given, else its address. */
fun SavedServer.label(): String = name?.takeIf { it.isNotBlank() } ?: addressLabel(address)
