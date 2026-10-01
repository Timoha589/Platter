package com.cappielloantonio.tempo.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.cappielloantonio.tempo.repository.PlaylistRepository;
import com.cappielloantonio.tempo.subsonic.models.Playlist;

import java.util.List;

public class PlaylistCatalogueViewModel extends AndroidViewModel {
    private final PlaylistRepository playlistRepository;

    private String type;

    /*
     * No value until the server answers - a null here is a failed request. It
     * used to start out null, so the page could not tell a list on its way
     * from one that had failed.
     */
    private final MutableLiveData<List<Playlist>> playlistList = new MutableLiveData<>();

    public PlaylistCatalogueViewModel(@NonNull Application application) {
        super(application);

        playlistRepository = new PlaylistRepository();
    }

    public LiveData<List<Playlist>> getPlaylistList(LifecycleOwner owner) {
        if (playlistList.getValue() == null) {
            playlistRepository.getPlaylists(false, -1).observe(owner, playlistList::postValue);
        }

        return playlistList;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }
}
