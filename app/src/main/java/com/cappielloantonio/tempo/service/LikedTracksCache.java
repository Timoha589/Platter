package com.cappielloantonio.tempo.service;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.repository.DownloadRepository;
import com.cappielloantonio.tempo.repository.StarredRepository;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.util.DownloadUtil;
import com.cappielloantonio.tempo.util.MappingUtil;
import com.cappielloantonio.tempo.util.Preferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * "Cache liked tracks", ticked on the liked tracks page: every liked track is
 * kept downloaded - the ones liked before the box was ticked, and each one
 * liked after, whether or not it has ever been played.
 * <p>
 * These downloads are marked as made for the like, so unliking a track takes
 * away only a copy that was there because of it; one the user downloaded by
 * hand stays. Unticking the box stops new downloads and keeps what is there.
 * <p>
 * They go through the ordinary download list, notification included: ticking
 * the box can start hundreds of downloads, and those need the foreground
 * service to survive the app being closed.
 */
@UnstableApi
public final class LikedTracksCache {
    private static final Executor executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private LikedTracksCache() {
    }

    /**
     * Brings the device into line with what is liked on the server: downloads
     * what is missing, and takes back the copies of tracks unliked elsewhere.
     */
    @MainThread
    public static void sync(Context context) {
        if (!Preferences.isLikedTracksCacheEnabled()) return;

        Context app = context.getApplicationContext();

        StarredRepository.get(starred -> {
            // null is a failed request, not an empty list: nothing may be taken back on it.
            if (starred == null || !Preferences.isLikedTracksCacheEnabled()) return;

            DownloaderManager manager = DownloadUtil.getDownloadTracker(app);
            Set<String> liked = new HashSet<>();
            List<String> heldAsCache = new ArrayList<>();

            for (Child song : StarredRepository.sample(starred.getSongs(), false, -1)) {
                liked.add(song.getId());

                if (manager.isInSmartCache(song.getId())) {
                    heldAsCache.add(song.getId());
                } else if (!manager.isRequested(song.getId())) {
                    manager.download(MappingUtil.mapDownload(song), new Download(song), Download.SOURCE_LIKED);
                }
            }

            executor.execute(() -> {
                DownloadRepository repository = new DownloadRepository();

                // Already on the device as cache: now it stays for the like.
                repository.changeSource(heldAsCache, Download.SOURCE_SMART, Download.SOURCE_LIKED);

                List<String> unliked = repository.getIdsBySource(Download.SOURCE_LIKED).stream()
                        .filter(id -> !liked.contains(id))
                        .collect(Collectors.toList());

                if (unliked.isEmpty()) return;

                mainHandler.post(() -> {
                    for (String id : unliked) DownloadUtil.getDownloadTracker(app).remove(id);
                });
            });
        });
    }

    @MainThread
    public static void onLiked(Context context, @Nullable Child song) {
        if (song == null || song.getId() == null || !Preferences.isLikedTracksCacheEnabled()) return;

        DownloaderManager manager = DownloadUtil.getDownloadTracker(context);

        if (manager.isInSmartCache(song.getId())) {
            executor.execute(() -> new DownloadRepository().changeSource(
                    Collections.singletonList(song.getId()), Download.SOURCE_SMART, Download.SOURCE_LIKED));
        } else if (!manager.isRequested(song.getId())) {
            manager.download(MappingUtil.mapDownload(song), new Download(song), Download.SOURCE_LIKED);
        }
    }

    /*
     * Whether or not the box is still ticked: a copy made for a like has no
     * reason to be on the device once the like is gone.
     */
    @MainThread
    public static void onUnliked(Context context, @Nullable String id) {
        if (id == null) return;

        Context app = context.getApplicationContext();

        executor.execute(() -> {
            Download download = new DownloadRepository().getDownload(id);

            if (download == null || download.getDownloadSource() != Download.SOURCE_LIKED) return;

            mainHandler.post(() -> DownloadUtil.getDownloadTracker(app).remove(id));
        });
    }
}
