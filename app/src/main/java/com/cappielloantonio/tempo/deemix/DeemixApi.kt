package com.cappielloantonio.tempo.deemix

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/*
 * Deemix plus - the web front end the user downloads music through - runs
 * next to Navidrome and fills the same library. Its /api is the one the site's
 * own page calls: a JSON login that hands back a bearer token, a Deezer search,
 * and the download routes that apply the account's daily quota and skip what
 * the library already holds.
 *
 * Gson builds these without running a constructor, so a field the server left
 * out is null whatever its Kotlin default says; everything read from a
 * response is therefore nullable.
 */

@Keep
data class DeemixLoginBody(val username: String, val password: String)

@Keep
data class DeemixLogin(val token: String?, val username: String?, val role: String?)

@Keep
data class DeemixUsage(
        @SerializedName("is_admin") val isAdmin: Boolean?,
        @SerializedName("user_daily") val userDaily: Int?,
        @SerializedName("user_limit") val userLimit: Int?,
        @SerializedName("global_daily") val globalDaily: Int?,
        @SerializedName("global_limit") val globalLimit: Int?
) {
    /** Tracks this account may still queue today, or null when no limit applies. */
    fun remaining(): Int? {
        if (isAdmin == true) return null
        val own = (userLimit ?: 0) - (userDaily ?: 0)
        val global = (globalLimit ?: 0) - (globalDaily ?: 0)
        return maxOf(0, minOf(own, global))
    }

    /** Whether the server-wide limit, not this account's, is what runs out first. */
    fun globalIsTighter(): Boolean {
        val own = (userLimit ?: 0) - (userDaily ?: 0)
        val global = (globalLimit ?: 0) - (globalDaily ?: 0)
        return global < own
    }
}

@Keep
data class DeezerArtistRef(val id: Long?, val name: String?)

@Keep
data class DeezerAlbumRef(
        val id: Long?,
        val title: String?,
        @SerializedName("cover_medium") val coverMedium: String?
)

@Keep
data class DeezerTrack(
        val id: Long?,
        val title: String?,
        val link: String?,
        val duration: Int?,
        val rank: Long?,
        @SerializedName("track_position") val trackPosition: Int?,
        @SerializedName("disk_number") val diskNumber: Int?,
        val artist: DeezerArtistRef?,
        val album: DeezerAlbumRef?
)

@Keep
data class DeezerAlbum(
        val id: Long?,
        val title: String?,
        @SerializedName("cover_medium") val coverMedium: String?,
        @SerializedName("cover_xl") val coverXl: String?,
        @SerializedName("nb_tracks") val trackCount: Int?,
        @SerializedName("record_type") val recordType: String?,
        @SerializedName("release_date") val releaseDate: String?,
        val artist: DeezerArtistRef?
)

@Keep
data class DeezerArtist(
        val id: Long?,
        val name: String?,
        @SerializedName("picture_medium") val pictureMedium: String?,
        @SerializedName("picture_xl") val pictureXl: String?,
        @SerializedName("nb_album") val albumCount: Int?,
        @SerializedName("nb_fan") val fanCount: Long?
)

@Keep
data class DeezerSearch(
        val success: Boolean?,
        val tracks: List<DeezerTrack>?,
        val artists: List<DeezerArtist>?,
        val albums: List<DeezerAlbum>?
)

@Keep
data class LibraryCheckItem(val id: String, val artist: String, val title: String)

@Keep
data class LibraryCheckBody(val tracks: List<LibraryCheckItem>)

@Keep
data class LibraryCheck(val results: Map<String, Boolean>?)

@Keep
data class DownloadUrlBody(val url: String)

@Keep
data class DownloadAlbumBody(val id: Long, val title: String)

@Keep
data class DownloadArtistBody(val id: Long, val name: String)

/** What /download answers for a single track. */
@Keep
data class DownloadOne(val success: Boolean?, val error: String?)

/** What /download-album and /download-artist answer. */
@Keep
data class DownloadMany(
        val success: Boolean?,
        val added: Int?,
        val skipped: Int?,
        @SerializedName("total_tracks") val total: Int?,
        val error: String?,
        val message: String?
)

interface DeemixService {
    @POST("api/auth/login")
    fun login(@Body body: DeemixLoginBody): Call<DeemixLogin>

    @GET("api/auth/usage")
    fun usage(@Header("Authorization") auth: String): Call<DeemixUsage>

    @GET("api/search-all")
    fun search(@Header("Authorization") auth: String, @Query("q") query: String): Call<DeezerSearch>

    @POST("api/navidrome/check-bulk")
    fun checkLibrary(@Header("Authorization") auth: String, @Body body: LibraryCheckBody): Call<LibraryCheck>

    @POST("api/download")
    fun downloadTrack(@Header("Authorization") auth: String, @Body body: DownloadUrlBody): Call<DownloadOne>

    @POST("api/download-album")
    fun downloadAlbum(@Header("Authorization") auth: String, @Body body: DownloadAlbumBody): Call<DownloadMany>

    @POST("api/download-artist")
    fun downloadArtist(@Header("Authorization") auth: String, @Body body: DownloadArtistBody): Call<DownloadMany>
}
