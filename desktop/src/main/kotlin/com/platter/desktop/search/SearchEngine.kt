package com.platter.desktop.search

import com.platter.desktop.api.SearchResult
import com.platter.desktop.api.Song
import com.platter.search.TrackSearch
import com.platter.search.TrackIndex
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
 * Combines indexed tracks with regular server search and a phrase lookup. If the matches are weak, tries again
 * with guesses: the nearest names in the library, the query with words dropped, the query cut short.
 */
class SearchEngine(
    private val source: SearchSource,
    private val signals: suspend () -> LibrarySignals,
    private val fuzzy: suspend () -> FuzzyIndex,
    private val songPage: (suspend (query: String, count: Int, offset: Int) -> List<Song>)? = null,
    private val localTracks: suspend (query: String, allowCorrections: Boolean) -> List<TrackIndex.Match<Song>> =
        { _, _ -> emptyList() },
) {
    /** Throws when every request failed (the server is down), so the screen can say so rather than "no results". */
    suspend fun search(query: String, onLocal: ((RankedResults) -> Unit)? = null): RankedResults {
        val variants = QueryVariants.of(query)
        if (variants.isEmpty()) return RankedResults.Empty

        val trackSearch = TrackSearch(query)
        val local = variants.flatMapIndexed { index, variant ->
            localTracks(variant.text, index == 0 && trackSearch.allowsCorrections())
        }
        if (local.isNotEmpty() && onLocal != null) {
            onLocal(SearchRanker.rank(variants, emptyList(), LibrarySignals.Empty, localTracks = local))
        }
        val results = fetch(variants, expandTracks = true, requireResponse = local.isEmpty())
        val signals = signals()
        val ranked = SearchRanker.rank(variants, results, signals, localTracks = local)
        if (ranked.hasStrongMatch || !trackSearch.allowsCorrections() || results.all { it == null }) return ranked

        val tried = variants.map { it.text.lowercase() }.toSet()
        val guesses = guessesFor(query).filter { it.text.lowercase() !in tried }.distinctBy { it.text.lowercase() }
        if (guesses.isEmpty()) return ranked

        // A round of guesses is handed the query as typed as well: the spellings that fetched these results are
        // approximations, and only the original can tell which result is nearest to what was meant.
        val retry = fetch(guesses, expandTracks = false, requireResponse = false)
        if (retry.all { it == null }) return ranked
        return SearchRanker.rank(variants + guesses, results + retry, signals, query, local)
    }

    private suspend fun fetch(variants: List<QueryVariants.Variant>, expandTracks: Boolean, requireResponse: Boolean): List<SearchResult?> {
        val results = coroutineScope {
            variants.mapIndexed { index, variant ->
                async {
                    try {
                        val result = source.search(variant.text)
                        if (index == 0 && expandTracks) addPhraseMatches(variant.text, result)
                        Result.success(result)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
            }.awaitAll()
        }
        if (requireResponse && results.all { it.isFailure }) results.first().getOrThrow()
        return results.map { it.getOrNull() }
    }

    private suspend fun addPhraseMatches(query: String, result: SearchResult) {
        val fetch = songPage ?: return
        val first = result.songs.orEmpty()
        val trackSearch = TrackSearch(query)
        val exact = first.any { trackSearch.match(it.title, it.artistLine(), it.artist) > 0 }
        val phrase = trackSearch.phrase(first.size, exact) ?: return
        val songs = first.toMutableList()
        var offset = if (phrase == query) first.size else 0
        while (offset < TrackSearch.MAX_SONGS_PER_PHRASE) {
            val page = try {
                fetch(phrase, TrackSearch.PAGE_SIZE, offset)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                break
            }
            songs += page
            if (page.size < TrackSearch.PAGE_SIZE) break
            offset += page.size
        }
        result.songs = songs
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
    }
}
