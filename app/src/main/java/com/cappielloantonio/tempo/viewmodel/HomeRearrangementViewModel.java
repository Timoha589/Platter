package com.cappielloantonio.tempo.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;

import com.cappielloantonio.tempo.model.HomeSector;
import com.cappielloantonio.tempo.util.HomeSectors;
import com.cappielloantonio.tempo.util.Preferences;

import java.util.ArrayList;
import java.util.List;

public class HomeRearrangementViewModel extends AndroidViewModel {
    private List<HomeSector> sectors = new ArrayList<>();

    public HomeRearrangementViewModel(@NonNull Application application) {
        super(application);
    }

    public List<HomeSector> getHomeSectorList() {
        if (sectors == null || sectors.isEmpty()) sectors = HomeSectors.load(getApplication());

        return sectors;
    }

    public void orderSectorLiveListAfterSwap(List<HomeSector> sectors) {
        this.sectors = sectors;
    }

    public void saveHomeSectorList(List<HomeSector> sectors) {
        Preferences.setHomeSectorList(sectors);
    }

    public void resetHomeSectorList() {
        Preferences.setHomeSectorList(null);
    }

    public void closeDialog() {
        sectors = null;
    }
}
