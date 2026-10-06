package com.platter.desktop.search

import java.text.Normalizer

/**
 * Rewrites of a query that a substring search on the server can never reach by itself - ported from the Android
 * app's `QueryVariants`.
 *
 * The server matches the characters it is given against the characters in the tags, and that leaves two everyday
 * misses on a library tagged in Cyrillic: the wrong keyboard layout ("tkrf" is what "елка" becomes on an English
 * layout) and romanisation ("Elka"). Neither is a typo - the query and the tag share no characters - but both
 * follow an invertible rule, so the client applies it and asks again.
 *
 * Variants are offered, never substituted: the original query is always first and always outranks them, so a
 * variant can only ever add results below the ones the user asked for.
 */
object QueryVariants {
    /** The Russian layout on the same keys: the glyph at index i of one string is on the key of index i of the other. */
    private const val LATIN_KEYS = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`"
    private const val CYRILLIC_KEYS = "йцукенгшщзхъфывапролджэячсмитьбюё"

    private const val LONGEST_LATIN_CLUSTER = 4

    /** Past this many words the query is a phrase, not a name split in two, and joining them asks for nothing real. */
    private const val MOST_WORDS_TO_JOIN = 3

    /** Past this many words, trying each one dropped in turn stops being a handful of requests. */
    private const val MOST_WORDS_TO_DROP_ONE_OF = 6
    private const val MAX_WORD_DROPS = 4

    /** The spellings people actually type, longest cluster first so "sch" is tried before "s". */
    private val LATIN_TO_CYRILLIC: Map<String, String> = linkedMapOf(
        "shch" to "щ", "sch" to "щ", "yo" to "ё", "zh" to "ж", "ch" to "ч", "sh" to "ш", "ts" to "ц", "kh" to "х",
        "yu" to "ю", "ya" to "я",
        "a" to "а", "b" to "б", "c" to "к", "d" to "д", "e" to "е", "f" to "ф", "g" to "г", "h" to "х", "i" to "и",
        "j" to "й", "k" to "к", "l" to "л", "m" to "м", "n" to "н", "o" to "о", "p" to "п", "q" to "к", "r" to "р",
        "s" to "с", "t" to "т", "u" to "у", "v" to "в", "w" to "в", "x" to "кс", "y" to "ы", "z" to "з",
    )

    private val CYRILLIC_TO_LATIN: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z",
        'и' to "i", 'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
        'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch",
        'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
    )

    /**
     * Words that carry the grammar of a title rather than what it is about. They are the words people misremember:
     * "Love in the Bottle" for "Love in a Bottle".
     */
    private val FUNCTION_WORDS: Set<String> = setOf(
        "a", "an", "the", "in", "on", "of", "to", "for", "at", "by", "with", "from", "into", "and", "or",
        "в", "во", "на", "и", "с", "со", "к", "ко", "по", "за", "о", "об", "обо", "у",
        "а", "но", "из", "от", "до", "для", "про", "под", "над", "при",
    )

    /** One spelling of the query, and how much a match on it is worth. */
    class Variant private constructor(val text: String, private val origin: Origin) {
        /** How the spelling was arrived at, which is the same thing as how much to trust it. */
        enum class Origin(val weight: Double) {
            /** What the user typed. */
            TYPED(1.0),

            /** A rule applied and inverted: layout, romanisation, ё. Worth a little less than what was typed. */
            REWRITTEN(0.82),

            /** A spelling nothing derived: a name the query was merely near, or a truncation of it. */
            GUESSED(0.65),
        }

        val weight: Double get() = origin.weight

        override fun toString(): String = text

        companion object {
            fun typed(text: String) = Variant(text, Origin.TYPED)
            fun rewritten(text: String) = Variant(text, Origin.REWRITTEN)
            fun guessed(text: String) = Variant(text, Origin.GUESSED)
        }
    }

    /** The query as typed, followed by every rewrite that reads as a different string: three or four in practice. */
    fun of(query: String?): List<Variant> {
        val trimmed = query?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyList()

        val variants = ArrayList<Variant>(5)
        variants += Variant.typed(trimmed)

        val lowered = trimmed.lowercase()

        // Each rewrite goes one way only: the way the query is not already written in.
        if (isMostly(lowered, ::isLatin)) {
            addIfNew(variants, swapLayout(lowered, LATIN_KEYS, CYRILLIC_KEYS), trimmed)
            addIfNew(variants, latinToCyrillic(lowered), trimmed)
        } else if (isMostly(lowered, ::isCyrillic)) {
            addIfNew(variants, swapLayout(lowered, CYRILLIC_KEYS, LATIN_KEYS), trimmed)
            addIfNew(variants, cyrillicToLatin(lowered), trimmed)
        }

        addInitialYoVariants(variants, trimmed)
        addJoinedWordsVariant(variants, trimmed)
        return variants
    }

    /** Titles run words together ("Summertime") and people type them apart; joined, the query is the title again. */
    private fun addJoinedWordsVariant(variants: MutableList<Variant>, original: String) {
        val words = wordsOf(original)
        if (words.size < 2 || words.size > MOST_WORDS_TO_JOIN) return
        addIfNew(variants, words.joinToString(""), original)
    }

    /** ё is written as е by half of everyone who writes Russian, and a server indexes them as different letters. */
    private fun addInitialYoVariants(variants: MutableList<Variant>, original: String) {
        for (variant in variants.toList()) {
            val text = variant.text.lowercase()
            if (text.startsWith("е")) addIfNew(variants, "ё" + text.substring(1), original)
            else if (text.startsWith("ё")) addIfNew(variants, "е" + text.substring(1), original)
        }
    }

    private fun addIfNew(variants: MutableList<Variant>, candidate: String?, original: String) {
        if (candidate.isNullOrEmpty()) return
        if (candidate.equals(original, ignoreCase = true)) return
        if (variants.any { it.text.equals(candidate, ignoreCase = true) }) return
        variants += Variant.rewritten(candidate)
    }

    /**
     * The query asked with fewer words, for when as typed it found nothing. The server wants every word somewhere
     * in the tags, so "Love in the Bottle" misses "Love in a Bottle" over one article. Grammar words go first, since
     * those are the ones misremembered; the ranker, handed the query as typed, puts the nearest title back on top.
     */
    fun withWordsDropped(query: String): List<String> {
        val words = wordsOf(query)
        if (words.size < 2) return emptyList()

        val drops = LinkedHashSet<String>()

        val meaningful = words.filterNot(::isFunctionWord)
        if (meaningful.isNotEmpty() && meaningful.size < words.size) drops += meaningful.joinToString(" ")

        if (words.size <= MOST_WORDS_TO_DROP_ONE_OF) {
            val order = words.indices.filter { isFunctionWord(words[it]) } + words.indices.filterNot { isFunctionWord(words[it]) }
            for (dropped in order) {
                val rest = words.toMutableList().also { it.removeAt(dropped) }
                // "the" on its own, or "in the", asks for half the library.
                if (!rest.all(::isFunctionWord)) drops += rest.joinToString(" ")
            }
        }

        return drops.filterNot { it.equals(query.trim(), ignoreCase = true) }.take(MAX_WORD_DROPS)
    }

    /** The whitespace-separated words of [text], as written. */
    fun wordsOf(text: String?): List<String> = text?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()

    /** Whether a word is grammar rather than meaning. Punctuation stuck to the word does not count. */
    fun isFunctionWord(word: String): Boolean {
        val folded = normalize(word)
        var start = 0
        var end = folded.length
        while (start < end && !folded[start].isLetterOrDigit()) start++
        while (end > start && !folded[end - 1].isLetterOrDigit()) end--
        // A word that is nothing but punctuation - "&", "-" - is grammar too.
        return start == end || folded.substring(start, end) in FUNCTION_WORDS
    }

    /**
     * Folds a string down to what "the same text" means for matching: case, accents and the е/ё, и/й pairs stop
     * counting. NFD splits a letter into its base plus its marks, so dropping the marks turns ё into е and é into e
     * in one pass.
     */
    fun normalize(text: String?): String {
        if (text == null) return ""
        val decomposed = Normalizer.normalize(text.lowercase().trim(), Normalizer.Form.NFD)
        val builder = StringBuilder(decomposed.length)
        for (c in decomposed) if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) builder.append(c)
        return builder.toString()
    }

    private fun isLatin(c: Char) = c in 'a'..'z'
    private fun isCyrillic(c: Char) = c in 'а'..'я' || c == 'ё'

    /** Whether the letters of the query are mostly of one script; a stray letter of the other must not stop a rewrite. */
    private fun isMostly(text: String, test: (Char) -> Boolean): Boolean {
        var letters = 0
        var matching = 0
        for (c in text) {
            if (!c.isLetter()) continue
            letters++
            if (test(c)) matching++
        }
        return letters > 0 && matching * 2 > letters
    }

    private fun swapLayout(text: String, from: String, to: String): String =
        buildString(text.length) {
            for (c in text) {
                val key = from.indexOf(c)
                append(if (key >= 0) to[key] else c)
            }
        }

    private fun latinToCyrillic(text: String): String = buildString(text.length) {
        var index = 0
        while (index < text.length) {
            var matched = 0
            var length = minOf(LONGEST_LATIN_CLUSTER, text.length - index)
            while (length > 0 && matched == 0) {
                val replacement = LATIN_TO_CYRILLIC[text.substring(index, index + length)]
                if (replacement != null) {
                    append(replacement)
                    matched = length
                }
                length--
            }
            if (matched == 0) {
                append(text[index])
                matched = 1
            }
            index += matched
        }
    }

    private fun cyrillicToLatin(text: String): String = buildString(text.length) {
        for (c in text) append(CYRILLIC_TO_LATIN[c] ?: c.toString())
    }
}
