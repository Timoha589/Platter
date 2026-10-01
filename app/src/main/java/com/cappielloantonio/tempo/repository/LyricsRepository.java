package com.cappielloantonio.tempo.repository;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.MutableLiveData;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.database.dao.OfflineLyricsDao;
import com.cappielloantonio.tempo.model.OfflineLyrics;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.Lyrics;
import com.cappielloantonio.tempo.subsonic.models.LyricsList;
import com.cappielloantonio.tempo.util.DownloadUtil;
import com.cappielloantonio.tempo.util.OpenSubsonicExtensionsUtil;
import com.google.gson.Gson;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Lyrics for the player, from the copy kept with a downloaded track first and
 * from the server after.
 * <p>
 * They used to come from the server and nowhere else, so a track saved for
 * offline play came back wordless as soon as there was no connection. A
 * downloaded track now has its words saved with it (see OfflineExtras), and
 * every request answers with that copy straight away before asking the server,
 * whose answer - when there is one - replaces it on screen and on disk.
 */
@OptIn(markerClass = UnstableApi.class)
public class LyricsRepository {
    private static final String TAG = "LyricsRepository";

    private static final Executor executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Gson gson = new Gson();

    private final OfflineLyricsDao offlineLyricsDao = AppDatabase.getInstance().offlineLyricsDao();

    public MutableLiveData<LyricsList> getLyricsList(Child song) {
        MutableLiveData<LyricsList> lyricsList = new MutableLiveData<>();
        String id = song.getId();

        executor.execute(() -> {
            OfflineLyrics saved = offlineLyricsDao.getOne(id);
            String savedJson = saved != null ? saved.getLyricsList() : null;

            if (savedJson != null) lyricsList.postValue(gson.fromJson(savedJson, LyricsList.class));

            // Queued behind the post above, so the saved copy is always the one overridden.
            mainHandler.post(() -> App.getSubsonicClientInstance(false)
                    .getOpenClient()
                    .getLyricsBySongId(id)
                    .enqueue(new Callback<ApiResponse>() {
                        @Override
                        public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                            if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse().getLyricsList() != null) {
                                LyricsList fresh = response.body().getSubsonicResponse().getLyricsList();
                                String freshJson = gson.toJson(fresh);

                                // The same words again would only rebuild the page and restart its highlight.
                                if (freshJson.equals(savedJson)) return;

                                lyricsList.setValue(fresh);
                                keepIfDownloaded(new OfflineLyrics(id, null, freshJson));
                            }
                        }

                        @Override
                        public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {

                        }
                    }));
        });

        return lyricsList;
    }

    public MutableLiveData<String> getLyrics(Child song) {
        MutableLiveData<String> lyrics = new MutableLiveData<>(null);
        String id = song.getId();

        executor.execute(() -> {
            OfflineLyrics saved = offlineLyricsDao.getOne(id);
            String savedValue = saved != null ? saved.getLyrics() : null;

            if (savedValue != null) lyrics.postValue(savedValue);

            mainHandler.post(() -> App.getSubsonicClientInstance(false)
                    .getMediaRetrievalClient()
                    .getLyrics(song.getArtist(), song.getTitle())
                    .enqueue(new Callback<ApiResponse>() {
                        @Override
                        public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                            if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse().getLyrics() != null) {
                                String fresh = response.body().getSubsonicResponse().getLyrics().getValue();

                                if (Objects.equals(fresh, savedValue)) return;

                                lyrics.setValue(fresh);
                                keepIfDownloaded(new OfflineLyrics(id, fresh, null));
                            }
                        }

                        @Override
                        public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {

                        }
                    }));
        });

        return lyrics;
    }

    /* A track that is only being streamed has nothing to be kept with. */
    private void keepIfDownloaded(OfflineLyrics lyrics) {
        if (!DownloadUtil.getDownloadTracker(App.getContext()).isRequested(lyrics.getId())) return;

        executor.execute(() -> offlineLyricsDao.insert(lyrics));
    }

    /**
     * Asks the server for a track's words and keeps the answer, unless words are
     * already kept for it.
     *
     * @return false only when the server could not be reached at all, which is
     * the caller's cue to stop asking about the rest of its tracks
     */
    @WorkerThread
    public boolean saveIfMissing(Child song) {
        if (offlineLyricsDao.getOne(song.getId()) != null) return true;

        try {
            @Nullable OfflineLyrics lyrics = OpenSubsonicExtensionsUtil.isSongLyricsExtensionAvailable()
                    ? fetchLyricsList(song)
                    : fetchLyrics(song);

            // No row for an error answer, so the track is asked about again next time.
            if (lyrics != null) offlineLyricsDao.insert(lyrics);

            return true;
        } catch (IOException e) {
            Log.w(TAG, "Lyrics for " + song.getId() + " could not be fetched", e);
            return false;
        }
    }

    @Nullable
    private OfflineLyrics fetchLyricsList(Child song) throws IOException {
        Response<ApiResponse> response = App.getSubsonicClientInstance(false)
                .getOpenClient()
                .getLyricsBySongId(song.getId())
                .execute();

        if (!response.isSuccessful() || response.body() == null) return null;

        // A track without words still answers with a list, just an empty one.
        LyricsList list = response.body().getSubsonicResponse().getLyricsList();

        return list != null ? new OfflineLyrics(song.getId(), null, gson.toJson(list)) : null;
    }

    @Nullable
    private OfflineLyrics fetchLyrics(Child song) throws IOException {
        Response<ApiResponse> response = App.getSubsonicClientInstance(false)
                .getMediaRetrievalClient()
                .getLyrics(song.getArtist(), song.getTitle())
                .execute();

        if (!response.isSuccessful() || response.body() == null) return null;

        Lyrics lyrics = response.body().getSubsonicResponse().getLyrics();

        return lyrics != null ? new OfflineLyrics(song.getId(), lyrics.getValue(), null) : null;
    }

    @WorkerThread
    public void delete(String id) {
        offlineLyricsDao.delete(id);
    }

    @WorkerThread
    public void deleteAll() {
        offlineLyricsDao.deleteAll();
    }
}
