package com.platter.desktop.deemix

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/*
 * Deemix plus - the web front end the user downloads music through - runs next to Navidrome and fills the same
 * library. Its /api is the one the site's own page calls: a JSON login that hands back a bearer token, a Deezer
 * search, and the download routes that apply the account's daily quota and skip what the library already holds.
 *
 * Ported from the Android app's `DeemixApi`. Gson builds these without running a constructor, so a field the server
 * left out is null whatever its Kotlin default says; everything read from a response is therefore nullable.
 */

data class DeemixLoginBody(val username: String, val password: String)

data class DeemixLogin(val token: String?, val username: String?, val role: String?)

data class DeemixUsage(
    @SerializedName("is_admin") val isAdmin: Boolean?,
    @SerializedName("user_daily") val userDaily: Int?,
    @SerializedName("user_limit") val userLimit: Int?,
    @SerializedName("global_daily") val globalDaily: Int?,
    @SerializedName("global_limit") val globalLimit: Int?,
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

data class DeezerArtistRef(val id: Long?, val name: String?)

data class DeezerAlbumRef(val id: Long?, val title: String?, @SerializedName("cover_medium") val coverMedium: String?)

data class DeezerTrack(
    val id: Long?,
    val title: String?,
    val link: String?,
    val duration: Int?,
    val rank: Long?,
    @SerializedName("track_position") val trackPosition: Int?,
    @SerializedName("disk_number") val diskNumber: Int?,
    val artist: DeezerArtistRef?,
    val album: DeezerAlbumRef?,
)

data class DeezerAlbum(
    val id: Long?,
    val title: String?,
    @SerializedName("cover_medium") val coverMedium: String?,
    @SerializedName("cover_xl") val coverXl: String?,
    @SerializedName("nb_tracks") val trackCount: Int?,
    @SerializedName("record_type") val recordType: String?,
    @SerializedName("release_date") val releaseDate: String?,
    val artist: DeezerArtistRef?,
)

data class DeezerArtist(
    val id: Long?,
    val name: String?,
    @SerializedName("picture_medium") val pictureMedium: String?,
    @SerializedName("picture_xl") val pictureXl: String?,
    @SerializedName("nb_album") val albumCount: Int?,
    @SerializedName("nb_fan") val fanCount: Long?,
)

data class DeezerSearch(
    val success: Boolean?,
    val tracks: List<DeezerTrack>?,
    val artists: List<DeezerArtist>?,
    val albums: List<DeezerAlbum>?,
)

data class LibraryCheckItem(val id: String, val artist: String, val title: String)

data class LibraryCheckBody(val tracks: List<LibraryCheckItem>)

data class LibraryCheck(val results: Map<String, Boolean>?)

data class DownloadUrlBody(val url: String)

data class DownloadAlbumBody(val id: Long, val title: String)

data class DownloadArtistBody(val id: Long, val name: String)

/** What /download answers for a single track. */
data class DownloadOne(val success: Boolean?, val error: String?)

/** What /download-album and /download-artist answer. */
data class DownloadMany(
    val success: Boolean?,
    val added: Int?,
    val skipped: Int?,
    @SerializedName("total_tracks") val total: Int?,
    val error: String?,
    val message: String?,
)

interface DeemixService {
    @POST("api/auth/login")
    suspend fun login(@Body body: DeemixLoginBody): Response<DeemixLogin>

    @GET("api/auth/usage")
    suspend fun usage(@Header("Authorization") auth: String): Response<DeemixUsage>

    @GET("api/search-all")
    suspend fun search(@Header("Authorization") auth: String, @Query("q") query: String): Response<DeezerSearch>

    @POST("api/navidrome/check-bulk")
    suspend fun checkLibrary(@Header("Authorization") auth: String, @Body body: LibraryCheckBody): Response<LibraryCheck>

    @POST("api/download")
    suspend fun downloadTrack(@Header("Authorization") auth: String, @Body body: DownloadUrlBody): Response<DownloadOne>

    @POST("api/download-album")
    suspend fun downloadAlbum(@Header("Authorization") auth: String, @Body body: DownloadAlbumBody): Response<DownloadMany>

    @POST("api/download-artist")
    suspend fun downloadArtist(@Header("Authorization") auth: String, @Body body: DownloadArtistBody): Response<DownloadMany>
}

data class DeezerList<T>(val data: List<T>?, val error: DeezerError?)

data class DeezerError(val message: String?)

/**
 * Deezer's public catalogue API - the one deemix itself reads - for the artist and album pages. It needs no account,
 * answers in half a second, and has what Deemix plus does not pass on: an artist's most played tracks, and the
 * discography without every album's tracklist fetched up front.
 */
interface DeezerCatalogService {
    @GET("artist/{id}")
    suspend fun artist(@Path("id") id: Long): Response<DeezerArtist>

    @GET("artist/{id}/top")
    suspend fun top(@Path("id") id: Long, @Query("limit") limit: Int): Response<DeezerList<DeezerTrack>>

    @GET("artist/{id}/albums")
    suspend fun albums(@Path("id") id: Long, @Query("limit") limit: Int): Response<DeezerList<DeezerAlbum>>

    @GET("album/{id}")
    suspend fun album(@Path("id") id: Long): Response<DeezerAlbum>

    @GET("album/{id}/tracks")
    suspend fun albumTracks(@Path("id") id: Long, @Query("limit") limit: Int): Response<DeezerList<DeezerTrack>>
}
