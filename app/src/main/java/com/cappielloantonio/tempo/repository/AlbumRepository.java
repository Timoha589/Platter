package com.cappielloantonio.tempo.repository;

import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.interfaces.DecadesCallback;
import com.cappielloantonio.tempo.interfaces.MediaCallback;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.subsonic.models.AlbumInfo;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.SubsonicResponse;
import com.cappielloantonio.tempo.subsonic.utils.ResponseUtil;
import com.cappielloantonio.tempo.util.ReleaseDateUtil;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class AlbumRepository {
    private static final int UNKNOWN_YEAR = -1;

    /*
     * The LiveData carries three states, and the difference between the last
     * two is what home needs to stop hiding its own failures: no value means
     * the request is still out, an empty list means the server answered and has
     * nothing, and null means the request failed. It used to start life holding
     * an empty list and stay that way on any error, so a refused login and an
     * empty library were indistinguishable - and, because the view model caches
     * the first non-null answer, one failed request left the section blank
     * until the app was restarted.
     */
    public MutableLiveData<List<AlbumID3>> getAlbums(String type, int size, Integer fromYear, Integer toYear) {
        MutableLiveData<List<AlbumID3>> listLiveAlbums = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getAlbumSongListClient()
                .getAlbumList2(type, size, 0, fromYear, toYear)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        SubsonicResponse body = ResponseUtil.body(response);

                        if (body == null || body.getAlbumList2() == null) {
                            listLiveAlbums.setValue(null);
                            return;
                        }

                        List<AlbumID3> albums = body.getAlbumList2().getAlbums();
                        listLiveAlbums.setValue(albums != null ? albums : new ArrayList<>());
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        listLiveAlbums.setValue(null);
                    }
                });

        return listLiveAlbums;
    }

    /* Shares one getStarred2 with the song and artist repositories - see StarredRepository */
    public MutableLiveData<List<AlbumID3>> getStarredAlbums(boolean random, int size) {
        MutableLiveData<List<AlbumID3>> starredAlbums = new MutableLiveData<>();

        StarredRepository.get(starred -> starredAlbums.setValue(
                starred != null ? StarredRepository.sample(starred.getAlbums(), random, size) : null
        ));

        return starredAlbums;
    }


    public MutableLiveData<List<Child>> getAlbumTracks(String id) {
        MutableLiveData<List<Child>> albumTracks = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getAlbum(id)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        List<Child> tracks = new ArrayList<>();

                        if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse().getAlbum() != null) {
                            if (response.body().getSubsonicResponse().getAlbum().getSongs() != null) {
                                tracks.addAll(response.body().getSubsonicResponse().getAlbum().getSongs());
                            }
                        }

                        albumTracks.setValue(tracks);
                    }

                    // null is a failed request, which the page can tell from an album with no tracks.
                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        albumTracks.setValue(null);
                    }
                });

        return albumTracks;
    }

    /*
     * No value until the server answers, null if it could not, and a list -
     * empty or not - when it did: a page can then tell "still loading" from
     * "nothing there". It used to start out as an empty list, which read as an
     * artist with no albums, and a failure never said anything at all.
     */
    public MutableLiveData<List<AlbumID3>> getArtistAlbums(String id) {
        MutableLiveData<List<AlbumID3>> artistsAlbum = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getArtist(id)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse().getArtist() != null) {
                            List<AlbumID3> albums = response.body().getSubsonicResponse().getArtist().getAlbums();
                            albums = albums != null ? new ArrayList<>(albums) : new ArrayList<>();
                            albums.sort(ReleaseDateUtil.NEWEST_FIRST);
                            artistsAlbum.setValue(albums);
                        } else {
                            artistsAlbum.setValue(null);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        artistsAlbum.setValue(null);
                    }
                });

        return artistsAlbum;
    }

    public MutableLiveData<AlbumID3> getAlbum(String id) {
        MutableLiveData<AlbumID3> album = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getAlbum(id)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse().getAlbum() != null) {
                            album.setValue(response.body().getSubsonicResponse().getAlbum());
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {

                    }
                });

        return album;
    }

    public MutableLiveData<AlbumInfo> getAlbumInfo(String id) {
        MutableLiveData<AlbumInfo> albumInfo = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getAlbumInfo2(id)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse().getAlbumInfo() != null) {
                            albumInfo.setValue(response.body().getSubsonicResponse().getAlbumInfo());
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {

                    }
                });

        return albumInfo;
    }

    public void getInstantMix(AlbumID3 album, int count, MediaCallback callback) {
        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getSimilarSongs2(album.getId(), count)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        List<Child> songs = new ArrayList<>();
                        SubsonicResponse body = ResponseUtil.body(response);

                        // An album with nothing similar has a similarSongs2 but no song list; addAll(null) threw.
                        if (body != null && body.getSimilarSongs2() != null && body.getSimilarSongs2().getSongs() != null) {
                            songs.addAll(body.getSimilarSongs2().getSongs());
                        }

                        callback.onLoadMedia(songs);
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        callback.onLoadMedia(new ArrayList<>());
                    }
                });
    }

    /*
     * Flashback's decade list: the oldest album and the newest bracket the
     * decades the library covers.
     *
     * Both legs used to go quiet whenever the answer was not the shape they
     * expected - an empty library, a Subsonic error inside an HTTP 200 - and
     * since the second leg is started from the first one's callback, a quiet
     * first leg meant the second never ran and the LiveData was never set at
     * all. The section sat waiting for an answer that could not arrive. Every
     * path now reports.
     */
    public MutableLiveData<List<Integer>> getDecades() {
        MutableLiveData<List<Integer>> decades = new MutableLiveData<>();

        getFirstAlbum(new DecadesCallback() {
            @Override
            public void onLoadYear(int first) {
                getLastAlbum(new DecadesCallback() {
                    @Override
                    public void onLoadYear(int last) {
                        if (first == UNKNOWN_YEAR || last == UNKNOWN_YEAR) {
                            decades.setValue(null);
                            return;
                        }

                        List<Integer> decadeList = new ArrayList<>();

                        int startDecade = first - (first % 10);
                        int lastDecade = last - (last % 10);

                        while (startDecade <= lastDecade) {
                            decadeList.add(startDecade);
                            startDecade = startDecade + 10;
                        }

                        decades.setValue(decadeList);
                    }
                });
            }
        });

        return decades;
    }

    private void getFirstAlbum(DecadesCallback callback) {
        getBoundaryYear(1900, Calendar.getInstance().get(Calendar.YEAR), callback);
    }

    private void getLastAlbum(DecadesCallback callback) {
        getBoundaryYear(Calendar.getInstance().get(Calendar.YEAR), 1900, callback);
    }

    /**
     * The year of the single album at one end of the library's range, or
     * {@link #UNKNOWN_YEAR} if there is not one to read.
     * <p>
     * An album the server has no year for reads as 0, which would otherwise be
     * taken at face value and turn the Flashback rail into two hundred decades
     * starting at the year nought.
     */
    private void getBoundaryYear(int fromYear, int toYear, DecadesCallback callback) {
        App.getSubsonicClientInstance(false)
                .getAlbumSongListClient()
                .getAlbumList2("byYear", 1, 0, fromYear, toYear)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        SubsonicResponse body = ResponseUtil.body(response);
                        List<AlbumID3> albums = body != null && body.getAlbumList2() != null
                                ? body.getAlbumList2().getAlbums()
                                : null;

                        if (albums == null || albums.isEmpty() || albums.get(0).getYear() <= 0) {
                            callback.onLoadYear(UNKNOWN_YEAR);
                            return;
                        }

                        callback.onLoadYear(albums.get(0).getYear());
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        callback.onLoadYear(UNKNOWN_YEAR);
                    }
                });
    }
}
