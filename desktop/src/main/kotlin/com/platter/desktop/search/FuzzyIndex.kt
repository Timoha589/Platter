package com.platter.desktop.search

/**
 * Finds the name in the library that a misspelling was probably aiming at.
 *
 * The rewrites in [QueryVariants] follow rules that can be inverted. A typo follows no rule, so the only way to
 * resolve one is to hold the query up against the names that actually exist and ask which is nearest.
 *
 * A correction is only ever turned back into another query - the server is asked again with the corrected spelling
 * and answers with real results. Being wrong here costs a request and some results ranked below the literal ones,
 * never a wrong answer presented as right.
 */
class FuzzyIndex private constructor(private val entries: List<Entry>) {
    private class Entry(val text: String, val normalized: String)
    private class Match(val text: String, val distance: Int)

    val isEmpty: Boolean get() = entries.isEmpty()

    /** The nearest names to [query], best first, or none when nothing is near enough to ask the server about again. */
    fun correctionsFor(query: String, limit: Int): List<String> {
        val normalized = QueryVariants.normalize(query)
        val budget = EditDistance.budgetFor(normalized.length)
        if (budget == 0 || entries.isEmpty()) return emptyList()

        val matches = entries.mapNotNull { nearest(it, normalized, budget) }
            // Fewest mistakes first; between two equally close names, the shorter, which the query accounts for more of.
            .sortedWith(compareBy<Match> { it.distance }.thenBy { it.text.length })

        val corrections = LinkedHashSet<String>()
        for (match in matches) {
            if (corrections.size >= limit) break
            corrections += match.text
        }
        return corrections.toList()
    }

    /**
     * The best this name can do against the query: as a whole, or as one of its words. When a word is what matched,
     * the word is what gets returned - handing the server a full album title would ask it for that one album.
     */
    private fun nearest(entry: Entry, query: String, budget: Int): Match? {
        val whole = EditDistance.within(entry.normalized, query, budget)
        if (whole >= 0) return Match(entry.text, whole)

        var best = -1
        var bestText: String? = null
        for ((start, end) in EditDistance.wordSpans(entry.normalized)) {
            val word = entry.normalized.substring(start, end)
            val distance = EditDistance.within(word, query, budget)
            if (distance >= 0 && (best < 0 || distance < best)) {
                best = distance
                // Folding never changes a length, so the offsets carry over to the original spelling; where it did,
                // the folded word is still a usable query.
                bestText = if (entry.text.length == entry.normalized.length) entry.text.substring(start, end) else word
            }
        }
        return if (best < 0) null else Match(bestText!!, best)
    }

    companion object {
        fun empty() = FuzzyIndex(emptyList())

        fun of(names: Collection<String>): FuzzyIndex =
            FuzzyIndex(names.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().map { Entry(it, QueryVariants.normalize(it)) }.toList())
    }
}
