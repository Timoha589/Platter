package com.cappielloantonio.tempo.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.cappielloantonio.tempo.interfaces.RatingCallback;
import com.cappielloantonio.tempo.interfaces.StarCallback;
import com.cappielloantonio.tempo.model.Chronology;
import com.cappielloantonio.tempo.model.Favorite;
import com.cappielloantonio.tempo.model.HomeSector;
import com.cappielloantonio.tempo.model.Rating;
import com.cappielloantonio.tempo.repository.AlbumRepository;
import com.cappielloantonio.tempo.repository.ArtistRepository;
import com.cappielloantonio.tempo.repository.ChronologyRepository;
import com.cappielloantonio.tempo.repository.FavoriteRepository;
import com.cappielloantonio.tempo.repository.PlaylistRepository;
import com.cappielloantonio.tempo.repository.RatingRepository;
import com.cappielloantonio.tempo.repository.SharingRepository;
import com.cappielloantonio.tempo.repository.SongRepository;
import com.cappielloantonio.tempo.repository.StarredRepository;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.subsonic.models.ArtistID3;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.Playlist;
import com.cappielloantonio.tempo.subsonic.models.Share;
import com.cappielloantonio.tempo.util.HomeSectors;
import com.cappielloantonio.tempo.util.Preferences;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class HomeViewModel extends AndroidViewModel {
    private static final String TAG = "HomeViewModel";

    private final SongRepository songRepository;
    private final AlbumRepository albumRepository;
    private final ArtistRepository artistRepository;
    private final ChronologyRepository chronologyRepository;
    private final FavoriteRepository favoriteRepository;
    private final RatingRepository ratingRepository;
    private final PlaylistRepository playlistRepository;
    private final SharingRepository sharingRepository;

    /*
     * Unset, not "set to null".
     *
     * new MutableLiveData<>(null) is a LiveData that already holds a value, and
     * that value is null: it bumps the version past START_VERSION, so every
     * observer that attaches is dispatched null straight away. Home read that
     * as "the server has nothing" and hid the section before its request had
     * even gone out, which is why sections flickered in rather than filling.
     *
     * With no initial value the three states are distinct, and the loading
     * placeholders can rely on them: no value means the request is still out,
     * null means it failed, a list - empty or not - is the server's answer.
     */
    private final MutableLiveData<List<Child>> dicoverSongSample = new MutableLiveData<>();
    private final MutableLiveData<List<AlbumID3>> newReleasedAlbum = new MutableLiveData<>();
    private final MutableLiveData<List<Child>> starredTracksSample = new MutableLiveData<>();
    private final MutableLiveData<List<Child>> starredTracks = new MutableLiveData<>();
    private final MutableLiveData<List<AlbumID3>> starredAlbums = new MutableLiveData<>();
    private final MutableLiveData<List<ArtistID3>> starredArtists = new MutableLiveData<>();
    private final MutableLiveData<List<AlbumID3>> mostPlayedAlbumSample = new MutableLiveData<>();
    private final MutableLiveData<List<AlbumID3>> recentlyPlayedAlbumSample = new MutableLiveData<>();
    private final MutableLiveData<List<Integer>> years = new MutableLiveData<>();
    private final MutableLiveData<List<AlbumID3>> recentlyAddedAlbumSample = new MutableLiveData<>();

    public static final int TOP_SONGS_LAST_WEEK = 0;
    public static final int TOP_SONGS_LAST_MONTH = 1;
    public static final int TOP_SONGS_LAST_YEAR = 2;

    private final MutableLiveData<Integer> topSongsPeriod = new MutableLiveData<>(TOP_SONGS_LAST_WEEK);
    private boolean topSongsPeriodChosen = false;
    private final LiveData<List<Chronology>> topSongs;
    private final MutableLiveData<List<Playlist>> pinnedPlaylists = new MutableLiveData<>();
    private final MutableLiveData<List<Share>> shares = new MutableLiveData<>();

    /* Last seen halves of the pinned-playlist intersection, see getPinnedPlaylists */
    private List<Playlist> remotePlaylists;
    private List<Playlist> localPinnedPlaylists;

    private List<HomeSector> sectors;

    public HomeViewModel(@NonNull Application application) {
        super(application);

        songRepository = new SongRepository();
        albumRepository = new AlbumRepository();
        artistRepository = new ArtistRepository();
        chronologyRepository = new ChronologyRepository();
        topSongs = Transformations.switchMap(topSongsPeriod, period ->
                chronologyRepository.getTopSongs(Preferences.getServerId(), topSongsSince(period)));
        favoriteRepository = new FavoriteRepository();
        ratingRepository = new RatingRepository();
        playlistRepository = new PlaylistRepository();
        sharingRepository = new SharingRepository();

        setOfflineFavorite();
        setOfflineRating();
    }

    public LiveData<List<Child>> getDiscoverSongSample(LifecycleOwner owner) {
        if (dicoverSongSample.getValue() == null) {
            songRepository.getRandomSample(10, null, null).observe(owner, dicoverSongSample::postValue);
        }

        return dicoverSongSample;
    }

    public LiveData<List<Child>> getRandomShuffleSample() {
        return songRepository.getRandomSample(100, null, null);
    }

    /*
     * "Your top tracks". The period is the source and the list is switched onto
     * a fresh query whenever it changes, so the old period's query stops
     * feeding the list. Before, every choice added one more observer on top of
     * the last week's, and the next play - which re-runs every Room query on
     * the table - put last week's list straight back.
     */
    public LiveData<List<Chronology>> getTopSongs() {
        return topSongs;
    }

    public LiveData<Integer> getTopSongsPeriod() {
        return topSongsPeriod;
    }

    public boolean isTopSongsPeriodChosen() {
        return topSongsPeriodChosen;
    }

    public void chooseTopSongsPeriod(int period) {
        topSongsPeriodChosen = true;
        topSongsPeriod.setValue(period);
    }

    /*
     * Nothing played in the default week: look a period further back instead
     * of hiding the section, and with it the only way to pick a period. Never
     * overrides a period the user chose. False once there is nowhere further.
     */
    public boolean widenTopSongsPeriod() {
        Integer period = topSongsPeriod.getValue();
        if (topSongsPeriodChosen || period == null || period >= TOP_SONGS_LAST_YEAR) return false;

        topSongsPeriod.setValue(period + 1);
        return true;
    }

    private static long topSongsSince(int period) {
        int days = period == TOP_SONGS_LAST_YEAR ? 365 : period == TOP_SONGS_LAST_MONTH ? 30 : 7;
        return System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
    }

    public LiveData<List<AlbumID3>> getRecentlyReleasedAlbums(LifecycleOwner owner) {
        if (newReleasedAlbum.getValue() == null) {
            int currentYear = Calendar.getInstance().get(Calendar.YEAR);

            /*
             * Asking byYear for the current year alone leaves the rail empty
             * every January, and permanently empty on a library whose newest
             * tags stop a year short - which is most libraries for most of the
             * year. The window covers the previous year too; the list is cut to
             * twenty after sorting, so the rail is the same length either way.
             */
            albumRepository.getAlbums("byYear", 500, currentYear, currentYear - 1).observe(owner, albums -> {
                if (albums == null) {
                    /* The request failed. Say so, rather than leaving the
                       section waiting on a value that is not coming. */
                    newReleasedAlbum.postValue(null);
                    return;
                }

                /*
                 * Comparator.comparing throws on a null key, and an album the
                 * server has no created date for is common enough. This ran in
                 * an observer on the main thread, so one untagged album took
                 * the app down on the way into home.
                 */
                Comparator<Date> newestFirst = Comparator.nullsLast(Comparator.<Date>reverseOrder());

                List<AlbumID3> sorted = new ArrayList<>(albums);
                sorted.sort(Comparator.comparing(AlbumID3::getCreated, newestFirst));

                newReleasedAlbum.postValue(new ArrayList<>(sorted.subList(0, Math.min(20, sorted.size()))));
            });
        }

        return newReleasedAlbum;
    }

    public LiveData<List<Child>> getStarredTracksSample(LifecycleOwner owner) {
        if (starredTracksSample.getValue() == null) {
            songRepository.getStarredSongs(true, 10).observe(owner, starredTracksSample::postValue);
        }

        return starredTracksSample;
    }

    public LiveData<List<Child>> getStarredTracks(LifecycleOwner owner) {
        if (starredTracks.getValue() == null) {
            songRepository.getStarredSongs(true, 20).observe(owner, starredTracks::postValue);
        }

        return starredTracks;
    }

    public LiveData<List<AlbumID3>> getStarredAlbums(LifecycleOwner owner) {
        if (starredAlbums.getValue() == null) {
            albumRepository.getStarredAlbums(true, 20).observe(owner, starredAlbums::postValue);
        }

        return starredAlbums;
    }

    public LiveData<List<ArtistID3>> getStarredArtists(LifecycleOwner owner) {
        if (starredArtists.getValue() == null) {
            artistRepository.getStarredArtists(true, 20).observe(owner, starredArtists::postValue);
        }

        return starredArtists;
    }

    public LiveData<List<Integer>> getYearList(LifecycleOwner owner) {
        if (years.getValue() == null) {
            albumRepository.getDecades().observe(owner, years::postValue);
        }

        return years;
    }

    public LiveData<List<AlbumID3>> getMostPlayedAlbums(LifecycleOwner owner) {
        if (mostPlayedAlbumSample.getValue() == null) {
            albumRepository.getAlbums("frequent", 20, null, null).observe(owner, mostPlayedAlbumSample::postValue);
        }

        return mostPlayedAlbumSample;
    }

    public LiveData<List<AlbumID3>> getMostRecentlyAddedAlbums(LifecycleOwner owner) {
        if (recentlyAddedAlbumSample.getValue() == null) {
            albumRepository.getAlbums("newest", 20, null, null).observe(owner, recentlyAddedAlbumSample::postValue);
        }

        return recentlyAddedAlbumSample;
    }

    public LiveData<List<AlbumID3>> getRecentlyPlayedAlbumList(LifecycleOwner owner) {
        if (recentlyPlayedAlbumSample.getValue() == null) {
            albumRepository.getAlbums("recent", 20, null, null).observe(owner, recentlyPlayedAlbumSample::postValue);
        }

        return recentlyPlayedAlbumSample;
    }

    /*
     * This used to hand back one shared field, primed with an empty list and
     * observed again on every tap, so a second tap on any mix card was
     * answered by every observer the earlier taps had left behind. The
     * repository's LiveData is already one request's answer; it is returned
     * as it is.
     */
    public LiveData<List<Child>> getMediaInstantMix(Child media) {
        return songRepository.getInstantMix(media.getId(), 20);
    }

    /*
     * The pinned set is the intersection of what the server has and what the
     * local pin table names.
     *
     * The two used to be read as nested observers, which registered a fresh
     * observer on the Room table for every emission of the remote list and,
     * more to the point, delivered nothing at all when either side came back
     * null - so a failed getPlaylists left the section waiting forever. They
     * are now observed side by side, and either one arriving recomputes the
     * intersection.
     */
    public LiveData<List<Playlist>> getPinnedPlaylists(LifecycleOwner owner) {
        remotePlaylists = null;
        localPinnedPlaylists = null;

        playlistRepository.getPlaylists(false, -1).observe(owner, remotes -> {
            remotePlaylists = remotes;
            mergePinnedPlaylists(remotes == null);
        });

        playlistRepository.getPinnedPlaylists().observe(owner, locals -> {
            localPinnedPlaylists = locals;
            mergePinnedPlaylists(false);
        });

        return pinnedPlaylists;
    }

    private void mergePinnedPlaylists(boolean remoteFailed) {
        if (remoteFailed) {
            pinnedPlaylists.setValue(null);
            return;
        }

        /* Still waiting on the server's list - the pin table alone says nothing. */
        if (remotePlaylists == null) return;

        List<Playlist> locals = localPinnedPlaylists;

        if (locals == null) {
            pinnedPlaylists.setValue(Collections.emptyList());
            return;
        }

        Set<String> pinned = new HashSet<>();
        for (Playlist local : locals) {
            Playlist remote = findPinned(local);
            if (remote == null) continue;
            pinned.add(remote.getId());

            /*
             * Found by name: move the pin onto the playlist it found, so the
             * playlist's own page knows it is pinned and can unpin it. The
             * table changes, and this runs again, now matching by id.
             */
            if (!remote.getId().equals(local.getId())) {
                playlistRepository.delete(local);
                playlistRepository.insert(remote);
            }
        }

        pinnedPlaylists.setValue(remotePlaylists.stream()
                .filter(remote -> pinned.contains(remote.getId()))
                .collect(Collectors.toList()));
    }

    /*
     * A pin names a playlist by id, but a generated playlist - daylist - is
     * sometimes replaced by a new one under the same name rather than
     * updated, and the pin was left pointing at nothing. When the id is gone,
     * the pin follows the name to the most recently changed playlist that has
     * it - among the listener's own. Every user of a server has a daylist,
     * and an administrator is sent all of them: following the name alone
     * pinned whichever stranger's daylist was refreshed last, which the
     * listener had never pinned and could not unpin.
     */
    private Playlist findPinned(Playlist local) {
        for (Playlist remote : remotePlaylists) {
            if (remote.getId().equals(local.getId())) return remote;
        }

        String name = baseName(local.getName());
        if (name == null) return null;
        String me = Preferences.getUser();

        return remotePlaylists.stream()
                .filter(remote -> remote.getOwner() == null || remote.getOwner().equals(me))
                .filter(remote -> name.equals(baseName(remote.getName())))
                .max(Comparator.comparing(Playlist::getChanged, Comparator.nullsFirst(Comparator.<Date>naturalOrder())))
                .orElse(null);
    }

    /* The daylist can carry the hour's mood after its name: "daylist · chill morning". */
    private static String baseName(String name) {
        if (name == null) return null;
        int mood = name.indexOf(" · ");
        return (mood >= 0 ? name.substring(0, mood) : name).trim().toLowerCase(Locale.ROOT);
    }

    public LiveData<List<Share>> getShares(LifecycleOwner owner) {
        if (shares.getValue() == null) {
            sharingRepository.getShares().observe(owner, shares::postValue);
        }

        return shares;
    }

    public void refreshDiscoverySongSample(LifecycleOwner owner) {
        songRepository.getRandomSample(10, null, null).observe(owner, dicoverSongSample::postValue);
    }

    /*
     * Asking for a section again means asking the server again, so these drop
     * the shared getStarred2 answer first - otherwise a refresh inside its
     * short lifetime would re-shuffle the same cached copy.
     */
    public void refreshSimilarSongSample(LifecycleOwner owner) {
        StarredRepository.invalidate();
        songRepository.getStarredSongs(true, 10).observe(owner, starredTracksSample::postValue);
    }

    public void refreshStarredTracks(LifecycleOwner owner) {
        StarredRepository.invalidate();
        songRepository.getStarredSongs(true, 20).observe(owner, starredTracks::postValue);
    }

    public void refreshStarredAlbums(LifecycleOwner owner) {
        StarredRepository.invalidate();
        albumRepository.getStarredAlbums(true, 20).observe(owner, starredAlbums::postValue);
    }

    public void refreshStarredArtists(LifecycleOwner owner) {
        StarredRepository.invalidate();
        artistRepository.getStarredArtists(true, 20).observe(owner, starredArtists::postValue);
    }

    public void refreshMostPlayedAlbums(LifecycleOwner owner) {
        albumRepository.getAlbums("frequent", 20, null, null).observe(owner, mostPlayedAlbumSample::postValue);
    }

    public void refreshMostRecentlyAddedAlbums(LifecycleOwner owner) {
        albumRepository.getAlbums("newest", 20, null, null).observe(owner, recentlyAddedAlbumSample::postValue);
    }

    public void refreshRecentlyPlayedAlbumList(LifecycleOwner owner) {
        albumRepository.getAlbums("recent", 20, null, null).observe(owner, recentlyPlayedAlbumSample::postValue);
    }

    public void refreshShares(LifecycleOwner owner) {
        sharingRepository.getShares().observe(owner, this.shares::postValue);
    }

    /*
     * Read again each time home is built, so an order saved in the rearrange
     * dialog shows the next time home does, not the next time the app starts.
     */
    public List<HomeSector> loadHomeSectorList() {
        sectors = HomeSectors.load(getApplication());
        return sectors;
    }

    public List<HomeSector> getHomeSectorList() {
        return sectors;
    }

    /* A hidden section is not loaded at all. */
    public boolean isHomeSectorHidden(String sectorId) {
        return sectors != null && sectors.stream()
                .noneMatch(sector -> sector.getId().equals(sectorId) && sector.isVisible());
    }

    public void setOfflineFavorite() {
        ArrayList<Favorite> favorites = getFavorites();
        ArrayList<Favorite> favoritesToSave = getFavoritesToSave(favorites);
        ArrayList<Favorite> favoritesToDelete = getFavoritesToDelete(favorites, favoritesToSave);

        manageFavoriteToSave(favoritesToSave);
        manageFavoriteToDelete(favoritesToDelete);
    }

    public void setOfflineRating() {
        ArrayList<Rating> ratings = getRatings();
        ArrayList<Rating> ratingsToSave = getRatingsToSave(ratings);
        ArrayList<Rating> ratingsToDelete = getRatingsToDelete(ratings, ratingsToSave);

        manageRatingToSave(ratingsToSave);
        manageRatingToDelete(ratingsToDelete);
    }

    private ArrayList<Rating> getRatings() {
        return new ArrayList<>(ratingRepository.getRatings());
    }

    private ArrayList<Rating> getRatingsToSave(ArrayList<Rating> ratings) {
        HashMap<String, Rating> filteredMap = new HashMap<>();

        for (Rating rating : ratings) {
            String key = rating.toString();

            if (!filteredMap.containsKey(key) || rating.getTimestamp() > filteredMap.get(key).getTimestamp()) {
                filteredMap.put(key, rating);
            }
        }

        return new ArrayList<>(filteredMap.values());
    }

    private ArrayList<Rating> getRatingsToDelete(ArrayList<Rating> ratings, ArrayList<Rating> ratingsToSave) {
        ArrayList<Rating> ratingsToDelete = new ArrayList<>();

        for (Rating rating : ratings) {
            if (!ratingsToSave.contains(rating)) {
                ratingsToDelete.add(rating);
            }
        }

        return ratingsToDelete;
    }

    private void manageRatingToSave(ArrayList<Rating> ratingsToSave) {
        for (Rating rating : ratingsToSave) {
            ratingRepository.rate(rating.getItemId(), rating.getRating(), new RatingCallback() {
                @Override
                public void onSuccess() {
                    ratingRepository.delete(rating);
                }
            });
        }
    }

    private void manageRatingToDelete(ArrayList<Rating> ratingsToDelete) {
        for (Rating rating : ratingsToDelete) {
            ratingRepository.delete(rating);
        }
    }

    private ArrayList<Favorite> getFavorites() {
        return new ArrayList<>(favoriteRepository.getFavorites());
    }

    private ArrayList<Favorite> getFavoritesToSave(ArrayList<Favorite> favorites) {
        HashMap<String, Favorite> filteredMap = new HashMap<>();

        for (Favorite favorite : favorites) {
            String key = favorite.toString();

            if (!filteredMap.containsKey(key) || favorite.getTimestamp() > filteredMap.get(key).getTimestamp()) {
                filteredMap.put(key, favorite);
            }
        }

        return new ArrayList<>(filteredMap.values());
    }

    private ArrayList<Favorite> getFavoritesToDelete(ArrayList<Favorite> favorites, ArrayList<Favorite> favoritesToSave) {
        ArrayList<Favorite> favoritesToDelete = new ArrayList<>();

        for (Favorite favorite : favorites) {
            if (!favoritesToSave.contains(favorite)) {
                favoritesToDelete.add(favorite);
            }
        }

        return favoritesToDelete;
    }

    private void manageFavoriteToSave(ArrayList<Favorite> favoritesToSave) {
        for (Favorite favorite : favoritesToSave) {
            if (favorite.getToStar()) {
                favoriteToStar(favorite);
            } else {
                favoriteToUnstar(favorite);
            }
        }
    }

    private void manageFavoriteToDelete(ArrayList<Favorite> favoritesToDelete) {
        for (Favorite favorite : favoritesToDelete) {
            favoriteRepository.delete(favorite);
        }
    }

    private void favoriteToStar(Favorite favorite) {
        if (favorite.getSongId() != null) {
            favoriteRepository.star(favorite.getSongId(), null, null, new StarCallback() {
                @Override
                public void onSuccess() {
                    favoriteRepository.delete(favorite);
                }
            });
        } else if (favorite.getAlbumId() != null) {
            favoriteRepository.star(null, favorite.getAlbumId(), null, new StarCallback() {
                @Override
                public void onSuccess() {
                    favoriteRepository.delete(favorite);
                }
            });
        } else if (favorite.getArtistId() != null) {
            favoriteRepository.star(null, null, favorite.getArtistId(), new StarCallback() {
                @Override
                public void onSuccess() {
                    favoriteRepository.delete(favorite);
                }
            });
        }
    }

    private void favoriteToUnstar(Favorite favorite) {
        if (favorite.getSongId() != null) {
            favoriteRepository.unstar(favorite.getSongId(), null, null, new StarCallback() {
                @Override
                public void onSuccess() {
                    favoriteRepository.delete(favorite);
                }
            });
        } else if (favorite.getAlbumId() != null) {
            favoriteRepository.unstar(null, favorite.getAlbumId(), null, new StarCallback() {
                @Override
                public void onSuccess() {
                    favoriteRepository.delete(favorite);
                }
            });
        } else if (favorite.getArtistId() != null) {
            favoriteRepository.unstar(null, null, favorite.getArtistId(), new StarCallback() {
                @Override
                public void onSuccess() {
                    favoriteRepository.delete(favorite);
                }
            });
        }
    }
}
