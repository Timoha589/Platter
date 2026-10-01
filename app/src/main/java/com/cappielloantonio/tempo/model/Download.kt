package com.cappielloantonio.tempo.model

import androidx.annotation.Keep
import androidx.media3.common.MediaItem
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.cappielloantonio.tempo.subsonic.models.Child
import com.cappielloantonio.tempo.subsonic.models.ReplayGain
import kotlinx.parcelize.Parcelize
import java.util.Date

@Keep
@Parcelize
@Entity(tableName = "download")
class Download(@PrimaryKey override val id: String) : Child(id) {
    @ColumnInfo(name = "playlist_id")
    var playlistId: String? = null

    @ColumnInfo(name = "playlist_name")
    var playlistName: String? = null

    @ColumnInfo(name = "download_state", defaultValue = "1")
    var downloadState: Int = 0

    @ColumnInfo(name = "download_uri", defaultValue = "")
    var downloadUri: String? = null

    /*
     * Why the track is on the device, which is what decides what may take it
     * off again: a download the user asked for stays until they remove it, one
     * made because the track is liked goes when it is unliked, and one smart
     * download made gives way once the cache runs past its size.
     */
    @ColumnInfo(name = "download_source", defaultValue = "0")
    var downloadSource: Int = SOURCE_MANUAL

    /* When the track last started playing: smart download lets the stalest go first. */
    @ColumnInfo(name = "last_played_at", defaultValue = "0")
    var lastPlayedAt: Long = 0

    /*
     * When the download was asked for, which is what the downloads screen puts
     * newest first. A whole album or playlist shares one, so its tracks keep
     * their own order under it. Zero on a record from before the column, until
     * DownloaderManager fills it in from the download index.
     */
    @ColumnInfo(name = "downloaded_at", defaultValue = "0")
    var downloadedAt: Long = 0

    constructor(child: Child) : this(child.id) {
        parentId = child.parentId
        isDir = child.isDir
        title = child.title
        album = child.album
        artist = child.artist
        track = child.track
        year = child.year
        genre = child.genre
        coverArtId = child.coverArtId
        size = child.size
        contentType = child.contentType
        suffix = child.suffix
        transcodedContentType = child.transcodedContentType
        transcodedSuffix = child.transcodedSuffix
        duration = child.duration
        bitrate = child.bitrate
        path = child.path
        isVideo = child.isVideo
        userRating = child.userRating
        averageRating = child.averageRating
        playCount = child.playCount
        discNumber = child.discNumber
        created = child.created
        starred = child.starred
        albumId = child.albumId
        artistId = child.artistId
        type = child.type
        bookmarkPosition = child.bookmarkPosition
        originalWidth = child.originalWidth
        originalHeight = child.originalHeight
        mediaType = child.mediaType
        displayArtist = child.displayArtist
        displayAlbumArtist = child.displayAlbumArtist
        displayComposer = child.displayComposer
        musicBrainzId = child.musicBrainzId
        explicitStatus = child.explicitStatus
        sortName = child.sortName
        comment = child.comment
        bpm = child.bpm
        bitDepth = child.bitDepth
        samplingRate = child.samplingRate
        channelCount = child.channelCount
        played = child.played
        replayGain = child.replayGain
    }

    /*
     * From what the player holds, for downloads started from the player
     * service - which has the queue's MediaItems and nothing else.
     */
    constructor(mediaItem: MediaItem) : this(mediaItem.mediaMetadata.extras!!.getString("id")!!) {
        val extras = mediaItem.mediaMetadata.extras!!

        parentId = extras.getString("parentId")
        isDir = extras.getBoolean("isDir")
        title = extras.getString("title")
        album = extras.getString("album")
        artist = extras.getString("artist")
        track = extras.getInt("track")
        year = extras.getInt("year")
        genre = extras.getString("genre")
        coverArtId = extras.getString("coverArtId")
        size = extras.getLong("size")
        contentType = extras.getString("contentType")
        suffix = extras.getString("suffix")
        transcodedContentType = extras.getString("transcodedContentType")
        transcodedSuffix = extras.getString("transcodedSuffix")
        duration = extras.getInt("duration")
        bitrate = extras.getInt("bitrate")
        path = extras.getString("path")
        isVideo = extras.getBoolean("isVideo")
        userRating = extras.getInt("userRating")
        averageRating = extras.getDouble("averageRating")
        playCount = extras.getLong("playCount")
        discNumber = extras.getInt("discNumber")
        created = extras.getLong("created").takeIf { it != 0L }?.let { Date(it) }
        starred = extras.getLong("starred").takeIf { it != 0L }?.let { Date(it) }
        albumId = extras.getString("albumId")
        artistId = extras.getString("artistId")
        type = extras.getString("type")
        replayGain = ReplayGain.from(extras)
    }

    companion object {
        const val SOURCE_MANUAL = 0
        const val SOURCE_SMART = 1
        const val SOURCE_LIKED = 2
    }
}
