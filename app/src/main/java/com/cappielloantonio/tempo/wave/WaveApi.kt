package com.cappielloantonio.tempo.wave

import androidx.annotation.Keep
import com.cappielloantonio.tempo.subsonic.models.Child
import com.google.gson.annotations.SerializedName
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.QueryMap

/*
 * The wave service that runs next to the daylist script on the server (see
 * wave/README.md in the Daylist project). Every call is signed the way a
 * Subsonic call is - u, t and s in the query - and the service checks that
 * signature with Navidrome, so it needs nothing the app does not already have.
 *
 * Gson builds these without running a constructor, so a field the server left
 * out is null whatever its Kotlin default says; everything read from a
 * response is therefore nullable.
 */

@Keep
data class WaveBatch(
        val session: String?,
        val batch: Int?,
        val slot: String?,
        val vibe: WaveVibe?,
        val tracks: List<WaveTrack>?
)

@Keep
data class WaveVibe(
        val label: String?,
        val next: String?,
        val transition: Boolean?
)

/**
 * @param song   the Subsonic song object, as getSong returns it
 * @param energy the track's energy as a quantile of the listener's own likes, 0..1
 * @param tempo  beats per minute
 */
@Keep
data class WaveTrack(
        val id: String?,
        val song: Child?,
        val energy: Double?,
        val tempo: Double?,
        val source: String?
)

@Keep
data class WaveEvent(
        val id: String,
        val type: String,
        @SerializedName("position_ms") val positionMs: Long = 0,
        @SerializedName("duration_ms") val durationMs: Long = 0
) {
    companion object {
        const val COMPLETE = "complete"
        const val SKIP = "skip"
        const val LIKE = "like"
        const val UNLIKE = "unlike"
        const val DISLIKE = "dislike"
    }
}

@Keep
data class WaveNextBody(val session: String, val events: List<WaveEvent>)

/**
 * The library searched by its embedded lyrics (lyrics.py in the service).
 *
 * @param indexing whether the service is still reading the library's lyrics -
 *                 on the very first search, for about a minute
 */
@Keep
data class WaveLyrics(
        val indexing: Boolean?,
        val indexed: Int?,
        val results: List<WaveLyric>?
)

/** @param snippet the lines the query was found in, joined with " / " */
@Keep
data class WaveLyric(
        val id: String?,
        val snippet: String?,
        val song: Child?
)

interface WaveService {
    @POST("start")
    fun start(@QueryMap auth: Map<String, String>): Call<WaveBatch>

    @POST("next")
    fun next(@QueryMap auth: Map<String, String>, @Body body: WaveNextBody): Call<WaveBatch>

    @POST("feedback")
    fun feedback(@QueryMap auth: Map<String, String>, @Body body: WaveNextBody): Call<ResponseBody>

    @POST("prepare")
    fun prepare(@QueryMap auth: Map<String, String>): Call<ResponseBody>

    @GET("lyrics/search")
    fun searchLyrics(@QueryMap auth: Map<String, String>, @Query("q") query: String, @Query("limit") limit: Int): Call<WaveLyrics>
}
