package com.cappielloantonio.tempo.subsonic.base

import androidx.annotation.Keep
import com.cappielloantonio.tempo.subsonic.models.SubsonicResponse
import com.google.gson.annotations.SerializedName

@Keep
class ApiResponse {
    /*
     * Nullable, not lateinit. Gson leaves the field untouched when the body
     * carries no "subsonic-response" - an HTML error page from a reverse proxy,
     * a 200 with an empty body - and reading a lateinit that was never assigned
     * throws. Retrofit runs onResponse on the main thread, so that throw took
     * the app down instead of being handled as the failed request it was.
     */
    @SerializedName("subsonic-response")
    var subsonicResponse: SubsonicResponse? = null
}