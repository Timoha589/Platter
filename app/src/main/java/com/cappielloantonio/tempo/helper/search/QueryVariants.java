package com.cappielloantonio.tempo.helper.search;

import androidx.annotation.NonNull;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites of a query that a substring search on the server can never reach by
 * itself.
 * <p>
 * The server matches the characters it is given against the characters in the
 * tags. That is the whole of its cleverness, and it leaves two everyday misses
 * on a library tagged in Cyrillic:
 * <ul>
 *     <li><b>The wrong keyboard layout.</b> "tkrf" is what "елка" becomes when
 *     the layout was still on English. The letters are in the right places; only
 *     the map from key to glyph is wrong.</li>
 *     <li><b>Romanisation.</b> "Elka" is how the same artist is typed by someone
 *     who does not want to switch layouts at all, and how half the internet
 *     spells them.</li>
 * </ul>
 * Neither is a typo, and neither is what {@link FuzzyIndex} handles: the query
 * and the tag here genuinely share no characters, so no amount of fuzzy matching
 * would bridge them. What they share is a rule, and the rule is invertible, so
 * the client applies it and asks again. A misspelling follows no rule and needs
 * the library in hand to resolve, which is the other class.
 * <p>
 * Variants are offered, never substituted. The original query is always first
 * and always outranks them ({@link Variant#weight()}), so a variant can only ever
 * add results below the ones the user actually asked for.
 */
public final class QueryVariants {
    /*
     * The Russian layout as it sits on the same keys. Read the two strings as
     * one table: the glyph at index i in LATIN_KEYS is printed on the key that
     * types the glyph at index i in CYRILLIC_KEYS.
     */
    private static final String LATIN_KEYS = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`";
    private static final String CYRILLIC_KEYS = "йцукенгшщзхъфывапролджэячсмитьбюё";

    /*
     * Romanisation, longest match first - "sch" has to be tried before "s", or
     * "Pushkin" transliterates through "s" + "ch" and never reaches щ. These are
     * the spellings people actually type, not a standards-body table: ISO 9
     * would romanise ч as "č", which nobody has ever typed into a search box.
     */
    private static final Map<String, String> LATIN_TO_CYRILLIC = new LinkedHashMap<>();
    private static final Map<Character, String> CYRILLIC_TO_LATIN = new LinkedHashMap<>();

    private static final int LONGEST_LATIN_CLUSTER = 4;

    /*
     * Words that carry the grammar of a title rather than what it is about.
     * They are the words people misremember: "Love in the Bottle" for "Love in
     * a Bottle", "Песня о любви" for "Песня про любовь". Articles, prepositions
     * and conjunctions only - a pronoun is often the part of a title people
     * remember best.
     */
    private static final Set<String> FUNCTION_WORDS = new HashSet<>(Arrays.asList(
            "a", "an", "the", "in", "on", "of", "to", "for", "at", "by", "with", "from", "into",
            "and", "or",
            "в", "во", "на", "и", "с", "со", "к", "ко", "по", "за", "о", "об", "обо", "у",
            "а", "но", "из", "от", "до", "для", "про", "под", "над", "при"
    ));

    /*
     * A server that answers a query of several words demands every one of them,
     * so one misremembered word is enough to find nothing. Dropping words is how
     * to ask for less; past this many words, trying each one in turn stops being
     * a handful of requests.
     */
    private static final int MOST_WORDS_TO_DROP_ONE_OF = 6;
    private static final int MAX_WORD_DROPS = 4;

    static {
        LATIN_TO_CYRILLIC.put("shch", "щ");
        LATIN_TO_CYRILLIC.put("sch", "щ");
        LATIN_TO_CYRILLIC.put("yo", "ё");
        LATIN_TO_CYRILLIC.put("zh", "ж");
        LATIN_TO_CYRILLIC.put("ch", "ч");
        LATIN_TO_CYRILLIC.put("sh", "ш");
        LATIN_TO_CYRILLIC.put("ts", "ц");
        LATIN_TO_CYRILLIC.put("kh", "х");
        LATIN_TO_CYRILLIC.put("yu", "ю");
        LATIN_TO_CYRILLIC.put("ya", "я");
        LATIN_TO_CYRILLIC.put("a", "а");
        LATIN_TO_CYRILLIC.put("b", "б");
        LATIN_TO_CYRILLIC.put("c", "к");
        LATIN_TO_CYRILLIC.put("d", "д");
        LATIN_TO_CYRILLIC.put("e", "е");
        LATIN_TO_CYRILLIC.put("f", "ф");
        LATIN_TO_CYRILLIC.put("g", "г");
        LATIN_TO_CYRILLIC.put("h", "х");
        LATIN_TO_CYRILLIC.put("i", "и");
        LATIN_TO_CYRILLIC.put("j", "й");
        LATIN_TO_CYRILLIC.put("k", "к");
        LATIN_TO_CYRILLIC.put("l", "л");
        LATIN_TO_CYRILLIC.put("m", "м");
        LATIN_TO_CYRILLIC.put("n", "н");
        LATIN_TO_CYRILLIC.put("o", "о");
        LATIN_TO_CYRILLIC.put("p", "п");
        LATIN_TO_CYRILLIC.put("q", "к");
        LATIN_TO_CYRILLIC.put("r", "р");
        LATIN_TO_CYRILLIC.put("s", "с");
        LATIN_TO_CYRILLIC.put("t", "т");
        LATIN_TO_CYRILLIC.put("u", "у");
        LATIN_TO_CYRILLIC.put("v", "в");
        LATIN_TO_CYRILLIC.put("w", "в");
        LATIN_TO_CYRILLIC.put("x", "кс");
        LATIN_TO_CYRILLIC.put("y", "ы");
        LATIN_TO_CYRILLIC.put("z", "з");

        CYRILLIC_TO_LATIN.put('а', "a");
        CYRILLIC_TO_LATIN.put('б', "b");
        CYRILLIC_TO_LATIN.put('в', "v");
        CYRILLIC_TO_LATIN.put('г', "g");
        CYRILLIC_TO_LATIN.put('д', "d");
        CYRILLIC_TO_LATIN.put('е', "e");
        CYRILLIC_TO_LATIN.put('ё', "e");
        CYRILLIC_TO_LATIN.put('ж', "zh");
        CYRILLIC_TO_LATIN.put('з', "z");
        CYRILLIC_TO_LATIN.put('и', "i");
        CYRILLIC_TO_LATIN.put('й', "i");
        CYRILLIC_TO_LATIN.put('к', "k");
        CYRILLIC_TO_LATIN.put('л', "l");
        CYRILLIC_TO_LATIN.put('м', "m");
        CYRILLIC_TO_LATIN.put('н', "n");
        CYRILLIC_TO_LATIN.put('о', "o");
        CYRILLIC_TO_LATIN.put('п', "p");
        CYRILLIC_TO_LATIN.put('р', "r");
        CYRILLIC_TO_LATIN.put('с', "s");
        CYRILLIC_TO_LATIN.put('т', "t");
        CYRILLIC_TO_LATIN.put('у', "u");
        CYRILLIC_TO_LATIN.put('ф', "f");
        CYRILLIC_TO_LATIN.put('х', "h");
        CYRILLIC_TO_LATIN.put('ц', "ts");
        CYRILLIC_TO_LATIN.put('ч', "ch");
        CYRILLIC_TO_LATIN.put('ш', "sh");
        CYRILLIC_TO_LATIN.put('щ', "sch");
        CYRILLIC_TO_LATIN.put('ъ', "");
        CYRILLIC_TO_LATIN.put('ы', "y");
        CYRILLIC_TO_LATIN.put('ь', "");
        CYRILLIC_TO_LATIN.put('э', "e");
        CYRILLIC_TO_LATIN.put('ю', "yu");
        CYRILLIC_TO_LATIN.put('я', "ya");
    }

    private QueryVariants() {
    }

    /** One spelling of the query, and how much a match on it is worth. */
    public static final class Variant {
        /**
         * How the spelling was arrived at, which is the same thing as how much
         * to trust it.
         */
        public enum Origin {
            /** What the user typed. */
            TYPED(1.0),

            /**
             * A rule applied and inverted - layout, romanisation, ё. Still a
             * guess about intent, so it is worth less than what was typed, but
             * the gap stays small enough that a strong rewritten match beats a
             * weak literal one: "Elka" matching the artist Ёлка outright should
             * outrank it matching the middle of an unrelated song title.
             */
            REWRITTEN(0.82),

            /**
             * A spelling nothing derived - a name from the library that the
             * query was merely near, or a truncation of the query. It answers a
             * question the user did not quite ask, so it sits below everything
             * that matched what they did ask.
             */
            GUESSED(0.65);

            private final double weight;

            Origin(double weight) {
                this.weight = weight;
            }
        }

        private final String text;
        private final Origin origin;

        private Variant(String text, Origin origin) {
            this.text = text;
            this.origin = origin;
        }

        /** A spelling arrived at by guessing - see {@link Origin#GUESSED}. */
        public static Variant guessed(String text) {
            return new Variant(text, Origin.GUESSED);
        }

        public String text() {
            return text;
        }

        public double weight() {
            return origin.weight;
        }

        @NonNull
        @Override
        public String toString() {
            return text;
        }
    }

    /**
     * The query as typed, followed by every rewrite that reads as a different
     * string - three or four in practice, five at the very worst. A pause in
     * typing therefore costs a handful of requests, not one per spelling anyone
     * could conceivably have meant.
     */
    public static List<Variant> of(String query) {
        List<Variant> variants = new ArrayList<>(5);

        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) return variants;

        variants.add(new Variant(trimmed, Variant.Origin.TYPED));

        String lowered = trimmed.toLowerCase();

        /*
         * Both rewrites are attempted in one direction only: the direction the
         * query is not already written in. Transliterating Cyrillic that is
         * already Cyrillic just spells the same word in a script the library
         * does not use.
         */
        if (isMostly(lowered, QueryVariants::isLatin)) {
            addIfNew(variants, swapLayout(lowered, LATIN_KEYS, CYRILLIC_KEYS), trimmed);
            addIfNew(variants, latinToCyrillic(lowered), trimmed);
        } else if (isMostly(lowered, QueryVariants::isCyrillic)) {
            addIfNew(variants, swapLayout(lowered, CYRILLIC_KEYS, LATIN_KEYS), trimmed);
            addIfNew(variants, cyrillicToLatin(lowered), trimmed);
        }

        addInitialYoVariants(variants, trimmed);
        addJoinedWordsVariant(variants, trimmed);

        return variants;
    }

    /*
     * Past this many words the query is a phrase, not a name split in two, and
     * running them together only asks for a string nobody tagged.
     */
    private static final int MOST_WORDS_TO_JOIN = 3;

    /**
     * The query with its spaces taken out.
     * <p>
     * Titles run words together - "streetcat", "Summertime", "Даваймы" - and
     * people type them apart. The server matches from the start of each word, so
     * "street cat" needs a word starting with "cat" and the title has none: the
     * track stays unfindable in the library while Deezer, which matches loosely,
     * offers it as something to download. Joined, the query is the title again.
     */
    private static void addJoinedWordsVariant(List<Variant> variants, String original) {
        List<String> words = wordsOf(original);
        if (words.size() < 2 || words.size() > MOST_WORDS_TO_JOIN) return;

        addIfNew(variants, String.join("", words), original);
    }

    /**
     * The one letter a Cyrillic spelling routinely leaves out.
     * <p>
     * ё is written as е by half of everyone who writes Russian, and a server
     * indexes the two as different letters - so "елка" misses the artist Ёлка by
     * one diaeresis, whether that spelling came from the keyboard, from the
     * wrong layout, or from romanising "Elka", where Latin "e" could have stood
     * for either letter all along. ё is nearly always the first letter of the
     * word or the stressed one, and the first is the case worth a request: it is
     * what turns Ёлка, Ёжик and Ёрш from unfindable into findable.
     * <p>
     * Applied to every Cyrillic spelling already gathered, the original
     * included, and in both directions.
     */
    private static void addInitialYoVariants(List<Variant> variants, String original) {
        for (Variant variant : new ArrayList<>(variants)) {
            String text = variant.text().toLowerCase();

            if (text.startsWith("е")) addIfNew(variants, "ё" + text.substring(1), original);
            else if (text.startsWith("ё")) addIfNew(variants, "е" + text.substring(1), original);
        }
    }

    private static void addIfNew(List<Variant> variants, String candidate, String original) {
        if (candidate == null || candidate.isEmpty()) return;
        if (candidate.equalsIgnoreCase(original)) return;

        for (Variant existing : variants) {
            if (existing.text().equalsIgnoreCase(candidate)) return;
        }

        variants.add(new Variant(candidate, Variant.Origin.REWRITTEN));
    }

    /**
     * The query asked with fewer words, for when as typed it found nothing.
     * <p>
     * The server wants every word of a query somewhere in the tags, so "Love in
     * the Bottle" misses "Love in a Bottle" over one article, however right the
     * rest of it is. Neither a spelling correction nor a truncation reaches it:
     * the wrong word is a real word, and it sits in the middle. What does reach
     * it is asking without that word - so the query is tried without its
     * grammar words at all, and then without each word in turn, the grammar
     * words first, since those are the ones misremembered.
     * <p>
     * Every one of these asks for more than was typed. The ranker, handed the
     * query as typed, is what puts the nearest title back on top.
     */
    public static List<String> withWordsDropped(String query) {
        List<String> words = wordsOf(query);
        if (words.size() < 2) return new ArrayList<>();

        Set<String> drops = new LinkedHashSet<>();

        List<String> meaningful = new ArrayList<>();
        for (String word : words) {
            if (!isFunctionWord(word)) meaningful.add(word);
        }
        if (!meaningful.isEmpty() && meaningful.size() < words.size()) drops.add(String.join(" ", meaningful));

        if (words.size() <= MOST_WORDS_TO_DROP_ONE_OF) {
            List<Integer> order = new ArrayList<>();
            for (int index = 0; index < words.size(); index++) if (isFunctionWord(words.get(index))) order.add(index);
            for (int index = 0; index < words.size(); index++) if (!isFunctionWord(words.get(index))) order.add(index);

            for (int dropped : order) {
                List<String> rest = new ArrayList<>(words);
                rest.remove(dropped);

                /* "the" on its own, or "in the", asks for half the library. */
                boolean onlyGrammar = true;
                for (String word : rest) {
                    if (!isFunctionWord(word)) onlyGrammar = false;
                }
                if (!onlyGrammar) drops.add(String.join(" ", rest));
            }
        }

        List<String> result = new ArrayList<>();
        for (String drop : drops) {
            if (result.size() >= MAX_WORD_DROPS) break;
            if (!drop.equalsIgnoreCase(query.trim())) result.add(drop);
        }

        return result;
    }

    /** The whitespace-separated words of {@code text}, as written. */
    public static List<String> wordsOf(String text) {
        List<String> words = new ArrayList<>();
        if (text == null) return words;

        for (String word : text.trim().split("\\s+")) {
            if (!word.isEmpty()) words.add(word);
        }

        return words;
    }

    /**
     * Whether a word is grammar rather than meaning - see
     * {@link #FUNCTION_WORDS}. Punctuation stuck to the word does not count.
     */
    public static boolean isFunctionWord(String word) {
        String folded = normalize(word);

        int start = 0;
        int end = folded.length();
        while (start < end && !Character.isLetterOrDigit(folded.charAt(start))) start++;
        while (end > start && !Character.isLetterOrDigit(folded.charAt(end - 1))) end--;

        /* A word that is nothing but punctuation - "&", "-" - is grammar too. */
        return start == end || FUNCTION_WORDS.contains(folded.substring(start, end));
    }

    /**
     * Folds a string down to what "the same text" means for matching: case,
     * accents and the е/ё, и/й pairs all stop counting. NFD splits a letter into
     * its base plus its marks, so dropping the marks turns ё into е and é into e
     * in the same pass.
     */
    public static String normalize(String text) {
        if (text == null) return "";

        String decomposed = Normalizer.normalize(text.toLowerCase().trim(), Normalizer.Form.NFD);

        StringBuilder builder = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char character = decomposed.charAt(i);
            if (Character.getType(character) != Character.NON_SPACING_MARK) builder.append(character);
        }

        return builder.toString();
    }

    private static boolean isLatin(char character) {
        return character >= 'a' && character <= 'z';
    }

    private static boolean isCyrillic(char character) {
        return character >= 'а' && character <= 'я' || character == 'ё';
    }

    /**
     * Whether the letters of the query are predominantly of one script. A query
     * carries digits, spaces and punctuation that belong to neither, and a
     * single stray letter from the other alphabet should not stop a rewrite, so
     * the test is a majority of the letters rather than all of them.
     */
    private static boolean isMostly(String text, CharacterTest test) {
        int letters = 0;
        int matching = 0;

        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (!Character.isLetter(character)) continue;

            letters++;
            if (test.matches(character)) matching++;
        }

        return letters > 0 && matching * 2 > letters;
    }

    private static String swapLayout(String text, String from, String to) {
        StringBuilder builder = new StringBuilder(text.length());

        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            int key = from.indexOf(character);

            builder.append(key >= 0 ? to.charAt(key) : character);
        }

        return builder.toString();
    }

    private static String latinToCyrillic(String text) {
        StringBuilder builder = new StringBuilder(text.length());

        int index = 0;
        while (index < text.length()) {
            int matched = 0;

            for (int length = Math.min(LONGEST_LATIN_CLUSTER, text.length() - index); length > 0 && matched == 0; length--) {
                String cluster = text.substring(index, index + length);
                String replacement = LATIN_TO_CYRILLIC.get(cluster);

                if (replacement != null) {
                    builder.append(replacement);
                    matched = length;
                }
            }

            if (matched == 0) {
                builder.append(text.charAt(index));
                matched = 1;
            }

            index += matched;
        }

        return builder.toString();
    }

    private static String cyrillicToLatin(String text) {
        StringBuilder builder = new StringBuilder(text.length());

        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            String replacement = CYRILLIC_TO_LATIN.get(character);

            builder.append(replacement != null ? replacement : String.valueOf(character));
        }

        return builder.toString();
    }

    private interface CharacterTest {
        boolean matches(char character);
    }
}
