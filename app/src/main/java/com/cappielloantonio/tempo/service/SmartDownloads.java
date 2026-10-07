package com.cappielloantonio.tempo.service;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.repository.DownloadRepository;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.DownloadUtil;
import com.cappielloantonio.tempo.util.FavoriteState;
import com.cappielloantonio.tempo.util.MappingUtil;
import com.cappielloantonio.tempo.util.Preferences;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Smart download: a track the user listens to <i>and</i> has liked is saved for
 * offline play, with no notification, and the ones played longest ago are let
 * go once there are more than the size chosen in settings.
 * <p>
 * Listening alone saves nothing. A track liked while it plays, or just after it
 * started, is saved the moment the like is known - the like on the server can
 * be newer than the one the queue was built with, so it is waited for too.
 * <p>
 * Only tracks saved this way are ever let go. One the user downloaded, or one
 * kept because it is liked, is out of the count - even if smart download
 * happened to be the one that saved it first.
 */
@UnstableApi
public final class SmartDownloads {
    private static final Executor executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    /* Pausing and resuming a track is not listening to it again. */
    @Nullable
    private static String lastListenedId;

    /* The track being listened to, for a like that comes after it started. */
    @Nullable
    private static MediaItem listening;

    private SmartDownloads() {
    }

    @MainThread
    public static void onListening(Context context, @Nullable MediaItem mediaItem) {
        if (mediaItem == null || !Preferences.isSmartDownloadEnabled()) return;

        Bundle extras = mediaItem.mediaMetadata.extras;

        // Radio streams and podcast episodes are not library tracks.
        if (extras == null || !Constants.MEDIA_TYPE_MUSIC.equals(extras.getString("type"))) return;

        String id = extras.getString("id");

        if (id == null || id.equals(lastListenedId)) return;
        lastListenedId = id;
        listening = mediaItem;

        saveIfWanted(context, mediaItem);
    }

    /** The like on a track changed: if it is the one being listened to and is now liked, it is saved. */
    @MainThread
    public static void onFavoriteChanged(Context context, @Nullable String mediaId) {
        if (mediaId == null || listening == null || !mediaId.equals(lastListenedId)) return;
        if (!Preferences.isSmartDownloadEnabled()) return;

        saveIfWanted(context, listening);
    }

    private static void saveIfWanted(Context context, MediaItem mediaItem) {
        Bundle extras = mediaItem.mediaMetadata.extras;
        if (extras == null) return;

        String id = extras.getString("id");
        DownloaderManager manager = DownloadUtil.getDownloadTracker(context);
        boolean liked = FavoriteState.isFavorite(id, extras.getLong("starred") != 0L);

        // One already saved only has its play time brought up to date.
        if (!liked && !manager.isInSmartCache(id)) return;

        Download download = new Download(mediaItem);
        manager.downloadInBackground(MappingUtil.mapDownload(download), download);

        trim(context);
    }

    /** Lets go of the smart-downloaded tracks played longest ago, down to the chosen size. */
    public static void trim(Context context) {
        Context app = context.getApplicationContext();
        int size = Preferences.getSmartDownloadCacheSize();

        executor.execute(() -> {
            List<String> ids = new DownloadRepository().getIdsBySource(Download.SOURCE_SMART);

            if (ids.size() <= size) return;

            List<String> stalest = new ArrayList<>(ids.subList(0, ids.size() - size));

            mainHandler.post(() -> {
                DownloaderManager manager = DownloadUtil.getDownloadTracker(app);
                for (String id : stalest) manager.remove(id);
            });
        });
    }
}
