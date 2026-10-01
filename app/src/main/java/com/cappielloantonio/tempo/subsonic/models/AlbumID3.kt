package com.cappielloantonio.tempo.subsonic.models

import android.os.Parcelable
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize
import java.time.Instant
import java.time.LocalDate
import java.util.*

@Keep
@Parcelize
open class AlbumID3 : Parcelable {
    var id: String? = null
    var name: String? = null
    var artist: String? = null
    var artistId: String? = null
    @SerializedName("coverArt")
    var coverArtId: String? = null
    var songCount: Int? = 0
    var duration: Int? = 0
    var playCount: Long? = 0
    var created: Date? = null
    var starred: Date? = null
    var year: Int = 0
    var genre: String? = null
    var played: Date? = Date(0)
    var userRating: Int? = 0
    var recordLabels: List<RecordLabel>? = null
    var musicBrainzId: String? = null
    var genres: List<ItemGenre>? = null
    var artists: List<ArtistID3>? = null
    var displayArtist: String? = null
    var releaseTypes: List<String>? = null
    var moods: List<String>? = null
    var sortName: String? = null
    var originalReleaseDate: ItemDate? = null
    var releaseDate: ItemDate? = null
    var isCompilation: Boolean? = null
    var discTitles: List<DiscTitle>? = null

    /**
     * The album credit to show.
     *
     * `artist` is the one name the server picked; OpenSubsonic's
     * `displayArtist` is the credit as tagged, so a split release reads
     * "A & B" rather than dropping half of it.
     */
    fun artistLine(): String? = displayArtist?.takeIf { it.isNotBlank() } ?: artist
}