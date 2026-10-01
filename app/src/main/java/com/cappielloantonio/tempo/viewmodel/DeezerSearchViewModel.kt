package com.cappielloantonio.tempo.viewmodel

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.deemix.DeemixCallback
import com.cappielloantonio.tempo.deemix.DeemixClient
import com.cappielloantonio.tempo.deemix.DeemixRequest
import com.cappielloantonio.tempo.deemix.DeemixResult
import com.cappielloantonio.tempo.deemix.DeemixUsage
import com.cappielloantonio.tempo.deemix.DeezerAlbum
import com.cappielloantonio.tempo.deemix.DeezerArtist
import com.cappielloantonio.tempo.deemix.DeezerTrack
import com.cappielloantonio.tempo.deemix.DeezerResults
import com.cappielloantonio.tempo.deemix.DownloadMany

/** One row of the Deezer section: something the site could download. */
data class DeezerHit(
        val kind: Kind,
        val id: Long,
        val title: String,
        /** The artist of an album or a track; empty for an artist. */
        val artist: String,
        /** The album of a track, the release year of an album. */
        val detail: String,
        val image: String?,
        val link: String?,
        val inLibrary: Boolean,
        /** The second line as a page wants it, instead of the one search builds. */
        val caption: String? = null
) {
    enum class Kind { ARTIST, ALBUM, TRACK }

    enum class State { IDLE, WORKING, QUEUED }

    val key: String get() = "$kind:$id"

    companion object {
        fun artist(artist: DeezerArtist): DeezerHit? {
            val id = artist.id ?: return null
            val name = artist.name?.takeIf { it.isNotBlank() } ?: return null
            return DeezerHit(Kind.ARTIST, id, name, "", "", artist.pictureMedium, null, false)
        }

        fun album(album: DeezerAlbum, caption: String? = null): DeezerHit? {
            val id = album.id ?: return null
            val title = album.title?.takeIf { it.isNotBlank() } ?: return null
            return DeezerHit(Kind.ALBUM, id, title, album.artist?.name.orEmpty(), "",
                    album.coverMedium, null, false, caption)
        }

        /** [cover] stands in for tracks that come without their album - an album's own tracklist. */
        fun track(track: DeezerTrack, inLibrary: Boolean, caption: String? = null, cover: String? = null): DeezerHit? {
            val id = track.id ?: return null
            val title = track.title?.takeIf { it.isNotBlank() } ?: return null
            if (track.link == null) return null
            return DeezerHit(Kind.TRACK, id, title, track.artist?.name.orEmpty(), track.album?.title.orEmpty(),
                    track.album?.coverMedium ?: cover, track.link, inLibrary, caption)
        }
    }
}

/**
 * The part of search that looks past the library: what Deezer has for the
 * query, through Deemix plus, and the downloads started from it.
 *
 * Activity-scoped, like the library search, so backing out of an artist lands
 * on the same rows - and so a download started here still reports how it went
 * after the screen that started it is gone.
 */
class DeezerSearchViewModel(application: Application) : AndroidViewModel(application) {
    private val artists = MutableLiveData<List<DeezerHit>>(emptyList())
    private val rows = MutableLiveData<List<DeezerHit>>(emptyList())
    private val states = MutableLiveData<Map<String, DeezerHit.State>>(emptyMap())
    private val usage = MutableLiveData<DeemixUsage?>(null)

    private var searchRequest: DeemixRequest? = null
    private var usageRequest: DeemixRequest? = null
    private var query = ""

    /** The artists rail. */
    fun getArtists(): LiveData<List<DeezerHit>> = artists

    /** Tracks, then albums. */
    fun getRows(): LiveData<List<DeezerHit>> = rows

    fun getStates(): LiveData<Map<String, DeezerHit.State>> = states

    fun getUsage(): LiveData<DeemixUsage?> = usage

    /**
     * Runs alongside the library search. Rows for an earlier query stay up
     * until this one's arrive, the same way the library's sections do.
     */
    fun search(query: String) {
        if (query == this.query && !(artists.value.isNullOrEmpty() && rows.value.isNullOrEmpty())) return
        this.query = query

        searchRequest?.cancel()
        searchRequest = DeemixClient.search(query) { result ->
            // No account, no service: the section simply is not there.
            val found = result.value?.let { toHits(it) } ?: emptyList()
            artists.value = found.filter { it.kind == DeezerHit.Kind.ARTIST }
            rows.value = found.filter { it.kind != DeezerHit.Kind.ARTIST }
        }

        if (usage.value == null) refreshUsage()
    }

    fun clear() {
        query = ""
        searchRequest?.cancel()
        artists.value = emptyList()
        rows.value = emptyList()
    }

    fun refreshUsage() {
        usageRequest?.cancel()
        usageRequest = DeemixClient.usage { result ->
            if (result.isOk) usage.value = result.value
        }
    }

    fun download(hit: DeezerHit) {
        if (states.value?.get(hit.key) == DeezerHit.State.WORKING) return
        setState(hit, DeezerHit.State.WORKING)

        val callback = DeemixCallback<DownloadMany> { result -> finished(hit, result) }
        when (hit.kind) {
            DeezerHit.Kind.TRACK -> DeemixClient.downloadTrack(hit.link.orEmpty(), callback)
            DeezerHit.Kind.ALBUM -> DeemixClient.downloadAlbum(hit.id, hit.title, callback)
            DeezerHit.Kind.ARTIST -> DeemixClient.downloadArtist(hit.id, hit.title, callback)
        }
    }

    private fun finished(hit: DeezerHit, result: DeemixResult<DownloadMany>) {
        val app = getApplication<Application>()
        val outcome = result.value

        if (outcome == null) {
            setState(hit, DeezerHit.State.IDLE)
            val message = when (result.failure) {
                DeemixResult.Failure.UNREACHABLE -> app.getString(R.string.deezer_download_unreachable)
                // The server words the quota refusal itself, in Russian, with the limit in it.
                DeemixResult.Failure.QUOTA -> result.message ?: app.getString(R.string.deemix_quota_exhausted)
                else -> app.getString(R.string.deezer_download_failed, result.message ?: "—")
            }
            toast(message)
            refreshUsage()
            return
        }

        val added = outcome.added ?: 0
        val skipped = outcome.skipped ?: 0
        setState(hit, if (added > 0 || hit.kind != DeezerHit.Kind.TRACK) DeezerHit.State.QUEUED else DeezerHit.State.IDLE)

        toast(when {
            hit.kind == DeezerHit.Kind.TRACK && added > 0 -> app.getString(R.string.deezer_download_queued)
            hit.kind == DeezerHit.Kind.TRACK -> app.getString(R.string.deezer_download_already)
            added == 0 -> app.getString(R.string.deezer_download_nothing_new)
            else -> app.resources.getQuantityString(R.plurals.deezer_download_added, added, added, skipped)
        })
        refreshUsage()
    }

    private fun setState(hit: DeezerHit, state: DeezerHit.State) {
        states.value = states.value.orEmpty() + (hit.key to state)
    }

    private fun toast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_LONG).show()
    }

    /*
     * Deezer ranks each kind on its own; the section takes the head of each.
     * Artists go in a rail that scrolls sideways, so there can be more of them;
     * under it the songs, which are what a search for a title is usually after,
     * then the records.
     */
    private fun toHits(results: DeezerResults): List<DeezerHit> {
        val search = results.search

        /*
         * Deezer's artist order is by name match, and a name is cheap: "twice"
         * put four unknown "Twice"s ahead of TWICE, and not the same four each
         * time. Who has the most fans is almost always who was meant.
         */
        val artists = search.artists.orEmpty()
                .sortedByDescending { it.fanCount ?: 0 }
                .mapNotNull { DeezerHit.artist(it) }
                .take(MAX_ARTISTS)
        val albums = search.albums.orEmpty().mapNotNull { DeezerHit.album(it) }.take(MAX_ALBUMS)
        val tracks = search.tracks.orEmpty().mapNotNull { DeezerHit.track(it, results.isInLibrary(it)) }.take(MAX_TRACKS)

        return artists + tracks + albums
    }

    override fun onCleared() {
        searchRequest?.cancel()
        usageRequest?.cancel()
    }

    private companion object {
        const val MAX_ARTISTS = 10
        const val MAX_ALBUMS = 3
        const val MAX_TRACKS = 6
    }
}
