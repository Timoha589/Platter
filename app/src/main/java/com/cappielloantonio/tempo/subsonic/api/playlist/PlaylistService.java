package com.cappielloantonio.tempo.subsonic.api.playlist;

import com.cappielloantonio.tempo.subsonic.base.ApiResponse;

import java.util.ArrayList;
import java.util.Map;

import retrofit2.Call;
import retrofit2.http.Field;
import retrofit2.http.FieldMap;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Query;
import retrofit2.http.QueryMap;

public interface PlaylistService {
    @GET("getPlaylists")
    Call<ApiResponse> getPlaylists(@QueryMap Map<String, String> params);

    @GET("getPlaylist")
    Call<ApiResponse> getPlaylist(@QueryMap Map<String, String> params, @Query("id") String id);

    @GET("createPlaylist")
    Call<ApiResponse> createPlaylist(@QueryMap Map<String, String> params, @Query("playlistId") String playlistId, @Query("name") String name, @Query("songId") ArrayList<String> songsId);

    @GET("updatePlaylist")
    Call<ApiResponse> updatePlaylist(@QueryMap Map<String, String> params, @Query("playlistId") String playlistId, @Query("name") String name, @Query("public") Boolean isPublic, @Query("songIdToAdd") ArrayList<String> songIdToAdd, @Query("songIndexToRemove") ArrayList<Integer> songIndexToRemove);

    @GET("deletePlaylist")
    Call<ApiResponse> deletePlaylist(@QueryMap Map<String, String> params, @Query("id") String id);

    /*
     * formPost variants. Creating a playlist from a large album, or adding a
     * few hundred tracks at once, sends one songId per track; past roughly 8 KB
     * of query string the request is rejected by every common reverse proxy
     * before Navidrome ever sees it.
     */
    @FormUrlEncoded
    @POST("createPlaylist")
    Call<ApiResponse> createPlaylistPost(@FieldMap Map<String, String> params, @Field("playlistId") String playlistId, @Field("name") String name, @Field("songId") ArrayList<String> songsId);

    @FormUrlEncoded
    @POST("updatePlaylist")
    Call<ApiResponse> updatePlaylistPost(@FieldMap Map<String, String> params, @Field("playlistId") String playlistId, @Field("name") String name, @Field("public") Boolean isPublic, @Field("songIdToAdd") ArrayList<String> songIdToAdd, @Field("songIndexToRemove") ArrayList<Integer> songIndexToRemove);
}
