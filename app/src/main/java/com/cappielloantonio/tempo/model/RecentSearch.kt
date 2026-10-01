package com.cappielloantonio.tempo.model

import android.os.Parcelable
import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Keep
@Parcelize
@Entity(tableName = "recent_search")
data class RecentSearch @JvmOverloads constructor(
    @PrimaryKey
    @ColumnInfo(name = "search")
    var search: String,

    /**
     * When the search was last run. The list is called "recent" and was ordered
     * alphabetically, so the newest query surfaced wherever its first letter put
     * it. Rows written before this column existed default to 0 and sort last,
     * which is the right place for a search of unknown age.
     */
    @ColumnInfo(name = "timestamp", defaultValue = "0")
    var timestamp: Long = System.currentTimeMillis()
) : Parcelable
