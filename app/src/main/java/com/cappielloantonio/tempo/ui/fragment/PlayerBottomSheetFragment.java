package com.cappielloantonio.tempo.ui.fragment;

import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.fragment.app.Fragment;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.viewpager2.widget.ViewPager2;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentPlayerBottomSheetBinding;
import com.cappielloantonio.tempo.databinding.ViewMiniPlayerTrackBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.PlayQueue;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.fragment.pager.PlayerControllerVerticalPager;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.MusicUtil;
import com.cappielloantonio.tempo.util.Preferences;
import com.cappielloantonio.tempo.util.SwipeGestureUtil;
import com.cappielloantonio.tempo.util.TrackSwipeCarousel;
import com.cappielloantonio.tempo.viewmodel.PlayerBottomSheetViewModel;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.List;

@OptIn(markerClass = UnstableApi.class)
public class PlayerBottomSheetFragment extends Fragment {
    private FragmentPlayerBottomSheetBinding bind;

    private PlayerBottomSheetViewModel playerBottomSheetViewModel;
    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    /*
     * The mini player's progress tick, made the way PlayerLyricsFragment's
     * sync ticker is and for the same reason. A new handler and runnable used
     * to be built every onStart while the last pair kept running - nothing
     * stopped it in onStop - and since the old runnable reposted whatever the
     * field held by then, each return to the app while music played added one
     * more tick a second. They went on in the background too, reading a
     * browser that had already been released.
     */
    private final Handler progressBarHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressBarRunnable = new Runnable() {
        @Override
        public void run() {
            MediaBrowser mediaBrowser = connectedBrowser();

            if (bind == null || mediaBrowser == null) return;

            setProgress(mediaBrowser);
            progressBarHandler.postDelayed(this, 1000);
        }
    };

    private GestureDetector headerGestureDetector;

    /**
     * A vertical gesture on the mini player is acted on once and then ignored
     * for the rest of that touch, so a long drag does not fire again on every
     * scroll callback.
     */
    private boolean headerVerticalGestureHandled;

    private TrackSwipeCarousel headerCarousel;

    /**
     * The gap between one row and the next while they are being dragged past
     * each other. Small: the bar is short, and the rows should read as a strip
     * rather than as separate cards.
     */
    private static final float HEADER_GAP_DP = 12f;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        bind = FragmentPlayerBottomSheetBinding.inflate(inflater, container, false);
        View view = bind.getRoot();

        playerBottomSheetViewModel = new ViewModelProvider(requireActivity()).get(PlayerBottomSheetViewModel.class);

        customizeBottomSheetBackground();
        customizeBottomSheetAction();
        initViewPager();
        setHeaderBookmarksButton();

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        /*
         * A fresh view starts with the mini player showing, whatever state the
         * sheet is in - and after a recreation the sheet comes back expanded
         * without ever reporting a slide. Posted, so it runs once the activity
         * has put back the state the sheet was saved in.
         */
        view.post(() -> {
            if (bind != null && getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).syncBottomSheetDecor();
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();

        initializeMediaBrowser();
        bindMediaController();
    }

    @Override
    public void onStop() {
        progressBarHandler.removeCallbacks(progressBarRunnable);
        releaseMediaBrowser();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (headerCarousel != null) headerCarousel.cancel();

        bind = null;
    }

    /*
     * The mini player sits on graphite (DESIGN.md card surface), which is what
     * player_header_bottom_sheet.xml declares. SurfaceColors composites the
     * tonal overlay regardless of elevationOverlayEnabled=false, so the old
     * call repainted the bar with a green-tinted grey.
     */
    private void customizeBottomSheetBackground() {
        bind.playerHeaderLayout.getRoot().setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.graphite));
    }

    private void customizeBottomSheetAction() {
        headerGestureDetector = new GestureDetector(requireContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent event) {
                headerVerticalGestureHandled = false;
                return true;
            }

            @Override
            public boolean onSingleTapUp(@NonNull MotionEvent event) {
                ((MainActivity) requireActivity()).expandBottomSheet();
                return true;
            }

            @Override
            public boolean onScroll(@Nullable MotionEvent from, @NonNull MotionEvent to, float distanceX, float distanceY) {
                return handleVertical(from, to);
            }

            @Override
            public boolean onFling(@Nullable MotionEvent from, @NonNull MotionEvent to, float velocityX, float velocityY) {
                return handleVertical(from, to);
            }
        });

        initHeaderCarousel();
    }

    /**
     * A sideways drag on the mini player carries the queue the same way the full
     * player's artwork does: sleeve, title and artist travel with the finger,
     * and the neighbouring track is pulled into the bar rather than swapped into
     * it afterwards. The detector above keeps everything else - the tap that
     * opens the sheet, the vertical drags that raise and dismiss it.
     */
    private void initHeaderCarousel() {
        headerCarousel = new TrackSwipeCarousel(
                bind.playerHeaderLayout.playerHeaderTrackStrip,
                new TrackSwipeCarousel.Callback() {
                    @Nullable
                    @Override
                    public Player player() {
                        return connectedBrowser();
                    }

                    @Override
                    public void onBindSlot(@NonNull View slot, @Nullable MediaMetadata metadata) {
                        bindHeaderTrack(ViewMiniPlayerTrackBinding.bind(slot), metadata);
                    }

                    @Override
                    public void onDragStart() {
                        // The bar is being dragged, not pulled: whatever the
                        // detector had started to make of the gesture is off.
                        headerVerticalGestureHandled = true;
                    }
                },
                HEADER_GAP_DP * getResources().getDisplayMetrics().density,
                // The bar lets its ripples spill, so nothing above the strip
                // would keep the neighbouring track out of the play button.
                true,
                headerGestureDetector,
                bind.playerHeaderLayout.playerHeaderTrackPrevious.getRoot(),
                bind.playerHeaderLayout.playerHeaderTrackCurrent.getRoot(),
                bind.playerHeaderLayout.playerHeaderTrackNext.getRoot()
        );

        bind.playerHeaderLayout.getRoot().setOnTouchListener(headerCarousel);
    }

    @Nullable
    private MediaBrowser connectedBrowser() {
        try {
            if (mediaBrowserListenableFuture == null || !mediaBrowserListenableFuture.isDone()) return null;

            return mediaBrowserListenableFuture.get();
        } catch (Exception exception) {
            return null;
        }
    }

    private void bindHeaderTrack(ViewMiniPlayerTrackBinding track, @Nullable MediaMetadata metadata) {
        Bundle extras = metadata != null ? metadata.extras : null;

        String title = extras != null ? extras.getString("title") : null;
        String artist = extras != null ? extras.getString("artist") : null;

        track.miniPlayerTitleLabel.setText(title);
        track.miniPlayerArtistLabel.setText(artist);

        track.miniPlayerTitleLabel.setVisibility(TextUtils.isEmpty(title) ? View.GONE : View.VISIBLE);
        track.miniPlayerArtistLabel.setVisibility(TextUtils.isEmpty(artist) ? View.GONE : View.VISIBLE);

        CustomGlideRequest.loadCover(
                requireContext(),
                track.miniPlayerCoverImage,
                extras != null ? extras.getString("coverArtId") : null,
                CustomGlideRequest.ResourceType.Song
        );
    }

    /**
     * Drags the mini player itself, because the sheet will not.
     * <p>
     * BottomSheetBehavior refuses to let its drag helper take a touch that
     * started inside the sheet's nested scrolling child - it expects that child
     * to drive the sheet through nested scrolling instead. The mini player is
     * laid over the body, so every touch on it counts as touching that child,
     * while the body never sees the gesture to pass it on. The sheet therefore
     * would not move at all until it had been opened by some other means, and
     * dismissing it took an expand and two drags. Acting on the gesture here
     * settles the sheet instead of tracking the finger, which is the part this
     * cannot recover.
     */
    private boolean handleVertical(@Nullable MotionEvent from, @Nullable MotionEvent to) {
        if (headerVerticalGestureHandled) return false;
        if (!SwipeGestureUtil.isVerticalDrag(from, to, requireContext())) return false;

        MainActivity activity = (MainActivity) requireActivity();

        /*
         * Only the mini player is driven from here. Part way through a drag the
         * header is still on screen, and acting then would dismiss a sheet the
         * user is merely closing - which is the one thing dragging down from the
         * full player is not allowed to do.
         */
        if (!activity.isBottomSheetCollapsed()) return false;

        headerVerticalGestureHandled = true;

        if (to.getY() > from.getY()) {
            activity.hideBottomSheet();
        } else {
            activity.expandBottomSheet();
        }

        return true;
    }

    private void initViewPager() {
        bind.playerBodyLayout.playerBodyBottomSheetViewPager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        bind.playerBodyLayout.playerBodyBottomSheetViewPager.setAdapter(new PlayerControllerVerticalPager(this));

        /*
         * Neither end of this pager is its own. A pull down past the player
         * moves the sheet, and a pull up past the queue belongs to the queue
         * (see NestedScrollableHost). What was left for the pager's own stretch
         * was a drag from the shuffle button: the whole queue page, button and
         * all, wobbling under a pull that goes nowhere.
         */
        View pager = bind.playerBodyLayout.playerBodyBottomSheetViewPager.getChildAt(0);
        if (pager != null) pager.setOverScrollMode(View.OVER_SCROLL_NEVER);
    }

    private void initializeMediaBrowser() {
        mediaBrowserListenableFuture = new MediaBrowser.Builder(requireContext(), new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
    }

    private void releaseMediaBrowser() {
        MediaController.releaseFuture(mediaBrowserListenableFuture);
    }

    private void bindMediaController() {
        mediaBrowserListenableFuture.addListener(() -> {
            try {
                MediaBrowser mediaBrowser = mediaBrowserListenableFuture.get();

                setMediaControllerListener(mediaBrowser);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, MoreExecutors.directExecutor());
    }

    private void setMediaControllerListener(MediaBrowser mediaBrowser) {
        setMediaControllerUI(mediaBrowser);
        setMetadata(mediaBrowser.getMediaMetadata());
        headerCarousel.bindAll();
        setContentDuration(mediaBrowser.getContentDuration());
        // Straight away: the tick is a second off, and the bar would show where it was left until then.
        setProgress(mediaBrowser);
        setPlayingState(mediaBrowser.isPlaying());
        setHeaderMediaController();

        mediaBrowser.addListener(new Player.Listener() {
            @Override
            public void onMediaMetadataChanged(@NonNull MediaMetadata mediaMetadata) {
                if (bind == null) return;

                setMediaControllerUI(mediaBrowser);
                setMetadata(mediaMetadata);
                setContentDuration(mediaBrowser.getContentDuration());
            }

            /*
             * A transition rather than a metadata change: the same track queued
             * twice in a row carries the same metadata, and the strip has to
             * follow the queue even when nothing about the track is different.
             */
            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                if (bind == null) return;

                headerCarousel.sync();
            }

            @Override
            public void onTimelineChanged(@NonNull Timeline timeline, int reason) {
                if (bind == null) return;

                headerCarousel.sync();
            }

            @Override
            public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
                if (bind == null) return;

                headerCarousel.sync();
            }

            @Override
            public void onRepeatModeChanged(int repeatMode) {
                if (bind == null) return;

                headerCarousel.sync();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                setPlayingState(isPlaying);

                if (bind != null) headerCarousel.sync();
            }

            @Override
            public void onSkipSilenceEnabledChanged(boolean skipSilenceEnabled) {
                Player.Listener.super.onSkipSilenceEnabledChanged(skipSilenceEnabled);
            }

        });
    }

    /*
     * What the rest of the app reads off the current track. The bar itself is
     * filled by the carousel instead, from the queue entry it is showing, so
     * that the row being dragged into place and the row already there are filled
     * the same way.
     */
    private void setMetadata(MediaMetadata mediaMetadata) {
        if (mediaMetadata.extras != null) {
            playerBottomSheetViewModel.setLiveMedia(mediaMetadata.extras.getString("type"), mediaMetadata.extras.getString("id"), queuedTrack(mediaMetadata));
            playerBottomSheetViewModel.setLiveAlbum(mediaMetadata.extras.getString("type"), mediaMetadata.extras.getString("albumId"));
            playerBottomSheetViewModel.setLiveArtist(mediaMetadata.extras.getString("type"), mediaMetadata.extras.getString("artistId"), mediaMetadata.artist != null ? mediaMetadata.artist.toString() : null);
            playerBottomSheetViewModel.setLiveDescription(mediaMetadata.extras.getString("description", null));
        }
    }

    /* The track as the queue carries it: what the player shows while the server's copy is on its way. */
    @Nullable
    private static Child queuedTrack(MediaMetadata mediaMetadata) {
        Bundle extras = mediaMetadata.extras;

        if (extras == null || extras.getString("id") == null) return null;
        if (!Constants.MEDIA_TYPE_MUSIC.equals(extras.getString("type"))) return null;

        return new Download(new MediaItem.Builder().setMediaMetadata(mediaMetadata).build());
    }

    private void setMediaControllerUI(MediaBrowser mediaBrowser) {
        if (mediaBrowser.getMediaMetadata().extras != null) {
            switch (mediaBrowser.getMediaMetadata().extras.getString("type", Constants.MEDIA_TYPE_MUSIC)) {
                case Constants.MEDIA_TYPE_PODCAST:
                    bind.playerHeaderLayout.playerHeaderFastForwardMediaButton.setVisibility(View.VISIBLE);
                    bind.playerHeaderLayout.playerHeaderRewindMediaButton.setVisibility(View.VISIBLE);
                    bind.playerHeaderLayout.placeholderRightView.setVisibility(View.INVISIBLE);
                    break;
                case Constants.MEDIA_TYPE_MUSIC:
                default:
                    bind.playerHeaderLayout.playerHeaderFastForwardMediaButton.setVisibility(View.GONE);
                    bind.playerHeaderLayout.playerHeaderRewindMediaButton.setVisibility(View.GONE);
                    bind.playerHeaderLayout.placeholderRightView.setVisibility(View.GONE);
                    break;
            }
        }
    }

    private void setContentDuration(long duration) {
        bind.playerHeaderLayout.playerHeaderSeekBar.setMax((int) (duration / 1000));
    }

    private void setProgress(MediaBrowser mediaBrowser) {
        if (bind != null)
            bind.playerHeaderLayout.playerHeaderSeekBar.setProgress((int) (mediaBrowser.getCurrentPosition() / 1000), true);
    }

    private void runProgressBarHandler(boolean isPlaying) {
        // Removed either way, so a second "playing" never starts a second tick.
        progressBarHandler.removeCallbacks(progressBarRunnable);

        if (isPlaying) progressBarHandler.postDelayed(progressBarRunnable, 1000);
    }

    private void setPlayingState(boolean isPlaying) {
        if (bind == null) return;

        bind.playerHeaderLayout.playerHeaderButton.setChecked(isPlaying);
        runProgressBarHandler(isPlaying);
    }

    private void setHeaderMediaController() {
        bind.playerHeaderLayout.playerHeaderButton.setOnClickListener(view -> bind.getRoot().findViewById(R.id.exo_play_pause).performClick());
        bind.playerHeaderLayout.playerHeaderRewindMediaButton.setOnClickListener(view -> bind.getRoot().findViewById(R.id.exo_rew).performClick());
        bind.playerHeaderLayout.playerHeaderFastForwardMediaButton.setOnClickListener(view -> bind.getRoot().findViewById(R.id.exo_ffwd).performClick());
    }

    /* Null while there is no view, which the sheet's callbacks can run ahead of. */
    @Nullable
    public View getPlayerHeader() {
        return bind != null ? bind.playerHeaderLayout.getRoot() : null;
    }

    public void goBackToFirstPage() {
        bind.playerBodyLayout.playerBodyBottomSheetViewPager.setCurrentItem(0, false);
        goToControllerPage();
    }

    public void goToControllerPage() {
        PlayerControllerVerticalPager playerControllerVerticalPager = (PlayerControllerVerticalPager) bind.playerBodyLayout.playerBodyBottomSheetViewPager.getAdapter();
        if (playerControllerVerticalPager != null) {
            PlayerControllerFragment playerControllerFragment = (PlayerControllerFragment) playerControllerVerticalPager.getRegisteredFragment(0);
            if (playerControllerFragment != null) {
                playerControllerFragment.goToControllerPage();
            }
        }
    }

    public void goToLyricsPage() {
        PlayerControllerVerticalPager playerControllerVerticalPager = (PlayerControllerVerticalPager) bind.playerBodyLayout.playerBodyBottomSheetViewPager.getAdapter();
        if (playerControllerVerticalPager != null) {
            PlayerControllerFragment playerControllerFragment = (PlayerControllerFragment) playerControllerVerticalPager.getRegisteredFragment(0);
            if (playerControllerFragment != null) {
                playerControllerFragment.goToLyricsPage();
            }
        }
    }

    public void goToQueuePage() {
        bind.playerBodyLayout.playerBodyBottomSheetViewPager.setCurrentItem(1, true);
    }

    private void setHeaderBookmarksButton() {
        if (Preferences.isSyncronizationEnabled()) {
            /*
             * Held, because getPlayQueue() is a fresh request each time it is
             * called: removing the observer through a second call asked the
             * server for the queue again and left this observer where it was.
             */
            LiveData<PlayQueue> savedQueue = playerBottomSheetViewModel.getPlayQueue();
            savedQueue.observeForever(new Observer<PlayQueue>() {
                @Override
                public void onChanged(PlayQueue playQueue) {
                    savedQueue.removeObserver(this);

                    if (bind == null) return;

                    /*
                     * A user who has never saved a queue gets a playQueue object
                     * with no entry list at all - reading it as a list threw.
                     * resolvedIndex() also prefers the index the server sent
                     * over matching on the current song id, which picks the
                     * wrong copy when a queue repeats a track.
                     */
                    int index = playQueue != null ? playQueue.resolvedIndex() : -1;

                    if (index != -1) {
                        List<Child> entries = playQueue.entryList();
                        long position = playQueue.getPosition() != null ? playQueue.getPosition() : 0;

                        bind.playerHeaderLayout.playerHeaderBookmarkMediaButton.setVisibility(View.VISIBLE);
                        bind.playerHeaderLayout.playerHeaderBookmarkMediaButton.setOnClickListener(v -> {
                            MediaManager.startQueue(mediaBrowserListenableFuture, entries, index, position);
                            bind.playerHeaderLayout.playerHeaderBookmarkMediaButton.setVisibility(View.GONE);
                        });
                    } else {
                        bind.playerHeaderLayout.playerHeaderBookmarkMediaButton.setVisibility(View.GONE);
                        bind.playerHeaderLayout.playerHeaderBookmarkMediaButton.setOnClickListener(null);
                    }
                }
            });

            bind.playerHeaderLayout.playerHeaderBookmarkMediaButton.setOnLongClickListener(v -> {
                bind.playerHeaderLayout.playerHeaderBookmarkMediaButton.setVisibility(View.GONE);
                return true;
            });

            new Handler().postDelayed(() -> {
                if (bind != null)
                    bind.playerHeaderLayout.playerHeaderBookmarkMediaButton.setVisibility(View.GONE);
            }, Preferences.getSyncCountdownTimer() * 1000L);
        }
    }
}
