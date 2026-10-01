package com.cappielloantonio.tempo.repository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.util.Consumer;
import androidx.lifecycle.MutableLiveData;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.SubsonicResponse;
import com.cappielloantonio.tempo.subsonic.utils.ResponseUtil;
import com.cappielloantonio.tempo.util.FavoriteState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class SongRepository {
    private static final String TAG = "SongRepository";

    /*
     * Shares one getStarred2 with the album and artist repositories - see
     * StarredRepository. The LiveData starts with no value at all rather than
     * an empty list: an empty list is an answer ("nothing is starred"), and
     * delivering it before the request has even been made is what made every
     * section on home flicker through an empty state on the way in. No value
     * means "still loading", and null means the request failed.
     */
    public MutableLiveData<List<Child>> getStarredSongs(boolean random, int size) {
        MutableLiveData<List<Child>> starredSongs = new MutableLiveData<>();

        StarredRepository.get(starred -> starredSongs.setValue(
                starred != null ? StarredRepository.sample(starred.getSongs(), random, size) : null
        ));

        return starredSongs;
    }

    /*
     * Always answers exactly once: the songs (possibly none), or null when the
     * request failed. A server with nothing similar to offer - Navidrome
     * without an external agent, for one - sends similarSongs2 with no song
     * list at all; that used to be passed on as a null list, and the callers
     * that enqueue the result threw on it. Staying silent was no better: the
     * one-shot observer in MediaManager.continuousPlay waited for good.
     */
    public MutableLiveData<List<Child>> getInstantMix(String id, int count) {
        MutableLiveData<List<Child>> instantMix = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getSimilarSongs2(id, count)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        SubsonicResponse body = ResponseUtil.body(response);

                        if (body == null || body.getSimilarSongs2() == null) {
                            instantMix.setValue(null);
                            return;
                        }

                        List<Child> songs = body.getSimilarSongs2().getSongs();
                        instantMix.setValue(songs != null ? new ArrayList<>(songs) : new ArrayList<>());
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        instantMix.setValue(null);
                    }
                });

        return instantMix;
    }

    public MutableLiveData<List<Child>> getRandomSample(int number, Integer fromYear, Integer toYear) {
        MutableLiveData<List<Child>> randomSongsSample = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getAlbumSongListClient()
                .getRandomSongs(number, fromYear, toYear)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        SubsonicResponse body = ResponseUtil.body(response);

                        if (body == null || body.getRandomSongs() == null) {
                            randomSongsSample.setValue(null);
                            return;
                        }

                        List<Child> songs = new ArrayList<>();

                        if (body.getRandomSongs().getSongs() != null) {
                            songs.addAll(body.getRandomSongs().getSongs());
                        }

                        randomSongsSample.setValue(songs);
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        randomSongsSample.setValue(null);
                    }
                });

        return randomSongsSample;
    }

    /**
     * @param time     when the track started playing, for a play sent late;
     *                 null to let the server use the moment it hears of it
     * @param answered told whether the server is done with the scrobble -
     *                 false only when it could not be reached or failed on its
     *                 side, where sending it again later may still work. A
     *                 scrobble it refused outright is done with too.
     */
    public void scrobble(String id, boolean submission, @Nullable Long time, @Nullable Consumer<Boolean> answered) {
        App.getSubsonicClientInstance(false)
                .getMediaAnnotationClient()
                .scrobble(id, submission, time)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (answered != null) answered.accept(response.code() < 500);
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        if (answered != null) answered.accept(false);
                    }
                });
    }

    /**
     * Reports the player's current state through the playbackReport extension.
     * <p>
     * Callers are expected to have checked
     * {@link com.cappielloantonio.tempo.util.OpenSubsonicExtensionsUtil#isPlaybackReportExtensionAvailable()}
     * first; a server without the extension answers 404 or error 70 and there
     * is nothing useful to do with either.
     */
    public void reportPlayback(String id, String mediaType, long positionMs, String state, boolean ignoreScrobble) {
        App.getSubsonicClientInstance(false)
                .getMediaAnnotationClient()
                .reportPlayback(id, mediaType, positionMs, state, null, ignoreScrobble)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {

                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {

                    }
                });
    }


    public MutableLiveData<List<Child>> getSongsByGenre(String id, int page) {
        MutableLiveData<List<Child>> songsByGenre = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getAlbumSongListClient()
                .getSongsByGenre(id, 100, 100 * page)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse().getSongsByGenre() != null) {
                            songsByGenre.setValue(response.body().getSubsonicResponse().getSongsByGenre().getSongs());
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {

                    }
                });

        return songsByGenre;
    }

    public MutableLiveData<List<Child>> getSongsByGenres(ArrayList<String> genresId) {
        MutableLiveData<List<Child>> songsByGenre = new MutableLiveData<>();
        // Each genre's answer adds to the others', and a track in two of them comes once.
        List<Child> all = new ArrayList<>();
        Set<String> ids = new HashSet<>();

        for (String id : genresId)
            App.getSubsonicClientInstance(false)
                    .getAlbumSongListClient()
                    .getSongsByGenre(id, 500, 0)
                    .enqueue(new Callback<ApiResponse>() {
                        @Override
                        public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                            if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse().getSongsByGenre() != null) {
                                List<Child> songs = response.body().getSubsonicResponse().getSongsByGenre().getSongs();
                                if (songs != null) {
                                    for (Child song : songs) if (ids.add(song.getId())) all.add(song);
                                }
                            }

                            songsByGenre.setValue(new ArrayList<>(all));
                        }

                        @Override
                        public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {

                        }
                    });

        return songsByGenre;
    }

    public MutableLiveData<Child> getSong(String id) {
        MutableLiveData<Child> song = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getSong(id)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            song.setValue(response.body().getSubsonicResponse().getSong());
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {

                    }
                });

        return song;
    }

    /**
     * Asks the server whether a track is liked, and records the answer in
     * {@link FavoriteState} - which is all the media notification needs, and
     * all it gets. The player sheet learns the same thing through
     * {@link #getSongOrSaved}, but only while it is on screen; with the app in
     * the background nothing else asks, and the notification went on showing
     * the like the track had when it was queued.
     */
    public void refreshFavorite(String id) {
        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getSong(id)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        SubsonicResponse body = ResponseUtil.body(response);

                        if (body != null && body.getSong() != null) {
                            FavoriteState.learn(body.getSong().getId(), body.getSong().getStarred() != null);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        // What the queue says stands until the server can be asked.
                    }
                });
    }

    /**
     * The track from the server, or from its download record when the server
     * cannot be asked.
     * <p>
     * The player builds its whole panel - the buttons, and the request for the
     * lyrics - off this answer. Offline there never was one, so a downloaded
     * track played with nothing but its title: no lyrics were even looked for.
     * The record written when it was downloaded holds the same track.
     */
    @OptIn(markerClass = UnstableApi.class)
    public MutableLiveData<Child> getSongOrSaved(String id) {
        MutableLiveData<Child> song = new MutableLiveData<>();

        Runnable useSaved = () -> new Thread(() -> {
            Child saved = AppDatabase.getInstance().downloadDao().getOne(id);
            if (saved != null) song.postValue(saved);
        }).start();

        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getSong(id)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        Child fresh = response.isSuccessful() && response.body() != null ? response.body().getSubsonicResponse().getSong() : null;

                        if (fresh != null) {
                            // First, so whatever redraws on the news already reads the new state.
                            FavoriteState.learn(fresh.getId(), fresh.getStarred() != null);
                            song.setValue(fresh);
                        } else {
                            useSaved.run();
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        useSaved.run();
                    }
                });

        return song;
    }
}
