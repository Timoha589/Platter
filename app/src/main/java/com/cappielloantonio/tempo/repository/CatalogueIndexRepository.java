package com.cappielloantonio.tempo.repository;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.database.dao.CatalogueNameDao;
import com.cappielloantonio.tempo.helper.search.QueryVariants;
import com.cappielloantonio.tempo.model.CatalogueName;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.subsonic.models.ArtistID3;
import com.cappielloantonio.tempo.subsonic.models.IndexID3;
import com.cappielloantonio.tempo.util.NetworkUtil;
import com.cappielloantonio.tempo.util.Preferences;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Keeps a local list of the names in the library, for spelling only.
 * <p>
 * Correcting "Мельнеца" means measuring it against the names that exist, and the
 * server offers no way to ask that - {@code search3} matches substrings, and a
 * misspelling has none worth matching. So the names are fetched once and kept.
 * <p>
 * Artists come whole in a single {@code getArtists}. Albums have to be paged, so
 * they are capped: a spelling reference stops paying for itself long before a
 * large library is exhausted, and the fetch is background work nobody asked for.
 * Tracks are left out entirely - stock Subsonic has no way to enumerate them,
 * and a library holds far more track titles than the other two put together.
 */
@OptIn(markerClass = UnstableApi.class)
public class CatalogueIndexRepository {
    public static final int KIND_ARTIST = 0;
    public static final int KIND_ALBUM = 1;

    /** Long enough that the sync is rare, short enough to notice a new artist. */
    private static final long STALE_AFTER_MS = 7L * 24 * 60 * 60 * 1000;

    private static final int ALBUM_PAGE_SIZE = 500;
    private static final int MAX_ALBUM_PAGES = 10;

    private final CatalogueNameDao catalogueNameDao = AppDatabase.getInstance().catalogueNameDao();
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean syncing = false;

    /**
     * Refreshes the name list if it is old enough to be worth the traffic.
     *
     * @param onFinished run on the main thread once new names are stored; not
     *                   run when the sync is skipped or fails
     */
    public void syncIfStale(Runnable onFinished) {
        if (syncing) return;
        if (NetworkUtil.isOffline()) return;

        /*
         * Data saving mode is a statement about bulk transfers, and this is
         * one - background paging nobody asked for, in aid of a convenience.
         * The prefix fallback still works without it, so search stays usable.
         */
        if (Preferences.isDataSavingMode()) return;

        long age = System.currentTimeMillis() - Preferences.getCatalogueIndexSyncedAt();
        if (age < STALE_AFTER_MS) return;

        syncing = true;
        fetchArtists(onFinished);
    }

    private void fetchArtists(Runnable onFinished) {
        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getArtists()
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        List<CatalogueName> names = new ArrayList<>();

                        if (response.isSuccessful()
                                && response.body() != null
                                && response.body().getSubsonicResponse().getArtists() != null
                                && response.body().getSubsonicResponse().getArtists().getIndices() != null) {
                            for (IndexID3 index : response.body().getSubsonicResponse().getArtists().getIndices()) {
                                if (index == null || index.getArtists() == null) continue;

                                for (ArtistID3 artist : index.getArtists()) {
                                    add(names, KIND_ARTIST, artist.getName());
                                }
                            }
                        }

                        store(KIND_ARTIST, names);
                        fetchAlbums(new ArrayList<>(), 0, onFinished);
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        syncing = false;
                    }
                });
    }

    /**
     * Pages through the albums alphabetically, accumulating names, and stops at
     * the first short page or at {@link #MAX_ALBUM_PAGES}.
     */
    private void fetchAlbums(List<CatalogueName> collected, int page, Runnable onFinished) {
        App.getSubsonicClientInstance(false)
                .getAlbumSongListClient()
                .getAlbumList2("alphabeticalByName", ALBUM_PAGE_SIZE, page * ALBUM_PAGE_SIZE, null, null)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        List<AlbumID3> albums = response.isSuccessful()
                                && response.body() != null
                                && response.body().getSubsonicResponse().getAlbumList2() != null
                                ? response.body().getSubsonicResponse().getAlbumList2().getAlbums()
                                : null;

                        if (albums != null) {
                            for (AlbumID3 album : albums) add(collected, KIND_ALBUM, album.getName());
                        }

                        boolean lastPage = albums == null
                                || albums.size() < ALBUM_PAGE_SIZE
                                || page + 1 >= MAX_ALBUM_PAGES;

                        if (lastPage) finish(collected, onFinished);
                        else fetchAlbums(collected, page + 1, onFinished);
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        /*
                         * Whatever was paged before the failure is still a better
                         * spelling reference than nothing, so it is kept - but the
                         * sync is not marked done, so the next visit tries again.
                         */
                        store(KIND_ALBUM, collected);
                        syncing = false;
                    }
                });
    }

    private void finish(List<CatalogueName> albums, Runnable onFinished) {
        store(KIND_ALBUM, albums);

        writeExecutor.execute(() -> {
            Preferences.setCatalogueIndexSyncedAt(System.currentTimeMillis());
            syncing = false;

            mainHandler.post(onFinished);
        });
    }

    private void store(int kind, List<CatalogueName> names) {
        if (names.isEmpty()) return;

        writeExecutor.execute(() -> {
            catalogueNameDao.deleteKind(kind);
            catalogueNameDao.insertAll(names);
        });
    }

    private static void add(List<CatalogueName> names, int kind, String name) {
        if (name == null) return;

        String trimmed = name.trim();
        if (trimmed.isEmpty()) return;

        names.add(new CatalogueName(kind, trimmed, QueryVariants.normalize(trimmed)));
    }
}
