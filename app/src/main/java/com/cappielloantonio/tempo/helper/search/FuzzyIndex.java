package com.cappielloantonio.tempo.helper.search;

import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.model.CatalogueName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds the name in the library that a misspelling was probably aiming at.
 * <p>
 * The rewrites in {@link QueryVariants} all follow rules that can be inverted:
 * "Elka" <em>is</em> "елка" under romanisation, with nothing to guess. A typo
 * follows no rule, so the only way to resolve one is to hold the query up
 * against the names that actually exist and ask which is nearest. That is what
 * the catalogue table is for, and this is the measuring.
 * <p>
 * A correction is only ever turned back into another query - the server is asked
 * again with the corrected spelling and answers with real results. So being
 * wrong here costs a request and some results ranked below the literal ones,
 * never a wrong answer presented as right.
 */
@UnstableApi
public final class FuzzyIndex {
    private final List<Entry> entries;

    private FuzzyIndex(List<Entry> entries) {
        this.entries = entries;
    }

    public static FuzzyIndex empty() {
        return new FuzzyIndex(Collections.emptyList());
    }

    /** Blocking; belongs on the same background thread as the ranking. */
    public static FuzzyIndex load() {
        List<CatalogueName> names = AppDatabase.getInstance().catalogueNameDao().getAll();

        List<Entry> entries = new ArrayList<>(names.size());
        for (CatalogueName name : names) {
            entries.add(new Entry(name.getName(), name.getNormalized()));
        }

        return new FuzzyIndex(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * The nearest names to {@code query}, best first, or an empty list when
     * nothing is near enough to be worth asking the server about again.
     *
     * @param query the raw query; folded here the same way the stored names were
     */
    public List<String> correctionsFor(String query, int limit) {
        String normalized = QueryVariants.normalize(query);

        int budget = EditDistance.budgetFor(normalized.length());
        if (budget == 0 || entries.isEmpty()) return Collections.emptyList();

        List<Match> matches = new ArrayList<>();

        for (Entry entry : entries) {
            Match match = nearest(entry, normalized, budget);
            if (match != null) matches.add(match);
        }

        /*
         * Fewest mistakes first; between two equally close names, the shorter
         * one, which is the one the query accounts for more of.
         */
        matches.sort((left, right) -> left.distance != right.distance
                ? Integer.compare(left.distance, right.distance)
                : Integer.compare(left.text.length(), right.text.length()));

        Set<String> corrections = new LinkedHashSet<>();
        for (Match match : matches) {
            if (corrections.size() >= limit) break;

            corrections.add(match.text);
        }

        return new ArrayList<>(corrections);
    }

    /**
     * The best this name can do against the query: as a whole, or as one of its
     * words.
     * <p>
     * When a word is what matched, the word is what gets returned. Handing the
     * server back a full album title as a query would ask it for that one album;
     * handing it the word asks for everything that word names.
     */
    private static Match nearest(Entry entry, String query, int budget) {
        int whole = EditDistance.within(entry.normalized, query, budget);
        if (whole >= 0) return new Match(entry.text, whole);

        int best = -1;
        String bestText = null;

        int start = 0;
        int length = entry.normalized.length();

        while (start < length) {
            while (start < length && !Character.isLetterOrDigit(entry.normalized.charAt(start))) start++;

            int end = start;
            while (end < length && Character.isLetterOrDigit(entry.normalized.charAt(end))) end++;
            if (end == start) break;

            String word = entry.normalized.substring(start, end);
            int distance = EditDistance.within(word, query, budget);

            if (distance >= 0 && (best < 0 || distance < best)) {
                best = distance;

                /*
                 * Folding never changes a length here - the names are trimmed
                 * before they are stored, and lowercasing and mark-stripping are
                 * character-for-character - so the same offsets carry over to the
                 * original spelling. The guard is for the case where something
                 * did change, where the folded word is still a usable query.
                 */
                bestText = entry.text.length() == entry.normalized.length()
                        ? entry.text.substring(start, end)
                        : word;
            }

            start = end;
        }

        return best < 0 ? null : new Match(bestText, best);
    }

    private static final class Entry {
        private final String text;
        private final String normalized;

        private Entry(String text, String normalized) {
            this.text = text;
            this.normalized = normalized;
        }
    }

    private static final class Match {
        private final String text;
        private final int distance;

        private Match(String text, int distance) {
            this.text = text;
            this.distance = distance;
        }
    }
}
