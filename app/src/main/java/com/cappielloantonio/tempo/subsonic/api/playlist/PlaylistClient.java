package com.cappielloantonio.tempo.subsonic.api.playlist;

import android.util.Log;

import com.cappielloantonio.tempo.subsonic.RetrofitClient;
import com.cappielloantonio.tempo.subsonic.Subsonic;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;
import com.cappielloantonio.tempo.util.OpenSubsonicExtensionsUtil;

import java.util.ArrayList;

import retrofit2.Call;

public class PlaylistClient {
    private static final String TAG = "BrowsingClient";

    private final Subsonic subsonic;
    private final PlaylistService playlistService;

    public PlaylistClient(Subsonic subsonic) {
        this.subsonic = subsonic;
        this.playlistService = new RetrofitClient(subsonic).getRetrofit().create(PlaylistService.class);
    }

    public Call<ApiResponse> getPlaylists() {
        Log.d(TAG, "getPlaylists()");
        return playlistService.getPlaylists(subsonic.getParams());
    }

    public Call<ApiResponse> getPlaylist(String id) {
        Log.d(TAG, "getPlaylist()");
        return playlistService.getPlaylist(subsonic.getParams(), id);
    }

    public Call<ApiResponse> createPlaylist(String playlistId, String name, ArrayList<String> songsId) {
        Log.d(TAG, "createPlaylist()");

        if (OpenSubsonicExtensionsUtil.isFormPostExtensionAvailable()) {
            return playlistService.createPlaylistPost(subsonic.getParams(), playlistId, name, songsId);
        }

        return playlistService.createPlaylist(subsonic.getParams(), playlistId, name, songsId);
    }

    public Call<ApiResponse> updatePlaylist(String playlistId, String name, Boolean isPublic, ArrayList<String> songIdToAdd, ArrayList<Integer> songIndexToRemove) {
        Log.d(TAG, "updatePlaylist()");

        if (OpenSubsonicExtensionsUtil.isFormPostExtensionAvailable()) {
            return playlistService.updatePlaylistPost(subsonic.getParams(), playlistId, name, isPublic, songIdToAdd, songIndexToRemove);
        }

        return playlistService.updatePlaylist(subsonic.getParams(), playlistId, name, isPublic, songIdToAdd, songIndexToRemove);
    }

    public Call<ApiResponse> deletePlaylist(String id) {
        Log.d(TAG, "deletePlaylist()");
        return playlistService.deletePlaylist(subsonic.getParams(), id);
    }
}
