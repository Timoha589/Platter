package com.platter.search;

import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Phrase lookup and exact track matching used by both clients. */
public final class TrackSearch {
    public static final int PAGE_SIZE = 20;
    public static final int MAX_SONGS_PER_PHRASE = 80;
    public static final int EXACT_TITLE = 1;
    public static final int EXACT_TITLE_AND_ARTIST = 2;
    private static final Pattern CREDIT_SEPARATOR = Pattern.compile(
            "[/;]|,\\s+|\\s+(?:feat\\.?|ft\\.?|featuring|&)\\s+", Pattern.CASE_INSENSITIVE);

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final String query;
    private final String key;
    private final String singleCharacter;

    public TrackSearch(String query) {
        this.query = query == null ? "" : query.trim();
        this.key = key(this.query);
        this.singleCharacter = singleCharacterKey(this.query, BreakIterator.getCharacterInstance(Locale.ROOT));
    }

    public boolean isSingleCharacter() {
        return !singleCharacter.isEmpty();
    }

    /** A full first page can hide another artist's recording of the same title. */
    public String phrase(int songCount, boolean hasExactTrack) {
        // Continue the original lookup when its first page could hide the exact title.
        if (isSingleCharacter()) return songCount >= PAGE_SIZE ? query : null;
        if (key.isEmpty() || query.indexOf('*') >= 0) return null;
        if (songCount < PAGE_SIZE && hasExactTrack) return null;
        if (query.indexOf('"') >= 0) return songCount >= PAGE_SIZE ? query : null;
        return '"' + query + '"';
    }

    /** A whole title, optionally preceded or followed by a credited performer. */
    public int match(String title, String artistLine, String artist) {
        if (isSingleCharacter()) return matchesName(title) ? EXACT_TITLE : 0;
        String titleKey = key(title);
        if (titleKey.isEmpty()) return 0;
        if (key.equals(titleKey)) return EXACT_TITLE;

        String afterTitle = titleKey + " ";
        if (key.startsWith(afterTitle) && hasArtist(key.substring(afterTitle.length()), artistLine, artist)) {
            return EXACT_TITLE_AND_ARTIST;
        }
        String beforeTitle = " " + titleKey;
        if (key.endsWith(beforeTitle) && hasArtist(key.substring(0, key.length() - beforeTitle.length()), artistLine, artist)) {
            return EXACT_TITLE_AND_ARTIST;
        }
        return 0;
    }

    public boolean matchesName(String name) {
        if (isSingleCharacter()) return singleCharacter.equals(literalKey(name));
        return !key.isEmpty() && key.equals(key(name));
    }

    public boolean allowsCorrections() {
        return !isSingleCharacter() && query.indexOf('*') < 0 && query.indexOf('"') < 0;
    }

    public static int trackPriority(int exact, boolean literalTitle, int edits) {
        if (exact > 0) return 3 + exact;
        if (literalTitle) return 3;
        return edits > 0 ? 3 - edits : 0;
    }

    public boolean matchesTitleWords(String title) {
        if (isSingleCharacter()) return matchesName(title);
        return !key.isEmpty() && (" " + key(title)).contains(" " + key);
    }

    private static boolean hasArtist(String name, String artistLine, String artist) {
        return creditKeys(artistLine, artist).contains(name);
    }

    static Set<String> creditKeys(String artistLine, String artist) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(key(artist));
        keys.add(key(artistLine));
        if (artistLine != null) {
            for (String part : CREDIT_SEPARATOR.split(artistLine)) keys.add(key(part));
        }
        keys.remove("");
        return keys;
    }

    static String key(String text) {
        if (text == null) return "";
        String decomposed = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        String folded = MARKS.matcher(decomposed).replaceAll("");
        return SEPARATORS.matcher(folded).replaceAll(" ").trim();
    }

    static String singleCharacterKey(String text, BreakIterator characters) {
        if (text == null) return "";
        String name = text.trim();
        if (name.isEmpty()) return "";
        characters.setText(name);
        return characters.next() == name.length() ? literalKey(name) : "";
    }

    private static String literalKey(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
    }
}
