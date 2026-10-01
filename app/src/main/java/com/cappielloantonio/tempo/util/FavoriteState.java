package com.cappielloantonio.tempo.util;

import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.wave.WaveState;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which tracks have been favourited or unfavourited since the app started.
 * <p>
 * The favourite state of a track reaches the app twice over, from two places
 * that do not talk to each other: the player sheet holds a {@link Child} it
 * fetched from the server, and the notification has only the extras the
 * {@code MediaItem} was built with. Toggling in one left the other showing the
 * state from before - and, worse, toggling from the stale side would send the
 * wrong instruction to the server.
 * <p>
 * This is the overlay both consult: an id that has been toggled reads back as
 * whatever it was toggled to, an id that has not falls through to whatever the
 * server last said. It lives for the process only; the server is still the
 * record, this just keeps the surfaces agreeing between refreshes.
 */
public final class FavoriteState {
    private static final Map<String, Boolean> toggled = new ConcurrentHashMap<>();

    /*
     * What the server said about a track the last time it was asked, which is
     * newer than the extras a queued MediaItem carries: those were written when
     * the track was queued, possibly days and a like on another device ago. A
     * toggle made here still wins over it - an answer to a request sent before
     * the tap must not undo the tap.
     */
    private static final Map<String, Boolean> known = new ConcurrentHashMap<>();

    /* The id of the track whose state last changed, so views can redraw. */
    private static final MutableLiveData<String> changes = new MutableLiveData<>();

    private FavoriteState() {
    }

    /**
     * @param serverState what the server last said about this track, for an id
     *                    that has not been toggled in this process
     */
    public static boolean isFavorite(@Nullable String mediaId, boolean serverState) {
        if (mediaId == null) return serverState;

        Boolean override = latest(mediaId);

        return override != null ? override : serverState;
    }

    /** Records what the server has just said about a track. */
    public static void learn(@Nullable String mediaId, boolean favorite) {
        if (mediaId == null) return;

        Boolean before = known.put(mediaId, favorite);

        // Only a change is news; every track the player opens is learned about.
        if (before == null || before != favorite) announce(mediaId);
    }

    @Nullable
    private static Boolean latest(String mediaId) {
        Boolean override = toggled.get(mediaId);
        return override != null ? override : known.get(mediaId);
    }

    public static void set(@Nullable String mediaId, boolean favorite) {
        if (mediaId == null) return;

        toggled.put(mediaId, favorite);
        announce(mediaId);
        // A heart on a wave track is feedback for the wave's next batch.
        WaveState.onFavoriteToggled(mediaId, favorite);
    }

    /*
     * Straight away on the main thread, which is where nearly all of this
     * happens. postValue() keeps only the last value posted before the main
     * thread gets round to it, so two tracks changing together - a like on one
     * and the server's answer for the next - reached the views as one, and the
     * player could miss the one it was showing.
     */
    private static void announce(String mediaId) {
        if (Looper.myLooper() == Looper.getMainLooper()) changes.setValue(mediaId);
        else changes.postValue(mediaId);
    }

    /**
     * Brings a {@link Child} up to date with a toggle that happened elsewhere -
     * from the notification, say, while the player sheet was already showing
     * this track.
     */
    public static void apply(@Nullable Child media) {
        if (media == null) return;

        Boolean override = latest(media.getId());

        if (override == null) return;

        media.setStarred(override ? new Date() : null);
    }

    /** Emits the id of the track whose favourite state just changed. */
    public static LiveData<String> changes() {
        return changes;
    }
}
