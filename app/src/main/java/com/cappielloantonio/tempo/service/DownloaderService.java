package com.cappielloantonio.tempo.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.C;
import androidx.media3.common.util.NotificationUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.scheduler.PlatformScheduler;
import androidx.media3.exoplayer.scheduler.Requirements;
import androidx.media3.exoplayer.scheduler.Scheduler;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.DownloadUtil;

import java.util.List;

@UnstableApi
public class DownloaderService extends androidx.media3.exoplayer.offline.DownloadService {

    private static final int JOB_ID = 1;
    private static final int FOREGROUND_NOTIFICATION_ID = 1;
    private static final int COMPLETED_NOTIFICATION_ID = FOREGROUND_NOTIFICATION_ID + 1;
    private static final int FAILED_NOTIFICATION_ID = FOREGROUND_NOTIFICATION_ID + 2;

    public DownloaderService() {
        super(FOREGROUND_NOTIFICATION_ID, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL, DownloadUtil.DOWNLOAD_NOTIFICATION_CHANNEL_ID, R.string.exo_download_notification_channel_name, 0);
    }

    @NonNull
    @Override
    protected DownloadManager getDownloadManager() {
        DownloadManager downloadManager = DownloadUtil.getDownloadManager(this);
        downloadManager.addListener(new TerminalStateNotificationHelper(this));
        return downloadManager;
    }

    @NonNull
    @Override
    protected Scheduler getScheduler() {
        return new PlatformScheduler(this, JOB_ID);
    }

    @NonNull
    @Override
    protected Notification getForegroundNotification(@NonNull List<Download> downloads, @Requirements.RequirementFlags int notMetRequirements) {
        return DownloadUtil.getDownloadNotificationHelper(this).buildProgressNotification(this, R.drawable.ic_download, null, null, downloads, notMetRequirements);
    }

    private static final class TerminalStateNotificationHelper implements DownloadManager.Listener {
        private final TerminalStateTally completed;
        private final TerminalStateTally failed;

        public TerminalStateNotificationHelper(Context context) {
            completed = new TerminalStateTally(context, COMPLETED_NOTIFICATION_ID, R.drawable.ic_check_circle, R.string.download_notification_completed);
            failed = new TerminalStateTally(context, FAILED_NOTIFICATION_ID, R.drawable.ic_error, R.string.download_notification_failed);
        }

        @Override
        public void onDownloadChanged(@NonNull DownloadManager downloadManager, Download download, @Nullable Exception finalException) {
            if (download.state == Download.STATE_COMPLETED) {
                completed.add(DownloaderManager.getDownloadNotificationMessage(download.request.id));
                DownloaderManager.updateRequestDownload(download);
            } else if (download.state == Download.STATE_FAILED) {
                failed.add(DownloaderManager.getDownloadNotificationMessage(download.request.id));
            }
        }

        @Override
        public void onDownloadRemoved(@NonNull DownloadManager downloadManager, Download download) {
            DownloaderManager.removeRequestDownload(download);
        }
    }

    /**
     * One notification counting every download that ended in the same state:
     * "download all" on a playlist posts "Downloaded: 50 tracks" once rather
     * than fifty notifications, and a track downloaded by hand afterwards bumps
     * that number instead of posting its own.
     */
    private static final class TerminalStateTally {
        private static final String EXTRA_COUNT = "com.cappielloantonio.tempo.DownloadTallyCount";

        /*
         * The system silently drops updates past roughly five a second from one
         * app, which would leave the shade showing a count short of the truth.
         */
        private static final long MIN_UPDATE_INTERVAL_MS = 500;

        /*
         * notify() reaches the system asynchronously, so a notification posted
         * this recently may not be listed as active yet.
         */
        private static final long SETTLE_MS = 2000;

        private final Context context;
        private final Handler handler = Util.createHandlerForCurrentOrMainLooper();
        private final Runnable publishRunnable = this::publish;
        private final int notificationId;
        @DrawableRes private final int icon;
        @StringRes private final int title;
        private final PendingIntent contentIntent;

        private int count;
        @Nullable private String latestTrackTitle;
        private boolean publishScheduled;
        private long publishedAtMs = C.TIME_UNSET;

        TerminalStateTally(Context context, int notificationId, @DrawableRes int icon, @StringRes int title) {
            this.context = context.getApplicationContext();
            this.notificationId = notificationId;
            this.icon = icon;
            this.title = title;

            /*
             * A tap opens the downloads tab, where what the count is counting
             * can be seen. NEW_TASK and SINGLE_TOP bring back the task that is
             * already there, as the media notification does (see MediaService).
             */
            contentIntent = PendingIntent.getActivity(
                    this.context,
                    0,
                    new Intent(this.context, MainActivity.class)
                            .setAction(Constants.ACTION_SHOW_DOWNLOADS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );
        }

        void add(@Nullable String trackTitle) {
            long now = SystemClock.elapsedRealtime();

            if (publishedAtMs == C.TIME_UNSET || now - publishedAtMs >= SETTLE_MS) {
                // Carry on from what the shade shows: nothing once the user has swiped it away, the old number if the process was restarted meanwhile.
                count = shownCount();
            }

            count++;
            if (trackTitle != null) latestTrackTitle = trackTitle;

            if (publishScheduled) return;

            long wait = publishedAtMs == C.TIME_UNSET ? 0 : publishedAtMs + MIN_UPDATE_INTERVAL_MS - now;
            if (wait <= 0) {
                publish();
            } else {
                publishScheduled = true;
                handler.postDelayed(publishRunnable, wait);
            }
        }

        private void publish() {
            publishScheduled = false;
            publishedAtMs = SystemClock.elapsedRealtime();

            Bundle extras = new Bundle();
            extras.putInt(EXTRA_COUNT, count);

            Notification notification = new NotificationCompat.Builder(context, DownloadUtil.DOWNLOAD_NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(icon)
                    .setContentTitle(context.getString(title, context.getResources().getQuantityString(R.plurals.download_track_count, count, count)))
                    .setContentText(latestTrackTitle)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true)
                    .addExtras(extras)
                    .build();

            NotificationUtil.setNotification(context, notificationId, notification);
        }

        private int shownCount() {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

            for (StatusBarNotification shown : manager.getActiveNotifications()) {
                if (shown.getId() == notificationId) {
                    return shown.getNotification().extras.getInt(EXTRA_COUNT, 0);
                }
            }

            return 0;
        }
    }
}
