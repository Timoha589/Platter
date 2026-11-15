package com.cappielloantonio.tempo.deemix

import com.cappielloantonio.tempo.subsonic.models.Child
import com.cappielloantonio.tempo.viewmodel.DeezerHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeezerLibraryMatcherTest {
    @Test
    fun studioTrackKeepsItsLocalIdWhenTheLiveTitleArrivesFirst() {
        val track = deezer()
        val live = local("live", title = "Song (Live)")
        val studio = local("studio")
        val selected = DeezerLibraryMatcher(listOf(live, studio)).match(track)
        val row = requireNotNull(DeezerHit.track(track, selected))

        assertTrue(row.inLibrary)
        assertEquals("studio", row.localTrackId)
        assertSame(studio, row.localTrack)
        assertEquals("studio", row.localTrack?.id)
    }

    @Test
    fun concertReleaseWithIdenticalTitleAndDurationCannotReplaceTheStudioRelease() {
        val track = deezer()
        val live = local("live", album = "Live in Paris")
        val row = requireNotNull(DeezerHit.track(track, DeezerLibraryMatcher(listOf(live)).match(track)))

        assertFalse(row.inLibrary)
        assertNull(row.localTrackId)
        assertNull(row.localTrack)
        assertSame(live, DeezerLibraryMatcher(listOf(live, local("studio"))).match(deezer(album = "Live in Paris")))
    }

    @Test
    fun explicitlyDifferentVersionsAreNotTreatedAsTitleSuffixes() {
        val track = deezer()
        for (version in listOf("Live", "Acoustic", "Radio Edit", "Demo", "Remastered", "with orchestra")) {
            assertNull(version, DeezerLibraryMatcher(listOf(local("other", title = "Song ($version)"))).match(track))
        }
    }

    @Test
    fun albumPageSuppliesTheReleaseWhenItsTracklistDoesNot() {
        val track = deezer(album = null, duration = null)
        val studio = local("studio")
        val live = local("live", album = "Live in Paris")

        val matcher = DeezerLibraryMatcher(listOf(live, studio))
        assertNull(matcher.match(track))
        assertSame(studio, matcher.match(track, albumTitle = "Studio Album"))
    }

    @Test
    fun unresolvedTiesLeaveNoLocalIdButRepeatedCopiesOfOneSearchResultAreDeduplicated() {
        val track = deezer()
        val first = local("first")
        val second = local("second")
        assertNull(DeezerLibraryMatcher(listOf(first, second)).match(track))
        assertSame(first, DeezerLibraryMatcher(listOf(first, first)).match(track))
    }

    @Test
    fun durationCanConfirmMissingReleaseMetadataButRejectsAConflictingRecording() {
        val track = deezer()
        val withinRounding = local("rounded", album = null, duration = 181)
        val longer = local("longer", duration = 188)
        assertSame(withinRounding, DeezerLibraryMatcher(listOf(longer, withinRounding)).match(track))
        assertNull(DeezerLibraryMatcher(listOf(longer)).match(track))
    }

    @Test
    fun featuredCreditIsOptionalButAConflictingGuestOrPartialArtistIsRejected() {
        val track = deezer(title = "Song (feat. Guest)", artist = "Adèle")
        val collaboration = local("joint", artist = "Adele/Guest")
        val tribute = local("tribute", artist = "Adele Tribute Band")
        val differentGuest = local("other-guest", title = "Song (feat. Other)")
        assertSame(collaboration, DeezerLibraryMatcher(listOf(tribute, differentGuest, collaboration)).match(track))
        assertNull(DeezerLibraryMatcher(listOf(tribute, differentGuest)).match(track))
    }

    @Test
    fun aConflictingTrackPositionCannotReplaceTheRequestedTakeOnTheSameAlbum() {
        val track = deezer().copy(trackPosition = 2, diskNumber = 1)
        val alternate = local("alternate").apply { this.track = 7; discNumber = 1 }
        assertNull(DeezerLibraryMatcher(listOf(alternate)).match(track))
    }

    private fun deezer(
        title: String = "Song",
        artist: String = "Adele",
        album: String? = "Studio Album",
        duration: Int? = 180,
    ): DeezerTrack = DeezerTrack(
        id = 42L,
        title = title,
        link = "https://example.invalid/track/42",
        duration = duration,
        rank = null,
        trackPosition = null,
        diskNumber = null,
        artist = DeezerArtistRef(1L, artist),
        album = album?.let { DeezerAlbumRef(2L, it, null) },
    )

    private fun local(
        id: String,
        title: String = "Song",
        artist: String = "Adele",
        album: String? = "Studio Album",
        duration: Int? = 180,
    ): Child = Child(id = id, title = title, artist = artist, album = album, duration = duration)
}
