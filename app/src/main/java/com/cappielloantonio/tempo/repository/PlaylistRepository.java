package com.cappielloantonio.tempo.repository;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.database.dao.PlaylistDao;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.Playlist;
import com.cappielloantonio.tempo.subsonic.models.SubsonicResponse;
import com.cappielloantonio.tempo.subsonic.utils.ResponseUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

@OptIn(markerClass = UnstableApi.class)
public class PlaylistRepository {
    private final PlaylistDao playlistDao = AppDatabase.getInstance().playlistDao();
    public MutableLiveData<List<Playlist>> getPlaylists(boolean random, int size) {
        MutableLiveData<List<Playlist>> listLivePlaylists = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getPlaylistClient()
                .getPlaylists()
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        SubsonicResponse body = ResponseUtil.body(response);

                        if (body == null || body.getPlaylists() == null) {
                            listLivePlaylists.setValue(null);
                            return;
                        }

                        List<Playlist> playlists = body.getPlaylists().getPlaylists();
                        List<Playlist> result = playlists != null ? new ArrayList<>(playlists) : new ArrayList<>();

                        if (random) {
                            Collections.shuffle(result);
                            result = new ArrayList<>(result.subList(0, Math.min(result.size(), size)));
                        }

                        listLivePlaylists.setValue(result);
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        listLivePlaylists.setValue(null);
                    }
                });

        return listLivePlaylists;
    }

    /* No value until the answer, null on a failure, the tracks (maybe none) otherwise. */
    public MutableLiveData<List<Child>> getPlaylistSongs(String id) {
        MutableLiveData<List<Child>> listLivePlaylistSongs = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getPlaylistClient()
                .getPlaylist(id)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse().getPlaylist() != null) {
                            List<Child> songs = response.body().getSubsonicResponse().getPlaylist().getEntries();
                            listLivePlaylistSongs.setValue(songs != null ? songs : new ArrayList<>());
                        } else {
                            listLivePlaylistSongs.setValue(null);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        listLivePlaylistSongs.setValue(null);
                    }
                });

        return listLivePlaylistSongs;
    }

    public void addSongToPlaylist(String playlistId, ArrayList<String> songsId) {
        App.getSubsonicClientInstance(false)
                .getPlaylistClient()
                .updatePlaylist(playlistId, null, true, songsId, null)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {

                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {

                    }
                });
    }

    public void createPlaylist(String playlistId, String name, ArrayList<String> songsId) {
        App.getSubsonicClientInstance(false)
                .getPlaylistClient()
                .createPlaylist(playlistId, name, songsId)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {

                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {

                    }
                });
    }

    public void updatePlaylist(String playlistId, String name, ArrayList<String> songsId) {
        App.getSubsonicClientInstance(false)
                .getPlaylistClient()
                .deletePlaylist(playlistId)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        createPlaylist(null, name, songsId);
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {

                    }
                });
    }

    public void updatePlaylist(String playlistId, String name, Boolean isPublic, ArrayList<String> songIdToAdd, ArrayList<Integer> songIndexToRemove) {
        App.getSubsonicClientInstance(false)
                .getPlaylistClient()
                .updatePlaylist(playlistId, name, isPublic, songIdToAdd, songIndexToRemove)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {

                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {

                    }
                });
    }

    /**
     * Takes one track out of a playlist and leaves everything else about it
     * alone - its id, its name, the artwork on it - unlike the edit dialog,
     * which rewrites a playlist by deleting and recreating it.
     * <p>
     * Subsonic removes by position rather than by id, and the position has to
     * be the one the server holds: the list on screen can be filtered or
     * sorted, so the playlist is read back and the track found in it rather
     * than the row that was tapped being trusted for its index.
     */
    public MutableLiveData<Boolean> removeSongFromPlaylist(String playlistId, String songId) {
        MutableLiveData<Boolean> isRemoved = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getPlaylistClient()
                .getPlaylist(playlistId)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        int index = indexOf(response, songId);

                        if (index == -1) {
                            isRemoved.setValue(false);
                        } else {
                            removeAt(playlistId, index, isRemoved);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        isRemoved.setValue(false);
                    }
                });

        return isRemoved;
    }

    private int indexOf(Response<ApiResponse> response, String songId) {
        if (!response.isSuccessful() || response.body() == null || response.body().getSubsonicResponse().getPlaylist() == null) {
            return -1;
        }

        List<Child> songs = response.body().getSubsonicResponse().getPlaylist().getEntries();

        if (songs == null) return -1;

        for (int index = 0; index < songs.size(); index++) {
            if (songs.get(index).getId().equals(songId)) return index;
        }

        return -1;
    }

    private void removeAt(String playlistId, int index, MutableLiveData<Boolean> isRemoved) {
        App.getSubsonicClientInstance(false)
                .getPlaylistClient()
                // Null everywhere else: nothing but the one position is being
                // touched, and a visibility sent along would be a visibility set.
                .updatePlaylist(playlistId, null, null, null, new ArrayList<>(Collections.singletonList(index)))
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        isRemoved.setValue(response.isSuccessful()
                                && response.body() != null
                                && response.body().getSubsonicResponse().getError() == null);
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        isRemoved.setValue(false);
                    }
                });
    }

    public void deletePlaylist(String playlistId) {
        App.getSubsonicClientInstance(false)
                .getPlaylistClient()
                .deletePlaylist(playlistId)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {

                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {

                    }
                });
    }

    public LiveData<List<Playlist>> getPinnedPlaylists() {
        return playlistDao.getAll();
    }

    public void insert(Playlist playlist) {
        InsertThreadSafe insert = new InsertThreadSafe(playlistDao, playlist);
        Thread thread = new Thread(insert);
        thread.start();
    }

    public void delete(Playlist playlist) {
        DeleteThreadSafe delete = new DeleteThreadSafe(playlistDao, playlist);
        Thread thread = new Thread(delete);
        thread.start();
    }

    private static class InsertThreadSafe implements Runnable {
        private final PlaylistDao playlistDao;
        private final Playlist playlist;

        public InsertThreadSafe(PlaylistDao playlistDao, Playlist playlist) {
            this.playlistDao = playlistDao;
            this.playlist = playlist;
        }

        @Override
        public void run() {
            playlistDao.insert(playlist);
        }
    }

    private static class DeleteThreadSafe implements Runnable {
        private final PlaylistDao playlistDao;
        private final Playlist playlist;

        public DeleteThreadSafe(PlaylistDao playlistDao, Playlist playlist) {
            this.playlistDao = playlistDao;
            this.playlist = playlist;
        }

        @Override
        public void run() {
            playlistDao.delete(playlist);
        }
    }
}
