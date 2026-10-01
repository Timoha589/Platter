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
import com.cappielloantonio.tempo.util.MappingUtil;
import com.cappielloantonio.tempo.util.Preferences;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Smart download: a track the user listens to is saved for offline play as it
 * plays, with no notification, and the ones played longest ago are let go
 * once there are more than the size chosen in settings.
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

        Download download = new Download(mediaItem);
        DownloadUtil.getDownloadTracker(context).downloadInBackground(MappingUtil.mapDownload(download), download);

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
