package com.cappielloantonio.tempo.ui.fragment;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.util.RepeatModeUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;
import androidx.navigation.fragment.NavHostFragment;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.InnerFragmentPlayerControllerBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.subsonic.models.ArtistID3;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.dialog.PlaylistChooserDialog;
import com.cappielloantonio.tempo.ui.dialog.RatingDialog;
import com.cappielloantonio.tempo.ui.dialog.TrackInfoDialog;
import com.cappielloantonio.tempo.ui.fragment.pager.PlayerControllerHorizontalPager;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.CoverColorUtil;
import com.cappielloantonio.tempo.util.DownloadUtil;
import com.cappielloantonio.tempo.util.FavoriteState;
import com.cappielloantonio.tempo.util.MappingUtil;
import com.cappielloantonio.tempo.util.MusicUtil;
import com.cappielloantonio.tempo.util.Preferences;
import com.cappielloantonio.tempo.viewmodel.PlayerBottomSheetViewModel;
import com.cappielloantonio.tempo.helper.view.LikeAnimation;
import com.google.android.material.chip.Chip;
import com.google.android.material.snackbar.Snackbar;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.List;
import java.util.Objects;

@UnstableApi
public class PlayerControllerFragment extends Fragment {
    private static final String TAG = "PlayerCoverFragment";

    private static final long FADE_OUT_DURATION = 160;
    private static final long FADE_IN_DURATION = 220;

    private InnerFragmentPlayerControllerBinding bind;
    private ViewPager2 playerMediaCoverViewPager;
    private ToggleButton buttonFavorite;
    private TextView playerMediaTitleLabel;
    private TextView playerArtistNameLabel;
    private Button playbackSpeedButton;
    private ToggleButton skipSilenceToggleButton;
    private Chip playerMediaExtension;
    private TextView playerMediaBitrate;
    private ConstraintLayout playerQuickActionView;
    private ImageButton playerOpenQueueButton;
    private ImageButton playerTrackInfo;
    private ImageButton playerDislikeButton;
    private ImageButton playerInstantMixButton;
    private ImageButton playerAddToPlaylistButton;
    private ImageButton playerDownloadButton;
    private ImageButton playerLyricsToggleButton;
    private ImageButton playerPlayPauseButton;

    private MainActivity activity;
    private PlayerBottomSheetViewModel playerBottomSheetViewModel;
    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    /*
     * The player is painted with the average colour of the artwork, fading into
     * the canvas black so the transport controls at the foot keep the contrast
     * they were designed against. Both the drawable and the colour it is
     * currently showing are held so a track change can be tweened rather than
     * cut.
     */
    private GradientDrawable playerBackground;
    private ValueAnimator playerBackgroundAnimator;
    @ColorInt
    private int playerBackgroundTint;
    @ColorInt
    private int playerBackgroundTintTarget;
    private CustomTarget<Bitmap> coverColorTarget;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        bind = InnerFragmentPlayerControllerBinding.inflate(inflater, container, false);
        View view = bind.getRoot();

        playerBottomSheetViewModel = new ViewModelProvider(requireActivity()).get(PlayerBottomSheetViewModel.class);

        init();
        initPlayerBackground();
        initQuickActionView();
        initCoverLyricsSlideView();
        initMediaListenable();
        initMediaLabelButton();
        initArtistLabelButton();

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        initializeBrowser();
        bindMediaController();

        Child media = playerBottomSheetViewModel.getLiveMedia().getValue();
        if (media != null) setDownloadButtonState(media);
    }

    @Override
    public void onStop() {
        releaseBrowser();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (playerBackgroundAnimator != null) {
            playerBackgroundAnimator.cancel();
            playerBackgroundAnimator = null;
        }

        coverColorTarget = null;

        bind = null;
    }

    private void init() {
        playerMediaCoverViewPager = bind.getRoot().findViewById(R.id.player_media_cover_view_pager);
        buttonFavorite = bind.getRoot().findViewById(R.id.button_favorite);
        playerMediaTitleLabel = bind.getRoot().findViewById(R.id.player_media_title_label);
        playerArtistNameLabel = bind.getRoot().findViewById(R.id.player_artist_name_label);
        playbackSpeedButton = bind.getRoot().findViewById(R.id.player_playback_speed_button);
        skipSilenceToggleButton = bind.getRoot().findViewById(R.id.player_skip_silence_toggle_button);
        playerMediaExtension = bind.getRoot().findViewById(R.id.player_media_extension);
        playerMediaBitrate = bind.getRoot().findViewById(R.id.player_media_bitrate);
        playerQuickActionView = bind.getRoot().findViewById(R.id.player_quick_action_view);
        playerOpenQueueButton = bind.getRoot().findViewById(R.id.player_open_queue_button);
        playerTrackInfo = bind.getRoot().findViewById(R.id.player_info_track);
        playerDislikeButton = bind.getRoot().findViewById(R.id.player_dislike_button);
        playerInstantMixButton = bind.getRoot().findViewById(R.id.player_instant_mix_button);
        playerAddToPlaylistButton = bind.getRoot().findViewById(R.id.player_add_to_playlist_button);
        playerDownloadButton = bind.getRoot().findViewById(R.id.player_download_button);
        playerLyricsToggleButton = bind.getRoot().findViewById(R.id.player_lyrics_toggle_button);
        playerPlayPauseButton = bind.getRoot().findViewById(R.id.exo_play_pause);
    }

    private void initQuickActionView() {
        /*
         * The bar used to be painted graphite to separate it from the surface
         * above. It is transparent now so the cover tint runs unbroken to the
         * foot of the player; the gradient has already reached the canvas black
         * by this point, which is the separation the graphite was providing.
         */
        playerQuickActionView.setBackground(null);

        playerOpenQueueButton.setOnClickListener(view -> {
            PlayerBottomSheetFragment playerBottomSheetFragment = (PlayerBottomSheetFragment) requireActivity().getSupportFragmentManager().findFragmentByTag("PlayerBottomSheet");
            if (playerBottomSheetFragment != null) {
                playerBottomSheetFragment.goToQueuePage();
            }
        });

        playerLyricsToggleButton.setOnClickListener(view -> fadeToPage(isShowingLyrics() ? 0 : 1));
    }

    /**
     * The cover and the lyrics no longer slide past each other — the pager does
     * not page by touch any more, so the swap is dressed as what it now is: the
     * artwork dissolves and the words take its place.
     */
    private void fadeToPage(int position) {
        if (playerMediaCoverViewPager.getCurrentItem() == position) return;

        playerMediaCoverViewPager.animate().cancel();
        playerMediaCoverViewPager.animate()
                .alpha(0f)
                .setDuration(FADE_OUT_DURATION)
                .withEndAction(() -> {
                    if (bind == null) return;

                    playerMediaCoverViewPager.setCurrentItem(position, false);
                    playerMediaCoverViewPager.animate().alpha(1f).setDuration(FADE_IN_DURATION).start();
                })
                .start();
    }

    private boolean isShowingLyrics() {
        return playerMediaCoverViewPager.getCurrentItem() == 1;
    }

    /**
     * Keeps the toggle showing the page it would take you to, not the one you
     * are on, so it reads the same whether the pages were changed by the button
     * or by swiping the cover.
     */
    private void setLyricsToggleState(boolean showingLyrics) {
        playerLyricsToggleButton.setImageResource(showingLyrics ? R.drawable.ic_album : R.drawable.ic_lyrics_text);
        playerLyricsToggleButton.setContentDescription(getString(showingLyrics ? R.string.player_cover_toggle_button : R.string.player_lyrics_toggle_button));
    }

    private void initPlayerBackground() {
        playerBackgroundTint = ContextCompat.getColor(requireContext(), R.color.carbon);
        playerBackgroundTintTarget = playerBackgroundTint;
        playerBackground = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, gradientFor(playerBackgroundTint));
        playerBackground.setGradientType(GradientDrawable.LINEAR_GRADIENT);

        bind.getRoot().setBackground(playerBackground);
        setPlayPauseTint(playerBackgroundTint);
    }

    /**
     * Landscape draws the play button without the white disc, so there is no
     * background there to tint and the call is a no-op by design.
     */
    private void setPlayPauseTint(@ColorInt int tint) {
        if (playerPlayPauseButton == null || playerPlayPauseButton.getBackground() == null) return;

        playerPlayPauseButton.setBackgroundTintList(ColorStateList.valueOf(CoverColorUtil.toAccentTint(tint)));
    }

    private int[] gradientFor(@ColorInt int tint) {
        int canvas = ContextCompat.getColor(requireContext(), R.color.void_black);
        return new int[]{tint, ColorUtils.blendARGB(tint, canvas, 0.7f), canvas};
    }

    /**
     * Pulls the artwork down to a thumbnail purely to average it — the player
     * only needs one colour out of it, and the full-size cover is already being
     * decoded for the cover page.
     */
    private void setBackgroundFromCover(MediaMetadata mediaMetadata) {
        String coverArtId = mediaMetadata.extras != null ? mediaMetadata.extras.getString("coverArtId") : null;
        Object cover = CustomGlideRequest.coverModel(requireContext(), coverArtId, !Preferences.isDataSavingMode());

        if (cover == null) {
            animateBackgroundTint(ContextCompat.getColor(requireContext(), R.color.carbon));
            return;
        }

        if (coverColorTarget != null) Glide.with(this).clear(coverColorTarget);

        coverColorTarget = new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                if (bind == null) return;

                int fallback = ContextCompat.getColor(requireContext(), R.color.carbon);
                animateBackgroundTint(CoverColorUtil.toBackgroundTint(CoverColorUtil.averageColor(resource, fallback)));
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                if (bind == null) return;
                animateBackgroundTint(ContextCompat.getColor(requireContext(), R.color.carbon));
            }

            @Override
            public void onLoadCleared(@Nullable Drawable placeholder) {
            }
        };

        Glide.with(this)
                .asBitmap()
                .load(cover)
                .dontTransform()
                .override(64, 64)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(coverColorTarget);
    }

    private void animateBackgroundTint(@ColorInt int tint) {
        if (playerBackground == null || tint == playerBackgroundTintTarget) return;

        if (playerBackgroundAnimator != null) playerBackgroundAnimator.cancel();

        playerBackgroundTintTarget = tint;

        playerBackgroundAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), playerBackgroundTint, tint);
        playerBackgroundAnimator.setDuration(500);
        playerBackgroundAnimator.addUpdateListener(animation -> {
            if (playerBackground == null) return;

            playerBackgroundTint = (int) animation.getAnimatedValue();
            playerBackground.setColors(gradientFor(playerBackgroundTint));
            setPlayPauseTint(playerBackgroundTint);
        });
        playerBackgroundAnimator.start();
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

                bind.nowPlayingMediaControllerView.setPlayer(mediaBrowser);

                setMediaControllerListener(mediaBrowser);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, MoreExecutors.directExecutor());
    }

    private void setMediaControllerListener(MediaBrowser mediaBrowser) {
        setMediaControllerUI(mediaBrowser);
        setMetadata(mediaBrowser.getMediaMetadata());
        setMediaInfo(mediaBrowser.getMediaMetadata());
        setBackgroundFromCover(mediaBrowser.getMediaMetadata());

        mediaBrowser.addListener(new Player.Listener() {
            @Override
            public void onMediaMetadataChanged(@NonNull MediaMetadata mediaMetadata) {
                setMediaControllerUI(mediaBrowser);
                setMetadata(mediaMetadata);
                setMediaInfo(mediaMetadata);
                setBackgroundFromCover(mediaMetadata);
            }
        });
    }

    private void setMetadata(MediaMetadata mediaMetadata) {
        playerMediaTitleLabel.setText(String.valueOf(mediaMetadata.title));
        playerArtistNameLabel.setText(String.valueOf(mediaMetadata.artist));

        playerMediaTitleLabel.setSelected(true);
        playerArtistNameLabel.setSelected(true);

        playerMediaTitleLabel.setVisibility(mediaMetadata.title != null && !Objects.equals(mediaMetadata.title, "") ? View.VISIBLE : View.GONE);
        playerArtistNameLabel.setVisibility(mediaMetadata.artist != null && !Objects.equals(mediaMetadata.artist, "") ? View.VISIBLE : View.GONE);
    }

    private void setMediaInfo(MediaMetadata mediaMetadata) {
        if (mediaMetadata.extras != null) {
            String extension = mediaMetadata.extras.getString("suffix", getString(R.string.player_media_format_unknown));
            int bitrate = mediaMetadata.extras.getInt("bitrate", 0);

            playerMediaExtension.setText(extension);

            if (bitrate == 0) {
                playerMediaBitrate.setVisibility(View.GONE);
            } else {
                playerMediaBitrate.setVisibility(View.VISIBLE);
                playerMediaBitrate.setText(getString(R.string.player_media_bitrate, bitrate));
            }
        }

        boolean isTranscodingExtension = !MusicUtil.getTranscodingFormatPreference().equals("raw");
        boolean isTranscodingBitrate = !MusicUtil.getBitratePreference().equals("0");

        if (isTranscodingExtension || isTranscodingBitrate) {
            playerMediaExtension.setText(R.string.player_media_transcoding);
            // The bitrate label may have been hidden just above; "Transcoding"
            // and "requested" only read as a pair when both are on screen.
            playerMediaBitrate.setVisibility(View.VISIBLE);
            playerMediaBitrate.setText(R.string.player_media_transcoding_requested);
        }

        playerTrackInfo.setOnClickListener(view -> {
            TrackInfoDialog dialog = TrackInfoDialog.newInstance(mediaMetadata);
            dialog.show(activity.getSupportFragmentManager(), null);
        });
    }

    private void setMediaControllerUI(MediaBrowser mediaBrowser) {
        initPlaybackSpeedButton(mediaBrowser);

        if (mediaBrowser.getMediaMetadata().extras != null) {
            switch (mediaBrowser.getMediaMetadata().extras.getString("type", Constants.MEDIA_TYPE_MUSIC)) {
                case Constants.MEDIA_TYPE_PODCAST:
                    bind.getRoot().setShowShuffleButton(false);
                    bind.getRoot().setShowRewindButton(true);
                    bind.getRoot().setShowPreviousButton(false);
                    bind.getRoot().setShowNextButton(false);
                    bind.getRoot().setShowFastForwardButton(true);
                    bind.getRoot().setRepeatToggleModes(RepeatModeUtil.REPEAT_TOGGLE_MODE_NONE);
                    bind.getRoot().findViewById(R.id.player_playback_speed_button).setVisibility(View.VISIBLE);
                    bind.getRoot().findViewById(R.id.player_skip_silence_toggle_button).setVisibility(View.VISIBLE);
                    bind.getRoot().findViewById(R.id.button_favorite).setVisibility(View.GONE);
                    playerDislikeButton.setVisibility(View.GONE);
                    playerInstantMixButton.setVisibility(View.GONE);
                    playerAddToPlaylistButton.setVisibility(View.GONE);
                    playerDownloadButton.setVisibility(View.GONE);
                    setPlaybackParameters(mediaBrowser);
                    break;
                case Constants.MEDIA_TYPE_RADIO:
                    bind.getRoot().setShowShuffleButton(false);
                    bind.getRoot().setShowRewindButton(false);
                    bind.getRoot().setShowPreviousButton(false);
                    bind.getRoot().setShowNextButton(false);
                    bind.getRoot().setShowFastForwardButton(false);
                    bind.getRoot().setRepeatToggleModes(RepeatModeUtil.REPEAT_TOGGLE_MODE_NONE);
                    bind.getRoot().findViewById(R.id.player_playback_speed_button).setVisibility(View.GONE);
                    bind.getRoot().findViewById(R.id.player_skip_silence_toggle_button).setVisibility(View.GONE);
                    bind.getRoot().findViewById(R.id.button_favorite).setVisibility(View.GONE);
                    playerDislikeButton.setVisibility(View.GONE);
                    playerInstantMixButton.setVisibility(View.GONE);
                    playerAddToPlaylistButton.setVisibility(View.GONE);
                    playerDownloadButton.setVisibility(View.GONE);
                    setPlaybackParameters(mediaBrowser);
                    break;
                case Constants.MEDIA_TYPE_MUSIC:
                default:
                    bind.getRoot().setShowShuffleButton(true);
                    bind.getRoot().setShowRewindButton(false);
                    bind.getRoot().setShowPreviousButton(true);
                    bind.getRoot().setShowNextButton(true);
                    bind.getRoot().setShowFastForwardButton(false);
                    bind.getRoot().setRepeatToggleModes(RepeatModeUtil.REPEAT_TOGGLE_MODE_ALL | RepeatModeUtil.REPEAT_TOGGLE_MODE_ONE);
                    bind.getRoot().findViewById(R.id.player_playback_speed_button).setVisibility(View.GONE);
                    bind.getRoot().findViewById(R.id.player_skip_silence_toggle_button).setVisibility(View.GONE);
                    bind.getRoot().findViewById(R.id.button_favorite).setVisibility(View.VISIBLE);
                    playerDislikeButton.setVisibility(View.VISIBLE);
                    playerInstantMixButton.setVisibility(View.VISIBLE);
                    playerAddToPlaylistButton.setVisibility(View.VISIBLE);
                    playerDownloadButton.setVisibility(View.VISIBLE);
                    resetPlaybackParameters(mediaBrowser);
                    break;
            }
        }
    }

    private void initCoverLyricsSlideView() {
        playerMediaCoverViewPager.setOrientation(ViewPager2.ORIENTATION_HORIZONTAL);
        playerMediaCoverViewPager.setAdapter(new PlayerControllerHorizontalPager(this));

        /*
         * Paging is driven from the toggle button only. Swiping the cover
         * sideways changes track now, and a pager with user input enabled both
         * swallowed that gesture and — because its RecyclerView disallows the
         * parent while it drags — kept vertical drags over the cover from
         * reaching the sheet.
         */
        playerMediaCoverViewPager.setUserInputEnabled(false);

        setLyricsToggleState(false);

        playerMediaCoverViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);

                setLyricsToggleState(position == 1);

                /*
                 * Nothing else to do here. The lyrics page used to switch the
                 * sheet and the queue pager off so the words could be scrolled,
                 * which left the whole player unable to be dragged up or down
                 * for as long as they were showing. Both stay on now: the lyrics
                 * view claims only the drags it can actually scroll with (see
                 * SwipeGestureUtil.claimVerticalScroll), so the text scrolls and
                 * everything around it still moves the player.
                 */
            }
        });
    }

    private void initMediaListenable() {
        /*
         * The favourite button now also lives in the notification, and the
         * track object here is a separate copy that knows nothing about a tap
         * made there. Redraw from the shared state whenever it changes for the
         * track this sheet is showing.
         */
        FavoriteState.changes().observe(getViewLifecycleOwner(), mediaId -> {
            Child media = playerBottomSheetViewModel.getLiveMedia().getValue();

            if (media == null || !media.getId().equals(mediaId)) return;

            FavoriteState.apply(media);
            setRatingButtonsState(media);
        });

        playerBottomSheetViewModel.getLiveMedia().observe(getViewLifecycleOwner(), media -> {
            if (media != null) {
                /* It may have been favourited from the notification while this was off screen */
                FavoriteState.apply(media);
                setRatingButtonsState(media);

                buttonFavorite.setOnClickListener(v -> {
                    playerBottomSheetViewModel.setFavorite(requireContext(), media);
                    setRatingButtonsState(media);
                    LikeAnimation.play(buttonFavorite, media.getStarred() != null);
                });
                playerDislikeButton.setOnClickListener(v -> {
                    playerBottomSheetViewModel.setDislike(media);
                    setRatingButtonsState(media);
                });
                playerInstantMixButton.setOnClickListener(v -> playerBottomSheetViewModel
                        .getMediaInstantMix(media)
                        .observe(getViewLifecycleOwner(), this::answerInstantMix));
                playerAddToPlaylistButton.setOnClickListener(v -> {
                    Bundle bundle = new Bundle();
                    bundle.putParcelable(Constants.TRACK_OBJECT, media);

                    PlaylistChooserDialog dialog = new PlaylistChooserDialog();
                    dialog.setArguments(bundle);
                    dialog.show(requireActivity().getSupportFragmentManager(), null);
                });
                setDownloadButtonState(media);
                playerDownloadButton.setOnClickListener(v -> toggleDownload(media));
                buttonFavorite.setOnLongClickListener(v -> {
                    Bundle bundle = new Bundle();
                    bundle.putParcelable(Constants.TRACK_OBJECT, media);

                    RatingDialog dialog = new RatingDialog();
                    dialog.setArguments(bundle);
                    dialog.show(requireActivity().getSupportFragmentManager(), null);

                    return true;
                });

                if (getActivity() != null) {
                    playerBottomSheetViewModel.refreshMediaInfo(media);
                }
            }
        });
    }

    /**
     * Liking and disliking are mutually exclusive, so both buttons are redrawn
     * from the track whenever either of them is used.
     */
    private void setRatingButtonsState(Child media) {
        buttonFavorite.setChecked(media.getStarred() != null);
        playerDislikeButton.setImageResource(PlayerBottomSheetViewModel.isDisliked(media)
                ? R.drawable.ic_thumb_down
                : R.drawable.ic_thumb_down_outlined);
    }

    /**
     * The button carries both halves of the action: an arrow while the track is
     * only on the server, a tick once it is on the device and tapping it would
     * give the copy back.
     */
    private void setDownloadButtonState(Child media) {
        boolean downloaded = DownloadUtil.getDownloadTracker(requireContext()).isDownloaded(media.getId());

        playerDownloadButton.setImageResource(downloaded ? R.drawable.ic_download_done : R.drawable.ic_download);
        playerDownloadButton.setContentDescription(getString(downloaded
                ? R.string.player_download_button_downloaded
                : R.string.player_download_button));
    }

    /**
     * Puts the mix in after the current track, and says so. The queue is on the
     * other page of the player, so without a word the tap looked like it had
     * done nothing - and a failed or empty mix really had.
     */
    private void answerInstantMix(@Nullable List<Child> mix) {
        if (getView() == null) return;

        // null is a failed request, and nothing similar is nothing to add.
        if (mix == null) {
            Snackbar.make(requireView(), R.string.player_instant_mix_failed, Snackbar.LENGTH_SHORT).show();
        } else if (mix.isEmpty()) {
            Snackbar.make(requireView(), R.string.player_instant_mix_empty, Snackbar.LENGTH_SHORT).show();
        } else {
            MediaManager.enqueue(mediaBrowserListenableFuture, mix, true);
            String added = getResources().getQuantityString(R.plurals.player_instant_mix_added, mix.size(), mix.size());
            Snackbar.make(requireView(), added, Snackbar.LENGTH_SHORT).show();
        }
    }

    private void toggleDownload(Child media) {
        if (DownloadUtil.getDownloadTracker(requireContext()).isDownloaded(media.getId())) {
            DownloadUtil.getDownloadTracker(requireContext()).remove(MappingUtil.mapDownload(media), new Download(media));
            Snackbar.make(requireView(), R.string.player_download_removed, Snackbar.LENGTH_SHORT).show();
        } else {
            /*
             * The tick only follows once the download reaches a terminal state
             * and DownloaderService writes it back, so the snackbar is what
             * answers the tap; the icon catches up on the next track change.
             */
            DownloadUtil.getDownloadTracker(requireContext()).download(MappingUtil.mapDownload(media), new Download(media));
            Snackbar.make(requireView(), R.string.player_download_started, Snackbar.LENGTH_SHORT).show();
        }

        setDownloadButtonState(media);
    }

    /*
     * The title and the artist name read what they open at the moment of the
     * tap. A listener built from each value as it arrived was only ever
     * replaced, never taken away, so while the current track's record was on
     * its way - or on a track that had none - the name opened the page of the
     * track before it.
     */
    private void initMediaLabelButton() {
        playerMediaTitleLabel.setOnClickListener(view -> {
            AlbumID3 album = playerBottomSheetViewModel.getLiveAlbum().getValue();
            if (album == null) return;

            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.ALBUM_OBJECT, album);
            NavHostFragment.findNavController(this).navigate(R.id.albumPageFragment, bundle);
            activity.collapseBottomSheetDelayed();
        });
    }

    private void initArtistLabelButton() {
        playerArtistNameLabel.setOnClickListener(view -> {
            ArtistID3 artist = playerBottomSheetViewModel.getLiveArtist().getValue();
            if (artist == null) return;

            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.ARTIST_OBJECT, artist);
            NavHostFragment.findNavController(this).navigate(R.id.artistPageFragment, bundle);
            activity.collapseBottomSheetDelayed();
        });
    }

    private void initPlaybackSpeedButton(MediaBrowser mediaBrowser) {
        playbackSpeedButton.setOnClickListener(view -> {
            float currentSpeed = Preferences.getPlaybackSpeed();

            if (currentSpeed == Constants.MEDIA_PLAYBACK_SPEED_080) {
                mediaBrowser.setPlaybackParameters(new PlaybackParameters(Constants.MEDIA_PLAYBACK_SPEED_100));
                playbackSpeedButton.setText(getString(R.string.player_playback_speed, Constants.MEDIA_PLAYBACK_SPEED_100));
                Preferences.setPlaybackSpeed(Constants.MEDIA_PLAYBACK_SPEED_100);
            } else if (currentSpeed == Constants.MEDIA_PLAYBACK_SPEED_100) {
                mediaBrowser.setPlaybackParameters(new PlaybackParameters(Constants.MEDIA_PLAYBACK_SPEED_125));
                playbackSpeedButton.setText(getString(R.string.player_playback_speed, Constants.MEDIA_PLAYBACK_SPEED_125));
                Preferences.setPlaybackSpeed(Constants.MEDIA_PLAYBACK_SPEED_125);
            } else if (currentSpeed == Constants.MEDIA_PLAYBACK_SPEED_125) {
                mediaBrowser.setPlaybackParameters(new PlaybackParameters(Constants.MEDIA_PLAYBACK_SPEED_150));
                playbackSpeedButton.setText(getString(R.string.player_playback_speed, Constants.MEDIA_PLAYBACK_SPEED_150));
                Preferences.setPlaybackSpeed(Constants.MEDIA_PLAYBACK_SPEED_150);
            } else if (currentSpeed == Constants.MEDIA_PLAYBACK_SPEED_150) {
                mediaBrowser.setPlaybackParameters(new PlaybackParameters(Constants.MEDIA_PLAYBACK_SPEED_175));
                playbackSpeedButton.setText(getString(R.string.player_playback_speed, Constants.MEDIA_PLAYBACK_SPEED_175));
                Preferences.setPlaybackSpeed(Constants.MEDIA_PLAYBACK_SPEED_175);
            } else if (currentSpeed == Constants.MEDIA_PLAYBACK_SPEED_175) {
                mediaBrowser.setPlaybackParameters(new PlaybackParameters(Constants.MEDIA_PLAYBACK_SPEED_200));
                playbackSpeedButton.setText(getString(R.string.player_playback_speed, Constants.MEDIA_PLAYBACK_SPEED_200));
                Preferences.setPlaybackSpeed(Constants.MEDIA_PLAYBACK_SPEED_200);
            } else if (currentSpeed == Constants.MEDIA_PLAYBACK_SPEED_200) {
                mediaBrowser.setPlaybackParameters(new PlaybackParameters(Constants.MEDIA_PLAYBACK_SPEED_080));
                playbackSpeedButton.setText(getString(R.string.player_playback_speed, Constants.MEDIA_PLAYBACK_SPEED_080));
                Preferences.setPlaybackSpeed(Constants.MEDIA_PLAYBACK_SPEED_080);
            }
        });

        skipSilenceToggleButton.setOnClickListener(view -> {
            Preferences.setSkipSilenceMode(!skipSilenceToggleButton.isChecked());
        });
    }

    public void goToControllerPage() {
        playerMediaCoverViewPager.animate().cancel();
        playerMediaCoverViewPager.setAlpha(1f);
        playerMediaCoverViewPager.setCurrentItem(0, false);
    }

    public void goToLyricsPage() {
        fadeToPage(1);
    }

    private void setPlaybackParameters(MediaBrowser mediaBrowser) {
        Button playbackSpeedButton = bind.getRoot().findViewById(R.id.player_playback_speed_button);
        float currentSpeed = Preferences.getPlaybackSpeed();
        boolean skipSilence = Preferences.isSkipSilenceMode();

        mediaBrowser.setPlaybackParameters(new PlaybackParameters(currentSpeed));
        playbackSpeedButton.setText(getString(R.string.player_playback_speed, currentSpeed));

        // TODO Skippare il silenzio
        skipSilenceToggleButton.setChecked(skipSilence);
    }

    private void resetPlaybackParameters(MediaBrowser mediaBrowser) {
        mediaBrowser.setPlaybackParameters(new PlaybackParameters(Constants.MEDIA_PLAYBACK_SPEED_100));
        // TODO Resettare lo skip del silenzio
    }
}