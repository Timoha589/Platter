package com.cappielloantonio.tempo.subsonic.api.bookmarks;

import com.cappielloantonio.tempo.subsonic.base.ApiResponse;

import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.http.Field;
import retrofit2.http.FieldMap;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Query;
import retrofit2.http.QueryMap;

public interface BookmarksService {
    @GET("getPlayQueue")
    Call<ApiResponse> getPlayQueue(@QueryMap Map<String, String> params);

    @GET("savePlayQueue")
    Call<ApiResponse> savePlayQueue(@QueryMap Map<String, String> params, @Query("id") List<String> ids, @Query("current") String current, @Query("position") long position);

    /*
     * indexBasedQueue extension. A queue can legitimately hold the same track
     * more than once; identifying the playing one by id cannot express that,
     * identifying it by index can.
     */
    @GET("getPlayQueueByIndex")
    Call<ApiResponse> getPlayQueueByIndex(@QueryMap Map<String, String> params);

    @GET("savePlayQueueByIndex")
    Call<ApiResponse> savePlayQueueByIndex(@QueryMap Map<String, String> params, @Query("id") List<String> ids, @Query("currentIndex") Integer currentIndex, @Query("position") long position);

    /*
     * formPost variants. A saved queue carries one id per track, so a few
     * hundred tracks push the query string past the 8 KB that nginx, Caddy and
     * Apache accept by default and the save fails against a proxied server.
     */
    @FormUrlEncoded
    @POST("savePlayQueue")
    Call<ApiResponse> savePlayQueuePost(@FieldMap Map<String, String> params, @Field("id") List<String> ids, @Field("current") String current, @Field("position") long position);

    @FormUrlEncoded
    @POST("savePlayQueueByIndex")
    Call<ApiResponse> savePlayQueueByIndexPost(@FieldMap Map<String, String> params, @Field("id") List<String> ids, @Field("currentIndex") Integer currentIndex, @Field("position") long position);
}
