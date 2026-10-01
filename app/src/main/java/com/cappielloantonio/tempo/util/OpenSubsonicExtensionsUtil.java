package com.cappielloantonio.tempo.util;

import com.cappielloantonio.tempo.subsonic.models.OpenSubsonicExtension;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;

import java.util.List;

/**
 * Capability negotiation against an OpenSubsonic server.
 * <p>
 * A server answers {@code getOpenSubsonicExtensions} with a name <em>and</em> a
 * list of supported versions per extension. Only the name used to be read here,
 * so an extension whose contract changed between versions - {@code songLyrics}
 * gained word-level cues in version 2, which Navidrome 0.63 ships - looked
 * identical to its version 1 predecessor and the client had no way to ask for
 * the newer shape. Every lookup now takes the version it needs.
 */
public class OpenSubsonicExtensionsUtil {
    /* Extension names as registered by the OpenSubsonic project. */
    private static final String TRANSCODE_OFFSET = "transcodeOffset";
    private static final String FORM_POST = "formPost";
    private static final String SONG_LYRICS = "songLyrics";
    private static final String TRANSCODING = "transcoding";
    private static final String PLAYBACK_REPORT = "playbackReport";
    private static final String SONIC_SIMILARITY = "sonicSimilarity";
    private static final String INDEX_BASED_QUEUE = "indexBasedQueue";
    private static final String API_KEY_AUTHENTICATION = "apiKeyAuthentication";
    private static final String GET_PODCAST_EPISODE = "getPodcastEpisode";
    private static final String TOP_SONGS_BY_ARTIST_ID = "topSongsByArtistId";

    /*
     * The stored list is parsed on every lookup, and lookups now happen on the
     * main thread from the player's own callbacks - once per playback state
     * change, and again before each request that has a POST variant. Keeping
     * the parse of the last string seen turns that back into a map lookup;
     * the raw string is the cache key, so a refreshed capability list from a
     * different server invalidates it by itself.
     */
    private static String cachedJson;
    private static List<OpenSubsonicExtension> cachedExtensions;

    private static synchronized List<OpenSubsonicExtension> getOpenSubsonicExtensions() {
        if (!Preferences.isOpenSubsonic()) return null;

        String json = Preferences.getOpenSubsonicExtensions();
        if (json == null) return null;

        if (!json.equals(cachedJson)) {
            cachedExtensions = new Gson().fromJson(
                    json,
                    new TypeToken<List<OpenSubsonicExtension>>() {
                    }.getType()
            );
            cachedJson = json;
        }

        return cachedExtensions;
    }

    private static OpenSubsonicExtension getOpenSubsonicExtension(String extensionName) {
        List<OpenSubsonicExtension> extensions = getOpenSubsonicExtensions();

        if (extensions == null) return null;

        return extensions.stream()
                .filter(openSubsonicExtension -> extensionName.equals(openSubsonicExtension.getName()))
                .findAny()
                .orElse(null);
    }

    /**
     * @return true when the server advertises the extension at that version or
     * newer. An extension with no version list is treated as version 1, which is
     * what servers predating the versioned registry send.
     */
    private static boolean supports(String extensionName, int version) {
        OpenSubsonicExtension extension = getOpenSubsonicExtension(extensionName);

        if (extension == null) return false;
        if (extension.getVersions() == null || extension.getVersions().isEmpty()) return version <= 1;

        return extension.getVersions().stream().anyMatch(supported -> supported != null && supported >= version);
    }

    public static boolean isTranscodeOffsetExtensionAvailable() {
        return supports(TRANSCODE_OFFSET, 1);
    }

    public static boolean isFormPostExtensionAvailable() {
        return supports(FORM_POST, 1);
    }

    public static boolean isSongLyricsExtensionAvailable() {
        return supports(SONG_LYRICS, 1);
    }

    /**
     * Version 2 adds the {@code kind} discriminator that separates a main lyric
     * layer from translation and pronunciation layers, plus word-level cues.
     */
    public static boolean isSongLyricsV2ExtensionAvailable() {
        return supports(SONG_LYRICS, 2);
    }

    /** Server-side transcoding decisions (Navidrome 0.61+). */
    public static boolean isTranscodingExtensionAvailable() {
        return supports(TRANSCODING, 1);
    }

    /** Playback timeline reporting, separate from scrobbling (Navidrome 0.62+). */
    public static boolean isPlaybackReportExtensionAvailable() {
        return supports(PLAYBACK_REPORT, 1);
    }

    /**
     * Audio-analysis based similarity (Navidrome 0.62+).
     * <p>
     * The extension registers as {@code sonicSimilarity}; this was asking for
     * "songSimilarity", a name no server has ever advertised, so the check
     * could only ever return false.
     */
    public static boolean isSonicSimilarityExtensionAvailable() {
        return supports(SONIC_SIMILARITY, 1);
    }

    public static boolean isIndexBasedQueueExtensionAvailable() {
        return supports(INDEX_BASED_QUEUE, 1);
    }

    public static boolean isApiKeyAuthenticationExtensionAvailable() {
        return supports(API_KEY_AUTHENTICATION, 1);
    }

    public static boolean isGetPodcastEpisodeExtensionAvailable() {
        return supports(GET_PODCAST_EPISODE, 1);
    }

    public static boolean isTopSongsByArtistIdExtensionAvailable() {
        return supports(TOP_SONGS_BY_ARTIST_ID, 1);
    }
}
