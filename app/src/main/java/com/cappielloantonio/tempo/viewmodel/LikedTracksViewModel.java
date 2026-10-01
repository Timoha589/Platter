package com.cappielloantonio.tempo.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.cappielloantonio.tempo.repository.SongRepository;
import com.cappielloantonio.tempo.subsonic.models.Child;

import java.util.List;

public class LikedTracksViewModel extends AndroidViewModel {
    private final SongRepository songRepository;

    // No value until the answer; null is a failed request.
    private final MutableLiveData<List<Child>> likedTracks = new MutableLiveData<>();

    public LikedTracksViewModel(@NonNull Application application) {
        super(application);

        songRepository = new SongRepository();
    }

    /*
     * Asked afresh on every visit, since a like set anywhere else belongs on the
     * page - while the list from the last visit stands in until the answer comes.
     */
    public LiveData<List<Child>> getLikedTracks(LifecycleOwner owner) {
        songRepository.getStarredSongs(false, -1).observe(owner, likedTracks::postValue);
        return likedTracks;
    }
}
