package com.platter.desktop

import com.platter.desktop.api.Auth
import com.platter.desktop.api.Credentials
import com.platter.desktop.api.LyricLine
import com.platter.desktop.api.LyricsList
import com.platter.desktop.api.LyricsPicker
import com.platter.desktop.api.Song
import com.platter.desktop.api.StructuredLyrics
import com.platter.desktop.api.SubsonicClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsTest {
    private fun line(start: Int?, text: String) = LyricLine().apply { this.start = start; value = text }

    private fun layer(kind: String?, synced: Boolean, lang: String? = null, vararg lines: LyricLine) = StructuredLyrics().apply {
        this.kind = kind
        this.synced = synced
        this.lang = lang
        this.line = lines.toList()
    }

    private fun list(vararg layers: StructuredLyrics) = LyricsList().apply { structuredLyrics = layers.toList() }

    @Test
    fun `the sung words beat a translation listed before them`() {
        val translation = layer("translation", true, "ru", line(0, "перевод"))
        val main = layer("main", true, "und", line(0, "words"))
        assertEquals("words", LyricsPicker.pick(list(translation, main))!!.line!!.single().value)
    }

    @Test
    fun `a v1 server's layer without a kind counts as the main one`() {
        val untyped = layer(null, false, null, line(null, "words"))
        val pronunciation = layer("pronunciation", true, null, line(0, "ba"))
        assertEquals("words", LyricsPicker.pick(list(pronunciation, untyped))!!.line!!.single().value)
    }

    @Test
    fun `timed beats untimed and the readers language breaks the tie`() {
        val untimed = layer("main", false, "en", line(null, "plain"))
        val timed = layer("main", true, "en", line(0, "timed"))
        assertEquals("timed", LyricsPicker.pick(list(untimed, timed), "en")!!.line!!.single().value)

        val english = layer("main", true, "en", line(0, "english"))
        val russian = layer("main", true, "ru", line(0, "русский"))
        assertEquals("русский", LyricsPicker.pick(list(english, russian), "ru")!!.line!!.single().value)
    }

    @Test
    fun `empty layers and empty lists give nothing`() {
        assertNull(LyricsPicker.pick(null))
        assertNull(LyricsPicker.pick(list()))
        assertNull(LyricsPicker.pick(list(layer("main", true, null))))
    }

    @Test
    fun `active line is the last one that has started, on the offset clock`() {
        val lines = listOf(line(1000, "a"), line(3000, "b"), line(5000, "c"))
        assertEquals(-1, LyricsPicker.activeLine(lines, 999))
        assertEquals(0, LyricsPicker.activeLine(lines, 1000))
        assertEquals(1, LyricsPicker.activeLine(lines, 4999))
        assertEquals(2, LyricsPicker.activeLine(lines, 60_000))
        // An offset of +500 makes everything arrive half a second earlier.
        assertEquals(1, LyricsPicker.activeLine(lines, 2500, offsetMs = 500))
    }

    @Test
    fun `seeking to a line lands on its start less the offset, never before zero`() {
        assertEquals(2500, LyricsPicker.seekPosition(line(3000, "b"), 500))
        assertEquals(0, LyricsPicker.seekPosition(line(100, "a"), 500))
        // A line with no start has nowhere to seek to - not the beginning of the track.
        assertNull(LyricsPicker.seekPosition(line(null, "x"), 0))
    }

    @Test
    fun `a late first line gets an empty one before it for the wait`() {
        val late = listOf(line(12_000, "a"), line(15_000, "b"))
        val shown = LyricsPicker.withIntro(late)
        assertEquals(listOf("", "a", "b"), shown.map { it.value })
        assertEquals(0, shown.first().start)
        // The wait is the active row until the first line starts.
        assertEquals(0, LyricsPicker.activeLine(shown, 5_000))
        assertEquals(1, LyricsPicker.activeLine(shown, 12_000))

        assertEquals(late, LyricsPicker.withIntro(late, gapMs = 20_000))
        val soon = listOf(line(1_000, "a"))
        assertEquals(soon, LyricsPicker.withIntro(soon))
        val alreadyBlank = listOf(line(9_000, ""), line(12_000, "a"))
        assertEquals(alreadyBlank, LyricsPicker.withIntro(alreadyBlank))
    }

    @Test
    fun `untimed lines do not count as synced even when the flag says so`() {
        assertFalse(LyricsPicker.isSynced(layer("main", true, null, line(null, "x"))))
        assertTrue(LyricsPicker.isSynced(layer("main", true, null, line(0, "x"))))
    }

    private fun client(fake: FakeSubsonic) = SubsonicClient(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt"))
    private fun song(id: String, artist: String = "X") = Song().apply { this.id = id; this.artist = artist; title = "T" }

    @Test
    fun `lyrics come from the layered endpoint, picking the main layer`() = FakeSubsonic().use { fake ->
        val lyrics = runBlocking { client(fake).lyrics(song("s2-1")) }
        assertTrue(lyrics.isSynced)
        assertEquals("Line 1 of the song", lyrics.lines.first())
        assertEquals(8, lyrics.lines.size)
    }

    @Test
    fun `a track the layered endpoint knows nothing of falls back to plain lyrics`() = FakeSubsonic().use { fake ->
        val lyrics = runBlocking { client(fake).lyrics(song("s3-1", artist = "Fleetwood Mac")) }
        assertFalse(lyrics.isSynced)
        assertEquals(listOf("First plain line", "Second plain line"), lyrics.lines)
        assertEquals(1, fake.requestsTo("getLyrics").size)
    }

    @Test
    fun `a track with no words at all is empty`() = FakeSubsonic().use { fake ->
        assertTrue(runBlocking { client(fake).lyrics(song("s5-1")) }.isEmpty)
    }
}
