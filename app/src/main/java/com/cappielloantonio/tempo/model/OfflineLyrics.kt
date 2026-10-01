package com.cappielloantonio.tempo.model

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The words kept with a downloaded track, so the lyrics page has something to
 * show when the server cannot be asked.
 *
 * One of the two columns is filled, depending on which way the server was
 * asked: `lyrics_list` holds the songLyrics extension's answer as JSON, and
 * `lyrics` the plain text of the older getLyrics. A row with neither is an
 * answer too - the server has no words for this track - and keeps it from
 * being asked again.
 */
@Keep
@Entity(tableName = "offline_lyrics")
data class OfflineLyrics(
    @PrimaryKey
    @ColumnInfo(name = "id")
    var id: String,

    @ColumnInfo(name = "lyrics")
    var lyrics: String?,

    @ColumnInfo(name = "lyrics_list")
    var lyricsList: String?
)
