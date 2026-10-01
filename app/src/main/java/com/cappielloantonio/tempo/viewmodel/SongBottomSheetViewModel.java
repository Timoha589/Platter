package com.cappielloantonio.tempo.viewmodel;

import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.interfaces.StarCallback;
import com.cappielloantonio.tempo.repository.AlbumRepository;
import com.cappielloantonio.tempo.repository.ArtistRepository;
import com.cappielloantonio.tempo.repository.FavoriteRepository;
import com.cappielloantonio.tempo.repository.PlaylistRepository;
import com.cappielloantonio.tempo.repository.SharingRepository;
import com.cappielloantonio.tempo.repository.SongRepository;
import com.cappielloantonio.tempo.service.LikedTracksCache;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.subsonic.models.ArtistID3;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.Playlist;
import com.cappielloantonio.tempo.subsonic.models.Share;
import com.cappielloantonio.tempo.util.NetworkUtil;

import java.util.Date;
import java.util.List;

@UnstableApi
public class SongBottomSheetViewModel extends AndroidViewModel {
    private final SongRepository songRepository;
    private final AlbumRepository albumRepository;
    private final ArtistRepository artistRepository;
    private final FavoriteRepository favoriteRepository;
    private final SharingRepository sharingRepository;
    private final PlaylistRepository playlistRepository;

    private Child song;


    public SongBottomSheetViewModel(@NonNull Application application) {
        super(application);

        songRepository = new SongRepository();
        albumRepository = new AlbumRepository();
        artistRepository = new ArtistRepository();
        favoriteRepository = new FavoriteRepository();
        sharingRepository = new SharingRepository();
        playlistRepository = new PlaylistRepository();
    }

    public MutableLiveData<Boolean> removeFromPlaylist(Playlist playlist) {
        return playlistRepository.removeSongFromPlaylist(playlist.getId(), song.getId());
    }

    public Child getSong() {
        return song;
    }

    public void setSong(Child song) {
        this.song = song;
    }

    public void setFavorite(Context context) {
        if (song.getStarred() != null) {
            if (NetworkUtil.isOffline()) {
                removeFavoriteOffline(song);
            } else {
                removeFavoriteOnline(song);
            }

            LikedTracksCache.onUnliked(context, song.getId());
        } else {
            if (NetworkUtil.isOffline()) {
                setFavoriteOffline(song);
            } else {
                setFavoriteOnline(context, song);
            }
        }
    }

    private void removeFavoriteOffline(Child media) {
        favoriteRepository.starLater(media.getId(), null, null, false);
        media.setStarred(null);
    }

    private void removeFavoriteOnline(Child media) {
        favoriteRepository.unstar(media.getId(), null, null, new StarCallback() {
            @Override
            public void onError() {
                // media.setStarred(new Date());
                favoriteRepository.starLater(media.getId(), null, null, false);
            }
        });

        media.setStarred(null);
    }

    private void setFavoriteOffline(Child media) {
        favoriteRepository.starLater(media.getId(), null, null, true);
        media.setStarred(new Date());
    }

    private void setFavoriteOnline(Context context, Child media) {
        favoriteRepository.star(media.getId(), null, null, new StarCallback() {
            @Override
            public void onError() {
                // media.setStarred(null);
                favoriteRepository.starLater(media.getId(), null, null, true);
            }
        });

        media.setStarred(new Date());

        LikedTracksCache.onLiked(context, media);
    }

    public LiveData<AlbumID3> getAlbum() {
        return albumRepository.getAlbum(song.getAlbumId());
    }

    public LiveData<ArtistID3> getArtist() {
        return artistRepository.getArtist(song.getArtistId());
    }

    /* One LiveData per request - see PlayerBottomSheetViewModel.getMediaInstantMix(). */
    public LiveData<List<Child>> getInstantMix(Child media) {
        return songRepository.getInstantMix(media.getId(), 20);
    }

    public MutableLiveData<Share> shareTrack() {
        return sharingRepository.createShare(song.getId(), song.getTitle(), null);
    }
}
