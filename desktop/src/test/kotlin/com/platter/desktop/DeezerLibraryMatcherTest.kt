package com.platter.desktop

import com.platter.desktop.api.Song
import com.platter.desktop.deemix.DeezerAlbumRef
import com.platter.desktop.deemix.DeezerArtistRef
import com.platter.desktop.deemix.DeezerHit
import com.platter.desktop.deemix.DeezerLibraryMatcher
import com.platter.desktop.deemix.DeezerTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DeezerLibraryMatcherTest {
    @Test
    fun `matched row keeps the selected studio recording and its ID`() {
        val studio = song("studio")
        val live = song("live", title = "Song (Live)")
        val alternate = song("alternate", album = "Live in Paris")
        val local = DeezerLibraryMatcher(listOf(live, alternate, studio)).match(track)
        val row = assertNotNull(DeezerHit.track(track, local))
        assertTrue(row.inLibrary)
        assertSame(studio, row.localTrack)
        assertEquals("studio", row.localTrackId)
    }

    @Test
    fun `ambiguous recordings do not get a library mark`() {
        val matches = DeezerLibraryMatcher(listOf(song("one"), song("another")))
        val row = assertNotNull(DeezerHit.track(track, matches.match(track)))
        assertFalse(row.inLibrary)
        assertNull(row.localTrackId)
    }

    @Test
    fun `the concert version is matched when that version was requested`() {
        val live = song("live", title = "Song (Live)", album = "Live in Paris")
        val catalogue = DeezerLibraryMatcher(listOf(song("studio"), live))
        val request = track.copy(title = "Song (Live)", album = DeezerAlbumRef(3L, "Live in Paris", null))
        assertSame(live, catalogue.match(request))
    }

    private val track = DeezerTrack(
        id = 42L,
        title = "Song",
        link = "https://example.invalid/track/42",
        duration = 180,
        rank = null,
        trackPosition = null,
        diskNumber = null,
        artist = DeezerArtistRef(1L, "Artist"),
        album = DeezerAlbumRef(2L, "Studio Album", null),
    )

    private fun song(id: String, title: String = "Song", album: String = "Studio Album") = Song().apply {
        this.id = id
        this.title = title
        this.album = album
        artist = "Artist"
        duration = 180
    }
}
