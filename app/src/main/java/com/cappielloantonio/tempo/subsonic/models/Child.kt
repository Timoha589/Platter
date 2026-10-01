package com.cappielloantonio.tempo.subsonic.models

import android.os.Parcelable
import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize
import java.util.*

@Keep
@Parcelize
open class Child(
    @PrimaryKey
    @ColumnInfo(name = "id")
    open val id: String,
    @ColumnInfo(name = "parent_id")
    @SerializedName("parent")
    var parentId: String? = null,
    @ColumnInfo(name = "is_dir")
    var isDir: Boolean = false,
    @ColumnInfo
    var title: String? = null,
    @ColumnInfo
    var album: String? = null,
    @ColumnInfo
    var artist: String? = null,
    @ColumnInfo
    var track: Int? = null,
    @ColumnInfo
    var year: Int? = null,
    @ColumnInfo
    @SerializedName("genre")
    var genre: String? = null,
    @ColumnInfo(name = "cover_art_id")
    @SerializedName("coverArt")
    var coverArtId: String? = null,
    @ColumnInfo
    var size: Long? = null,
    @ColumnInfo(name = "content_type")
    var contentType: String? = null,
    @ColumnInfo
    var suffix: String? = null,
    @ColumnInfo("transcoding_content_type")
    var transcodedContentType: String? = null,
    @ColumnInfo(name = "transcoded_suffix")
    var transcodedSuffix: String? = null,
    @ColumnInfo
    var duration: Int? = null,
    @ColumnInfo("bitrate")
    @SerializedName("bitRate")
    var bitrate: Int? = null,
    @ColumnInfo
    var path: String? = null,
    @ColumnInfo(name = "is_video")
    @SerializedName("isVideo")
    var isVideo: Boolean = false,
    @ColumnInfo(name = "user_rating")
    var userRating: Int? = null,
    @ColumnInfo(name = "average_rating")
    var averageRating: Double? = null,
    @ColumnInfo(name = "play_count")
    var playCount: Long? = null,
    @ColumnInfo(name = "disc_number")
    var discNumber: Int? = null,
    @ColumnInfo
    var created: Date? = null,
    @ColumnInfo
    var starred: Date? = null,
    @ColumnInfo(name = "album_id")
    var albumId: String? = null,
    @ColumnInfo(name = "artist_id")
    var artistId: String? = null,
    @ColumnInfo
    var type: String? = null,
    @ColumnInfo(name = "bookmark_position")
    var bookmarkPosition: Long? = null,
    @ColumnInfo(name = "original_width")
    var originalWidth: Int? = null,
    @ColumnInfo(name = "original_height")
    var originalHeight: Int? = null,

    /*
     * OpenSubsonic additions to the Child object. Navidrome fills these in and
     * they were being parsed away: the multi-artist display strings, the
     * lossless stream description a hi-fi listener wants to see, and the
     * mediaType that separates a song from a podcast episode or an audiobook.
     *
     * List-valued OpenSubsonic fields (genres, artists, albumArtists,
     * contributors, moods, isrc) are deliberately left out: Child doubles as
     * the Room superclass for queue/download/chronology, and those would each
     * need a type converter and a column of JSON.
     */
    @ColumnInfo(name = "media_type")
    var mediaType: String? = null,
    @ColumnInfo(name = "display_artist")
    var displayArtist: String? = null,
    @ColumnInfo(name = "display_album_artist")
    var displayAlbumArtist: String? = null,
    @ColumnInfo(name = "display_composer")
    var displayComposer: String? = null,
    @ColumnInfo(name = "music_brainz_id")
    var musicBrainzId: String? = null,
    @ColumnInfo(name = "explicit_status")
    var explicitStatus: String? = null,
    @ColumnInfo(name = "sort_name")
    var sortName: String? = null,
    @ColumnInfo
    var comment: String? = null,
    @ColumnInfo
    var bpm: Int? = null,
    @ColumnInfo(name = "bit_depth")
    var bitDepth: Int? = null,
    @ColumnInfo(name = "sampling_rate")
    var samplingRate: Int? = null,
    @ColumnInfo(name = "channel_count")
    var channelCount: Int? = null,
    @ColumnInfo
    var played: Date? = null,

    /*
     * The loudness the server measured, which the replay gain setting plays the
     * track at. Unlike the list-valued fields it is a single object, so it
     * embeds as plain columns. Tags read from the stream are only a fallback:
     * a transcode or an MP4 container does not carry them through.
     */
    @Embedded(prefix = "replay_gain_")
    var replayGain: ReplayGain? = null
) : Parcelable {
    /**
     * The artist line to put in front of a reader.
     *
     * `artist` is a single name chosen by the server; `displayArtist` is the
     * credit as tagged, so a collaboration reads "A & B" instead of just "A".
     */
    fun artistLine(): String? = displayArtist?.takeIf { it.isNotBlank() } ?: artist

    /** The album-artist credit, same rule. */
    fun albumArtistLine(): String? = displayAlbumArtist?.takeIf { it.isNotBlank() } ?: artist
}