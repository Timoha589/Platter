package com.cappielloantonio.tempo.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import com.cappielloantonio.tempo.repository.GenreRepository;
import com.cappielloantonio.tempo.subsonic.models.Genre;

import java.util.ArrayList;
import java.util.List;

public class FilterViewModel extends AndroidViewModel {
    private final GenreRepository genreRepository;

    private final ArrayList<String> selectedFiltersID = new ArrayList<>();
    private final ArrayList<String> selectedFilters = new ArrayList<>();

    public FilterViewModel(@NonNull Application application) {
        super(application);

        genreRepository = new GenreRepository();
    }

    public LiveData<List<Genre>> getGenreList() {
        return genreRepository.getGenres(false, -1);
    }

    /* One chip can stand for several server names (Genre.sources): all of them are asked for. */
    public void addFilter(List<String> filterIDs, String filterName) {
        selectedFiltersID.addAll(filterIDs);
        selectedFilters.add(filterName);
    }

    public void removeFilter(List<String> filterIDs, String filterName) {
        selectedFiltersID.removeAll(filterIDs);
        selectedFilters.remove(filterName);
    }

    public ArrayList<String> getFilters() {
        return selectedFiltersID;
    }

    public ArrayList<String> getFilterNames() {
        return selectedFilters;
    }
}
