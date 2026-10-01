package com.cappielloantonio.tempo.helper.search;

import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.model.Favorite;
import com.cappielloantonio.tempo.util.Preferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What this listener has already done with the library, as three sets of ids.
 * <p>
 * The server ranks nothing: {@code search3} answers with whatever matched, in
 * whatever order it stored it, identically for every user of that server. But
 * the app has been keeping a record of what was starred, downloaded and played
 * all along, and the answer to "which of these twenty albums did you mean" is
 * usually in it. This is that record, reduced to membership tests.
 * <p>
 * Ids are namespaced by kind. A classic Subsonic server numbers songs, albums
 * and artists in separate sequences, so id "42" can name all three, and an
 * un-namespaced set would let a starred song vouch for an unrelated album.
 */
@UnstableApi
public final class LibrarySignals {
    /*
     * How far back to read. Ranking only asks whether an item is in the set, so
     * the cap is about keeping the query cheap, not about relevance - a track
     * played two thousand plays ago is not a signal worth a slower search.
     */
    private static final int HISTORY_DEPTH = 500;

    public enum Kind {
        SONG,
        ALBUM,
        ARTIST
    }

    private final Set<String> starred;
    private final Set<String> downloaded;
    private final Set<String> played;

    private LibrarySignals(Set<String> starred, Set<String> downloaded, Set<String> played) {
        this.starred = starred;
        this.downloaded = downloaded;
        this.played = played;
    }

    public static LibrarySignals empty() {
        return new LibrarySignals(Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
    }

    /**
     * Reads the three tables. Blocking, so it belongs on whatever thread the
     * ranking itself runs on and never on the main one.
     */
    public static LibrarySignals load() {
        AppDatabase database = AppDatabase.getInstance();
        String server = Preferences.getServerId();

        Set<String> starred = new HashSet<>();
        for (Favorite favorite : database.favoriteDao().getAll()) {
            add(starred, Kind.SONG, favorite.getSongId());
            add(starred, Kind.ALBUM, favorite.getAlbumId());
            add(starred, Kind.ARTIST, favorite.getArtistId());
        }

        Set<String> downloaded = new HashSet<>();
        addAll(downloaded, Kind.SONG, database.downloadDao().getAllIds());

        Set<String> played = new HashSet<>();
        addAll(played, Kind.SONG, database.chronologyDao().getPlayedSongIds(server, HISTORY_DEPTH));
        addAll(played, Kind.ALBUM, database.chronologyDao().getPlayedAlbumIds(server, HISTORY_DEPTH));
        addAll(played, Kind.ARTIST, database.chronologyDao().getPlayedArtistIds(server, HISTORY_DEPTH));

        return new LibrarySignals(starred, downloaded, played);
    }

    public boolean isStarred(Kind kind, String id) {
        return contains(starred, kind, id);
    }

    public boolean isDownloaded(String songId) {
        return contains(downloaded, Kind.SONG, songId);
    }

    public boolean wasPlayed(Kind kind, String id) {
        return contains(played, kind, id);
    }

    private static void addAll(Set<String> target, Kind kind, List<String> ids) {
        if (ids == null) return;

        for (String id : ids) add(target, kind, id);
    }

    private static void add(Set<String> target, Kind kind, String id) {
        if (id != null && !id.isEmpty()) target.add(key(kind, id));
    }

    private static boolean contains(Set<String> target, Kind kind, String id) {
        return id != null && !id.isEmpty() && target.contains(key(kind, id));
    }

    private static String key(Kind kind, String id) {
        return kind.ordinal() + ":" + id;
    }
}
