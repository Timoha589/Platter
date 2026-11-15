package com.platter.search;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

/** In-memory index over a snapshot of the server's track metadata. */
public final class TrackIndex<T> {
    private static final int MAX_MATCHES = 100;
    private final NavigableMap<String, List<T>> titles = new TreeMap<>();
    private final Map<String, List<T>> singleCharacterTitles = new HashMap<>();
    private final NavigableMap<String, List<String>> titleWords = new TreeMap<>();
    private final NavigableMap<String, List<T>> credits = new TreeMap<>();
    private final NavigableMap<String, List<T>> albums = new TreeMap<>();
    private final Map<Integer, List<Title<T>>> lengths = new HashMap<>();
    private final Function<T, String> id;
    private final Function<T, String> title;
    private final Function<T, String> artistLine;
    private final Function<T, String> artist;

    public TrackIndex(Iterable<T> tracks, Function<T, String> id, Function<T, String> title,
                      Function<T, String> artistLine, Function<T, String> artist, Function<T, String> album) {
        this.id = id;
        this.title = title;
        this.artistLine = artistLine;
        this.artist = artist;
        BreakIterator characters = BreakIterator.getCharacterInstance(Locale.ROOT);
        for (T track : tracks) {
            if (track == null || id.apply(track) == null) continue;
            String text = title.apply(track);
            add(singleCharacterTitles, TrackSearch.singleCharacterKey(text, characters), track);
            String name = TrackSearch.key(text);
            if (name.isEmpty()) continue;
            add(titles, name, track);
            add(albums, TrackSearch.key(album.apply(track)), track);
            for (String credit : TrackSearch.creditKeys(artistLine.apply(track), artist.apply(track))) add(credits, credit, track);
        }
        titles.forEach((name, songs) -> {
            String[] words = name.split(" ");
            for (int i = 1; i < words.length; i++) add(titleWords, words[i], name);
            String compact = compact(name);
            lengths.computeIfAbsent(compact.length(), n -> new ArrayList<>()).add(new Title<>(compact, songs));
        });
    }

    @FunctionalInterface
    public interface Distance {
        int within(String query, String title, int budget);
    }

    public static final class Match<T> {
        public final T song;
        /** Positive for a spelling correction. */
        public final int edits;
        public final boolean prefix;

        Match(T song, int edits) {
            this(song, edits, false);
        }

        Match(T song, int edits, boolean prefix) {
            this.song = song;
            this.edits = edits;
            this.prefix = prefix;
        }
    }

    public List<Match<T>> find(String query, Distance distance) {
        return find(query, distance, track -> true);
    }

    public List<Match<T>> find(String query, Distance distance, Predicate<T> visible) {
        String singleCharacter = TrackSearch.singleCharacterKey(query, BreakIterator.getCharacterInstance(Locale.ROOT));
        if (!singleCharacter.isEmpty()) {
            Map<String, Match<T>> matches = new LinkedHashMap<>();
            collect(matches, singleCharacterTitles.get(singleCharacter), visible);
            return new ArrayList<>(matches.values());
        }
        String key = TrackSearch.key(query);
        if (key.isEmpty() || query.indexOf('*') >= 0) return Collections.emptyList();

        Map<String, Match<T>> matches = new LinkedHashMap<>();
        List<Match<T>> corrected = new ArrayList<>();
        boolean allowCorrections = distance != null && query.indexOf('"') < 0;
        for (int cut = key.indexOf(' '); cut >= 0; cut = key.indexOf(' ', cut + 1)) {
            String before = key.substring(0, cut);
            String after = key.substring(cut + 1);
            matchPair(before, after, distance, matches, corrected, allowCorrections, visible);
            matchPair(after, before, distance, matches, corrected, allowCorrections, visible);
        }
        collect(matches, titles.get(key), visible);
        collectPrefix(matches, titles, key, visible);
        collectTitleWords(matches, key, visible);

        String spelling = compact(key);
        int budget = budget(key, spelling);
        if (budget > 0 && allowCorrections && matches.size() < MAX_MATCHES) {
            long mask = letters(spelling);
            for (int length = spelling.length() - budget; length <= spelling.length() + budget; length++) {
                for (Title<T> candidate : lengths.getOrDefault(length, Collections.emptyList())) {
                    if (Long.bitCount(mask & ~candidate.letters) > budget || Long.bitCount(candidate.letters & ~mask) > budget) continue;
                    int edits = distance.within(spelling, candidate.name, budget);
                    if (edits < 0) continue;
                    for (T track : candidate.songs) {
                        if (visible.test(track) && !matches.containsKey(id.apply(track))) {
                            corrected.add(new Match<>(track, Math.max(1, edits), candidate.name.startsWith(spelling)));
                        }
                    }
                }
            }
        }
        corrected.sort((a, b) -> a.edits == b.edits ? Boolean.compare(b.prefix, a.prefix) : Integer.compare(a.edits, b.edits));
        for (Match<T> hit : corrected) {
            if (matches.size() == MAX_MATCHES) break;
            matches.putIfAbsent(id.apply(hit.song), hit);
        }
        collectPrefix(matches, credits, key, visible);
        collectPrefix(matches, albums, key, visible);
        return new ArrayList<>(matches.values());
    }

    private void matchPair(String titleKey, String creditKey, Distance distance,
                             Map<String, Match<T>> matches, List<Match<T>> corrected, boolean allowCorrections, Predicate<T> visible) {
        String spelling = compact(titleKey);
        int budget = allowCorrections ? budget(titleKey, spelling) : 0;
        for (T track : credits.getOrDefault(creditKey, Collections.emptyList())) {
            if (matches.size() == MAX_MATCHES) break;
            if (!visible.test(track)) continue;
            String name = TrackSearch.key(title.apply(track));
            if (titleKey.equals(name)) {
                matches.putIfAbsent(id.apply(track), new Match<>(track, 0));
                continue;
            }
            if (budget == 0) continue;
            String candidate = compact(name);
            int edits = distance.within(spelling, candidate, budget);
            if (edits >= 0) corrected.add(new Match<>(track, Math.max(1, edits), candidate.startsWith(spelling)));
        }
        String creditSpelling = compact(creditKey);
        int creditBudget = allowCorrections ? budget(creditKey, creditSpelling) : 0;
        if (creditBudget == 0 || matches.size() == MAX_MATCHES) return;
        for (T track : titles.getOrDefault(titleKey, Collections.emptyList())) {
            if (!visible.test(track) || matches.containsKey(id.apply(track))) continue;
            for (String credit : TrackSearch.creditKeys(artistLine.apply(track), artist.apply(track))) {
                int edits = distance.within(creditSpelling, compact(credit), creditBudget);
                if (edits >= 0) corrected.add(new Match<>(track, Math.max(1, edits)));
            }
        }
    }

    private static String compact(String key) {
        return key.replace(" ", "");
    }

    private static int budget(String key, String spelling) {
        if (key.length() < 3) return 0;
        return spelling.length() < 9 ? 1 : 2;
    }

    private void collectPrefix(Map<String, Match<T>> matches, NavigableMap<String, List<T>> index, String key, Predicate<T> visible) {
        for (Map.Entry<String, List<T>> entry : index.tailMap(key, true).entrySet()) {
            if (matches.size() == MAX_MATCHES || !entry.getKey().startsWith(key)) break;
            collect(matches, entry.getValue(), visible);
        }
    }

    private void collectTitleWords(Map<String, Match<T>> matches, String key, Predicate<T> visible) {
        int cut = key.indexOf(' ');
        String first = cut < 0 ? key : key.substring(0, cut);
        String phrase = " " + key;
        for (Map.Entry<String, List<String>> entry : titleWords.tailMap(first, true).entrySet()) {
            if (matches.size() == MAX_MATCHES || !entry.getKey().startsWith(first)) break;
            for (String name : entry.getValue()) {
                if (matches.size() == MAX_MATCHES) break;
                if (cut < 0 || name.contains(phrase)) collect(matches, titles.get(name), visible);
            }
        }
    }

    private void collect(Map<String, Match<T>> matches, List<T> tracks, Predicate<T> visible) {
        if (tracks == null) return;
        for (T track : tracks) {
            if (matches.size() == MAX_MATCHES) break;
            if (visible.test(track)) matches.putIfAbsent(id.apply(track), new Match<>(track, 0));
        }
    }

    private static <T> void add(Map<String, List<T>> index, String key, T song) {
        if (!key.isEmpty()) index.computeIfAbsent(key, unused -> new ArrayList<>()).add(song);
    }

    private static long letters(String name) {
        long letters = 0;
        for (int i = 0; i < name.length(); i++) letters |= 1L << name.charAt(i);
        return letters;
    }

    private static final class Title<T> {
        final String name;
        final List<T> songs;
        final long letters;

        Title(String name, List<T> songs) {
            this.name = name;
            this.songs = songs;
            this.letters = letters(name);
        }
    }
}
