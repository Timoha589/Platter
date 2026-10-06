package com.platter.desktop.search

import com.platter.desktop.api.Album
import com.platter.desktop.api.Artist
import com.platter.desktop.api.SearchResult
import com.platter.desktop.api.Song
import com.platter.desktop.search.LibrarySignals.Kind
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/** One search, answered: each list in relevance order, plus the single best hit. */
class RankedResults(
    val artists: List<Artist>,
    val albums: List<Album>,
    val songs: List<Song>,
    val top: Top?,
) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && songs.isEmpty()

    /** The best hit across all three types; the three have no common supertype, and each is opened differently. */
    sealed interface Top {
        val title: String
        val subtitle: String?
        val coverArtId: String?

        class OfArtist(val artist: Artist) : Top {
            override val title get() = artist.name.orEmpty()
            override val subtitle: String? get() = null
            override val coverArtId get() = artist.coverArtId
        }

        class OfAlbum(val album: Album) : Top {
            override val title get() = album.name.orEmpty()
            override val subtitle get() = album.artistLine()
            override val coverArtId get() = album.coverArtId
        }

        class OfSong(val song: Song) : Top {
            override val title get() = song.title.orEmpty()
            override val subtitle get() = song.artistLine()
            override val coverArtId get() = song.coverArtId
        }
    }

    companion object {
        val Empty = RankedResults(emptyList(), emptyList(), emptyList(), null)
    }
}

/**
 * Puts the results in the order a person would have put them in - ported from the Android app's `SearchRanker`.
 *
 * `search3` answers a substring query with whatever matched, in the order the server happened to store it. Nothing
 * here filters - every result the server sent is kept - but three things decide where each one lands:
 *  1. **How the query matched.** A name that is the query beats a name that starts with it, which beats a name with
 *     a word starting with it, which beats a name that merely contains it.
 *  2. **Which spelling matched.** A hit on the query as typed outranks a hit on one of the rewrites.
 *  3. **Whether it is already yours.** Starred, played before, played often, rated.
 */
object SearchRanker {
    // Match tiers. The gaps between them keep the personal boost from overturning a genuinely better textual match.
    private const val EXACT = 100.0
    private const val PREFIX = 82.0
    private const val WORD_PREFIX = 64.0
    private const val CONTAINS = 40.0

    /** Something the server matched on a field this does not read: kept, at the bottom, because the server had a reason. */
    private const val UNEXPLAINED = 5.0

    /** How much of the name the query accounts for: "Ёлка" answers "Ёлка" better than "Ёлка и её друзья" does. */
    private const val COVERAGE_BONUS = 8.0

    /** A hit on the artist of a song, or its album, is evidence about the song, not a match on its own title. */
    private const val SECONDARY_FIELD_FACTOR = 0.6

    /**
     * The ceiling on familiarity: a familiar item matching weakly (CONTAINS, 40) can overtake an unfamiliar
     * word-prefix match (64) but never an unfamiliar prefix match (82). Being yours is a tie-breaker, never a
     * reason to bury the obvious answer.
     */
    private const val MAX_PERSONAL_BOOST = 30.0

    private const val STARRED_BOOST = 18.0
    private const val PLAYED_BOOST = 12.0
    private const val MAX_PLAY_COUNT_BOOST = 10.0
    private const val RATING_BOOST_PER_STAR = 2.0

    /** Applied only when choosing the single top result: a query that names an artist usually means the artist. */
    private const val ARTIST_TOP_PRIOR = 6.0
    private const val ALBUM_TOP_PRIOR = 2.0

    /**
     * What a name is worth for being a near-miss of what the user actually typed, rather than of the guessed
     * spelling that fetched it. Only ever applies to a round of guesses: "Playing Godd" falls back to "Playing",
     * and "Playing God" is one keystroke from what was typed while "Playing The Angel" is not.
     */
    private const val NEAR_MISS = 98.0
    private const val NEAR_MISS_PER_EDIT = 18.0

    /** A phrase with one word wrong, in the same round of guesses. */
    private const val WORD_OVERLAP = 96.0
    private const val MIN_WORD_RECALL = 0.5
    private const val NEAR_WORD = 0.8
    private const val FUNCTION_WORD_WEIGHT = 0.3

    /**
     * @param variants the spellings that were queried
     * @param results one response per spelling, aligned with [variants]; null where that request failed
     * @param intent the query as the user typed it, when the spellings queried were guesses at it
     */
    fun rank(
        variants: List<QueryVariants.Variant>,
        results: List<SearchResult?>,
        signals: LibrarySignals,
        intent: String? = null,
    ): RankedResults {
        if (variants.isEmpty()) return RankedResults.Empty

        val typed = intent?.let(::Intent)
        val queries = variants.map { QueryVariants.normalize(it.text) }
        val weights = variants.map { it.weight }

        // Deduplicated across responses: the same album comes back from the query as typed and from a rewrite.
        val artists = LinkedHashMap<String, Artist>()
        val albums = LinkedHashMap<String, Album>()
        val songs = LinkedHashMap<String, Song>()
        for (result in results) {
            if (result == null) continue
            result.artists.orEmpty().forEach { a -> a.id?.let { artists.putIfAbsent(it, a) } }
            result.albums.orEmpty().forEach { a -> a.id?.let { albums.putIfAbsent(it, a) } }
            result.songs.orEmpty().forEach { s -> s.id?.let { songs.putIfAbsent(it, s) } }
        }

        val scoredArtists = artists.values.map { scoreArtist(it, queries, weights, signals, typed) }.sortedByDescending { it.total }
        val scoredAlbums = albums.values.map { scoreAlbum(it, queries, weights, signals, typed) }.sortedByDescending { it.total }
        val scoredSongs = songs.values.map { scoreSong(it, queries, weights, signals, typed) }.sortedByDescending { it.total }

        return RankedResults(
            scoredArtists.map { it.item },
            scoredAlbums.map { it.item },
            scoredSongs.map { it.item },
            pickTop(scoredArtists.firstOrNull(), scoredAlbums.firstOrNull(), scoredSongs.firstOrNull()),
        )
    }

    private fun scoreArtist(artist: Artist, queries: List<String>, weights: List<Double>, signals: LibrarySignals, intent: Intent?): Scored<Artist> {
        val match = max(bestMatch(queries, weights, artist.name, artist.sortName), nearMiss(intent, artist.name))
        val personal = personalBoost(
            starred = signals.isStarred(Kind.ARTIST, artist.id) || artist.starred != null,
            played = signals.wasPlayed(Kind.ARTIST, artist.id),
            playCount = null,
            userRating = artist.userRating,
        )
        return Scored(artist, match, personal)
    }

    private fun scoreAlbum(album: Album, queries: List<String>, weights: List<Double>, signals: LibrarySignals, intent: Intent?): Scored<Album> {
        val match = max(
            max(bestMatch(queries, weights, album.name, album.sortName), bestMatch(queries, weights, album.artistLine()) * SECONDARY_FIELD_FACTOR),
            nearMiss(intent, album.name),
        )
        val personal = personalBoost(
            starred = signals.isStarred(Kind.ALBUM, album.id) || album.starred != null,
            played = signals.wasPlayed(Kind.ALBUM, album.id) || isPlayed(album.played),
            playCount = album.playCount,
            userRating = album.userRating,
        )
        return Scored(album, match, personal)
    }

    private fun scoreSong(song: Song, queries: List<String>, weights: List<Double>, signals: LibrarySignals, intent: Intent?): Scored<Song> {
        val match = max(
            max(
                bestMatch(queries, weights, song.title),
                max(bestMatch(queries, weights, song.artistLine()), bestMatch(queries, weights, song.album)) * SECONDARY_FIELD_FACTOR,
            ),
            nearMiss(intent, song.title),
        )
        val personal = personalBoost(
            starred = signals.isStarred(Kind.SONG, song.id) || song.starred != null,
            played = signals.wasPlayed(Kind.SONG, song.id) || isPlayed(song.played),
            playCount = song.playCount,
            userRating = song.userRating,
        )
        return Scored(song, match, personal)
    }

    /** The best any of the spellings does against any of the given fields, discounted by how much it is trusted. */
    private fun bestMatch(queries: List<String>, weights: List<Double>, vararg fields: String?): Double {
        var best = 0.0
        for (i in queries.indices) {
            val query = queries[i]
            if (query.isEmpty()) continue
            for (field in fields) {
                if (field.isNullOrEmpty()) continue
                val score = matchScore(QueryVariants.normalize(field), query) * weights[i]
                if (score > best) best = score
            }
        }
        return best
    }

    /**
     * How close this name is to what the user typed, when what they typed is not what was queried. Zero unless it is
     * within a mistake or two, or - for a query of several words - unless it shares most of them.
     */
    private fun nearMiss(intent: Intent?, field: String?): Double {
        if (intent == null || field.isNullOrEmpty()) return 0.0

        val normalized = QueryVariants.normalize(field)
        var best = 0.0
        if (intent.budget > 0) {
            val distance = EditDistance.nearestWord(normalized, intent.text, intent.budget)
            if (distance >= 0) best = NEAR_MISS - NEAR_MISS_PER_EDIT * distance
        }
        return max(best, wordOverlap(intent, normalized))
    }

    /**
     * How much of a phrase this name is, word by word. Edit distance reads a phrase as one long string, and "love in
     * the bottle" is three edits from "love in a bottle" though three words of four are right. Here each word is
     * matched on its own, counted both ways, and grammar words weigh little.
     */
    private fun wordOverlap(intent: Intent, field: String): Double {
        if (intent.words.size < 2) return 0.0
        val fieldWords = EditDistance.wordsIn(field)
        if (fieldWords.isEmpty()) return 0.0

        var queryWeight = 0.0
        var queryFound = 0.0
        intent.words.forEachIndexed { index, word ->
            val weight = wordWeight(word)
            val last = index == intent.words.size - 1
            queryWeight += weight
            queryFound += weight * (fieldWords.maxOfOrNull { wordMatch(word, it, last) } ?: 0.0)
        }

        var fieldWeight = 0.0
        var fieldFound = 0.0
        for (candidate in fieldWords) {
            val weight = wordWeight(candidate)
            fieldWeight += weight
            var best = 0.0
            intent.words.forEachIndexed { index, word -> best = max(best, wordMatch(word, candidate, index == intent.words.size - 1)) }
            fieldFound += weight * best
        }

        val recall = queryFound / queryWeight
        val precision = fieldFound / fieldWeight

        // Less than half of what was asked for is a different title.
        if (recall < MIN_WORD_RECALL || recall + precision == 0.0) return 0.0
        return WORD_OVERLAP * 2 * recall * precision / (recall + precision)
    }

    /** 1 for the same word; less for one a slip away, or - for the word typed last, which may be unfinished - one it begins. */
    private fun wordMatch(queryWord: String, fieldWord: String, last: Boolean): Double {
        if (queryWord == fieldWord) return 1.0
        if (last && queryWord.length >= 2 && fieldWord.startsWith(queryWord)) return NEAR_WORD
        val budget = EditDistance.budgetFor(min(queryWord.length, fieldWord.length))
        return if (budget > 0 && EditDistance.within(queryWord, fieldWord, budget) >= 0) NEAR_WORD else 0.0
    }

    private fun wordWeight(word: String) = if (QueryVariants.isFunctionWord(word)) FUNCTION_WORD_WEIGHT else 1.0

    private fun matchScore(text: String, query: String): Double {
        if (text.isEmpty()) return 0.0
        if (text == query) return EXACT

        val coverage = COVERAGE_BONUS * min(1.0, query.length.toDouble() / text.length)
        return when {
            text.startsWith(query) -> PREFIX + coverage
            hasWordStartingWith(text, query) -> WORD_PREFIX + coverage
            text.contains(query) -> CONTAINS + coverage
            else -> 0.0
        }
    }

    /** "Река и море" answers a search for "море" (a word starts with it) better than one for "оре" (it only contains it). */
    private fun hasWordStartingWith(text: String, query: String): Boolean =
        EditDistance.wordSpans(text).any { (start, _) -> text.startsWith(query, start) }

    private fun personalBoost(starred: Boolean, played: Boolean, playCount: Long?, userRating: Int?): Double {
        var boost = 0.0
        if (starred) boost += STARRED_BOOST
        if (played) boost += PLAYED_BOOST
        if (playCount != null && playCount > 0) boost += min(MAX_PLAY_COUNT_BOOST, 3.5 * log10(1.0 + playCount))
        if (userRating != null && userRating > 0) boost += userRating * RATING_BOOST_PER_STAR
        return min(MAX_PERSONAL_BOOST, boost)
    }

    /** Servers that have never seen the item played still send a played date, as the epoch rather than as nothing. */
    private fun isPlayed(played: String?): Boolean = !played.isNullOrBlank() && !played.startsWith("1970") && !played.startsWith("0001")

    /**
     * The single result to lead with, or null when no candidate matched on any field this can read. Leading with a
     * result whose relevance cannot be explained is worse than leading with nothing.
     */
    private fun pickTop(artist: Scored<Artist>?, album: Scored<Album>?, song: Scored<Song>?): RankedResults.Top? {
        val artistScore = if (artist == null || !artist.matched) Double.NEGATIVE_INFINITY else artist.total + ARTIST_TOP_PRIOR
        val albumScore = if (album == null || !album.matched) Double.NEGATIVE_INFINITY else album.total + ALBUM_TOP_PRIOR
        val songScore = if (song == null || !song.matched) Double.NEGATIVE_INFINITY else song.total

        val best = max(artistScore, max(albumScore, songScore))
        if (best == Double.NEGATIVE_INFINITY) return null

        return when (best) {
            artistScore -> RankedResults.Top.OfArtist(artist!!.item)
            albumScore -> RankedResults.Top.OfAlbum(album!!.item)
            else -> RankedResults.Top.OfSong(song!!.item)
        }
    }

    private class Scored<T>(val item: T, private val match: Double, private val personal: Double) {
        /** Kept apart from the total because familiarity alone must never promote something to the top card. */
        val matched: Boolean get() = match > 0
        val total: Double get() = (if (match > 0) match else UNEXPLAINED) + personal
    }

    /** The query as typed, folded once for every name it is held against. */
    private class Intent(typed: String) {
        val text = QueryVariants.normalize(typed)
        val budget = EditDistance.budgetFor(text.length)
        val words = EditDistance.wordsIn(text)
    }
}
