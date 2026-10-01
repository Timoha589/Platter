package com.cappielloantonio.tempo.repository;

import android.os.SystemClock;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.interfaces.StarredCallback;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;
import com.cappielloantonio.tempo.subsonic.models.Starred2;
import com.cappielloantonio.tempo.subsonic.models.SubsonicResponse;
import com.cappielloantonio.tempo.subsonic.utils.ResponseUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * One getStarred2 call, shared by everything that needs a starred item.
 * <p>
 * getStarred2 answers with every starred song, album and artist in the library
 * in a single body. Home reads all three across six sections - Made for you,
 * Best of, Radio stations, and the three starred rails - and each of those used
 * to issue its own call, a seventh going out when starred-sync is on. Seven
 * copies of the same potentially multi-megabyte response were requested on
 * every visit to home, and since they shared a connection pool and a 30 second
 * read timeout, the later ones were the ones that fell over: which sections
 * came up empty was effectively random, and varied run to run on the same
 * server.
 * <p>
 * Callers that arrive while a request is in flight are queued onto it, and the
 * answer is held briefly afterwards so the sections built later in the same
 * pass still hit the same copy. A failure is never cached - the next caller
 * retries - and starring something, or refreshing a section by hand,
 * invalidates it.
 * <p>
 * Everything here runs on the main thread: Retrofit dispatches its callbacks
 * there, and the repositories are called from fragments and view models.
 */
public final class StarredRepository {
    /*
     * Long enough to cover one pass over home, short enough that a star set on
     * another device shows up on the next visit rather than on the next launch.
     */
    private static final long TTL = 30_000;

    private static final List<StarredCallback> waiting = new ArrayList<>();

    @Nullable
    private static Starred2 cached;
    private static long cachedAt;
    private static boolean inFlight;

    private StarredRepository() {
    }

    @MainThread
    public static void get(StarredCallback callback) {
        if (cached != null && SystemClock.elapsedRealtime() - cachedAt < TTL) {
            callback.onResult(cached);
            return;
        }

        waiting.add(callback);

        if (inFlight) return;
        inFlight = true;

        App.getSubsonicClientInstance(false)
                .getAlbumSongListClient()
                .getStarred2()
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        SubsonicResponse body = ResponseUtil.body(response);
                        deliver(body != null ? body.getStarred2() : null);
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        deliver(null);
                    }
                });
    }

    @MainThread
    public static void invalidate() {
        cached = null;
        cachedAt = 0;
    }

    /**
     * A copy of {@code items}, shuffled and cut to {@code size} when
     * {@code random} is set.
     * <p>
     * The copy is the point: the list handed to a caller belongs to the shared
     * response, so shuffling or sub-listing it in place would reorder - and
     * truncate - what every other section sees.
     */
    public static <T> List<T> sample(@Nullable List<T> items, boolean random, int size) {
        if (items == null) return Collections.emptyList();

        List<T> copy = new ArrayList<>(items);

        if (!random) return copy;

        Collections.shuffle(copy);

        return size >= 0 && size < copy.size() ? new ArrayList<>(copy.subList(0, size)) : copy;
    }

    private static void deliver(@Nullable Starred2 starred) {
        if (starred != null) {
            cached = starred;
            cachedAt = SystemClock.elapsedRealtime();
        }

        List<StarredCallback> toNotify = new ArrayList<>(waiting);
        waiting.clear();
        inFlight = false;

        for (StarredCallback callback : toNotify) {
            callback.onResult(starred);
        }
    }
}
