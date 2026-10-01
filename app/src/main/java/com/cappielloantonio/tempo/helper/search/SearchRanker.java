package com.cappielloantonio.tempo.helper.search;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.subsonic.models.ArtistID3;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.SearchResult3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts the results in the order a person would have put them in.
 * <p>
 * {@code search3} answers a substring query with whatever matched, in the order
 * the server happened to store it. Searching "Ёлка" on a library that also holds
 * a song called "Ёлка, ёлочка" can return the song first and the artist you were
 * plainly after somewhere below the fold. Nothing here filters - every result the
 * server sent is kept - but three things decide where each one lands:
 * <ol>
 *     <li><b>How the query matched.</b> A name that <em>is</em> the query beats a
 *     name that starts with it, which beats a name with a word starting with it,
 *     which beats a name that merely contains it somewhere.</li>
 *     <li><b>Which spelling matched.</b> A hit on the query as typed outranks a
 *     hit on one of the rewrites from {@link QueryVariants}.</li>
 *     <li><b>Whether it is already yours.</b> Starred, downloaded, played before,
 *     or played often - see {@link LibrarySignals}.</li>
 * </ol>
 */
@UnstableApi
public final class SearchRanker {
    /* Match tiers. The gaps between them are what keep the personal boost from
       overturning a genuinely better textual match - see MAX_PERSONAL_BOOST. */
    private static final double EXACT = 100;
    private static final double PREFIX = 82;
    private static final double WORD_PREFIX = 64;
    private static final double CONTAINS = 40;

    /*
     * Something the server matched on a field this does not read - a path, a
     * comment, a genre. It stays in the results, at the bottom, because the
     * server had a reason and this does not know what it was.
     */
    private static final double UNEXPLAINED = 5;

    /* How much of the name the query accounts for: "Ёлка" is a better answer to
       "Ёлка" than "Ёлка и её друзья" is, though both start with it. */
    private static final double COVERAGE_BONUS = 8;

    /* A hit on the artist of a song, or on the album it comes from, counts - but
       as evidence about the song, not as a match on the song's own title. */
    private static final double SECONDARY_FIELD_FACTOR = 0.6;

    /*
     * The ceiling on familiarity. Set so that a familiar item matching weakly
     * (CONTAINS, 40) can overtake an unfamiliar word-prefix match (64) but never
     * an unfamiliar prefix match (82): being yours is a tie-breaker among
     * plausible answers, never a reason to bury the obvious one.
     */
    private static final double MAX_PERSONAL_BOOST = 30;

    private static final double STARRED_BOOST = 18;
    private static final double DOWNLOADED_BOOST = 14;
    private static final double PLAYED_BOOST = 12;
    private static final double MAX_PLAY_COUNT_BOOST = 10;
    private static final double RATING_BOOST_PER_STAR = 2;

    /*
     * Applied only when choosing the single top result, to break a tie between
     * types. A query that names an artist usually means the artist, even when an
     * album carries the same name; anything less close than a near-tie is still
     * decided by the match itself.
     */
    private static final double ARTIST_TOP_PRIOR = 6;
    private static final double ALBUM_TOP_PRIOR = 2;

    /*
     * What a name is worth for being a near-miss of what the user actually
     * typed, rather than of the guessed spelling that fetched it.
     *
     * This only ever applies to a round of guesses, where by definition the
     * query as typed matched nothing, and the candidates arrived under some
     * other spelling. Searching "Playing Godd" falls back to asking for
     * "Playing", which returns everything with that word in it; among those,
     * "Playing God" is one keystroke from what was typed and "Playing The Angel"
     * is not, and nothing in the guessed query can tell them apart. One mistake
     * away scores above any prefix match on a truncation, which is the point.
     */
    private static final double NEAR_MISS = 98;
    private static final double NEAR_MISS_PER_EDIT = 18;

    /*
     * A phrase with one word wrong, in the same round of guesses. Every word
     * right and nothing more would score just under a clean near miss; "Love in
     * a Bottle" for "Love in the Bottle" lands around 85, above any prefix
     * match on a shortened query, which is where it belongs.
     */
    private static final double WORD_OVERLAP = 96;
    private static final double MIN_WORD_RECALL = 0.5;
    private static final double NEAR_WORD = 0.8;
    private static final double FUNCTION_WORD_WEIGHT = 0.3;

    private SearchRanker() {
    }

    /**
     * @param variants the spellings that were queried
     * @param results  one response per spelling, positionally aligned with
     *                 {@code variants}; entries may be null where that request
     *                 failed
     */
    public static RankedResults rank(List<QueryVariants.Variant> variants, List<SearchResult3> results, LibrarySignals signals) {
        return rank(variants, results, signals, null);
    }

    /**
     * @param intent the query as the user typed it, when the spellings actually
     *               queried were guesses at it; null when they were not
     */
    public static RankedResults rank(List<QueryVariants.Variant> variants, List<SearchResult3> results, LibrarySignals signals, @Nullable String intent) {
        if (variants.isEmpty()) return RankedResults.empty();

        Intent typed = intent == null ? null : new Intent(intent);

        List<String> normalizedQueries = new ArrayList<>(variants.size());
        List<Double> weights = new ArrayList<>(variants.size());
        for (QueryVariants.Variant variant : variants) {
            normalizedQueries.add(QueryVariants.normalize(variant.text()));
            weights.add(variant.weight());
        }

        /*
         * Deduplicated across responses: the same album comes back from the
         * query as typed and from a rewrite of it, and it is one album. Scoring
         * happens against every spelling regardless of which response carried
         * it, so the order the responses arrive in cannot change the ranking.
         */
        Map<String, ArtistID3> artists = new LinkedHashMap<>();
        Map<String, AlbumID3> albums = new LinkedHashMap<>();
        Map<String, Child> songs = new LinkedHashMap<>();

        for (SearchResult3 result : results) {
            if (result == null) continue;

            collect(artists, result.getArtists());
            collect(albums, result.getAlbums());
            collect(songs, result.getSongs());
        }

        List<Scored<ArtistID3>> scoredArtists = new ArrayList<>(artists.size());
        for (ArtistID3 artist : artists.values()) {
            scoredArtists.add(scoreArtist(artist, normalizedQueries, weights, signals, typed));
        }

        List<Scored<AlbumID3>> scoredAlbums = new ArrayList<>(albums.size());
        for (AlbumID3 album : albums.values()) {
            scoredAlbums.add(scoreAlbum(album, normalizedQueries, weights, signals, typed));
        }

        List<Scored<Child>> scoredSongs = new ArrayList<>(songs.size());
        for (Child song : songs.values()) {
            scoredSongs.add(scoreSong(song, normalizedQueries, weights, signals, typed));
        }

        sort(scoredArtists);
        sort(scoredAlbums);
        sort(scoredSongs);

        return new RankedResults(
                items(scoredArtists),
                items(scoredAlbums),
                items(scoredSongs),
                pickTop(scoredArtists, scoredAlbums, scoredSongs)
        );
    }

    private static <T> void collect(Map<String, T> target, List<T> items) {
        if (items == null) return;

        for (T item : items) {
            String id = idOf(item);
            if (id != null && !target.containsKey(id)) target.put(id, item);
        }
    }

    private static String idOf(Object item) {
        if (item instanceof ArtistID3) return ((ArtistID3) item).getId();
        if (item instanceof AlbumID3) return ((AlbumID3) item).getId();
        if (item instanceof Child) return ((Child) item).getId();

        return null;
    }

    private static Scored<ArtistID3> scoreArtist(ArtistID3 artist, List<String> queries, List<Double> weights, LibrarySignals signals, @Nullable Intent intent) {
        double match = Math.max(
                bestMatch(queries, weights, artist.getName(), artist.getSortName()),
                nearMiss(intent, artist.getName())
        );

        double personal = personalBoost(
                signals.isStarred(LibrarySignals.Kind.ARTIST, artist.getId()) || artist.getStarred() != null,
                false,
                signals.wasPlayed(LibrarySignals.Kind.ARTIST, artist.getId()),
                null,
                artist.getUserRating()
        );

        return new Scored<>(artist, match, personal);
    }

    private static Scored<AlbumID3> scoreAlbum(AlbumID3 album, List<String> queries, List<Double> weights, LibrarySignals signals, @Nullable Intent intent) {
        double match = Math.max(
                Math.max(
                        bestMatch(queries, weights, album.getName(), album.getSortName()),
                        bestMatch(queries, weights, album.artistLine()) * SECONDARY_FIELD_FACTOR
                ),
                nearMiss(intent, album.getName())
        );

        double personal = personalBoost(
                signals.isStarred(LibrarySignals.Kind.ALBUM, album.getId()) || album.getStarred() != null,
                false,
                signals.wasPlayed(LibrarySignals.Kind.ALBUM, album.getId()) || isPlayed(album.getPlayed()),
                album.getPlayCount(),
                album.getUserRating()
        );

        return new Scored<>(album, match, personal);
    }

    private static Scored<Child> scoreSong(Child song, List<String> queries, List<Double> weights, LibrarySignals signals, @Nullable Intent intent) {
        double match = Math.max(
                Math.max(
                        bestMatch(queries, weights, song.getTitle()),
                        Math.max(
                                bestMatch(queries, weights, song.artistLine()),
                                bestMatch(queries, weights, song.getAlbum())
                        ) * SECONDARY_FIELD_FACTOR
                ),
                nearMiss(intent, song.getTitle())
        );

        double personal = personalBoost(
                signals.isStarred(LibrarySignals.Kind.SONG, song.getId()) || song.getStarred() != null,
                signals.isDownloaded(song.getId()),
                signals.wasPlayed(LibrarySignals.Kind.SONG, song.getId()) || isPlayed(song.getPlayed()),
                song.getPlayCount(),
                song.getUserRating()
        );

        return new Scored<>(song, match, personal);
    }

    /**
     * The best any of the spellings does against any of the given fields,
     * discounted by how much that spelling is trusted.
     */
    private static double bestMatch(List<String> queries, List<Double> weights, String... fields) {
        double best = 0;

        for (int i = 0; i < queries.size(); i++) {
            String query = queries.get(i);
            if (query.isEmpty()) continue;

            for (String field : fields) {
                if (field == null || field.isEmpty()) continue;

                double score = matchScore(QueryVariants.normalize(field), query) * weights.get(i);
                if (score > best) best = score;
            }
        }

        return best;
    }

    /**
     * How close this name is to what the user typed, when what they typed is not
     * what was queried. Zero unless it is within a mistake or two, or - for a
     * query of several words - unless it shares most of them.
     */
    private static double nearMiss(@Nullable Intent intent, String field) {
        if (intent == null || field == null || field.isEmpty()) return 0;

        String normalized = QueryVariants.normalize(field);
        double best = 0;

        if (intent.budget > 0) {
            int distance = EditDistance.nearestWord(normalized, intent.text, intent.budget);
            if (distance >= 0) best = NEAR_MISS - NEAR_MISS_PER_EDIT * distance;
        }

        return Math.max(best, wordOverlap(intent, normalized));
    }

    /**
     * How much of a phrase this name is, word by word.
     * <p>
     * Edit distance measures a phrase as one long string, and "love in the
     * bottle" is three edits from "love in a bottle" - past any budget, for all
     * that three words of four are right. Here each word is matched on its own,
     * counted both ways: the share of the query found in the name, and the share
     * of the name the query accounts for, so "Love in a Bottle" beats "Love in
     * the Dark" and "Time in a Bottle" alike. Grammar words weigh little either
     * way, being the words that are misremembered.
     */
    private static double wordOverlap(Intent intent, String field) {
        if (intent.words.size() < 2) return 0;

        List<String> fieldWords = wordsOf(field);
        if (fieldWords.isEmpty()) return 0;

        double queryWeight = 0;
        double queryFound = 0;

        for (int index = 0; index < intent.words.size(); index++) {
            String word = intent.words.get(index);
            double weight = wordWeight(word);
            boolean last = index == intent.words.size() - 1;

            queryWeight += weight;

            double best = 0;
            for (String candidate : fieldWords) best = Math.max(best, wordMatch(word, candidate, last));
            queryFound += weight * best;
        }

        double fieldWeight = 0;
        double fieldFound = 0;

        for (String candidate : fieldWords) {
            double weight = wordWeight(candidate);
            fieldWeight += weight;

            double best = 0;
            for (int index = 0; index < intent.words.size(); index++) {
                best = Math.max(best, wordMatch(intent.words.get(index), candidate, index == intent.words.size() - 1));
            }
            fieldFound += weight * best;
        }

        double recall = queryFound / queryWeight;
        double precision = fieldFound / fieldWeight;

        /* Less than half of what was asked for is a different title. */
        if (recall < MIN_WORD_RECALL || recall + precision == 0) return 0;

        return WORD_OVERLAP * 2 * recall * precision / (recall + precision);
    }

    /**
     * 1 for the same word; less for one a slip away, or - for the word typed
     * last, which may be unfinished - for a word it begins.
     */
    private static double wordMatch(String queryWord, String fieldWord, boolean last) {
        if (queryWord.equals(fieldWord)) return 1;
        if (last && queryWord.length() >= 2 && fieldWord.startsWith(queryWord)) return NEAR_WORD;

        int budget = EditDistance.budgetFor(Math.min(queryWord.length(), fieldWord.length()));
        return budget > 0 && EditDistance.within(queryWord, fieldWord, budget) >= 0 ? NEAR_WORD : 0;
    }

    private static double wordWeight(String word) {
        return QueryVariants.isFunctionWord(word) ? FUNCTION_WORD_WEIGHT : 1;
    }

    private static List<String> wordsOf(String normalized) {
        List<String> words = new ArrayList<>();

        int start = 0;
        int length = normalized.length();

        while (start < length) {
            while (start < length && !Character.isLetterOrDigit(normalized.charAt(start))) start++;

            int end = start;
            while (end < length && Character.isLetterOrDigit(normalized.charAt(end))) end++;
            if (end > start) words.add(normalized.substring(start, end));

            start = end;
        }

        return words;
    }

    private static double matchScore(String text, String query) {
        if (text.isEmpty()) return 0;
        if (text.equals(query)) return EXACT;

        double coverage = COVERAGE_BONUS * Math.min(1.0, (double) query.length() / text.length());

        if (text.startsWith(query)) return PREFIX + coverage;
        if (hasWordStartingWith(text, query)) return WORD_PREFIX + coverage;
        if (text.contains(query)) return CONTAINS + coverage;

        return 0;
    }

    /**
     * Whether any word of {@code text} starts with the query - the difference
     * between "Река и море" answering a search for "море" (it does) and
     * answering one for "оре" (it does, but only just, and lower down).
     */
    private static boolean hasWordStartingWith(String text, String query) {
        int index = 0;

        while (index < text.length()) {
            while (index < text.length() && !Character.isLetterOrDigit(text.charAt(index))) index++;
            if (index >= text.length()) break;

            if (text.startsWith(query, index)) return true;

            while (index < text.length() && Character.isLetterOrDigit(text.charAt(index))) index++;
        }

        return false;
    }

    private static double personalBoost(boolean starred, boolean downloaded, boolean played, @Nullable Long playCount, @Nullable Integer userRating) {
        double boost = 0;

        if (starred) boost += STARRED_BOOST;
        if (downloaded) boost += DOWNLOADED_BOOST;
        if (played) boost += PLAYED_BOOST;

        if (playCount != null && playCount > 0) {
            boost += Math.min(MAX_PLAY_COUNT_BOOST, 3.5 * Math.log10(1 + playCount));
        }

        if (userRating != null && userRating > 0) {
            boost += userRating * RATING_BOOST_PER_STAR;
        }

        return Math.min(MAX_PERSONAL_BOOST, boost);
    }

    /**
     * Servers that have never seen the item played still send a played date, as
     * the epoch rather than as nothing.
     */
    private static boolean isPlayed(@Nullable Date played) {
        return played != null && played.getTime() > 0;
    }

    private static <T> void sort(List<Scored<T>> scored) {
        scored.sort(Comparator.comparingDouble((Scored<T> entry) -> entry.total()).reversed());
    }

    private static <T> List<T> items(List<Scored<T>> scored) {
        List<T> items = new ArrayList<>(scored.size());
        for (Scored<T> entry : scored) items.add(entry.item);

        return items;
    }

    /**
     * The single result to lead with, or null when no candidate matched the
     * query on any field this can read. Leading with a result whose relevance
     * cannot be explained is worse than leading with nothing: the card asserts
     * "this is the one you meant" and would have no grounds for it.
     */
    @Nullable
    private static RankedResults.TopResult pickTop(List<Scored<ArtistID3>> artists, List<Scored<AlbumID3>> albums, List<Scored<Child>> songs) {
        Scored<ArtistID3> artist = first(artists);
        Scored<AlbumID3> album = first(albums);
        Scored<Child> song = first(songs);

        double artistScore = artist == null || !artist.matched() ? Double.NEGATIVE_INFINITY : artist.total() + ARTIST_TOP_PRIOR;
        double albumScore = album == null || !album.matched() ? Double.NEGATIVE_INFINITY : album.total() + ALBUM_TOP_PRIOR;
        double songScore = song == null || !song.matched() ? Double.NEGATIVE_INFINITY : song.total();

        double best = Math.max(artistScore, Math.max(albumScore, songScore));

        if (best == Double.NEGATIVE_INFINITY) return null;

        if (best == artistScore) return RankedResults.TopResult.of(artist.item);
        if (best == albumScore) return RankedResults.TopResult.of(album.item);

        return RankedResults.TopResult.of(song.item);
    }

    @Nullable
    private static <T> Scored<T> first(List<Scored<T>> scored) {
        return scored.isEmpty() ? null : scored.get(0);
    }

    private static final class Scored<T> {
        private final T item;
        private final double match;
        private final double personal;

        private Scored(T item, double match, double personal) {
            this.item = item;
            this.match = match;
            this.personal = personal;
        }

        /**
         * Whether the query is anywhere in this item's own text. Kept apart from
         * the total because familiarity alone must never promote something to
         * the top card, however starred and however often played.
         */
        private boolean matched() {
            return match > 0;
        }

        private double total() {
            return (match > 0 ? match : UNEXPLAINED) + personal;
        }
    }

    /** The query as typed, folded once for every name it is held against. */
    private static final class Intent {
        private final String text;
        private final int budget;
        private final List<String> words;

        private Intent(String typed) {
            text = QueryVariants.normalize(typed);
            budget = EditDistance.budgetFor(text.length());
            words = wordsOf(text);
        }
    }
}
