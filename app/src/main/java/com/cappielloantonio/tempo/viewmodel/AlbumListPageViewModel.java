package com.cappielloantonio.tempo.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.cappielloantonio.tempo.repository.AlbumRepository;
import com.cappielloantonio.tempo.repository.DownloadRepository;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.subsonic.models.ArtistID3;
import com.cappielloantonio.tempo.util.Constants;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;

public class AlbumListPageViewModel extends AndroidViewModel {
    private final AlbumRepository albumRepository;
    private final DownloadRepository downloadRepository;

    public String title;
    public ArtistID3 artist;

    private MutableLiveData<List<AlbumID3>> albumList;

    public int maxNumber = 500;

    public AlbumListPageViewModel(@NonNull Application application) {
        super(application);

        albumRepository = new AlbumRepository();
        downloadRepository = new DownloadRepository();
    }

    public LiveData<List<AlbumID3>> getAlbumList(LifecycleOwner owner) {
        // No value until the answer; null is a failed request.
        albumList = new MutableLiveData<>();

        switch (title) {
            case Constants.ALBUM_RECENTLY_PLAYED:
                albumRepository.getAlbums("recent", maxNumber, null, null).observe(owner, albums -> albumList.setValue(albums));
                break;
            case Constants.ALBUM_MOST_PLAYED:
                albumRepository.getAlbums("frequent", maxNumber, null, null).observe(owner, albums -> albumList.setValue(albums));
                break;
            case Constants.ALBUM_RECENTLY_ADDED:
                albumRepository.getAlbums("newest", maxNumber, null, null).observe(owner, albums -> albumList.setValue(albums));
                break;
            case Constants.ALBUM_STARRED:
                albumList = albumRepository.getStarredAlbums(false, -1);
                break;
            /*
             * AlbumListPageFragment has always accepted an artist and set this
             * title, but nothing here ever answered it, so "all albums by this
             * artist" opened an empty page. Nothing linked to it either, which
             * is presumably why it went unnoticed; the artist page does now.
             */
            case Constants.ALBUM_FROM_ARTIST:
                if (artist != null) {
                    albumRepository.getArtistAlbums(artist.getId()).observe(owner, albums -> albumList.setValue(albums));
                }
                break;
            case Constants.ALBUM_NEW_RELEASES:
                int currentYear = Calendar.getInstance().get(Calendar.YEAR);
                albumRepository.getAlbums("byYear", maxNumber, currentYear, currentYear).observe(owner, albums -> {
                    if (albums == null) {
                        albumList.setValue(null);
                        return;
                    }

                    albums.sort(Comparator.comparing(AlbumID3::getCreated).reversed());
                    albumList.postValue(albums.subList(0, Math.min(20, albums.size())));
                });
                break;
            default:
                // Nothing to ask the server for: an answer, and an empty one.
                albumList.setValue(new ArrayList<>());
                break;
        }

        return albumList;
    }
}
