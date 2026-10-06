package com.platter.desktop.search

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * How far apart two spellings are, counted in single-character mistakes.
 *
 * Optimal string alignment: insertions, deletions, substitutions, and the transposition of two adjacent characters.
 * That last one is why plain Levenshtein is not enough - "Мельинца" is one slip of the fingers and Levenshtein
 * charges two edits for it, the same as for a word that is genuinely two mistakes away.
 *
 * Every comparison is bounded. Nothing here ever needs to know that two strings are nine edits apart, only whether
 * they are within one or two, and the bound is what lets almost every candidate be rejected without a matrix.
 */
internal object EditDistance {
    /** Short words are left alone: at four characters nearly every name is within one edit of nearly every other. */
    private const val SHORTEST_CORRECTABLE = 5
    private const val ONE_EDIT_UP_TO = 7

    /** Beyond a couple of mistakes it is a different word, not a misspelt one. */
    private const val MAX_EDITS = 2

    /** How many mistakes to forgive in a query of this length; 0 means none. */
    fun budgetFor(length: Int): Int = when {
        length < SHORTEST_CORRECTABLE -> 0
        length <= ONE_EDIT_UP_TO -> 1
        else -> MAX_EDITS
    }

    /** The distance between the two, or -1 once it is certain to exceed [budget]. */
    fun within(left: String, right: String, budget: Int): Int {
        val leftLength = left.length
        val rightLength = right.length

        // A length difference is a lower bound on the distance, and checking it first skips almost every name.
        if (abs(leftLength - rightLength) > budget) return -1
        if (left == right) return 0
        if (leftLength == 0 || rightLength == 0) return if (max(leftLength, rightLength) <= budget) max(leftLength, rightLength) else -1

        var twoBack = IntArray(rightLength + 1)
        var previous = IntArray(rightLength + 1) { it }
        var current = IntArray(rightLength + 1)

        for (i in 1..leftLength) {
            current[0] = i
            var rowBest = current[0]

            for (j in 1..rightLength) {
                val cost = if (left[i - 1] == right[j - 1]) 0 else 1

                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost)

                if (i > 1 && j > 1 && left[i - 1] == right[j - 2] && left[i - 2] == right[j - 1]) {
                    current[j] = min(current[j], twoBack[j - 2] + 1)
                }

                if (current[j] < rowBest) rowBest = current[j]
            }

            // The distance never decreases along a diagonal, so once a whole row is over budget the answer is too.
            if (rowBest > budget) return -1

            val discarded = twoBack
            twoBack = previous
            previous = current
            current = discarded
        }

        val distance = previous[rightLength]
        return if (distance <= budget) distance else -1
    }

    /**
     * The best any single word of [text] does against [query], or the whole of [text] if that does better. The word
     * pass is what lets a query for one artist reach an album called "Мельница - Лучшее".
     *
     * @return the distance, or -1 when nothing is within [budget]
     */
    fun nearestWord(text: String, query: String, budget: Int): Int {
        var best = within(text, query, budget)
        if (best == 0) return 0

        for (word in wordsIn(text)) {
            val distance = within(word, query, budget)
            if (distance >= 0 && (best < 0 || distance < best)) best = distance
        }
        return best
    }

    /** Runs of letters and digits, in order. */
    fun wordsIn(text: String): List<String> = wordSpans(text).map { (start, end) -> text.substring(start, end) }

    /** The same words as [start, end) offsets, for callers that need to map back into another string. */
    fun wordSpans(text: String): List<Pair<Int, Int>> {
        val spans = ArrayList<Pair<Int, Int>>()
        var start = 0
        val length = text.length
        while (start < length) {
            while (start < length && !text[start].isLetterOrDigit()) start++
            var end = start
            while (end < length && text[end].isLetterOrDigit()) end++
            if (end > start) spans += start to end
            start = end
        }
        return spans
    }
}
