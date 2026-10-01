package com.cappielloantonio.tempo.model

import android.os.Parcelable
import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * A rating that has not reached the server yet.
 *
 * Subsonic's setRating endpoint takes a plain id, so — unlike [Favorite], which
 * mirrors star's three separate parameters — one column covers songs, albums
 * and artists alike.
 */
@Keep
@Parcelize
@Entity(tableName = "rating")
data class Rating(
    @PrimaryKey
    @ColumnInfo(name = "timestamp")
    var timestamp: Long,

    @ColumnInfo(name = "itemId")
    val itemId: String,

    @ColumnInfo(name = "rating")
    val rating: Int,
) : Parcelable {
    override fun toString(): String = itemId
}
