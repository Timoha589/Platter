package com.cappielloantonio.tempo.deemix

import androidx.annotation.Keep
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/*
 * Deezer's public catalogue API - the one deemix itself reads - for the
 * artist and album pages. It needs no account, answers in half a second, and
 * has what Deemix plus does not pass on: an artist's most played tracks, and
 * the discography without every album's tracklist fetched up front (the
 * site's own discography call takes seconds on a big artist for exactly that).
 *
 * What touches the account - downloads, the library check, previews - still
 * goes through Deemix plus.
 */

@Keep
data class DeezerList<T>(val data: List<T>?, val error: DeezerError?)

@Keep
data class DeezerError(val message: String?)

interface DeezerCatalogService {
    @GET("artist/{id}")
    fun artist(@Path("id") id: Long): Call<DeezerArtist>

    @GET("artist/{id}/top")
    fun top(@Path("id") id: Long, @Query("limit") limit: Int): Call<DeezerList<DeezerTrack>>

    @GET("artist/{id}/albums")
    fun albums(@Path("id") id: Long, @Query("limit") limit: Int): Call<DeezerList<DeezerAlbum>>

    @GET("album/{id}")
    fun album(@Path("id") id: Long): Call<DeezerAlbum>

    @GET("album/{id}/tracks")
    fun albumTracks(@Path("id") id: Long, @Query("limit") limit: Int): Call<DeezerList<DeezerTrack>>

    /** Tracks only - for finding one song, where search-all's artists and albums are noise. */
    @GET("search/track")
    fun searchTracks(@Query("q") query: String, @Query("limit") limit: Int): Call<DeezerList<DeezerTrack>>
}

internal object DeezerCatalog {
    val service: DeezerCatalogService by lazy {
        Retrofit.Builder()
                .baseUrl("https://api.deezer.com/")
                .addConverterFactory(GsonConverterFactory.create())
                .client(OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(20, TimeUnit.SECONDS)
                        .build())
                .build()
                .create(DeezerCatalogService::class.java)
    }
}
