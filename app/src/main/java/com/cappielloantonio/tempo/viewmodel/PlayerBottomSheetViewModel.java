package com.cappielloantonio.tempo.viewmodel;

import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.interfaces.StarCallback;
import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.model.Queue;
import com.cappielloantonio.tempo.repository.AlbumRepository;
import com.cappielloantonio.tempo.repository.ArtistRepository;
import com.cappielloantonio.tempo.repository.FavoriteRepository;
import com.cappielloantonio.tempo.repository.LyricsRepository;
import com.cappielloantonio.tempo.repository.QueueRepository;
import com.cappielloantonio.tempo.repository.RatingRepository;
import com.cappielloantonio.tempo.repository.SongRepository;
import com.cappielloantonio.tempo.service.LikedTracksCache;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.subsonic.models.ArtistID3;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.LyricsList;
import com.cappielloantonio.tempo.subsonic.models.PlayQueue;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.DownloadUtil;
import com.cappielloantonio.tempo.util.FavoriteState;
import com.cappielloantonio.tempo.util.MappingUtil;
import com.cappielloantonio.tempo.util.NetworkUtil;
import com.cappielloantonio.tempo.wave.WaveState;
import com.cappielloantonio.tempo.util.OpenSubsonicExtensionsUtil;
import com.cappielloantonio.tempo.util.Preferences;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@OptIn(markerClass = UnstableApi.class)
public class PlayerBottomSheetViewModel extends AndroidViewModel {
    private static final String TAG = "PlayerBottomSheetViewModel";

    private final SongRepository songRepository;
    private final AlbumRepository albumRepository;
    private final ArtistRepository artistRepository;
    private final QueueRepository queueRepository;
    private final FavoriteRepository favoriteRepository;
    private final RatingRepository ratingRepository;
    private final LyricsRepository lyricsRepository;
    private final MutableLiveData<String> descriptionLiveData = new MutableLiveData<>(null);

    /* Everything the player shows about the current track. See TrackDetail. */
    private final TrackDetail<Child> liveMedia = new TrackDetail<>();
    private final TrackDetail<AlbumID3> liveAlbum = new TrackDetail<>();
    private final TrackDetail<ArtistID3> liveArtist = new TrackDetail<>();
    private final TrackDetail<String> liveLyrics = new TrackDetail<>();
    private final TrackDetail<LyricsList> liveLyricsList = new TrackDetail<>();



    public PlayerBottomSheetViewModel(@NonNull Application application) {
        super(application);

        songRepository = new SongRepository();
        albumRepository = new AlbumRepository();
        artistRepository = new ArtistRepository();
        queueRepository = new QueueRepository();
        favoriteRepository = new FavoriteRepository();
        ratingRepository = new RatingRepository();
        lyricsRepository = new LyricsRepository();
    }

    @Override
    protected void onCleared() {
        super.onCleared();

        liveMedia.release();
        liveAlbum.release();
        liveArtist.release();
        liveLyrics.release();
        liveLyricsList.release();
    }

    /**
     * One field of the player's "current track" panel, and the single server
     * request it is waiting on.
     * <p>
     * Each of these used to be filled by calling
     * {@code repository.getX(id).observe(owner, field::postValue)} afresh on
     * every track change: a new request, and a new observer that was never taken
     * off again. Swiping through a queue left dozens of requests in flight and
     * dozens of live observers answering out of order - which is what made fast
     * skipping crawl, and could leave the favourite button showing a track from
     * three swipes ago.
     * <p>
     * The wait is a forever observer rather than a MediatorLiveData source, and
     * that distinction matters: a Mediator only listens to its sources while
     * something is listening to it, and the panels these feed are built lazily -
     * the lyrics page does not exist until the first time it is opened. Answers
     * that arrived before anyone was looking were dropped on the floor, which is
     * how every track came to report that it had no words. Waiting regardless of
     * who is reading, and dropping the previous wait as the next one starts,
     * keeps exactly one answer live per field without tying delivery to the
     * lifecycle of a page that may not exist yet.
     */
    private static class TrackDetail<T> {
        private final MutableLiveData<T> value = new MutableLiveData<>(null);
        private final Observer<T> observer = value::setValue;

        @Nullable
        private LiveData<T> awaiting;

        /**
         * What was last asked for. A track change that leaves this pointing at
         * the same record - the same album while a queue plays through it, the
         * same artist over several tracks - is not worth another round trip.
         */
        @Nullable
        private String requestedFor;

        LiveData<T> live() {
            return value;
        }

        boolean isRequestedFor(@Nullable String id) {
            return id != null && id.equals(requestedFor);
        }

        void await(String id, LiveData<T> request) {
            await(id, null, request);
        }

        /**
         * @param meanwhile shown from now until the request answers, in place of
         *                  whatever the previous id left behind
         */
        void await(String id, @Nullable T meanwhile, LiveData<T> request) {
            release();

            requestedFor = id;
            awaiting = request;

            if (meanwhile != null) value.setValue(meanwhile);

            request.observeForever(observer);
        }

        void clear() {
            release();

            requestedFor = null;
            value.setValue(null);
        }

        void release() {
            if (awaiting == null) return;

            awaiting.removeObserver(observer);
            awaiting = null;
        }
    }

    public LiveData<List<Queue>> getQueueSong() {
        return queueRepository.getLiveQueue();
    }

    public void setFavorite(Context context, Child media) {
        if (media != null) {
            if (media.getStarred() != null) {
                if (NetworkUtil.isOffline()) {
                    removeFavoriteOffline(media);
                } else {
                    removeFavoriteOnline(media);
                }

                LikedTracksCache.onUnliked(context, media.getId());
            } else {
                // A track cannot be liked and disliked at once.
                if (isDisliked(media)) clearRating(media);

                if (NetworkUtil.isOffline()) {
                    setFavoriteOffline(media);
                } else {
                    setFavoriteOnline(context, media);
                }
            }

            // The notification shows the same state from its own copy of the track.
            FavoriteState.set(media.getId(), media.getStarred() != null);
        }
    }

    /**
     * Subsonic servers have no dislike, so the lowest star rating stands in for
     * one. Toggling a dislike on drops the star, mirroring what setFavorite()
     * does to a rating, so the two states stay mutually exclusive.
     */
    public void setDislike(Child media) {
        if (media == null) return;

        if (isDisliked(media)) {
            clearRating(media);
        } else {
            ratingRepository.rate(media.getId(), Constants.MEDIA_RATING_DISLIKE);
            media.setUserRating(Constants.MEDIA_RATING_DISLIKE);
            // On a wave track this steers the wave away from it straight away,
            // not only once the server rereads the ratings.
            WaveState.onDisliked(media.getId());

            if (media.getStarred() != null) {
                if (NetworkUtil.isOffline()) {
                    removeFavoriteOffline(media);
                } else {
                    removeFavoriteOnline(media);
                }

                FavoriteState.set(media.getId(), false);
                LikedTracksCache.onUnliked(getApplication(), media.getId());
            }
        }
    }

    public static boolean isDisliked(Child media) {
        return media != null
                && media.getUserRating() != null
                && media.getUserRating() == Constants.MEDIA_RATING_DISLIKE;
    }

    private void clearRating(Child media) {
        ratingRepository.rate(media.getId(), Constants.MEDIA_RATING_NONE);
        media.setUserRating(Constants.MEDIA_RATING_NONE);
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

    public LiveData<String> getLiveLyrics() {
        return liveLyrics.live();
    }

    public LiveData<LyricsList> getLiveLyricsList() {
        return liveLyricsList.live();
    }

    public void refreshMediaInfo(Child media) {
        if (media == null || media.getId() == null) return;

        if (OpenSubsonicExtensionsUtil.isSongLyricsExtensionAvailable()) {
            if (liveLyricsList.isRequestedFor(media.getId())) return;

            liveLyrics.clear();
            liveLyricsList.await(media.getId(), lyricsRepository.getLyricsList(media));
        } else {
            if (liveLyrics.isRequestedFor(media.getId())) return;

            liveLyricsList.clear();
            liveLyrics.await(media.getId(), lyricsRepository.getLyrics(media));
        }
    }

    public LiveData<Child> getLiveMedia() {
        return liveMedia.live();
    }

    /**
     * @param queued the track as the queue has it, built from its MediaItem -
     *               shown until the server's copy arrives
     */
    public void setLiveMedia(String mediaType, String mediaId, @Nullable Child queued) {
        if (mediaType == null) return;

        switch (mediaType) {
            case Constants.MEDIA_TYPE_MUSIC:
                descriptionLiveData.setValue(null);

                if (mediaId == null || liveMedia.isRequestedFor(mediaId)) return;

                /*
                 * The player used to go on showing the previous track until
                 * getSong came back: its like, its dislike, and buttons that
                 * still acted on it - a like tapped in that window went to the
                 * track that had just finished. The queued copy is on hand the
                 * moment the track changes and carries everything those
                 * buttons read; the server's copy then corrects it, and the
                 * like in particular is laid over from FavoriteState.
                 */
                if (queued != null) FavoriteState.apply(queued);
                liveMedia.await(mediaId, queued, songRepository.getSongOrSaved(mediaId));
                break;
            case Constants.MEDIA_TYPE_PODCAST:
                liveMedia.clear();
                break;
        }
    }

    public LiveData<AlbumID3> getLiveAlbum() {
        return liveAlbum.live();
    }

    public void setLiveAlbum(String mediaType, String albumId) {
        if (mediaType == null) return;

        switch (mediaType) {
            case Constants.MEDIA_TYPE_MUSIC:
                if (liveAlbum.isRequestedFor(albumId)) return;

                /*
                 * The previous album has to go the moment the track changes:
                 * the title opens whatever is held here, and until getAlbum
                 * answers - or for good, on a track with no album - a tap
                 * would open the album of the track that just finished.
                 */
                liveAlbum.clear();
                if (albumId != null) liveAlbum.await(albumId, albumRepository.getAlbum(albumId));
                break;
            case Constants.MEDIA_TYPE_PODCAST:
                liveAlbum.clear();
                break;
        }
    }

    public LiveData<ArtistID3> getLiveArtist() {
        return liveArtist.live();
    }

    /**
     * @param artistName the name the queue carries - with the id, all the
     *                   artist page needs, so the name opens the right page
     *                   before getArtist answers, or if it never does
     */
    public void setLiveArtist(String mediaType, String artistId, @Nullable String artistName) {
        if (mediaType == null) return;

        switch (mediaType) {
            case Constants.MEDIA_TYPE_MUSIC:
                if (liveArtist.isRequestedFor(artistId)) return;

                /*
                 * The artist name opens whatever is held here. Left alone
                 * until getArtist answered - or for good, on a track with no
                 * artist id - it went on holding the previous track's artist,
                 * and a tap on the new name opened the old one's page.
                 */
                if (artistId == null) {
                    liveArtist.clear();
                    return;
                }

                ArtistID3 queued = new ArtistID3();
                queued.setId(artistId);
                queued.setName(artistName);

                liveArtist.await(artistId, queued, artistRepository.getArtist(artistId));
                break;
            case Constants.MEDIA_TYPE_PODCAST:
                liveArtist.clear();
                break;
        }
    }

    public void setLiveDescription(String description) {
        descriptionLiveData.setValue(description);
    }

    public LiveData<String> getLiveDescription() {
        return descriptionLiveData;
    }

    /*
     * A LiveData of its own for every request. This used to be one field shared
     * by all of them, primed with an empty list and observed afresh on every
     * tap: the empty list was enqueued as if it were the mix, and each earlier
     * observer was still attached when the next answer came - the second tap
     * queued the mix twice, the third three times.
     */
    public LiveData<List<Child>> getMediaInstantMix(Child media) {
        return songRepository.getInstantMix(media.getId(), 20);
    }

    public LiveData<PlayQueue> getPlayQueue() {
        return queueRepository.getPlayQueue();
    }

    /**
     * Bookmarks the queue on the server.
     * <p>
     * The playing track used to be identified by id alone and the position was
     * hard-coded to zero, so a restored queue always started the track over,
     * and a queue holding the same track twice resumed at the first copy. Both
     * are now taken from the player.
     *
     * @param currentIndex index of the playing track in the queue
     * @param positionMs   playback position within it
     */
    public boolean savePlayQueue(int currentIndex, long positionMs) {
        List<Child> queue = queueRepository.getMedia();

        if (queue.isEmpty()) return false;

        List<String> ids = queue.stream().map(Child::getId).collect(Collectors.toList());

        int index = (currentIndex >= 0 && currentIndex < queue.size()) ? currentIndex : 0;

        queueRepository.savePlayQueue(ids, index, queue.get(index).getId(), Math.max(positionMs, 0));

        return true;
    }
}
