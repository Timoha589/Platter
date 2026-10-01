package com.cappielloantonio.tempo.viewmodel

import android.app.Application
import android.icu.text.CompactDecimalFormat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.deemix.DeemixClient
import com.cappielloantonio.tempo.deemix.DeemixRequest
import com.cappielloantonio.tempo.deemix.DeezerAlbum
import com.cappielloantonio.tempo.deemix.DeezerAlbumPage
import com.cappielloantonio.tempo.deemix.DeezerArtistPage
import java.util.Locale

/** One Deezer artist's or album's page, loaded once for the life of the screen. */
class DeezerPageViewModel(application: Application) : AndroidViewModel(application) {
    class Page(
            val title: String,
            val image: String?,
            val caption: String,
            val tracks: List<DeezerHit>,
            val albums: List<DeezerHit>
    )

    private val page = MutableLiveData<Page?>(null)
    private val failed = MutableLiveData(false)
    private var request: DeemixRequest? = null

    /** Whether the artist's track list shows everything rather than the first few. */
    var tracksExpanded = false

    fun getPage(): LiveData<Page?> = page

    fun getFailed(): LiveData<Boolean> = failed

    fun load(kind: DeezerHit.Kind, id: Long) {
        if (page.value != null || request != null) return
        failed.value = false

        request = if (kind == DeezerHit.Kind.ALBUM) {
            DeemixClient.albumPage(id) { result ->
                request = null
                result.value?.let { page.value = albumPage(it) } ?: run { failed.value = true }
            }
        } else {
            DeemixClient.artistPage(id) { result ->
                request = null
                result.value?.let { page.value = artistPage(it) } ?: run { failed.value = true }
            }
        }
    }

    fun retry(kind: DeezerHit.Kind, id: Long) {
        request?.cancel()
        request = null
        page.value = null
        load(kind, id)
    }

    private fun artistPage(loaded: DeezerArtistPage): Page {
        val app = getApplication<Application>()
        val artist = loaded.artist

        // By an artist's own page, the artist is a given: a track is placed by its record.
        val tracks = loaded.top.mapNotNull {
            DeezerHit.track(it, loaded.inLibrary[it.id.toString()] == true, caption = it.album?.title.orEmpty())
        }
        val albums = loaded.albums.mapNotNull { DeezerHit.album(it, caption = releaseCaption(it)) }

        val role = app.getString(R.string.label_role_artist)
        val fans = artist.fanCount ?: 0
        val caption = if (fans > 0) {
            val count = CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT).format(fans)
            app.getString(R.string.deezer_page_caption, role,
                    app.resources.getQuantityString(R.plurals.deezer_fans, if (fans < 1000) fans.toInt() else 1000, count))
        } else {
            role
        }

        return Page(artist.name.orEmpty(), artist.pictureXl ?: artist.pictureMedium, caption, tracks, albums)
    }

    private fun albumPage(loaded: DeezerAlbumPage): Page {
        val album = loaded.album
        val cover = album.coverXl ?: album.coverMedium

        // An album's tracklist comes without the album: the rows borrow its cover.
        val tracks = loaded.tracks.mapNotNull {
            DeezerHit.track(it, loaded.inLibrary[it.id.toString()] == true,
                    caption = trackCaption(it.artist?.name, it.duration), cover = album.coverMedium)
        }

        val caption = listOfNotNull(
                releaseType(album.recordType),
                album.artist?.name?.takeIf { it.isNotBlank() },
                album.releaseDate?.take(4)?.takeIf { it.isNotBlank() }
        ).joinToString(" · ")

        return Page(album.title.orEmpty(), cover, caption, tracks, emptyList())
    }

    private fun releaseCaption(album: DeezerAlbum): String =
            listOfNotNull(releaseType(album.recordType), album.releaseDate?.take(4)?.takeIf { it.isNotBlank() })
                    .joinToString(" · ")

    private fun releaseType(recordType: String?): String = getApplication<Application>().getString(when (recordType) {
        "single" -> R.string.deezer_type_single
        "ep" -> R.string.deezer_type_ep
        "compile" -> R.string.deezer_type_compilation
        else -> R.string.label_role_album
    })

    private fun trackCaption(artist: String?, duration: Int?): String {
        val time = duration?.takeIf { it > 0 }?.let { "%d:%02d".format(it / 60, it % 60) }
        return listOfNotNull(artist?.takeIf { it.isNotBlank() }, time).joinToString(" · ")
    }

    override fun onCleared() {
        request?.cancel()
    }
}
