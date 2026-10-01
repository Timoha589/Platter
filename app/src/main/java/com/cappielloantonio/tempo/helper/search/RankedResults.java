package com.cappielloantonio.tempo.helper.search;

import androidx.annotation.Nullable;

import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.subsonic.models.ArtistID3;
import com.cappielloantonio.tempo.subsonic.models.Child;

import java.util.Collections;
import java.util.List;

/** One search, answered: each list in relevance order, plus the single best hit. */
public final class RankedResults {
    private final List<ArtistID3> artists;
    private final List<AlbumID3> albums;
    private final List<Child> songs;
    private final TopResult top;

    RankedResults(List<ArtistID3> artists, List<AlbumID3> albums, List<Child> songs, @Nullable TopResult top) {
        this.artists = artists;
        this.albums = albums;
        this.songs = songs;
        this.top = top;
    }

    public static RankedResults empty() {
        return new RankedResults(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), null);
    }

    public List<ArtistID3> getArtists() {
        return artists;
    }

    public List<AlbumID3> getAlbums() {
        return albums;
    }

    public List<Child> getSongs() {
        return songs;
    }

    /** The one result to lead with, or null when nothing matched at all. */
    @Nullable
    public TopResult getTop() {
        return top;
    }

    public boolean isEmpty() {
        return artists.isEmpty() && albums.isEmpty() && songs.isEmpty();
    }

    /**
     * The best hit across all three types.
     * <p>
     * Held as one of three nullable fields rather than as a common supertype
     * because the three have none: an artist, an album and a song are unrelated
     * classes from the API models, and each is navigated to differently.
     */
    public static final class TopResult {
        public enum Kind {
            ARTIST,
            ALBUM,
            SONG
        }

        private final Kind kind;
        private final ArtistID3 artist;
        private final AlbumID3 album;
        private final Child song;

        private TopResult(Kind kind, ArtistID3 artist, AlbumID3 album, Child song) {
            this.kind = kind;
            this.artist = artist;
            this.album = album;
            this.song = song;
        }

        static TopResult of(ArtistID3 artist) {
            return new TopResult(Kind.ARTIST, artist, null, null);
        }

        static TopResult of(AlbumID3 album) {
            return new TopResult(Kind.ALBUM, null, album, null);
        }

        static TopResult of(Child song) {
            return new TopResult(Kind.SONG, null, null, song);
        }

        public Kind getKind() {
            return kind;
        }

        public ArtistID3 getArtist() {
            return artist;
        }

        public AlbumID3 getAlbum() {
            return album;
        }

        public Child getSong() {
            return song;
        }

        /** The name to print. */
        public String getTitle() {
            switch (kind) {
                case ARTIST:
                    return artist.getName();
                case ALBUM:
                    return album.getName();
                default:
                    return song.getTitle();
            }
        }

        /** The credit under the name; null for an artist, who is their own credit. */
        @Nullable
        public String getSubtitle() {
            switch (kind) {
                case ALBUM:
                    return album.artistLine();
                case SONG:
                    return song.artistLine();
                default:
                    return null;
            }
        }

        @Nullable
        public String getCoverArtId() {
            switch (kind) {
                case ARTIST:
                    return artist.getCoverArtId();
                case ALBUM:
                    return album.getCoverArtId();
                default:
                    return song.getCoverArtId();
            }
        }
    }
}
