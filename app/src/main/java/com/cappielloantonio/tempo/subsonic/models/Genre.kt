package com.cappielloantonio.tempo.subsonic.models

import android.os.Parcelable
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Keep
@Parcelize
class Genre : Parcelable {
    @SerializedName("value")
    var genre: String? = null
    var songCount = 0
    var albumCount = 0

    /*
     * Every name on the server this genre stands for, when it is more than
     * one - see GenreNames.merge. Null for a genre as the server sent it.
     */
    var sources: ArrayList<String>? = null

    /* The server names to ask for this genre's tracks by. */
    fun names(): List<String> = sources ?: listOfNotNull(genre)
}