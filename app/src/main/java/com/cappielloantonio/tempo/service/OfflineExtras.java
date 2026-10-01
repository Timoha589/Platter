package com.cappielloantonio.tempo.service;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.media3.common.util.UnstableApi;

import com.bumptech.glide.Glide;
import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.database.dao.DownloadDao;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.repository.LyricsRepository;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.util.NetworkUtil;
import com.cappielloantonio.tempo.util.Preferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Everything a downloaded track is shown with, kept on the device next to its
 * audio: the cover and the lyrics.
 * <p>
 * A download used to be the audio file and nothing more. The cover lived only
 * in Glide's cache - an LRU shared with every image the app ever showed, and
 * keyed by an address whose token changed each launch, so it was as good as
 * gone the moment the server could not be asked - and the words were never
 * saved anywhere at all. Offline, a downloaded track played as a blank square
 * with an empty lyrics page.
 * <p>
 * Covers are kept once per cover id, since a whole album shares one, and a
 * cover goes when no download still points at it.
 */
@UnstableApi
public final class OfflineExtras {
    private static final String TAG = "OfflineExtras";

    private static final String COVER_DIRECTORY = "covers";

    /*
     * A cover is saved as soon as a download is asked for, while the record
     * naming it is written on a thread of its own. One saved a moment ago is
     * left alone rather than read as belonging to nothing.
     */
    private static final long PRUNE_GRACE_MS = 60_000;

    private static final Executor executor = Executors.newSingleThreadExecutor();

    private OfflineExtras() {
    }

    /** Saves the cover and lyrics for a track that is being downloaded. */
    public static void keep(Context context, Child track) {
        Context app = context.getApplicationContext();

        executor.execute(() -> {
            if (NetworkUtil.isOffline()) return;

            new LyricsRepository().saveIfMissing(track);
            saveCover(app, track.getCoverArtId());
        });
    }

    /** Lets go of what was kept for a track that is no longer downloaded. */
    public static void forget(Context context, String id) {
        Context app = context.getApplicationContext();

        executor.execute(() -> {
            new LyricsRepository().delete(id);
            pruneCovers(app);
        });
    }

    public static void forgetAll(Context context) {
        Context app = context.getApplicationContext();

        executor.execute(() -> {
            new LyricsRepository().deleteAll();

            File[] covers = coverDirectory(app).listFiles();
            if (covers != null) for (File cover : covers) cover.delete();
        });
    }

    /**
     * Fills in what is missing for downloads made before any of this was kept,
     * or while the server could not be reached. Runs once each launch; a track
     * that already has its cover and words costs one query and no requests.
     */
    public static void catchUp(Context context) {
        Context app = context.getApplicationContext();

        executor.execute(() -> {
            if (NetworkUtil.isOffline()) return;

            DownloadDao downloadDao = AppDatabase.getInstance().downloadDao();
            LyricsRepository lyricsRepository = new LyricsRepository();

            for (Download download : downloadDao.getWithoutLyrics()) {
                // A server that cannot be reached would fail every one of them the same way.
                if (!lyricsRepository.saveIfMissing(download)) return;
            }

            for (String coverArtId : downloadDao.getCoverArtIds()) {
                saveCover(app, coverArtId);
            }

            pruneCovers(app);
        });
    }

    /** The cover kept with a download, or null when none is. */
    @Nullable
    public static File keptCover(Context context, String coverArtId) {
        File cover = coverFile(context, coverArtId);
        return cover.isFile() ? cover : null;
    }

    private static File coverDirectory(Context context) {
        return new File(context.getFilesDir(), COVER_DIRECTORY);
    }

    private static File coverFile(Context context, String coverArtId) {
        return new File(coverDirectory(context), coverArtId.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    @WorkerThread
    private static void saveCover(Context context, @Nullable String coverArtId) {
        if (coverArtId == null) return;

        File cover = coverFile(context, coverArtId);
        File partial = new File(cover.getPath() + ".part");

        if (cover.isFile()) {
            if (isImage(cover)) return;

            // Left by a build that kept whatever the server sent; asked for again below.
            cover.delete();
        }

        try {
            // Through Glide, so a cover already on screen is copied out of its cache rather than fetched again.
            File fetched = Glide.with(context)
                    .asFile()
                    .load(CustomGlideRequest.createGlideUrl(coverArtId, Preferences.getImageSize()))
                    .submit()
                    .get();

            /*
             * A track the server no longer has still answers getCoverArt with a
             * success and a few bytes of error. Kept, that would stand in for the
             * cover for good: it is never fetched again, and never decodes.
             */
            if (!isImage(fetched)) return;

            coverDirectory(context).mkdirs();
            copy(fetched, partial);

            // Renamed into place, so a half-written file is never taken for a cover.
            if (!partial.renameTo(cover)) partial.delete();
        } catch (ExecutionException | IOException e) {
            Log.w(TAG, "Cover " + coverArtId + " could not be saved", e);
            partial.delete();
        } catch (InterruptedException e) {
            partial.delete();
            Thread.currentThread().interrupt();
        }
    }

    @WorkerThread
    private static void pruneCovers(Context context) {
        File[] covers = coverDirectory(context).listFiles();
        if (covers == null) return;

        Set<String> inUse = new HashSet<>();
        for (String coverArtId : AppDatabase.getInstance().downloadDao().getCoverArtIds()) {
            inUse.add(coverFile(context, coverArtId).getName());
        }

        long now = System.currentTimeMillis();

        for (File cover : covers) {
            if (!inUse.contains(cover.getName()) && now - cover.lastModified() > PRUNE_GRACE_MS) cover.delete();
        }
    }

    /* Reads the header only, which is all it takes to tell an image from an error message. */
    private static boolean isImage(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;

        BitmapFactory.decodeFile(file.getPath(), options);

        return options.outWidth > 0 && options.outHeight > 0;
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[16 * 1024];
            int read;

            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
    }
}
