package com.cappielloantonio.tempo.service;

import android.content.ComponentName;

import androidx.annotation.OptIn;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.interfaces.MediaIndexCallback;
import com.cappielloantonio.tempo.model.Chronology;
import com.cappielloantonio.tempo.repository.ChronologyRepository;
import com.cappielloantonio.tempo.repository.QueueRepository;
import com.cappielloantonio.tempo.repository.SongRepository;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.InternetRadioStation;
import com.cappielloantonio.tempo.subsonic.models.PodcastEpisode;
import com.cappielloantonio.tempo.subsonic.api.mediaannotation.MediaAnnotationClient;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.MappingUtil;
import com.cappielloantonio.tempo.util.OpenSubsonicExtensionsUtil;
import com.cappielloantonio.tempo.util.Preferences;
import com.cappielloantonio.tempo.wave.WaveState;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.List;
import java.util.concurrent.ExecutionException;

public class MediaManager {
    private static final String TAG = "MediaManager";

    public static void reset(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        if (mediaBrowserListenableFuture.get().isPlaying()) {
                            mediaBrowserListenableFuture.get().pause();
                        }

                        mediaBrowserListenableFuture.get().stop();
                        mediaBrowserListenableFuture.get().clearMediaItems();
                        clearDatabase();
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void hide(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        if (mediaBrowserListenableFuture.get().isPlaying()) {
                            mediaBrowserListenableFuture.get().pause();
                        }
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void check(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        if (mediaBrowserListenableFuture.get().getMediaItemCount() < 1) {
                            List<Child> media = getQueueRepository().getMedia();
                            if (media != null && media.size() >= 1) {
                                init(mediaBrowserListenableFuture, media);
                            }
                        }
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void init(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, List<Child> media) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        mediaBrowserListenableFuture.get().clearMediaItems();
                        mediaBrowserListenableFuture.get().setMediaItems(MappingUtil.mapMediaItems(media));
                        mediaBrowserListenableFuture.get().seekTo(getQueueRepository().getLastPlayedMediaIndex(), getQueueRepository().getLastPlayedMediaTimestamp());
                        mediaBrowserListenableFuture.get().prepare();
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void startQueue(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, List<Child> media, int startIndex) {
        startQueue(mediaBrowserListenableFuture, media, startIndex, 0);
    }

    /**
     * @param startPositionMs where to resume inside the starting track; a
     *                        restored server queue carries one, everything else
     *                        passes zero
     */
    public static void startQueue(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, List<Child> media, int startIndex, long startPositionMs) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        mediaBrowserListenableFuture.get().clearMediaItems();
                        mediaBrowserListenableFuture.get().setMediaItems(MappingUtil.mapMediaItems(media));
                        mediaBrowserListenableFuture.get().prepare();
                        mediaBrowserListenableFuture.get().seekTo(startIndex, Math.max(startPositionMs, 0));
                        mediaBrowserListenableFuture.get().play();
                        enqueueDatabase(media, true, 0);
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void startQueue(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, Child media) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        mediaBrowserListenableFuture.get().clearMediaItems();
                        mediaBrowserListenableFuture.get().setMediaItem(MappingUtil.mapMediaItem(media));
                        mediaBrowserListenableFuture.get().prepare();
                        mediaBrowserListenableFuture.get().play();
                        enqueueDatabase(media, true, 0);
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void startRadio(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, InternetRadioStation internetRadioStation) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        mediaBrowserListenableFuture.get().clearMediaItems();
                        mediaBrowserListenableFuture.get().setMediaItem(MappingUtil.mapInternetRadioStation(internetRadioStation));
                        mediaBrowserListenableFuture.get().prepare();
                        mediaBrowserListenableFuture.get().play();
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void startPodcast(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, PodcastEpisode podcastEpisode) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        mediaBrowserListenableFuture.get().clearMediaItems();
                        mediaBrowserListenableFuture.get().setMediaItem(MappingUtil.mapMediaItem(podcastEpisode));
                        mediaBrowserListenableFuture.get().prepare();
                        mediaBrowserListenableFuture.get().play();
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void enqueue(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, List<Child> media, boolean playImmediatelyAfter) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        if (playImmediatelyAfter && mediaBrowserListenableFuture.get().getNextMediaItemIndex() != -1) {
                            enqueueDatabase(media, false, mediaBrowserListenableFuture.get().getNextMediaItemIndex());
                            mediaBrowserListenableFuture.get().addMediaItems(mediaBrowserListenableFuture.get().getNextMediaItemIndex(), MappingUtil.mapMediaItems(media));
                        } else {
                            enqueueDatabase(media, false, mediaBrowserListenableFuture.get().getMediaItemCount());
                            mediaBrowserListenableFuture.get().addMediaItems(MappingUtil.mapMediaItems(media));
                        }
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void enqueue(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, Child media, boolean playImmediatelyAfter) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        if (playImmediatelyAfter && mediaBrowserListenableFuture.get().getNextMediaItemIndex() != -1) {
                            enqueueDatabase(media, false, mediaBrowserListenableFuture.get().getNextMediaItemIndex());
                            mediaBrowserListenableFuture.get().addMediaItem(mediaBrowserListenableFuture.get().getNextMediaItemIndex(), MappingUtil.mapMediaItem(media));
                        } else {
                            enqueueDatabase(media, false, mediaBrowserListenableFuture.get().getMediaItemCount());
                            mediaBrowserListenableFuture.get().addMediaItem(MappingUtil.mapMediaItem(media));
                        }
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void shuffle(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, List<Child> media, int startIndex, int endIndex) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        mediaBrowserListenableFuture.get().removeMediaItems(startIndex, endIndex + 1);
                        mediaBrowserListenableFuture.get().addMediaItems(MappingUtil.mapMediaItems(media).subList(startIndex, endIndex + 1));
                        swapDatabase(media);
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void swap(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, List<Child> media, int from, int to) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        mediaBrowserListenableFuture.get().moveMediaItem(from, to);
                        swapDatabase(media);
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void remove(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, List<Child> media, int toRemove) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        if (mediaBrowserListenableFuture.get().getMediaItemCount() > 1 && mediaBrowserListenableFuture.get().getCurrentMediaItemIndex() != toRemove) {
                            mediaBrowserListenableFuture.get().removeMediaItem(toRemove);
                            removeDatabase(media, toRemove);
                        } else {
                            removeDatabase(media, -1);
                        }
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void removeRange(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, List<Child> media, int fromItem, int toItem) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        mediaBrowserListenableFuture.get().removeMediaItems(fromItem, toItem);
                        removeRangeDatabase(media, fromItem, toItem);
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void getCurrentIndex(ListenableFuture<MediaBrowser> mediaBrowserListenableFuture, MediaIndexCallback callback) {
        if (mediaBrowserListenableFuture != null) {
            mediaBrowserListenableFuture.addListener(() -> {
                try {
                    if (mediaBrowserListenableFuture.isDone()) {
                        callback.onRecovery(mediaBrowserListenableFuture.get().getCurrentMediaItemIndex());
                    }
                } catch (ExecutionException | InterruptedException e) {
                    e.printStackTrace();
                }
            }, MoreExecutors.directExecutor());
        }
    }

    public static void setLastPlayedTimestamp(MediaItem mediaItem) {
        if (mediaItem != null) getQueueRepository().setLastPlayedTimestamp(mediaItem.mediaId);
    }

    public static void setPlayingPausedTimestamp(MediaItem mediaItem, long ms) {
        if (mediaItem != null)
            getQueueRepository().setPlayingPausedTimestamp(mediaItem.mediaId, ms);
    }

    /**
     * Tells the server what the player is doing right now.
     * <p>
     * Scrobbling reports a play after the fact, so a server can only guess at
     * the live position and never learns that playback paused - Navidrome's
     * "now playing" kept counting a paused track forward and only dropped it
     * when the scrobble aged out. The playbackReport extension carries the
     * state and the real position instead.
     * <p>
     * Every report sets {@code ignoreScrobble}: play counts stay with
     * {@link ScrobbleTracker}, so nothing is counted twice on a server that
     * supports both.
     *
     * @param state one of {@link MediaAnnotationClient.PlaybackState}
     */
    public static void reportPlayback(MediaItem mediaItem, String state, long positionMs) {
        if (mediaItem == null || mediaItem.mediaMetadata.extras == null) return;
        if (!Preferences.isScrobblingEnabled()) return;
        if (!OpenSubsonicExtensionsUtil.isPlaybackReportExtensionAvailable()) return;

        String mediaType = reportableMediaType(mediaItem.mediaMetadata.extras.getString("type"));
        if (mediaType == null) return;

        String id = mediaItem.mediaMetadata.extras.getString("id");
        if (id == null) return;

        getSongRepository().reportPlayback(id, mediaType, Math.max(positionMs, 0), state, true);
    }

    /**
     * Maps the app's own media types onto the two the extension accepts.
     * Anything else - an internet radio stream has no server-side id to report
     * - returns null and is not reported.
     */
    private static String reportableMediaType(String type) {
        if (Constants.MEDIA_TYPE_MUSIC.equals(type)) return MediaAnnotationClient.PlaybackMediaType.SONG;
        if (Constants.MEDIA_TYPE_PODCAST.equals(type)) return MediaAnnotationClient.PlaybackMediaType.PODCAST;
        return null;
    }

    /**
     * Appends to the queue from inside the player service, where the player is
     * at hand and no browser is needed - the wave uses it to add its next batch.
     * Must run on the player's thread.
     */
    public static void appendToQueue(Player player, List<Child> media) {
        enqueueDatabase(media, false, player.getMediaItemCount());
        player.addMediaItems(MappingUtil.mapMediaItems(media));
    }

    /*
     * A wave keeps its own queue topped up (WaveController), well before the
     * last track - an instant mix added on top would push the wave's next
     * batch behind ten tracks it did not choose.
     */
    public static void continuousPlay(MediaItem mediaItem) {
        if (mediaItem != null && mediaItem.mediaMetadata.extras != null
                && WaveState.isWaveTrack(mediaItem.mediaMetadata.extras.getString("id"))) {
            return;
        }
        instantMixAfter(mediaItem);
    }

    /** The fallback when the wave service cannot deliver and the queue has run out. */
    public static void continuousPlayAfterWave(MediaItem mediaItem) {
        instantMixAfter(mediaItem);
    }

    @OptIn(markerClass = UnstableApi.class)
    private static void instantMixAfter(MediaItem mediaItem) {
        if (mediaItem != null && Preferences.isContinuousPlayEnabled() && Preferences.isInstantMixUsable()) {
            Preferences.setLastInstantMix();

            LiveData<List<Child>> instantMix = getSongRepository().getInstantMix(mediaItem.mediaId, 10);
            instantMix.observeForever(new Observer<List<Child>>() {
                @Override
                public void onChanged(List<Child> media) {
                    instantMix.removeObserver(this);

                    if (media == null || media.isEmpty()) return;

                    /*
                     * A browser of its own, since this runs inside the service
                     * with no screen's browser to borrow - and released as soon
                     * as the tracks are in. It never used to be: every queue that
                     * ran out left one more connection to the session open for
                     * the life of the process, each one sent every player update
                     * from then on.
                     */
                    ListenableFuture<MediaBrowser> mediaBrowserListenableFuture = new MediaBrowser.Builder(
                            App.getContext(),
                            new SessionToken(App.getContext(), new ComponentName(App.getContext(), MediaService.class))
                    ).buildAsync();

                    mediaBrowserListenableFuture.addListener(() -> {
                        try {
                            MediaBrowser mediaBrowser = mediaBrowserListenableFuture.get();

                            if (mediaBrowser.getNextMediaItemIndex() != -1) {
                                enqueueDatabase(media, false, mediaBrowser.getNextMediaItemIndex());
                                mediaBrowser.addMediaItems(mediaBrowser.getNextMediaItemIndex(), MappingUtil.mapMediaItems(media));
                            } else {
                                enqueueDatabase(media, false, mediaBrowser.getMediaItemCount());
                                mediaBrowser.addMediaItems(MappingUtil.mapMediaItems(media));
                            }
                        } catch (ExecutionException | InterruptedException e) {
                            e.printStackTrace();
                        } finally {
                            MediaBrowser.releaseFuture(mediaBrowserListenableFuture);
                        }
                    }, MoreExecutors.directExecutor());
                }
            });
        }
    }

    public static void saveChronology(MediaItem mediaItem) {
        if (mediaItem != null) {
            getChronologyRepository().insert(new Chronology(mediaItem));
        }
    }

    private static QueueRepository getQueueRepository() {
        return new QueueRepository();
    }

    private static SongRepository getSongRepository() {
        return new SongRepository();
    }

    private static ChronologyRepository getChronologyRepository() {
        return new ChronologyRepository();
    }

    private static void enqueueDatabase(List<Child> media, boolean reset, int afterIndex) {
        getQueueRepository().insertAll(media, reset, afterIndex);
    }

    private static void enqueueDatabase(Child media, boolean reset, int afterIndex) {
        getQueueRepository().insert(media, reset, afterIndex);
    }

    private static void swapDatabase(List<Child> media) {
        getQueueRepository().rearrange(media);
    }

    private static void removeDatabase(List<Child> media, int toRemove) {
        if (toRemove != -1) {
            media.remove(toRemove);
            getQueueRepository().rearrange(media);
        }
    }

    private static void removeRangeDatabase(List<Child> media, int fromItem, int toItem) {
        /*
         * By position. removeAll() over a view of the same list went by
         * equality, and compacted the list underneath the view it was still
         * reading from - it only came out right because the one caller clears
         * to the end of the queue.
         */
        media.subList(fromItem, toItem).clear();

        getQueueRepository().rearrange(media);
    }

    public static void clearDatabase() {
        getQueueRepository().deleteAll();
    }
}
