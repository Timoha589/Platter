package com.platter.desktop.api

import retrofit2.http.Field
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.QueryMap

/*
 * The endpoints the desktop client calls, as suspend functions. Same shape as
 * the Android app's per-area services (`@QueryMap` for the auth parameters,
 * `@Query` for the endpoint's own) collapsed into one interface.
 */
interface SubsonicService {
    @GET("ping")
    suspend fun ping(@QueryMap params: Map<String, String>): ApiResponse

    @GET("getArtists")
    suspend fun getArtists(@QueryMap params: Map<String, String>): ApiResponse

    @GET("getArtist")
    suspend fun getArtist(@QueryMap params: Map<String, String>, @Query("id") id: String): ApiResponse

    @GET("getAlbum")
    suspend fun getAlbum(@QueryMap params: Map<String, String>, @Query("id") id: String): ApiResponse

    @GET("getAlbumList2")
    suspend fun getAlbumList2(
        @QueryMap params: Map<String, String>,
        @Query("type") type: String,
        @Query("size") size: Int,
        @Query("offset") offset: Int,
        @Query("genre") genre: String?,
        @Query("fromYear") fromYear: Int?,
        @Query("toYear") toYear: Int?,
    ): ApiResponse

    @GET("getRandomSongs")
    suspend fun getRandomSongs(@QueryMap params: Map<String, String>, @Query("size") size: Int): ApiResponse

    @GET("getStarred2")
    suspend fun getStarred2(@QueryMap params: Map<String, String>): ApiResponse

    @GET("search3")
    suspend fun search3(
        @QueryMap params: Map<String, String>,
        @Query("query") query: String,
        @Query("songCount") songCount: Int,
        @Query("albumCount") albumCount: Int,
        @Query("artistCount") artistCount: Int,
    ): ApiResponse

    @GET("getPlaylists")
    suspend fun getPlaylists(@QueryMap params: Map<String, String>): ApiResponse

    @GET("getPlaylist")
    suspend fun getPlaylist(@QueryMap params: Map<String, String>, @Query("id") id: String): ApiResponse

    @GET("getLyricsBySongId")
    suspend fun getLyricsBySongId(@QueryMap params: Map<String, String>, @Query("id") id: String): ApiResponse

    @GET("getLyrics")
    suspend fun getLyrics(@QueryMap params: Map<String, String>, @Query("artist") artist: String?, @Query("title") title: String?): ApiResponse

    @GET("setRating")
    suspend fun setRating(@QueryMap params: Map<String, String>, @Query("id") id: String, @Query("rating") rating: Int): ApiResponse

    @GET("star")
    suspend fun star(@QueryMap params: Map<String, String>, @Query("id") id: String?, @Query("albumId") albumId: String?, @Query("artistId") artistId: String?): ApiResponse

    @GET("unstar")
    suspend fun unstar(@QueryMap params: Map<String, String>, @Query("id") id: String?, @Query("albumId") albumId: String?, @Query("artistId") artistId: String?): ApiResponse

    @GET("scrobble")
    suspend fun scrobble(
        @QueryMap params: Map<String, String>,
        @Query("id") id: String,
        @Query("submission") submission: Boolean,
        @Query("time") time: Long?,
    ): ApiResponse

    @GET("getTopSongs")
    suspend fun getTopSongs(@QueryMap params: Map<String, String>, @Query("artist") artist: String, @Query("count") count: Int): ApiResponse

    @GET("getSimilarSongs2")
    suspend fun getSimilarSongs2(@QueryMap params: Map<String, String>, @Query("id") id: String, @Query("count") count: Int): ApiResponse

    @GET("getArtistInfo2")
    suspend fun getArtistInfo2(@QueryMap params: Map<String, String>, @Query("id") id: String): ApiResponse

    @GET("getGenres")
    suspend fun getGenres(@QueryMap params: Map<String, String>): ApiResponse

    @GET("getSongsByGenre")
    suspend fun getSongsByGenre(
        @QueryMap params: Map<String, String>,
        @Query("genre") genre: String,
        @Query("count") count: Int,
        @Query("offset") offset: Int,
    ): ApiResponse

    @GET("createPlaylist")
    suspend fun createPlaylist(
        @QueryMap params: Map<String, String>,
        @Query("playlistId") playlistId: String?,
        @Query("name") name: String?,
        @Query("songId") songIds: List<String>,
    ): ApiResponse

    @GET("updatePlaylist")
    suspend fun updatePlaylist(
        @QueryMap params: Map<String, String>,
        @Query("playlistId") playlistId: String,
        @Query("name") name: String?,
        @Query("public") isPublic: Boolean?,
        @Query("songIdToAdd") songIdsToAdd: List<String>,
        @Query("songIndexToRemove") songIndexesToRemove: List<Int>,
    ): ApiResponse

    @GET("deletePlaylist")
    suspend fun deletePlaylist(@QueryMap params: Map<String, String>, @Query("id") id: String): ApiResponse

    @GET("createShare")
    suspend fun createShare(@QueryMap params: Map<String, String>, @Query("id") id: String): ApiResponse

    @GET("startScan")
    suspend fun startScan(@QueryMap params: Map<String, String>): ApiResponse

    @GET("getScanStatus")
    suspend fun getScanStatus(@QueryMap params: Map<String, String>): ApiResponse

    @GET("getOpenSubsonicExtensions")
    suspend fun getOpenSubsonicExtensions(@QueryMap params: Map<String, String>): ApiResponse

    @GET("getPlayQueue")
    suspend fun getPlayQueue(@QueryMap params: Map<String, String>): ApiResponse

    @GET("getPlayQueueByIndex")
    suspend fun getPlayQueueByIndex(@QueryMap params: Map<String, String>): ApiResponse

    @GET("savePlayQueue")
    suspend fun savePlayQueue(
        @QueryMap params: Map<String, String>,
        @Query("id") ids: List<String>,
        @Query("current") current: String?,
        @Query("position") position: Long,
    ): ApiResponse

    @GET("savePlayQueueByIndex")
    suspend fun savePlayQueueByIndex(
        @QueryMap params: Map<String, String>,
        @Query("id") ids: List<String>,
        @Query("currentIndex") currentIndex: Int?,
        @Query("position") position: Long,
    ): ApiResponse

    /*
     * formPost variants: a saved queue carries one id per track, and a few hundred of them push the query string
     * past the 8 KB that nginx, Caddy and Apache accept by default.
     */
    @FormUrlEncoded
    @POST("savePlayQueue")
    suspend fun savePlayQueuePost(
        @FieldMap params: Map<String, String>,
        @Field("id") ids: List<String>,
        @Field("current") current: String?,
        @Field("position") position: Long,
    ): ApiResponse

    @FormUrlEncoded
    @POST("savePlayQueueByIndex")
    suspend fun savePlayQueueByIndexPost(
        @FieldMap params: Map<String, String>,
        @Field("id") ids: List<String>,
        @Field("currentIndex") currentIndex: Int?,
        @Field("position") position: Long,
    ): ApiResponse

    @GET("getPodcasts")
    suspend fun getPodcasts(@QueryMap params: Map<String, String>, @Query("includeEpisodes") includeEpisodes: Boolean, @Query("id") id: String?): ApiResponse

    @GET("getNewestPodcasts")
    suspend fun getNewestPodcasts(@QueryMap params: Map<String, String>, @Query("count") count: Int): ApiResponse

    @GET("refreshPodcasts")
    suspend fun refreshPodcasts(@QueryMap params: Map<String, String>): ApiResponse

    @GET("createPodcastChannel")
    suspend fun createPodcastChannel(@QueryMap params: Map<String, String>, @Query("url") url: String): ApiResponse

    @GET("deletePodcastChannel")
    suspend fun deletePodcastChannel(@QueryMap params: Map<String, String>, @Query("id") id: String): ApiResponse

    @GET("deletePodcastEpisode")
    suspend fun deletePodcastEpisode(@QueryMap params: Map<String, String>, @Query("id") id: String): ApiResponse

    @GET("downloadPodcastEpisode")
    suspend fun downloadPodcastEpisode(@QueryMap params: Map<String, String>, @Query("id") id: String): ApiResponse

    @GET("getInternetRadioStations")
    suspend fun getInternetRadioStations(@QueryMap params: Map<String, String>): ApiResponse

    @GET("createInternetRadioStation")
    suspend fun createInternetRadioStation(
        @QueryMap params: Map<String, String>,
        @Query("streamUrl") streamUrl: String,
        @Query("name") name: String,
        @Query("homepageUrl") homepageUrl: String?,
    ): ApiResponse

    @GET("updateInternetRadioStation")
    suspend fun updateInternetRadioStation(
        @QueryMap params: Map<String, String>,
        @Query("id") id: String,
        @Query("streamUrl") streamUrl: String,
        @Query("name") name: String,
        @Query("homepageUrl") homepageUrl: String?,
    ): ApiResponse

    @GET("deleteInternetRadioStation")
    suspend fun deleteInternetRadioStation(@QueryMap params: Map<String, String>, @Query("id") id: String): ApiResponse
}
