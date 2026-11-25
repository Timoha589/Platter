package com.cappielloantonio.tempo.ui.fragment;

import android.content.ComponentName;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;

import com.cappielloantonio.tempo.databinding.InnerFragmentPlayerCoverBinding;
import com.cappielloantonio.tempo.helper.view.DissolvingCoverView;
import com.cappielloantonio.tempo.service.CoverTransition;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.util.TrackSwipeCarousel;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.concurrent.ExecutionException;

@UnstableApi
public class PlayerCoverFragment extends Fragment {
    private InnerFragmentPlayerCoverBinding bind;
    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    private TrackSwipeCarousel coverCarousel;
    private final TrackSwipeCarousel.OverlayTurn dissolve =
            (outgoing, incoming, token, progress) -> ((DissolvingCoverView) outgoing).dissolve(token, progress);

    /**
     * The gutter between one sleeve and the next while they are being dragged
     * past each other, matching the page's own 24dp margin.
     */
    private static final float COVER_GAP_DP = 24f;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        bind = InnerFragmentPlayerCoverBinding.inflate(inflater, container, false);
        View view = bind.getRoot();

        initCoverGestures();

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        CoverTransition.updates().observe(getViewLifecycleOwner(), transition -> {
            if (coverCarousel != null && connectedBrowser() != null) coverCarousel.syncCover(transition, dissolve, null);
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        initializeBrowser();
        bindMediaController();
    }

    @Override
    public void onStop() {
        if (coverCarousel != null) coverCarousel.cancel();
        releaseBrowser();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (coverCarousel != null) coverCarousel.cancel();
        coverCarousel = null;

        bind = null;
    }

    /**
     * A sideways drag over the artwork carries the queue: the sleeve follows the
     * finger, and the neighbouring track is dragged into place rather than
     * appearing once the swipe is over. Anything more vertical than horizontal
     * is left alone, so the sheet can still be dragged, and the queue still
     * reached, from the cover.
     */
    private void initCoverGestures() {
        coverCarousel = new TrackSwipeCarousel(
                bind.nowPlayingCoverStrip,
                new TrackSwipeCarousel.Callback() {
                    @Nullable
                    @Override
                    public Player player() {
                        return connectedBrowser();
                    }

                    @Override
                    public void onBindSlot(@NonNull View slot, @Nullable MediaMetadata metadata) {
                        String artwork = metadata != null && metadata.extras != null ? metadata.extras.getString("coverArtId") : null;
                        ((DissolvingCoverView) slot).loadArtwork(artwork);
                    }

                    @Override
                    public void onDragStart() {
                        // Nothing on the page to put away: the cover is the page.
                    }
                },
                COVER_GAP_DP * getResources().getDisplayMetrics().density,
                // The page around the strip does the clipping, so a sleeve
                // travels to the edge of the screen rather than to the gutter.
                false,
                // Nothing is left for a tap to do over the artwork.
                null,
                bind.nowPlayingSongCoverPrevious,
                bind.nowPlayingSongCoverImageView,
                bind.nowPlayingSongCoverNext
        );

        coverCarousel.setCoverStage(bind.getRoot());
        bind.getRoot().setOnTouchListener(coverCarousel);
    }

    @Nullable
    private MediaBrowser connectedBrowser() {
        try {
            if (mediaBrowserListenableFuture == null || !mediaBrowserListenableFuture.isDone()) return null;

            return mediaBrowserListenableFuture.get();
        } catch (ExecutionException | InterruptedException exception) {
            return null;
        }
    }

    private void initializeBrowser() {
        mediaBrowserListenableFuture = new MediaBrowser.Builder(requireContext(), new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
    }

    private void releaseBrowser() {
        ListenableFuture<MediaBrowser> future = mediaBrowserListenableFuture;
        mediaBrowserListenableFuture = null;
        if (future != null) MediaBrowser.releaseFuture(future);
    }

    private void bindMediaController() {
        ListenableFuture<MediaBrowser> future = mediaBrowserListenableFuture;
        future.addListener(() -> {
            if (future != mediaBrowserListenableFuture || bind == null) return;
            try {
                setMediaBrowserListener(future.get());
            } catch (Exception exception) {
                exception.printStackTrace();
            }
        }, MoreExecutors.directExecutor());
    }

    private void setMediaBrowserListener(MediaBrowser mediaBrowser) {
        coverCarousel.bindAll();
        coverCarousel.syncCover(CoverTransition.updates().getValue(), dissolve, null);

        mediaBrowser.addListener(new Player.Listener() {
            @Override
            public void onEvents(@NonNull Player player, @NonNull Player.Events events) {
                if (mediaBrowser != connectedBrowser()) return;
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED,
                        Player.EVENT_IS_PLAYING_CHANGED)) {
                    coverCarousel.syncCover(CoverTransition.updates().getValue(), dissolve, null);
                }
            }
        });
    }

}
