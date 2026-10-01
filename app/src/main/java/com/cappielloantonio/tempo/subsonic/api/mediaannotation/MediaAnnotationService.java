package com.cappielloantonio.tempo.subsonic.api.mediaannotation;

import com.cappielloantonio.tempo.subsonic.base.ApiResponse;

import java.util.Map;

import retrofit2.Call;
import retrofit2.http.Field;
import retrofit2.http.FieldMap;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Query;
import retrofit2.http.QueryMap;

public interface MediaAnnotationService {
    @GET("star")
    Call<ApiResponse> star(@QueryMap Map<String, String> params, @Query("id") String id, @Query("albumId") String albumId, @Query("artistId") String artistId);

    @GET("unstar")
    Call<ApiResponse> unstar(@QueryMap Map<String, String> params, @Query("id") String id, @Query("albumId") String albumId, @Query("artistId") String artistId);

    @GET("setRating")
    Call<ApiResponse> setRating(@QueryMap Map<String, String> params, @Query("id") String id, @Query("rating") int rating);

    /*
     * time is when the track started playing, in ms since the epoch. Left
     * out, the server files the play under the moment the request arrives -
     * wrong for one sent late, after a spell offline.
     */
    @GET("scrobble")
    Call<ApiResponse> scrobble(@QueryMap Map<String, String> params, @Query("id") String id, @Query("submission") Boolean submission, @Query("time") Long time);

    /*
     * playbackReport extension. Scrobbling only tells the server that a track
     * was played; reportPlayback tells it what the player is doing right now,
     * so the server's "now playing" reflects a pause or a seek instead of
     * extrapolating a position from the last scrobble.
     */
    @GET("reportPlayback")
    Call<ApiResponse> reportPlayback(@QueryMap Map<String, String> params, @Query("mediaId") String mediaId, @Query("mediaType") String mediaType, @Query("positionMs") long positionMs, @Query("state") String state, @Query("playbackRate") Float playbackRate, @Query("ignoreScrobble") Boolean ignoreScrobble);

    @FormUrlEncoded
    @POST("reportPlayback")
    Call<ApiResponse> reportPlaybackPost(@FieldMap Map<String, String> params, @Field("mediaId") String mediaId, @Field("mediaType") String mediaType, @Field("positionMs") long positionMs, @Field("state") String state, @Field("playbackRate") Float playbackRate, @Field("ignoreScrobble") Boolean ignoreScrobble);
}
