package com.cappielloantonio.tempo.service;

import android.app.Notification;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.scheduler.Requirements;
import androidx.media3.exoplayer.scheduler.Scheduler;

import com.cappielloantonio.tempo.util.DownloadUtil;

import java.util.List;

/**
 * Runs the downloads smart download makes - see {@link SmartDownloads}.
 */
@UnstableApi
public class SmartDownloaderService extends androidx.media3.exoplayer.offline.DownloadService {

    public SmartDownloaderService() {
        /*
         * No notification: these are tracks saved while the user listens, and
         * the point is that it happens unseen. Staying a plain background
         * service is also what lets the player start it with the app out of
         * sight - the player's own foreground service keeps the process alive
         * in the meantime.
         */
        super(FOREGROUND_NOTIFICATION_ID_NONE);
    }

    @NonNull
    @Override
    protected DownloadManager getDownloadManager() {
        Context context = getApplicationContext();
        DownloadManager downloadManager = DownloadUtil.getSmartDownloadManager(context);

        downloadManager.addListener(new DownloadManager.Listener() {
            @Override
            public void onDownloadChanged(@NonNull DownloadManager manager, @NonNull Download download, @Nullable Exception finalException) {
                if (download.state == Download.STATE_COMPLETED) {
                    DownloaderManager.updateRequestDownload(download);
                    SmartDownloads.trim(context);
                } else if (download.state == Download.STATE_FAILED) {
                    // Off the list, so the next listen can try it afresh.
                    DownloadUtil.getDownloadTracker(context).remove(download.request.id);
                }
            }

            @Override
            public void onDownloadRemoved(@NonNull DownloadManager manager, @NonNull Download download) {
                DownloaderManager.removeRequestDownload(download);
            }
        });

        return downloadManager;
    }

    @Nullable
    @Override
    protected Scheduler getScheduler() {
        return null;
    }

    @NonNull
    @Override
    protected Notification getForegroundNotification(@NonNull List<Download> downloads, @Requirements.RequirementFlags int notMetRequirements) {
        // Never asked for: with no notification id the service is never put in the foreground.
        throw new UnsupportedOperationException();
    }
}
