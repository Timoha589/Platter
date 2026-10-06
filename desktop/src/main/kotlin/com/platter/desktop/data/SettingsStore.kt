package com.platter.desktop.data

import com.google.gson.Gson
import com.platter.desktop.api.Credentials
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/** A page, written down so it can be opened again after a restart. What the other fields mean depends on [kind]. */
class SavedScreen(
    var kind: String? = null,
    var id: String? = null,
    var title: String? = null,
    var type: String? = null,
    var fromYear: Int? = null,
    var toYear: Int? = null,
)

/**
 * A server the listener has signed in to, kept after signing out so it can be picked again from the sign-in screen.
 * The password is sealed by [Secrets]; without DPAPI there is none, and the listener types it again.
 */
class SavedServer(
    var id: String? = null,
    var name: String? = null,
    var address: String? = null,
    var username: String? = null,
    var sealedPassword: String? = null,
    /** This server's address on the home network; the one in [Settings] is a copy for whichever server is signed in. */
    var localAddress: String? = null,
)

/** What survives a restart. Credentials are the token and salt, never the password. */
class Settings {
    var serverUrl: String? = null
    var username: String? = null
    var token: String? = null
    var salt: String? = null
    var volume: Int = 100

    /** The interface language, as an [com.platter.desktop.i18n.AppLanguage] tag; null follows the system. */
    var language: String? = null

    /** Every server signed in to and not forgotten, and the id of the one signed in now (null while signed out). */
    var servers: List<SavedServer> = emptyList()
    var activeServer: String? = null

    /** The sign-in password, sealed by [Secrets]; only the wave service needs it. */
    var sealedPassword: String? = null

    /** Newest first, no repeats, capped; what the search box offers before anything is typed. */
    var recentSearches: List<String> = emptyList()

    /** Playlists shown on the home screen. Pinning is this app's own idea - Subsonic has none. */
    var pinnedPlaylists: List<String> = emptyList()

    /** Home sections in the order the listener arranged them, and the ones switched off; empty means the defaults. */
    var homeOrder: List<String> = emptyList()
    var homeHidden: List<String> = emptyList()

    /** Off stops both "now playing" and the scrobble when a track has been heard. */
    var scrobbling: Boolean = true

    /** The cap on stream bitrate in kbps, 0 for the original file; and the format to transcode to, null for as stored. */
    var maxBitrate: Int = 0
    var streamFormat: String? = null

    /** `off`, `track` or `album`; read when the audio engine starts. */
    var replayGain: String = "track"

    /** Keep the play queue on the server, and offer to pick it up on start. Off, as on the phone: it writes to the server. */
    var syncQueue: Boolean = false

    /** How many seconds the offer to resume stays up. */
    var syncSeconds: Int = 15

    /** The server's address on the home network, used while it answers; null for none. */
    var localAddress: String? = null

    /** The equalizer's button in the player bar; off, it is reached from Settings only. */
    var equalizerButton: Boolean = false

    /** The equalizer: on or off, the preamp in dB, and ten band gains in dB (empty is flat). */
    var eqEnabled: Boolean = false
    var eqPreamp: Float = 0f
    var eqBands: List<Float> = emptyList()

    /** The Deemix plus site, written by hand; null to have it looked for beside the music server. */
    var deemixUrl: String? = null

    /** The password for the site where it differs from the server's, sealed by [Secrets]. */
    var sealedDeemixPassword: String? = null

    /** Where downloaded songs are kept; null for the default folder beside the settings. */
    var downloadFolder: String? = null

    /** Cap on the bitrate of a download in kbps, 0 for the original file. */
    var downloadBitrate: Int = 0

    /** Save songs as they are listened to, keeping this many; the ones played longest ago make room. */
    var smartDownload: Boolean = false
    var smartCount: Int = 100

    /** Keep every liked song downloaded. */
    var keepLiked: Boolean = false

    /** Widths in dp of the sidebar on the left and of the queue / lyrics panel on the right, as the listener dragged them. */
    var sidebarWidth: Int = 340
    var panelWidth: Int = 360

    /** Height in dp of that panel when the window is upright and it sits under the page. */
    var panelHeight: Int = 360

    /** Height in dp of the words in the full-screen player of an upright window; 0 leaves it to the window's own proportions. */
    var wordsHeight: Int = 0

    /** The sidebar's "Public playlists" list is folded away. */
    var publicPlaylistsCollapsed: Boolean = false

    /** The panel that was open on the right (a [com.platter.desktop.SidePanel] name), null for none; and the full player's words. */
    var sidePanel: String? = null
    var fullPlayerLyrics: Boolean = false

    /** The pages the listener was on, oldest first, the last one being the page shown; empty means home. */
    var screens: List<SavedScreen> = emptyList()

    /** Small picks that are not worth a field each - the library's tab and sorting, the downloads filter. */
    var choices: Map<String, String> = emptyMap()

    /** When the next automatic look for a new version is due, in epoch milliseconds; "Later" pushes it a day on. */
    var nextUpdateCheck: Long = 0

    /** The window as the listener left it. The size and place are those of the window not maximised; 0 and null mean none yet. */
    var windowWidth: Int = 0
    var windowHeight: Int = 0
    var windowX: Int? = null
    var windowY: Int? = null
    var windowMaximized: Boolean = false

    fun credentials(): Credentials? {
        val server = serverUrl ?: return null
        val user = username ?: return null
        val tok = token ?: return null
        val s = salt ?: return null
        return Credentials(server, user, tok, s)
    }

    fun withCredentials(c: Credentials?): Settings = also {
        serverUrl = c?.serverUrl
        username = c?.username
        token = c?.token
        salt = c?.salt
    }
}

/**
 * A JSON file under %APPDATA%\Platter. Replaces the Android app's
 * SharedPreferences-backed `Preferences`; it is small enough that one
 * read-modify-write file is plenty.
 */
class SettingsStore(private val file: Path = defaultPath()) {
    private val gson = Gson()

    /** The folder beside the settings file, where the other things worth keeping across a restart go. */
    val directory: Path get() = file.parent

    @Synchronized
    fun load(): Settings = try {
        if (Files.exists(file)) Files.newBufferedReader(file).use { gson.fromJson(it, Settings::class.java) } ?: Settings() else Settings()
    } catch (e: Exception) {
        // A corrupt or half-written file should not stop the app starting: sign in again.
        Settings()
    }

    @Synchronized
    fun update(change: Settings.() -> Unit) {
        val settings = load().apply(change)
        Files.createDirectories(file.parent)
        // Write beside the target and swap, so a crash mid-write cannot leave half a file.
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.newBufferedWriter(tmp).use { gson.toJson(settings, it) }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        fun defaultPath(): Path {
            val base = System.getenv("APPDATA")?.let { Paths.get(it) } ?: Paths.get(System.getProperty("user.home"), ".config")
            return base.resolve("Platter").resolve("settings.json")
        }
    }
}
