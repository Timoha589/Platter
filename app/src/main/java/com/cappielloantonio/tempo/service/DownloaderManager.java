package com.cappielloantonio.tempo.service;

import static androidx.media3.common.util.Assertions.checkNotNull;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadCursor;
import androidx.media3.exoplayer.offline.DownloadHelper;
import androidx.media3.exoplayer.offline.DownloadIndex;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.offline.DownloadService;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.repository.DownloadRepository;
import com.cappielloantonio.tempo.util.DownloadUtil;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@UnstableApi
public class DownloaderManager {
    private static final String TAG = "DownloaderManager";

    private final Context context;
    private final DataSource.Factory dataSourceFactory;

    private static HashMap<String, Download> downloads;

    /*
     * Which of the two download lists each track is on - see
     * DownloadUtil.getSmartDownloadManager(). A track is only ever on one:
     * both lists write the same cache, so taking a track off one would delete
     * the file the other still counts as downloaded. These are filled the
     * moment a download is asked for rather than when it completes, because
     * that is exactly the window in which both sides could ask for the same
     * track.
     */
    private static final Set<String> userIds = ConcurrentHashMap.newKeySet();
    private static final Set<String> smartIds = ConcurrentHashMap.newKeySet();

    public DownloaderManager(Context context, DataSource.Factory dataSourceFactory, DownloadManager downloadManager, DownloadManager smartDownloadManager) {
        this.context = context.getApplicationContext();
        this.dataSourceFactory = dataSourceFactory;

        downloads = new HashMap<>();
        userIds.clear();
        smartIds.clear();

        loadDownloads(downloadManager.getDownloadIndex(), userIds);
        loadDownloads(smartDownloadManager.getDownloadIndex(), smartIds);

        // The index has always noted when each download started; records from before downloaded_at take it from there.
        Map<String, Long> startedAt = new HashMap<>();
        for (Download download : downloads.values()) startedAt.put(download.request.id, download.startTimeMs);
        getDownloadRepository().fillInDownloadedAt(startedAt);

        OfflineExtras.catchUp(this.context);
    }

    private DownloadRequest buildDownloadRequest(MediaItem mediaItem) {
        return DownloadHelper
                .forMediaItem(
                        context,
                        mediaItem,
                        DownloadUtil.buildRenderersFactory(context, false),
                        dataSourceFactory)
                .getDownloadRequest(Util.getUtf8Bytes(checkNotNull(mediaItem.mediaId)))
                .copyWithId(mediaItem.mediaId);
    }

    public boolean isDownloaded(String mediaId) {
        @Nullable Download download = downloads.get(mediaId);
        return download != null && download.state != Download.STATE_FAILED;
    }

    public boolean isDownloaded(MediaItem mediaItem) {
        return isDownloaded(mediaItem.mediaId);
    }

    public boolean areDownloaded(List<MediaItem> mediaItems) {
        return mediaItems.stream().anyMatch(this::isDownloaded);
    }

    /* On either list, whether finished, still coming, or failed. */
    public boolean isRequested(String mediaId) {
        return userIds.contains(mediaId) || smartIds.contains(mediaId);
    }

    /* On the list smart download keeps, whatever its record now says it is for. */
    public boolean isInSmartCache(String mediaId) {
        return smartIds.contains(mediaId);
    }

    public void download(MediaItem mediaItem, com.cappielloantonio.tempo.model.Download download) {
        download(mediaItem, download, com.cappielloantonio.tempo.model.Download.SOURCE_MANUAL);
    }

    /**
     * @param source why the track is wanted, which later decides whether it may
     *               be taken off again - see {@code Download.downloadSource}
     */
    public void download(MediaItem mediaItem, com.cappielloantonio.tempo.model.Download download, int source) {
        download.setDownloadUri(mediaItem.requestMetadata.mediaUri.toString());
        download.setDownloadSource(source);
        if (download.getDownloadedAt() == 0) download.setDownloadedAt(System.currentTimeMillis());

        OfflineExtras.keep(context, download);

        if (smartIds.contains(mediaItem.mediaId)) {
            /*
             * Smart download already has it, or is fetching it. Rather than
             * download it again onto the other list, its record is rewritten,
             * which is all it takes for the track to stop counting as cache.
             */
            @Nullable Download existing = downloads.get(mediaItem.mediaId);
            download.setDownloadState(existing != null && existing.state == Download.STATE_COMPLETED ? 1 : 0);
            insertDatabase(download);
            return;
        }

        DownloadService.sendAddDownload(context, DownloaderService.class, buildDownloadRequest(mediaItem), false);
        userIds.add(mediaItem.mediaId);
        insertDatabase(download);
    }

    public void download(List<MediaItem> mediaItems, List<com.cappielloantonio.tempo.model.Download> downloads) {
        // One time for the whole album or playlist, or it would come out in reverse on the downloads screen.
        long now = System.currentTimeMillis();
        for (com.cappielloantonio.tempo.model.Download download : downloads) download.setDownloadedAt(now);

        for (int counter = 0; counter < mediaItems.size(); counter++) {
            download(mediaItems.get(counter), downloads.get(counter));
        }
    }

    /**
     * Smart download: saves a track the user has started listening to, onto
     * the list no notification is ever shown for. A track already on the
     * device, or on its way, only has its play time brought up to date - which
     * is what keeps a track in heavy rotation from being the next one evicted.
     */
    public void downloadInBackground(MediaItem mediaItem, com.cappielloantonio.tempo.model.Download download) {
        String id = mediaItem.mediaId;
        long now = System.currentTimeMillis();

        if (isRequested(id)) {
            getDownloadRepository().setLastPlayedAt(id, now);
            return;
        }

        if (!send(() -> DownloadService.sendAddDownload(context, SmartDownloaderService.class, buildDownloadRequest(mediaItem), false))) {
            return;
        }

        download.setDownloadUri(mediaItem.requestMetadata.mediaUri.toString());
        download.setDownloadSource(com.cappielloantonio.tempo.model.Download.SOURCE_SMART);
        download.setLastPlayedAt(now);
        download.setDownloadedAt(now);

        smartIds.add(id);
        insertDatabase(download);
        OfflineExtras.keep(context, download);
    }

    public void remove(MediaItem mediaItem, com.cappielloantonio.tempo.model.Download download) {
        remove(mediaItem.mediaId);
    }

    public void remove(List<MediaItem> mediaItems, List<com.cappielloantonio.tempo.model.Download> downloads) {
        for (int counter = 0; counter < mediaItems.size(); counter++) {
            remove(mediaItems.get(counter), downloads.get(counter));
        }
    }

    public void remove(String id) {
        final boolean smart = smartIds.contains(id);
        final Class<? extends DownloadService> service = smart ? SmartDownloaderService.class : DownloaderService.class;

        // Left as it is if the service cannot be reached: a record deleted here
        // would leave a file on the device that nothing lists.
        if (!send(() -> DownloadService.sendRemoveDownload(context, service, id, false))) return;

        (smart ? smartIds : userIds).remove(id);
        deleteDatabase(id);
        downloads.remove(id);
        OfflineExtras.forget(context, id);
    }

    public void removeAll() {
        DownloadService.sendRemoveAllDownloads(context, DownloaderService.class, false);
        send(() -> DownloadService.sendRemoveAllDownloads(context, SmartDownloaderService.class, false));
        userIds.clear();
        smartIds.clear();
        deleteAllDatabase();
        DownloadUtil.eraseDownloadFolder(context);
        OfflineExtras.forgetAll(context);
    }

    /*
     * Android refuses to start a service for an app it considers to be in the
     * background. The player's foreground service normally exempts us; when it
     * does not, the request is dropped rather than crashing whoever made it.
     */
    private boolean send(Runnable request) {
        try {
            request.run();
            return true;
        } catch (IllegalStateException e) {
            Log.w(TAG, "Download service could not be started", e);
            return false;
        }
    }

    private void loadDownloads(DownloadIndex downloadIndex, Set<String> ids) {
        try (DownloadCursor loadedDownloads = downloadIndex.getDownloads()) {
            while (loadedDownloads.moveToNext()) {
                Download download = loadedDownloads.getDownload();
                downloads.put(download.request.id, download);
                ids.add(download.request.id);
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to query downloads", e);
        }
    }

    public static String getDownloadNotificationMessage(String id) {
        com.cappielloantonio.tempo.model.Download download = getDownloadRepository().getDownload(id);
        return download != null ? download.getTitle() : null;
    }

    public static void updateRequestDownload(Download download) {
        updateDatabase(download.request.id);
        downloads.put(download.request.id, download);
    }

    public static void removeRequestDownload(Download download) {
        deleteDatabase(download.request.id);
        downloads.remove(download.request.id);
        userIds.remove(download.request.id);
        smartIds.remove(download.request.id);
        OfflineExtras.forget(App.getContext(), download.request.id);
    }

    private static DownloadRepository getDownloadRepository() {
        return new DownloadRepository();
    }

    private static void insertDatabase(com.cappielloantonio.tempo.model.Download download) {
        getDownloadRepository().insert(download);
    }

    private static void deleteDatabase(String id) {
        getDownloadRepository().delete(id);
    }

    private static void deleteAllDatabase() {
        getDownloadRepository().deleteAll();
    }

    private static void updateDatabase(String id) {
        getDownloadRepository().update(id);
    }
}
