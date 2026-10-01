package com.cappielloantonio.tempo.service;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.media3.common.util.BitmapLoader;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSourceBitmapLoader;

import com.bumptech.glide.Glide;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.concurrent.Executors;

/**
 * Artwork for the notification and the lock screen.
 * <p>
 * The session's own loader fetched each track's artwork address over the
 * network every time, so without a connection a downloaded track's
 * notification had no cover even while the player showed one. Covers from the
 * server are taken through Glide instead: the copy kept with a download when
 * there is one, the image cache after that, and the server only as a last
 * resort. Anything else is left to the default loader.
 */
@UnstableApi
public final class CoverBitmapLoader implements BitmapLoader {
    /* Generous for a notification, and still far short of a full-size scan. */
    private static final int SIZE = 1024;

    private final Context context;
    private final BitmapLoader fallback;
    private final ListeningExecutorService executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());

    public CoverBitmapLoader(Context context) {
        this.context = context.getApplicationContext();
        this.fallback = new DataSourceBitmapLoader(this.context);
    }

    @Override
    public boolean supportsMimeType(String mimeType) {
        return fallback.supportsMimeType(mimeType);
    }

    @Override
    public ListenableFuture<Bitmap> decodeBitmap(byte[] data) {
        return fallback.decodeBitmap(data);
    }

    @Override
    public ListenableFuture<Bitmap> loadBitmap(Uri uri) {
        String coverArtId = "getCoverArt".equals(uri.getLastPathSegment()) ? uri.getQueryParameter("id") : null;

        if (coverArtId == null) return fallback.loadBitmap(uri);

        return executor.submit(() -> Glide.with(context)
                .asBitmap()
                .load(CustomGlideRequest.coverModel(context, coverArtId, true))
                .submit(SIZE, SIZE)
                .get());
    }
}
