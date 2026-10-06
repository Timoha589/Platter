package com.platter.desktop.deemix

import com.platter.desktop.i18n.t
import java.text.NumberFormat
import java.util.Locale

/** One row of the Deezer section: something the site could download. */
data class DeezerHit(
    val kind: Kind,
    val id: Long,
    val title: String,
    /** The artist of an album or a track; empty for an artist. */
    val artist: String,
    /** The album of a track. */
    val detail: String,
    val image: String?,
    val link: String?,
    val inLibrary: Boolean,
    /** The second line as a page wants it, instead of the one search builds. */
    val caption: String? = null,
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
            return DeezerHit(Kind.ALBUM, id, title, album.artist?.name.orEmpty(), "", album.coverMedium, null, false, caption)
        }

        /** [cover] stands in for tracks that come without their album - an album's own tracklist. */
        fun track(track: DeezerTrack, inLibrary: Boolean, caption: String? = null, cover: String? = null): DeezerHit? {
            val id = track.id ?: return null
            val title = track.title?.takeIf { it.isNotBlank() } ?: return null
            if (track.link == null) return null
            return DeezerHit(Kind.TRACK, id, title, track.artist?.name.orEmpty(), track.album?.title.orEmpty(), track.album?.coverMedium ?: cover, track.link, inLibrary, caption)
        }
    }
}

/** What a search for Deezer's catalogue turned up: artists for a rail, and rows of tracks then albums. */
class DeezerHits(val artists: List<DeezerHit>, val rows: List<DeezerHit>) {
    val isEmpty: Boolean get() = artists.isEmpty() && rows.isEmpty()

    companion object {
        private const val MAX_ARTISTS = 10
        private const val MAX_ALBUMS = 3
        private const val MAX_TRACKS = 6

        /**
         * Deezer ranks each kind on its own; the section takes the head of each. Artists go in a rail that scrolls
         * sideways, so there can be more of them; under it the songs, which are what a search for a title is
         * usually after, then the records.
         *
         * Deezer's artist order is by name match, and a name is cheap: "twice" put four unknown "Twice"s ahead of
         * TWICE. Who has the most fans is almost always who was meant.
         */
        fun of(results: DeezerResults): DeezerHits {
            val search = results.search
            val artists = search.artists.orEmpty().sortedByDescending { it.fanCount ?: 0 }.mapNotNull { DeezerHit.artist(it) }.take(MAX_ARTISTS)
            val albums = search.albums.orEmpty().mapNotNull { DeezerHit.album(it) }.take(MAX_ALBUMS)
            val tracks = search.tracks.orEmpty().mapNotNull { DeezerHit.track(it, results.isInLibrary(it)) }.take(MAX_TRACKS)
            return DeezerHits(artists, tracks + albums)
        }
    }
}

/** One Deezer artist's or album's page, ready to show. */
class DeezerPage(
    val kind: DeezerHit.Kind,
    val title: String,
    val image: String?,
    val caption: String,
    val tracks: List<DeezerHit>,
    val albums: List<DeezerHit>,
) {
    companion object {
        fun ofArtist(loaded: DeezerArtistPage): DeezerPage {
            val artist = loaded.artist
            // By an artist's own page, the artist is a given: a track is placed by its record.
            val tracks = loaded.top.mapNotNull { DeezerHit.track(it, loaded.inLibrary[it.id.toString()] == true, caption = it.album?.title.orEmpty()) }
            val albums = loaded.albums.mapNotNull { DeezerHit.album(it, caption = releaseCaption(it)) }

            val fans = artist.fanCount ?: 0
            val caption = if (fans > 0) t("Artist · %s fans", compact(fans)) else t("Artist")
            return DeezerPage(DeezerHit.Kind.ARTIST, artist.name.orEmpty(), artist.pictureXl ?: artist.pictureMedium, caption, tracks, albums)
        }

        fun ofAlbum(loaded: DeezerAlbumPage): DeezerPage {
            val album = loaded.album
            // An album's tracklist comes without the album: the rows borrow its cover.
            val tracks = loaded.tracks.mapNotNull {
                DeezerHit.track(it, loaded.inLibrary[it.id.toString()] == true, caption = trackCaption(it.artist?.name, it.duration), cover = album.coverMedium)
            }
            val caption = listOfNotNull(
                releaseType(album.recordType),
                album.artist?.name?.takeIf { it.isNotBlank() },
                album.releaseDate?.take(4)?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            return DeezerPage(DeezerHit.Kind.ALBUM, album.title.orEmpty(), album.coverXl ?: album.coverMedium, caption, tracks, emptyList())
        }

        private fun releaseCaption(album: DeezerAlbum): String =
            listOfNotNull(releaseType(album.recordType), album.releaseDate?.take(4)?.takeIf { it.isNotBlank() }).joinToString(" · ")

        fun releaseType(recordType: String?): String = when (recordType) {
            "single" -> t("Single")
            "ep" -> t("EP")
            "compile" -> t("Compilation")
            else -> t("Album")
        }

        private fun trackCaption(artist: String?, duration: Int?): String {
            val time = duration?.takeIf { it > 0 }?.let { "%d:%02d".format(it / 60, it % 60) }
            return listOfNotNull(artist?.takeIf { it.isNotBlank() }, time).joinToString(" · ")
        }

        /** "900K", "1.2M". */
        fun compact(n: Long): String = NumberFormat.getCompactNumberInstance(Locale.US, NumberFormat.Style.SHORT).format(n)
    }
}

/** How the site's account and quota read on screen - the same words in settings and in search. */
object DeemixWords {
    fun role(role: String?): String = when (role) {
        "admin" -> t("administrator")
        "special" -> t("extended limit")
        else -> t("user")
    }

    fun quota(usage: DeemixUsage?): String {
        if (usage == null) return ""
        val remaining = usage.remaining() ?: return t("No daily limit · %d downloaded today", usage.userDaily ?: 0)
        return when {
            remaining == 0 -> t("Daily limit reached")
            usage.globalIsTighter() -> t("%d left today · server limit", remaining)
            else -> t("%d of %d left today", remaining, usage.userLimit ?: 0)
        }
    }
}
