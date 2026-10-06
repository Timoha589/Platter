package com.platter.desktop

import com.platter.desktop.api.Album
import com.platter.desktop.api.Artist
import com.platter.desktop.api.SearchResult
import com.platter.desktop.api.Song
import com.platter.desktop.search.EditDistance
import com.platter.desktop.search.FuzzyIndex
import com.platter.desktop.search.LibrarySignals
import com.platter.desktop.search.QueryVariants
import com.platter.desktop.search.RankedResults
import com.platter.desktop.search.SearchEngine
import com.platter.desktop.search.SearchRanker
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun artist(id: String, name: String) = Artist().apply { this.id = id; this.name = name }
private fun album(id: String, name: String, by: String? = null, starred: Boolean = false) =
    Album().apply { this.id = id; this.name = name; artist = by; if (starred) this.starred = "2024-01-01T00:00:00Z" }
private fun song(id: String, title: String, by: String? = null, onAlbum: String? = null) =
    Song().apply { this.id = id; this.title = title; artist = by; album = onAlbum }

private fun typed(text: String) = listOf(QueryVariants.Variant.typed(text))

class QueryVariantsTest {
    private fun texts(query: String) = QueryVariants.of(query).map { it.text }

    @Test
    fun `the query as typed always comes first and alone when nothing can be rewritten`() {
        assertEquals(listOf("1984"), texts("1984"))
        assertEquals("Abbey Road", texts("Abbey Road").first())
        assertTrue(QueryVariants.of("   ").isEmpty())
    }

    @Test
    fun `an English layout typed for a Russian name is swapped key for key`() {
        // t k r f are the keys of е л к а.
        assertTrue("елка" in texts("tkrf"))
        // And е at the start of a word may be ё.
        assertTrue("ёлка" in texts("tkrf"))
    }

    @Test
    fun `a romanised name is turned back into Cyrillic`() {
        val variants = texts("Elka")
        assertTrue("елка" in variants)
        assertTrue("ёлка" in variants)
    }

    @Test
    fun `a Russian query also offers the other layout and the romanisation`() {
        val variants = texts("елка")
        assertTrue("tkrf" in variants, "layout: $variants")
        assertTrue("elka" in variants, "romanisation: $variants")
        assertTrue("ёлка" in variants, "yo: $variants")
    }

    @Test
    fun `a rewrite is trusted less than what was typed`() {
        val variants = QueryVariants.of("Elka")
        assertEquals(1.0, variants.first().weight)
        assertTrue(variants.drop(1).all { it.weight < 1.0 })
        assertTrue(QueryVariants.Variant.guessed("x").weight < variants[1].weight)
    }

    @Test
    fun `words typed apart are also tried run together`() {
        assertTrue("streetcat" in texts("street cat"))
        // A phrase of many words is not one name split up.
        assertTrue(texts("one two three four").none { it == "onetwothreefour" })
    }

    @Test
    fun `dropping words starts with the grammar words`() {
        val drops = QueryVariants.withWordsDropped("Love in the Bottle")
        assertEquals("Love Bottle", drops.first())
        assertTrue("Love in Bottle" in drops)
        assertTrue(drops.size <= 4)
        // One word is nothing to drop; only grammar left asks for half the library.
        assertTrue(QueryVariants.withWordsDropped("Bottle").isEmpty())
        assertTrue(QueryVariants.withWordsDropped("in the").isEmpty())
    }

    @Test
    fun `folding ignores case, accents and the yo`() {
        assertEquals(QueryVariants.normalize("ЁЛКА"), QueryVariants.normalize("елка"))
        assertEquals("cafe", QueryVariants.normalize("Café"))
        assertEquals("", QueryVariants.normalize(null))
    }
}

class EditDistanceTest {
    @Test
    fun `a swap of two neighbours is one mistake, not two`() {
        assertEquals(1, EditDistance.within("мельинца", "мельница", 1))
        assertEquals(1, EditDistance.within("radiohead", "radiohaed", 1))
    }

    @Test
    fun `the budget is a hard stop`() {
        assertEquals(-1, EditDistance.within("abcdefgh", "abcdwxyz", 2))
        assertEquals(0, EditDistance.within("same", "same", 0))
        assertEquals(-1, EditDistance.within("short", "muchlongerword", 2))
    }

    @Test
    fun `short words are left alone and longer ones forgive more`() {
        assertEquals(0, EditDistance.budgetFor(4))
        assertEquals(1, EditDistance.budgetFor(5))
        assertEquals(1, EditDistance.budgetFor(7))
        assertEquals(2, EditDistance.budgetFor(8))
    }

    @Test
    fun `one word of a longer name can be the near one`() {
        assertEquals(1, EditDistance.nearestWord("мельница - лучшее", "мельнеца", 2))
        assertEquals(-1, EditDistance.nearestWord("абсолютно другое", "мельница", 2))
    }
}

class FuzzyIndexTest {
    private val index = FuzzyIndex.of(listOf("Мельница", "Radiohead", "The Dark Side of the Moon", "Мельница - Лучшее"))

    @Test
    fun `a misspelling is corrected towards the name it was aiming at`() {
        assertEquals(listOf("Мельница"), index.correctionsFor("Мельнеца", 2).take(1))
        assertEquals(listOf("Radiohead"), index.correctionsFor("Radiohaed", 1))
    }

    @Test
    fun `a word of a long title is offered as the word, not the whole title`() {
        assertTrue("Dark" in index.correctionsFor("Darck", 3))
        assertTrue("Moon" in index.correctionsFor("Mooon", 3))
    }

    @Test
    fun `short queries and an empty library correct nothing`() {
        assertTrue(index.correctionsFor("Cat", 3).isEmpty())
        assertTrue(FuzzyIndex.empty().correctionsFor("Radiohead", 3).isEmpty())
        assertTrue(FuzzyIndex.empty().isEmpty)
    }

    @Test
    fun `the closest comes first and the limit holds`() {
        val library = FuzzyIndex.of(listOf("Platinum", "Platinun", "Plutinum"))
        val found = library.correctionsFor("Platinum", 2)
        assertEquals("Platinum", found.first())
        assertEquals(2, found.size)
    }
}

class SearchRankerTest {
    private fun rank(query: String, result: SearchResult, signals: LibrarySignals = LibrarySignals.Empty) =
        SearchRanker.rank(typed(query), listOf(result), signals)

    @Test
    fun `a name that is the query beats one that starts with it, and that beats one that holds it`() {
        val ranked = rank(
            "abbey",
            SearchResult().apply {
                albums = listOf(album("1", "Grabbeyx"), album("2", "Road to Abbey"), album("3", "Abbey Road"), album("4", "Abbey"))
            },
        )
        assertEquals(listOf("4", "3", "2", "1"), ranked.albums.map { it.id })
    }

    @Test
    fun `an artist named like the query leads over an album of the same name`() {
        val ranked = rank(
            "nirvana",
            SearchResult().apply {
                albums = listOf(album("a", "Nirvana"))
                artists = listOf(artist("x", "Nirvana"))
                songs = listOf(song("s", "Nirvana", "Someone Else"))
            },
        )
        assertIs<RankedResults.Top.OfArtist>(ranked.top)
    }

    @Test
    fun `the closer a name is to the query the more of it the query accounts for`() {
        val ranked = rank("Ёлка", SearchResult().apply { songs = listOf(song("2", "Ёлка и её друзья"), song("1", "елка")) })
        assertEquals(listOf("1", "2"), ranked.songs.map { it.id })
    }

    @Test
    fun `being starred and played can lift a weak match over a plain word match, never over a prefix match`() {
        val signals = LibrarySignals(
            starred = listOf(LibrarySignals.Kind.ALBUM to "familiar"),
            played = listOf(LibrarySignals.Kind.ALBUM to "familiar"),
        )
        val weak = album("familiar", "Grabbeyx")
        val word = album("word", "Road Abbeyx")
        val prefix = album("prefix", "Abbey Road")

        val overWord = rank("abbey", SearchResult().apply { albums = listOf(word, weak) }, signals)
        assertEquals("familiar", overWord.albums.first().id)

        val overPrefix = rank("abbey", SearchResult().apply { albums = listOf(weak, prefix) }, signals)
        assertEquals("prefix", overPrefix.albums.first().id)
    }

    @Test
    fun `ids are told apart by kind`() {
        // A starred song 42 must not vouch for album 42.
        val signals = LibrarySignals(starred = listOf(LibrarySignals.Kind.SONG to "42"))
        assertTrue(signals.isStarred(LibrarySignals.Kind.SONG, "42"))
        assertTrue(!signals.isStarred(LibrarySignals.Kind.ALBUM, "42"))
    }

    @Test
    fun `a hit on the artist of a song counts, but less than a hit on its title`() {
        val ranked = rank(
            "miles",
            SearchResult().apply { songs = listOf(song("by", "Blue in Green", by = "Miles Davis"), song("title", "Miles Ahead", by = "Gil Evans")) },
        )
        assertEquals(listOf("title", "by"), ranked.songs.map { it.id })
    }

    @Test
    fun `everything the server sent is kept, and an unexplained hit never leads`() {
        val ranked = rank("zzz", SearchResult().apply { songs = listOf(song("1", "Something unrelated", "Nobody")) })
        assertEquals(1, ranked.songs.size)
        assertNull(ranked.top)
    }

    @Test
    fun `the same result from two spellings is one result`() {
        val variants = listOf(QueryVariants.Variant.typed("Elka"), QueryVariants.Variant.rewritten("елка"))
        val one = SearchResult().apply { artists = listOf(artist("1", "Ёлка")) }
        val ranked = SearchRanker.rank(variants, listOf(null, one), LibrarySignals.Empty)
        assertEquals(1, ranked.artists.size)
        assertEquals("Ёлка", ranked.top?.title)
    }

    @Test
    fun `a name one slip from what was typed beats a longer name the guess happened to fetch`() {
        // "Playing Godd" fell back to asking for "Playing", which returns everything with that word.
        val ranked = SearchRanker.rank(
            listOf(QueryVariants.Variant.guessed("Playing")),
            listOf(SearchResult().apply { albums = listOf(album("angel", "Playing The Angel"), album("god", "Playing God")) }),
            LibrarySignals.Empty,
            intent = "Playing Godd",
        )
        assertEquals("god", ranked.albums.first().id)
    }

    @Test
    fun `a phrase with one word wrong finds the title it was meant for`() {
        val ranked = SearchRanker.rank(
            listOf(QueryVariants.Variant.guessed("Love Bottle")),
            listOf(SearchResult().apply { songs = listOf(song("dark", "Love in the Dark"), song("time", "Time in a Bottle"), song("right", "Love in a Bottle")) }),
            LibrarySignals.Empty,
            intent = "Love in the Bottle",
        )
        assertEquals("right", ranked.songs.first().id)
    }

    @Test
    fun `an item the server marks as played counts as played, an epoch date does not`() {
        val played = album("p", "Abbey Roadx").apply { this.played = "2025-05-05T10:00:00Z" }
        val never = album("n", "Abbey Roadx").apply { this.played = "1970-01-01T00:00:00Z" }
        val ranked = rank("abbey", SearchResult().apply { albums = listOf(never, played) })
        assertEquals("p", ranked.albums.first().id)
    }

    @Test
    fun `nothing at all is an empty answer`() {
        assertEquals(RankedResults.Empty, SearchRanker.rank(emptyList(), emptyList(), LibrarySignals.Empty))
        assertTrue(rank("a", SearchResult()).isEmpty)
    }
}

class SearchEngineTest {
    private fun engine(index: FuzzyIndex = FuzzyIndex.empty(), source: (String) -> SearchResult) =
        SearchEngine({ q -> source(q) }, { LibrarySignals.Empty }, { index })

    @Test
    fun `a Cyrillic name typed on the wrong layout is found by asking in the right one`() = runBlocking {
        val asked = ArrayList<String>()
        val result = engine { q ->
            asked += q
            if (q == "елка") SearchResult().apply { artists = listOf(artist("1", "Елка")) } else SearchResult()
        }.search("tkrf")
        assertEquals("Елка", result.top?.title)
        assertTrue("tkrf" in asked && "елка" in asked, "asked: $asked")
    }

    @Test
    fun `when nothing matched, the nearest name in the library is asked for`() = runBlocking {
        val asked = ArrayList<String>()
        val library = FuzzyIndex.of(listOf("Мельница"))
        val result = engine(library) { q ->
            asked += q
            if (q == "Мельница") SearchResult().apply { artists = listOf(artist("m", "Мельница")) } else SearchResult()
        }.search("Мельнеца")
        assertEquals("Мельница", result.artists.single().name)
        assertEquals("Мельница", result.top?.title)
        assertTrue("Мельница" in asked)
    }

    @Test
    fun `a word wrong in the middle of a phrase is found by dropping words`() = runBlocking {
        val result = engine { q ->
            // The server wants every word: only the shorter ask finds anything.
            if (q.lowercase() == "love bottle" || q.lowercase() == "love in bottle") SearchResult().apply { songs = listOf(song("1", "Love in a Bottle")) } else SearchResult()
        }.search("Love in the Bottle")
        assertEquals("Love in a Bottle", result.songs.single().title)
    }

    @Test
    fun `a typo at the end is found by cutting the query short`() = runBlocking {
        val result = engine { q ->
            if (q == "Radioh") SearchResult().apply { artists = listOf(artist("r", "Radiohead")) } else SearchResult()
        }.search("Radiohzzd")
        assertEquals("Radiohead", result.top?.title)
    }

    @Test
    fun `an honest empty answer stays empty and does not retry forever`() = runBlocking {
        var calls = 0
        val result = engine { calls++; SearchResult() }.search("qqqq")
        assertTrue(result.isEmpty)
        assertTrue(calls < 12, "asked $calls times")
    }

    @Test
    fun `one spelling failing does not spoil the others, every one failing is reported`() = runBlocking {
        val partial = engine { q ->
            if (q == "Elka") throw IOException("one failed")
            SearchResult().apply { artists = listOf(artist("1", "Елка")) }
        }.search("Elka")
        assertNotNull(partial.top)

        assertFailsWith<IOException> { engine { throw IOException("down") }.search("anything") }
        Unit
    }

    @Test
    fun `a blank query asks nothing`() = runBlocking {
        var calls = 0
        assertTrue(engine { calls++; SearchResult() }.search("  ").isEmpty)
        assertEquals(0, calls)
    }
}
