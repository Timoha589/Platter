package com.cappielloantonio.tempo.ui.fragment;

import android.content.ComponentName;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;

import com.cappielloantonio.tempo.databinding.InnerFragmentPlayerCoverBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
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
    public void onStart() {
        super.onStart();
        initializeBrowser();
        bindMediaController();
    }

    @Override
    public void onStop() {
        releaseBrowser();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (coverCarousel != null) coverCarousel.cancel();

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
                        CustomGlideRequest.loadCover(
                                requireContext(),
                                (ImageView) slot,
                                metadata != null && metadata.extras != null ? metadata.extras.getString("coverArtId") : null,
                                CustomGlideRequest.ResourceType.Song
                        );
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
        MediaBrowser.releaseFuture(mediaBrowserListenableFuture);
    }

    private void bindMediaController() {
        mediaBrowserListenableFuture.addListener(() -> {
            try {
                MediaBrowser mediaBrowser = mediaBrowserListenableFuture.get();
                setMediaBrowserListener(mediaBrowser);
            } catch (Exception exception) {
                exception.printStackTrace();
            }
        }, MoreExecutors.directExecutor());
    }

    private void setMediaBrowserListener(MediaBrowser mediaBrowser) {
        coverCarousel.bindAll();

        mediaBrowser.addListener(new Player.Listener() {
            /*
             * A transition rather than a metadata change: the same track queued
             * twice in a row carries the same metadata, and the strip has to
             * follow the queue even when nothing about the track is different.
             */
            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                if (bind == null) return;

                coverCarousel.sync();
            }

            @Override
            public void onTimelineChanged(@NonNull Timeline timeline, int reason) {
                if (bind == null) return;

                coverCarousel.sync();
            }

            @Override
            public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
                if (bind == null) return;

                coverCarousel.sync();
            }

            @Override
            public void onRepeatModeChanged(int repeatMode) {
                if (bind == null) return;

                coverCarousel.sync();
            }

            /*
             * Not because playing and pausing changes the sleeve, but because it
             * is the thing the player says most often. sync() checks the strip
             * against the queue and costs nothing when the two agree, so every
             * play and pause is a chance to notice one that has come adrift.
             */
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (bind == null) return;

                coverCarousel.sync();
            }
        });
    }
}