package com.cappielloantonio.tempo.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import com.cappielloantonio.tempo.repository.AlbumRepository;
import com.cappielloantonio.tempo.repository.ArtistRepository;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.subsonic.models.ArtistID3;
import com.cappielloantonio.tempo.subsonic.models.ArtistInfo2;
import com.cappielloantonio.tempo.subsonic.models.Child;

import java.util.List;

public class ArtistPageViewModel extends AndroidViewModel {
    /**
     * How many of an artist's most played tracks the page shows. The rest are
     * behind "see all"; twenty pushed the albums a long scroll down the page.
     */
    public static final int TOP_SONGS_COUNT = 10;

    /**
     * Collaborations shown on the page; the rest are behind "see all". An
     * artist the server knows only from joint credits has nothing else on the
     * page, so it shows more of them.
     */
    public static final int COLLABORATIONS_COUNT = 5;
    public static final int COLLABORATIONS_ONLY_COUNT = 20;

    private final AlbumRepository albumRepository;
    private final ArtistRepository artistRepository;

    private ArtistID3 artist;

    public ArtistPageViewModel(@NonNull Application application) {
        super(application);

        albumRepository = new AlbumRepository();
        artistRepository = new ArtistRepository();
    }

    public LiveData<List<AlbumID3>> getAlbumList() {
        return albumRepository.getArtistAlbums(artist.getId());
    }

    public LiveData<ArtistInfo2> getArtistInfo(String id) {
        return artistRepository.getArtistFullInfo(id);
    }

    public LiveData<List<Child>> getArtistTopSongList() {
        return artistRepository.getTopSongs(artist.getName(), TOP_SONGS_COUNT);
    }

    public LiveData<List<Child>> getCollaborations() {
        return artistRepository.getCollaborations(artist.getName());
    }

    /**
     * Whether the artist is one the server has no page for: a name that only
     * ever appears in joint credits, opened from one of them.
     */
    public boolean isCollaborationsOnly() {
        return artist.getId() == null;
    }

    public LiveData<List<Child>> getArtistShuffleList() {
        return artistRepository.getRandomSong(artist, 50);
    }

    public LiveData<List<Child>> getArtistInstantMix() {
        return artistRepository.getInstantMix(artist, 20);
    }

    public ArtistID3 getArtist() {
        return artist;
    }

    public void setArtist(ArtistID3 artist) {
        this.artist = artist;
    }
}
