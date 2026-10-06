package com.platter.desktop.search

import com.platter.desktop.api.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.ceil

/** Where a search goes: the server's `search3`, or a stand-in in tests. */
fun interface SearchSource {
    suspend fun search(query: String): SearchResult
}

/**
 * Runs a query and every rewrite of it worth trying, ranks what comes back, and - when as typed it found nothing at
 * all - tries again with guesses: the nearest names in the library, the query with words dropped, the query cut
 * short. The same two-round search the Android app's `SearchViewModel` does.
 */
class SearchEngine(
    private val source: SearchSource,
    private val signals: suspend () -> LibrarySignals,
    private val fuzzy: suspend () -> FuzzyIndex,
) {
    /** Throws when every request failed (the server is down), so the screen can say so rather than "no results". */
    suspend fun search(query: String): RankedResults {
        val variants = QueryVariants.of(query)
        if (variants.isEmpty()) return RankedResults.Empty

        val ranked = round(variants, intent = null)
        if (!ranked.isEmpty) return ranked

        val guesses = guessesFor(query)
        if (guesses.isEmpty()) return ranked

        // A round of guesses is handed the query as typed as well: the spellings that fetched these results are
        // approximations, and only the original can tell which result is nearest to what was meant.
        return round(guesses, intent = query)
    }

    private suspend fun round(variants: List<QueryVariants.Variant>, intent: String?): RankedResults {
        var failure: Exception? = null
        val results = coroutineScope {
            variants.map { variant ->
                async {
                    try {
                        source.search(variant.text)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failure = failure ?: e
                        null
                    }
                }
            }.awaitAll()
        }
        failure?.let { if (results.all { it == null }) throw it }
        return SearchRanker.rank(variants, results, signals(), intent)
    }

    /**
     * What the query might have been, when as typed it was nothing at all. A correction from the library index
     * catches a misspelling anywhere in the word, but only for an artist or an album, the names held locally.
     * Dropping words catches a phrase with one word wrong. A truncation catches a mistake near the end of anything.
     */
    private suspend fun guessesFor(query: String): List<QueryVariants.Variant> {
        val guesses = ArrayList<QueryVariants.Variant>()
        fuzzy().correctionsFor(query, MAX_CORRECTIONS).forEach { guesses += QueryVariants.Variant.guessed(it) }
        QueryVariants.withWordsDropped(query).forEach { guesses += QueryVariants.Variant.guessed(it) }
        prefixOf(query)?.let { guesses += QueryVariants.Variant.guessed(it) }
        return guesses
    }

    /** A typo is usually in what was typed last, so cutting the tail off often lands back on something real. */
    private fun prefixOf(query: String): String? {
        val trimmed = query.trim()
        val length = ceil(trimmed.length * PREFIX_FALLBACK_RATIO).toInt()
        if (length < SHORTEST_PREFIX || length >= trimmed.length) return null

        // Cutting mid-phrase can leave a trailing space, which some servers treat as a token boundary.
        val prefix = trimmed.substring(0, length).trim()
        return if (prefix.length < SHORTEST_PREFIX) null else prefix
    }

    companion object {
        /** How many library names a misspelling may be corrected towards at once. */
        private const val MAX_CORRECTIONS = 2
        private const val PREFIX_FALLBACK_RATIO = 0.6
        private const val SHORTEST_PREFIX = 4

        /** Below this a query is not about anything ("Enter at least three characters" on the phone). */
        const val MIN_QUERY_LENGTH = 3
    }
}
