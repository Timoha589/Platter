package com.cappielloantonio.tempo.subsonic.models

import android.os.Parcelable
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize
import java.util.*

@Keep
@Parcelize
open class ArtistID3 : Parcelable {
    var id: String? = null
    var name: String? = null
    @SerializedName("coverArt")
    var coverArtId: String? = null
    var albumCount = 0
    var starred: Date? = null

    /* OpenSubsonic additions. `roles` says what the artist did on a release -
       it is what lets a "composer" or "conductor" credit be told apart from a
       performer credit. */
    var musicBrainzId: String? = null
    var sortName: String? = null
    var roles: List<String>? = null
    var userRating: Int? = null
    var averageRating: Double? = null
}