package com.cappielloantonio.tempo.viewmodel;

import android.app.Application;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import com.cappielloantonio.tempo.repository.ArtistRepository;
import com.cappielloantonio.tempo.repository.SongRepository;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.subsonic.models.ArtistID3;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.Genre;
import com.cappielloantonio.tempo.util.Constants;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SongListPageViewModel extends AndroidViewModel {
    private final SongRepository songRepository;
    private final ArtistRepository artistRepository;

    public String title;
    public String toolbarTitle;
    public Genre genre;
    public ArtistID3 artist;
    public AlbumID3 album;

    private MutableLiveData<List<Child>> songList;

    public ArrayList<String> filters = new ArrayList<>();
    public ArrayList<String> filterNames = new ArrayList<>();

    public int year = 0;
    public int maxNumberByYear = 500;
    public int maxNumberByGenre = 100;

    /*
     * Where a genre's pages have got to. A genre can stand for more than one
     * name on the server (Genre.sources): each is read to its end, a page at
     * a time, before the next.
     */
    private int genreSource;
    private int genrePage;
    private boolean genreLoading;

    public SongListPageViewModel(@NonNull Application application) {
        super(application);

        songRepository = new SongRepository();
        artistRepository = new ArtistRepository();
    }

    public LiveData<List<Child>> getSongList() {
        songList = new MutableLiveData<>(new ArrayList<>());

        switch (title) {
            case Constants.MEDIA_BY_GENRE:
                songList = new MutableLiveData<>();
                genreSource = 0;
                genrePage = 0;
                genreLoading = false;
                loadGenrePage();
                break;
            case Constants.MEDIA_BY_ARTIST:
                songList = artistRepository.getTopSongs(artist.getName(), 50);
                break;
            case Constants.MEDIA_BY_GENRES:
                songList = songRepository.getSongsByGenres(filters);
                break;
            case Constants.MEDIA_BY_YEAR:
                songList = songRepository.getRandomSample(maxNumberByYear, year, year + 10);
                break;
        }

        return songList;
    }

    public void getSongsByPage(LifecycleOwner owner) {
        switch (title) {
            case Constants.MEDIA_BY_GENRE:
                if (!genreLoading && genreSource < genre.names().size()) loadGenrePage();
                break;
            case Constants.MEDIA_BY_ARTIST:
            case Constants.MEDIA_BY_GENRES:
            case Constants.MEDIA_BY_YEAR:
                break;
        }
    }

    private void loadGenrePage() {
        genreLoading = true;

        LiveData<List<Child>> page = songRepository.getSongsByGenre(genre.names().get(genreSource), genrePage);
        page.observeForever(new Observer<List<Child>>() {
            @Override
            public void onChanged(List<Child> children) {
                page.removeObserver(this);
                onGenrePage(children != null ? children : new ArrayList<>());
            }
        });
    }

    private void onGenrePage(List<Child> children) {
        genreLoading = false;

        List<Child> songs = songList.getValue() != null ? songList.getValue() : new ArrayList<>();

        // A track tagged with two of the names comes once.
        Set<String> ids = new HashSet<>();
        for (Child song : songs) ids.add(song.getId());
        for (Child child : children) if (ids.add(child.getId())) songs.add(child);

        genrePage++;
        boolean sourceDone = children.size() < maxNumberByGenre;
        if (sourceDone) {
            genreSource++;
            genrePage = 0;
        }

        songList.setValue(songs);

        // Nothing more of this name to scroll for, which may leave nothing to
        // scroll at all: the next name's tracks come straight on.
        if (sourceDone && genreSource < genre.names().size()) loadGenrePage();
    }

    public String getFiltersTitle() {
        return TextUtils.join(", ", filterNames);
    }
}
