package com.cappielloantonio.tempo.glide;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.Log;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.signature.ObjectKey;
import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.service.OfflineExtras;
import com.cappielloantonio.tempo.util.Preferences;
import com.cappielloantonio.tempo.util.ServerAddress;
import com.cappielloantonio.tempo.util.Util;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.Objects;

public class CustomGlideRequest {
    private static final String TAG = "CustomGlideRequest";

    /**
     * Cover radius in pixels. The preference is expressed in dp (6dp is the
     * DESIGN.md card/image radius) while Glide's RoundedCorners takes pixels,
     * so the density conversion happens here.
     * <p>
     * Covers are always rounded: square ones sat among rounded cards, buttons
     * and sheets as the one thing on screen with sharp corners.
     */
    public static final int CORNER_RADIUS = dpToPx(Preferences.getRoundedCornerSize());

    private static int dpToPx(int dp) {
        return Math.max(1, Math.round(dp * Resources.getSystem().getDisplayMetrics().density));
    }

    public static final DiskCacheStrategy DEFAULT_DISK_CACHE_STRATEGY = DiskCacheStrategy.ALL;

    public enum ResourceType {
        Unknown,
        Album,
        Artist,
        Folder,
        Directory,
        Playlist,
        Podcast,
        Radio,
        Song,
    }

    public static RequestOptions createRequestOptions(Context context, String item, ResourceType type) {
        RequestOptions options = new RequestOptions()
                .placeholder(new ColorDrawable(placeholderColor(context)))
                .fallback(getPlaceholder(context, type))
                .error(getPlaceholder(context, type))
                .diskCacheStrategy(DEFAULT_DISK_CACHE_STRATEGY)
                .signature(new ObjectKey(item != null ? item : 0));

        /*
         * DESIGN.md "Geometry Rhythm": artist photography is always a circular
         * crop ("the circle IS the visual"), every other cover is a square at
         * the 6dp image radius. The alternation between the two is what makes
         * the feed read as Spotify.
         */
        return type == ResourceType.Artist
                ? options.transform(new CircleCrop())
                : options.transform(new CenterCrop(), new RoundedCorners(CORNER_RADIUS));
    }

    private static int placeholderColor(Context context) {
        return ContextCompat.getColor(context, R.color.artworkPlaceholderColor);
    }

    @Nullable
    private static Drawable getPlaceholder(Context context, ResourceType type) {
        switch (type) {
            case Album:
                return AppCompatResources.getDrawable(context, R.drawable.ic_placeholder_album);
            case Artist:
                return AppCompatResources.getDrawable(context, R.drawable.ic_placeholder_artist);
            case Folder:
                return AppCompatResources.getDrawable(context, R.drawable.ic_placeholder_folder);
            case Directory:
                return AppCompatResources.getDrawable(context, R.drawable.ic_placeholder_directory);
            case Playlist:
                return AppCompatResources.getDrawable(context, R.drawable.ic_placeholder_playlist);
            case Podcast:
                return AppCompatResources.getDrawable(context, R.drawable.ic_placeholder_podcast);
            case Radio:
                return AppCompatResources.getDrawable(context, R.drawable.ic_placeholder_radio);
            case Song:
                return AppCompatResources.getDrawable(context, R.drawable.ic_placeholder_song);
            default:
            case Unknown:
                return new ColorDrawable(placeholderColor(context));
        }
    }

    /**
     * Loads a cover into a view that may already be showing it.
     * <p>
     * The player's swipe carousel refills the sleeves parked on either side of
     * the queue far more often than they actually change - a queue edited, a
     * shuffle or repeat mode toggled, a track landing - and a plain load would
     * start a fresh request, and a fresh fade, every time. What the view is
     * showing is remembered on the view, so a rebind that changes nothing costs
     * nothing.
     * <p>
     * The note is written only once the artwork is actually on screen. Claiming
     * it up front would mean a request that never lands - the server unreachable
     * for a moment, the load cancelled - left the view insisting it was showing
     * a cover it had never received, and every later attempt at that track would
     * be skipped: one missed request and the sleeve stays frozen for good. Glide
     * keeps an identical request that is already running rather than restarting
     * it, so asking again while one is in flight costs nothing either.
     */
    public static void loadCover(Context context, ImageView view, @Nullable String item, ResourceType type) {
        if (Objects.equals(view.getTag(R.id.cover_art_tag), item)) return;

        view.setTag(R.id.cover_art_tag, null);

        Builder.from(context, item, type)
                .build()
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException exception, @Nullable Object model, @NonNull Target<Drawable> target, boolean isFirstResource) {
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model, Target<Drawable> target, @NonNull DataSource dataSource, boolean isFirstResource) {
                        view.setTag(R.id.cover_art_tag, item);
                        return false;
                    }
                })
                .into(view);
    }

    public static String createUrl(String item, int size) {
        Map<String, String> params = App.getSubsonicClientInstance(false).getParams();

        /*
         * Every value is URL-encoded: a cleartext password (low security mode)
         * or a username may contain &, +, % or a space, and splicing those in
         * raw produced a URL the server read as a different set of parameters.
         */
        StringBuilder query = Util.authenticationQuery(params);

        if (size != -1) Util.appendQueryParam(query, "size", String.valueOf(size));
        Util.appendQueryParam(query, "id", item);

        String uri = App.getSubsonicClientInstance(false).getUrl() + "getCoverArt" + query;

        Log.d(TAG, "createUrl() " + uri);

        return uri;
    }

    /**
     * The cover as Glide should be asked for it: the copy kept with a downloaded
     * track when there is one, the server's otherwise.
     *
     * @param allowNetwork whether the server may be asked when no copy is kept -
     *                     a kept cover costs no data, so data saving still shows it
     * @return null when there is nothing to load
     */
    @Nullable
    public static Object coverModel(Context context, @Nullable String item, boolean allowNetwork) {
        if (item == null) return null;

        File kept = OfflineExtras.keptCover(context, item);
        if (kept != null) return kept;

        return allowNetwork ? createGlideUrl(item, Preferences.getImageSize()) : null;
    }

    /**
     * The server's cover address, keyed in Glide's cache by what it shows rather
     * than by how it was asked for.
     * <p>
     * The address carries the credentials, and with a saved password the salt -
     * and so the token - is made afresh every launch. Glide keys its disk cache
     * on the address, so every cover cached in one session was a stranger to the
     * next: harmless while the server could be asked again, a blank square the
     * moment it could not. For the same reason the key names the server by its
     * main address, whichever one the cover was fetched through: a cover cached
     * at home, over the local address, is the same cover away.
     */
    public static GlideUrl createGlideUrl(String item, int size) {
        String cacheKey = ServerAddress.stableBase() + "/rest/getCoverArt?id=" + item + "&size=" + size;

        return new GlideUrl(createUrl(item, size)) {
            @Override
            public String getCacheKey() {
                return cacheKey;
            }

            /*
             * Fetched from the address in use when the fetch happens, as tracks
             * are: a request made at home and run after leaving it goes to the
             * main address instead of timing out on the home one.
             */
            @Override
            public String toStringUrl() {
                return ServerAddress.rewrite(Uri.parse(super.toStringUrl())).toString();
            }

            @Override
            public URL toURL() throws MalformedURLException {
                return new URL(toStringUrl());
            }
        };
    }

    public static class Builder {
        private final RequestManager requestManager;
        private final Object item;
        private final RequestOptions options;

        /*
         * The options belong to this one request. They used to be pushed into
         * the RequestManager's defaults - one manager per screen, shared by
         * every load on it - so each cover rewrote the placeholder, signature
         * and crop that any other load through that manager started from.
         */
        private Builder(Context context, String item, ResourceType type) {
            this.requestManager = Glide.with(context);
            this.item = coverModel(context, item, !Preferences.isDataSavingMode());
            this.options = createRequestOptions(context, item, type);
        }

        public static Builder from(Context context, String item, ResourceType type) {
            return new Builder(context, item, type);
        }

        public RequestBuilder<Drawable> build() {
            return requestManager
                    .load(item)
                    .apply(options)
                    .transition(DrawableTransitionOptions.withCrossFade());
        }
    }
}
