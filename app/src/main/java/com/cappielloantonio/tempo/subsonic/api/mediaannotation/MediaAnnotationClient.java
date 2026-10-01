package com.cappielloantonio.tempo.subsonic.api.mediaannotation;

import android.util.Log;

import com.cappielloantonio.tempo.subsonic.RetrofitClient;
import com.cappielloantonio.tempo.subsonic.Subsonic;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;
import com.cappielloantonio.tempo.util.OpenSubsonicExtensionsUtil;

import retrofit2.Call;

public class MediaAnnotationClient {
    private static final String TAG = "MediaAnnotationClient";

    private final Subsonic subsonic;
    private final MediaAnnotationService mediaAnnotationService;

    public MediaAnnotationClient(Subsonic subsonic) {
        this.subsonic = subsonic;
        this.mediaAnnotationService = new RetrofitClient(subsonic).getRetrofit().create(MediaAnnotationService.class);
    }

    public Call<ApiResponse> star(String id, String albumId, String artistId) {
        Log.d(TAG, "star()");
        return mediaAnnotationService.star(subsonic.getParams(), id, albumId, artistId);
    }

    public Call<ApiResponse> unstar(String id, String albumId, String artistId) {
        Log.d(TAG, "unstar()");
        return mediaAnnotationService.unstar(subsonic.getParams(), id, albumId, artistId);
    }

    public Call<ApiResponse> setRating(String id, int rating) {
        Log.d(TAG, "setRating()");
        return mediaAnnotationService.setRating(subsonic.getParams(), id, rating);
    }

    public Call<ApiResponse> scrobble(String id, boolean submission, Long time) {
        Log.d(TAG, "scrobble()");
        return mediaAnnotationService.scrobble(subsonic.getParams(), id, submission, time);
    }

    /**
     * Reports the player's current state to the server (playbackReport
     * extension).
     *
     * @param state          one of {@link PlaybackState}
     * @param ignoreScrobble true for reports that should only refresh the
     *                       "now playing" display, never touch play counts
     */
    public Call<ApiResponse> reportPlayback(String mediaId, String mediaType, long positionMs, String state, Float playbackRate, boolean ignoreScrobble) {
        Log.d(TAG, "reportPlayback(" + state + ")");

        if (OpenSubsonicExtensionsUtil.isFormPostExtensionAvailable()) {
            return mediaAnnotationService.reportPlaybackPost(subsonic.getParams(), mediaId, mediaType, positionMs, state, playbackRate, ignoreScrobble);
        }

        return mediaAnnotationService.reportPlayback(subsonic.getParams(), mediaId, mediaType, positionMs, state, playbackRate, ignoreScrobble);
    }

    /** The four states the playbackReport extension defines. */
    public static final class PlaybackState {
        public static final String STARTING = "starting";
        public static final String PLAYING = "playing";
        public static final String PAUSED = "paused";
        public static final String STOPPED = "stopped";

        private PlaybackState() {
        }
    }

    /** The media kinds the extension accepts - not the app's own type names. */
    public static final class PlaybackMediaType {
        public static final String SONG = "song";
        public static final String PODCAST = "podcast";

        private PlaybackMediaType() {
        }
    }
}
