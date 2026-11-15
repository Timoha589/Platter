package com.cappielloantonio.tempo.deemix

import com.cappielloantonio.tempo.helper.ArtistCredit
import com.cappielloantonio.tempo.helper.search.QueryVariants
import com.cappielloantonio.tempo.subsonic.models.Child
import com.platter.search.RecordingMatcher
import com.platter.search.RecordingMetadata

internal class DeezerLibraryMatcher(songs: List<Child>) {
    private val matcher = RecordingMatcher(
            songs.filterNot { it.isVideo },
            { song -> RecordingMetadata(song.title, song.artist, song.album, song.duration, song.track,
                    song.discNumber, artistLine = song.artistLine(), id = song.id) },
            QueryVariants::normalize,
            ArtistCredit::pieces
    )

    fun match(track: DeezerTrack, albumTitle: String? = track.album?.title): Child? =
            matcher.match(RecordingMetadata(track.title, track.artist?.name, albumTitle, track.duration,
                    track.trackPosition, track.diskNumber))
}
