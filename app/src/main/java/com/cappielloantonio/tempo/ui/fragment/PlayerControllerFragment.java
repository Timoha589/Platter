package com.cappielloantonio.tempo.ui.fragment;

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
import androidx.constraintlayout.widget.Guideline;
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
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
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
    private static final long SLIDE_DURATION = 380;

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
     * The player is painted with a slope between the two strongest colours of
     * the artwork, from its top left corner to its bottom right one, falling off
     * into the canvas black so the transport controls at the foot keep the
     * contrast they were designed against - the same background the desktop
     * player has. Both the drawable and the two colours it is currently showing
     * are held so a track change can be tweened rather than cut.
     */
    private static final long BACKGROUND_DURATION = 800;

    private GradientDrawable playerBackground;
    private ValueAnimator playerBackgroundAnimator;
    @ColorInt
    private int playerBackgroundFirst;
    @ColorInt
    private int playerBackgroundSecond;
    @ColorInt
    private int playerBackgroundFirstTarget;
    @ColorInt
    private int playerBackgroundSecondTarget;
    private CustomTarget<Bitmap> coverColorTarget;

    /*
     * Landscape only. Without the words the player and the cover are a pair,
     * close together and centred in the window. With them the player is on the
     * left, where it slides to, and the words take the rest. The four guidelines
     * the layout hangs from are placed here; the player's two slide, and the
     * cover's page - which becomes the words - is put in its place at once, while
     * it is faded out and nothing shows of it.
     */
    private static final float PAGE_MARGIN_DP = 24f;
    private static final float PAIR_GAP_DP = 28f;
    private static final float WORDS_GAP_DP = 12f;
    private static final float CONTROLS_WIDTH_DP = 360f;
    private static final float CONTROLS_MIN_WIDTH_DP = 280f;

    private Guideline controlsStartGuide;
    private Guideline controlsEndGuide;
    private Guideline pagerStartGuide;
    private Guideline pagerEndGuide;
    private ConstraintLayout landscapeLayout;
    private ValueAnimator slideAnimator;
    private boolean showingWords;

    /** 0 with the cover, 1 with the words, and in between while the player is sliding. */
    private float slide;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        bind = InnerFragmentPlayerControllerBinding.inflate(inflater, container, false);
        View view = bind.getRoot();

        playerBottomSheetViewModel = new ViewModelProvider(requireActivity()).get(PlayerBottomSheetViewModel.class);

        init();
        initLandscapePair();
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

        if (slideAnimator != null) {
            slideAnimator.cancel();
            slideAnimator = null;
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
        playerBackgroundFirst = playerBackgroundFirstTarget = ContextCompat.getColor(requireContext(), R.color.steel);
        playerBackgroundSecond = playerBackgroundSecondTarget = ContextCompat.getColor(requireContext(), R.color.iron);
        playerBackground = new GradientDrawable(GradientDrawable.Orientation.TL_BR, gradientFor(playerBackgroundFirst, playerBackgroundSecond));
        playerBackground.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        // Colours this dark and a slope this long change by a level every few pixels: dithered, the steps are noise, not stripes.
        playerBackground.setDither(true);

        bind.getRoot().setBackground(playerBackground);
        setPlayPauseTint(playerBackgroundFirst);
    }

    /**
     * Landscape draws the play button without the white disc, so there is no
     * background there to tint and the call is a no-op by design.
     */
    private void setPlayPauseTint(@ColorInt int tint) {
        if (playerPlayPauseButton == null || playerPlayPauseButton.getBackground() == null) return;

        playerPlayPauseButton.setBackgroundTintList(ColorStateList.valueOf(CoverColorUtil.toAccentTint(tint)));
    }

    private int[] gradientFor(@ColorInt int first, @ColorInt int second) {
        int canvas = ContextCompat.getColor(requireContext(), R.color.void_black);
        return new int[]{first, second, ColorUtils.blendARGB(second, canvas, 0.7f)};
    }

    /**
     * Pulls the artwork down to a thumbnail purely to count its colours - the
     * player only needs two out of it, and the full-size cover is already being
     * decoded for the cover page.
     */
    private void setBackgroundFromCover(MediaMetadata mediaMetadata) {
        String coverArtId = mediaMetadata.extras != null ? mediaMetadata.extras.getString("coverArtId") : null;
        Object cover = CustomGlideRequest.coverModel(requireContext(), coverArtId, !Preferences.isDataSavingMode());

        if (cover == null) {
            animateBackground(ContextCompat.getColor(requireContext(), R.color.steel), ContextCompat.getColor(requireContext(), R.color.iron));
            return;
        }

        if (coverColorTarget != null) Glide.with(this).clear(coverColorTarget);

        coverColorTarget = new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                if (bind == null) return;

                int[] palette = CoverColorUtil.palette(
                        resource,
                        ContextCompat.getColor(requireContext(), R.color.steel),
                        ContextCompat.getColor(requireContext(), R.color.iron)
                );
                animateBackground(palette[0], palette[1]);
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                if (bind == null) return;
                animateBackground(ContextCompat.getColor(requireContext(), R.color.steel), ContextCompat.getColor(requireContext(), R.color.iron));
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

    private void animateBackground(@ColorInt int first, @ColorInt int second) {
        if (playerBackground == null) return;
        if (first == playerBackgroundFirstTarget && second == playerBackgroundSecondTarget) return;

        if (playerBackgroundAnimator != null) playerBackgroundAnimator.cancel();

        playerBackgroundFirstTarget = first;
        playerBackgroundSecondTarget = second;

        // Both colours turn over together, from wherever the last turn had got to.
        final int fromFirst = playerBackgroundFirst;
        final int fromSecond = playerBackgroundSecond;

        playerBackgroundAnimator = ValueAnimator.ofFloat(0f, 1f);
        playerBackgroundAnimator.setDuration(BACKGROUND_DURATION);
        playerBackgroundAnimator.setInterpolator(new FastOutSlowInInterpolator());
        playerBackgroundAnimator.addUpdateListener(animation -> {
            if (playerBackground == null) return;

            float fraction = (float) animation.getAnimatedValue();
            playerBackgroundFirst = ColorUtils.blendARGB(fromFirst, first, fraction);
            playerBackgroundSecond = ColorUtils.blendARGB(fromSecond, second, fraction);

            playerBackground.setColors(gradientFor(playerBackgroundFirst, playerBackgroundSecond));
            setPlayPauseTint(playerBackgroundFirst);
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

    private void initLandscapePair() {
        landscapeLayout = bind.getRoot().findViewById(R.id.now_playing_media_controller_layout);
        if (landscapeLayout == null) return;

        controlsStartGuide = landscapeLayout.findViewById(R.id.controls_start_guideline);
        controlsEndGuide = landscapeLayout.findViewById(R.id.vertical_guideline);
        pagerStartGuide = landscapeLayout.findViewById(R.id.pager_start_guideline);
        pagerEndGuide = landscapeLayout.findViewById(R.id.pager_end_guideline);

        if (controlsStartGuide == null || pagerStartGuide == null || pagerEndGuide == null) {
            landscapeLayout = null;
            return;
        }

        landscapeLayout.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) placeLandscape(slide);
        });
    }

    /**
     * Puts the guidelines where they belong for this window, [progress] of the
     * way from the pair to the player-and-words. The pair: the player's
     * controls (up to 360dp wide) and the cover, a square as tall as the room
     * between the margins, with a small gap, together in the middle. The words:
     * the controls on the left margin and the words from there to the right one.
     */
    private void placeLandscape(float progress) {
        if (landscapeLayout == null) return;

        float density = getResources().getDisplayMetrics().density;
        float width = landscapeLayout.getWidth();
        float height = landscapeLayout.getHeight();
        if (width <= 0 || height <= 0) return;

        float margin = PAGE_MARGIN_DP * density;
        float gap = PAIR_GAP_DP * density;
        float cover = Math.max(0f, height - 2 * margin);
        float controls = Math.max(CONTROLS_MIN_WIDTH_DP * density, Math.min(CONTROLS_WIDTH_DP * density, width - cover - gap - 2 * margin));

        // Where the player's text begins, and so where the pair begins.
        float left = Math.max(margin, (width - (controls + gap + cover)) / 2f);

        float pairStart = left - margin;
        float pairEnd = pairStart + controls + 2 * margin;
        float wordsEnd = controls + 2 * margin;

        controlsStartGuide.setGuidelineBegin(Math.round(pairStart * (1f - progress)));
        controlsEndGuide.setGuidelineBegin(Math.round(pairEnd + (wordsEnd - pairEnd) * progress));

        if (showingWords) {
            pagerStartGuide.setGuidelineBegin(Math.round(wordsEnd + WORDS_GAP_DP * density));
            pagerEndGuide.setGuidelineBegin(Math.round(width - margin));
        } else {
            pagerStartGuide.setGuidelineBegin(Math.round(left + controls + gap));
            pagerEndGuide.setGuidelineBegin(Math.round(left + controls + gap + cover));
        }
    }

    private void slideLandscape(boolean words) {
        if (landscapeLayout == null || showingWords == words) return;

        showingWords = words;

        if (slideAnimator != null) slideAnimator.cancel();

        slideAnimator = ValueAnimator.ofFloat(slide, words ? 1f : 0f);
        slideAnimator.setDuration(SLIDE_DURATION);
        slideAnimator.setInterpolator(new FastOutSlowInInterpolator());
        slideAnimator.addUpdateListener(animation -> {
            slide = (float) animation.getAnimatedValue();
            placeLandscape(slide);
        });
        slideAnimator.start();
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
                slideLandscape(position == 1);

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