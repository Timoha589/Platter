package com.cappielloantonio.tempo.repository;

import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;
import com.cappielloantonio.tempo.subsonic.models.ArtistID3;
import com.cappielloantonio.tempo.subsonic.models.ArtistInfo2;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.IndexID3;
import com.cappielloantonio.tempo.subsonic.models.SubsonicResponse;
import com.cappielloantonio.tempo.subsonic.utils.ResponseUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ArtistRepository {
    /*
     * Shares one getStarred2 with the song and album repositories - see
     * StarredRepository.
     *
     * This used to follow up with getArtist for every artist in the answer and
     * push each one into the list as it landed, so home fired up to fifty extra
     * requests and the artist rails grew a card at a time, in arbitrary order,
     * for as long as they took. getStarred2 already returns the id, name,
     * coverArt and albumCount - everything ArtistAdapter and
     * ArtistHorizontalAdapter read - so the follow-up bought nothing.
     */
    public MutableLiveData<List<ArtistID3>> getStarredArtists(boolean random, int size) {
        MutableLiveData<List<ArtistID3>> starredArtists = new MutableLiveData<>();

        StarredRepository.get(starred -> starredArtists.setValue(
                starred != null ? StarredRepository.sample(starred.getArtists(), random, size) : null
        ));

        return starredArtists;
    }

    public MutableLiveData<List<ArtistID3>> getArtists(boolean random, int size) {
        MutableLiveData<List<ArtistID3>> listLiveArtists = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getArtists()
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            List<ArtistID3> artists = new ArrayList<>();

                            if(response.body().getSubsonicResponse().getArtists() != null && response.body().getSubsonicResponse().getArtists().getIndices() != null) {
                                for (IndexID3 index : response.body().getSubsonicResponse().getArtists().getIndices()) {
                                    if(index != null && index.getArtists() != null) {
                                        artists.addAll(index.getArtists());
                                    }
                                }
                            }

                            /*
                             * The answer already carries the id, name, cover and album
                             * count ArtistAdapter reads. The random sample used to follow
                             * up with getArtist for every artist and push each one in as
                             * it landed, so the library's artist rail started out empty
                             * and grew a card at a time, in whatever order they came.
                             */
                            if (random) {
                                Collections.shuffle(artists);
                                if (size >= 0) artists = new ArrayList<>(artists.subList(0, Math.min(size, artists.size())));
                            }

                            listLiveArtists.setValue(artists);
                        } else {
                            listLiveArtists.setValue(null);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        // null is a failed request, so a loading placeholder knows to stop waiting.
                        listLiveArtists.setValue(null);
                    }
                });

        return listLiveArtists;
    }

    public MutableLiveData<ArtistID3> getArtistInfo(String id) {
        MutableLiveData<ArtistID3> artist = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getArtist(id)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse().getArtist() != null) {
                            artist.setValue(response.body().getSubsonicResponse().getArtist());
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {

                    }
                });

        return artist;
    }

    /* No value until the answer, null on a failure - see AlbumRepository.getArtistAlbums. */
    public MutableLiveData<ArtistInfo2> getArtistFullInfo(String id) {
        MutableLiveData<ArtistInfo2> artistFullInfo = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getArtistInfo2(id)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse().getArtistInfo2() != null) {
                            artistFullInfo.setValue(response.body().getSubsonicResponse().getArtistInfo2());
                        } else {
                            artistFullInfo.setValue(null);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        artistFullInfo.setValue(null);
                    }
                });

        return artistFullInfo;
    }


    public MutableLiveData<ArtistID3> getArtist(String id) {
        MutableLiveData<ArtistID3> artist = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getArtist(id)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse().getArtist() != null) {
                            artist.setValue(response.body().getSubsonicResponse().getArtist());
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {

                    }
                });

        return artist;
    }

    public MutableLiveData<List<Child>> getInstantMix(ArtistID3 artist, int count) {
        MutableLiveData<List<Child>> instantMix = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getSimilarSongs2(artist.getId(), count)
                .enqueue(new Callback<ApiResponse>() {
                    /*
                     * Never null and always answered, like SongRepository's:
                     * an artist with nothing similar comes back as a
                     * similarSongs2 with no song list, and the radio buttons
                     * that read this called isEmpty() on it.
                     */
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        SubsonicResponse body = ResponseUtil.body(response);
                        List<Child> songs = body != null && body.getSimilarSongs2() != null ? body.getSimilarSongs2().getSongs() : null;

                        instantMix.setValue(songs != null ? new ArrayList<>(songs) : new ArrayList<>());
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        instantMix.setValue(new ArrayList<>());
                    }
                });

        return instantMix;
    }

    public MutableLiveData<List<Child>> getRandomSong(ArtistID3 artist, int count) {
        MutableLiveData<List<Child>> randomSongs = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getTopSongs(artist.getName(), count)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse().getTopSongs() != null && response.body().getSubsonicResponse().getTopSongs().getSongs() != null) {
                            List<Child> songs = response.body().getSubsonicResponse().getTopSongs().getSongs();

                            if (songs != null && !songs.isEmpty()) {
                                Collections.shuffle(songs);
                            }

                            randomSongs.setValue(songs);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {

                    }
                });

        return randomSongs;
    }

    /* No value until the answer, null on a failure, a list (maybe empty) otherwise - see AlbumRepository.getArtistAlbums. */
    public MutableLiveData<List<Child>> getTopSongs(String artistName, int count) {
        MutableLiveData<List<Child>> topSongs = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getTopSongs(artistName, count)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse().getTopSongs() != null) {
                            List<Child> songs = response.body().getSubsonicResponse().getTopSongs().getSongs();
                            topSongs.setValue(songs != null ? songs : new ArrayList<>());
                        } else {
                            topSongs.setValue(null);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        topSongs.setValue(null);
                    }
                });

        return topSongs;
    }
}
