package com.cappielloantonio.tempo.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.repository.DownloadRepository;

import java.util.List;

public class DownloadViewModel extends AndroidViewModel {
    private static final String TAG = "DownloadViewModel";

    private final DownloadRepository downloadRepository;

    /* Which Download.SOURCE_* the downloads screen is showing, or null for all of them. */
    @Nullable
    private Integer shownSource;

    public DownloadViewModel(@NonNull Application application) {
        super(application);

        downloadRepository = new DownloadRepository();
    }

    public LiveData<List<Download>> getDownloadedTracks() {
        return downloadRepository.getLiveDownloadNewestFirst();
    }

    @Nullable
    public Integer getShownSource() {
        return shownSource;
    }

    public void setShownSource(@Nullable Integer shownSource) {
        this.shownSource = shownSource;
    }
}
