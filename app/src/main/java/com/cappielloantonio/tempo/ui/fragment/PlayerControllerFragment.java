package com.cappielloantonio.tempo.ui.fragment;

import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.res.ColorStateList;
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
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
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
import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.InnerFragmentPlayerControllerBinding;
import com.cappielloantonio.tempo.helper.view.AutoMixTimeBar;
import com.cappielloantonio.tempo.helper.view.PlayerGradient;
import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.service.AutoMixTimeline;
import com.cappielloantonio.tempo.service.CoverTransition;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.subsonic.models.ArtistID3;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.fragment.bottomsheetdialog.ArtistPickerBottomSheetDialog;
import com.cappielloantonio.tempo.ui.fragment.bottomsheetdialog.EqualizerBottomSheetDialog;
import com.cappielloantonio.tempo.ui.fragment.bottomsheetdialog.PlayerMoreBottomSheetDialog;
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
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.Collections;
import java.util.Objects;

@UnstableApi
public class PlayerControllerFragment extends Fragment implements PlayerMoreBottomSheetDialog.Host {
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
    private ImageButton playerMoreButton;
    private ImageButton playerInstantMixButton;
    private ImageButton playerLyricsToggleButton;
    private ImageButton playerPlayPauseButton;
    private AutoMixTimeBar timeBar;
    private View autoMixLabel;

    private MainActivity activity;
    private PlayerBottomSheetViewModel playerBottomSheetViewModel;
    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    /** What is playing, as the player knows it: the more sheet reads the title, the kind and the track info from it. */
    private MediaMetadata currentMetadata = MediaMetadata.EMPTY;

    private PlayerGradient playerGradient;

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
        playerGradient = new PlayerGradient(view, Glide.with(this), this::setPlayPauseTint);
        initQuickActionView();
        initCoverLyricsSlideView();
        initMediaListenable();
        initMediaLabelButton();
        initArtistLabelButton();

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        AutoMixTimeline.updates().observe(getViewLifecycleOwner(), this::showMixTimeline);
        CoverTransition.updates().observe(getViewLifecycleOwner(), overlap -> {
            if (bind == null) return;
            Player player = bind.nowPlayingMediaControllerView.getPlayer();
            if (player != null) {
                playerGradient.updateOverlap(player, overlap);
                showAutoMixLabel(player, overlap);
            }
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
        releaseBrowser();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        playerGradient.dispose();
        playerGradient = null;

        if (slideAnimator != null) {
            slideAnimator.cancel();
            slideAnimator = null;
        }

        timeBar = null;
        autoMixLabel = null;
        bind = null;
    }

    private void init() {
        timeBar = bind.getRoot().findViewById(R.id.exo_progress);
        autoMixLabel = bind.getRoot().findViewById(R.id.player_auto_mix_label);
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
        playerMoreButton = bind.getRoot().findViewById(R.id.player_more_button);
        playerInstantMixButton = bind.getRoot().findViewById(R.id.player_instant_mix_button);
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
        playerMoreButton.setOnClickListener(view -> PlayerMoreBottomSheetDialog.show(this));
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

    /**
     * Landscape draws the play button without the white disc, so there is no
     * background there to tint and the call is a no-op by design.
     */
    private void setPlayPauseTint(@ColorInt int tint) {
        if (playerPlayPauseButton == null || playerPlayPauseButton.getBackground() == null) return;

        playerPlayPauseButton.setBackgroundTintList(ColorStateList.valueOf(CoverColorUtil.toAccentTint(tint)));
    }

    private void initializeBrowser() {
        mediaBrowserListenableFuture = new MediaBrowser.Builder(requireContext(), new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
    }

    private void releaseBrowser() {
        ListenableFuture<MediaBrowser> future = mediaBrowserListenableFuture;
        mediaBrowserListenableFuture = null;
        if (bind != null) bind.nowPlayingMediaControllerView.setPlayer(null);
        if (future != null) MediaBrowser.releaseFuture(future);
    }

    private void bindMediaController() {
        ListenableFuture<MediaBrowser> future = mediaBrowserListenableFuture;
        future.addListener(() -> {
            if (future != mediaBrowserListenableFuture || bind == null) return;
            try {
                MediaBrowser mediaBrowser = future.get();

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
        CoverTransition overlap = CoverTransition.updates().getValue();
        playerGradient.updateTrack(mediaBrowser, overlap);
        showAutoMixLabel(mediaBrowser, overlap);
        showMixTimeline(AutoMixTimeline.updates().getValue());

        mediaBrowser.addListener(new Player.Listener() {
            @Override
            public void onEvents(@NonNull Player player, @NonNull Player.Events events) {
                if (bind == null || bind.nowPlayingMediaControllerView.getPlayer() != player) return;
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED)) {
                    showMixTimeline(AutoMixTimeline.updates().getValue());
                    showAutoMixLabel(player, CoverTransition.updates().getValue());
                }
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_MEDIA_METADATA_CHANGED,
                        Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)) {
                    playerGradient.updateTrack(player, CoverTransition.updates().getValue());
                }
            }

            @Override
            public void onMediaMetadataChanged(@NonNull MediaMetadata mediaMetadata) {
                if (bind == null || bind.nowPlayingMediaControllerView.getPlayer() != mediaBrowser) return;
                setMediaControllerUI(mediaBrowser);
                setMetadata(mediaMetadata);
                setMediaInfo(mediaMetadata);
            }
        });
    }

    private void showAutoMixLabel(@NonNull Player player, @Nullable CoverTransition overlap) {
        MediaItem item = player.getCurrentMediaItem();
        boolean mixing = overlap != null && overlap.getMode() == CoverTransition.Mode.DISSOLVE && item != null
                && player.getCurrentMediaItemIndex() == overlap.getToIndex() && item.mediaId.equals(overlap.getToId());
        autoMixLabel.setVisibility(mixing ? View.VISIBLE : View.GONE);
    }

    private void showMixTimeline(@Nullable AutoMixTimeline.State state) {
        if (bind == null || timeBar == null) return;
        Player player = bind.nowPlayingMediaControllerView.getPlayer();
        MediaItem item = player != null ? player.getCurrentMediaItem() : null;
        timeBar.showMixSegments(state != null && item != null
                ? state.segmentsFor(player.getCurrentMediaItemIndex(), item.mediaId)
                : Collections.emptyList());
    }

    private void setMetadata(MediaMetadata mediaMetadata) {
        currentMetadata = mediaMetadata;

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
                    playerInstantMixButton.setVisibility(View.GONE);
                    refreshPlaybackSpeedControls();
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
                    playerInstantMixButton.setVisibility(View.GONE);
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
                    playerInstantMixButton.setVisibility(View.VISIBLE);
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
                playerInstantMixButton.setOnClickListener(v -> activity.enqueueInstantMix(media));
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
     * Liking and disliking are mutually exclusive: a dislike takes the heart
     * away, so the heart is redrawn from the track whenever either is used.
     */
    private void setRatingButtonsState(Child media) {
        buttonFavorite.setChecked(media.getStarred() != null);
    }

    // --- the more sheet ----------------------------------------------------------------------

    @Override
    public CharSequence moreTitle() {
        return currentMetadata.title;
    }

    @Override
    public CharSequence moreArtist() {
        return currentMetadata.artist;
    }

    @Override
    public boolean moreIsMusic() {
        String type = currentMetadata.extras != null ? currentMetadata.extras.getString("type", Constants.MEDIA_TYPE_MUSIC) : Constants.MEDIA_TYPE_MUSIC;
        return Constants.MEDIA_TYPE_MUSIC.equals(type) && playerBottomSheetViewModel.getLiveMedia().getValue() != null;
    }

    @Override
    public boolean moreIsDownloaded() {
        Child media = playerBottomSheetViewModel.getLiveMedia().getValue();
        return media != null && DownloadUtil.getDownloadTracker(requireContext()).isDownloaded(media.getId());
    }

    @Override
    public boolean moreIsDisliked() {
        return PlayerBottomSheetViewModel.isDisliked(playerBottomSheetViewModel.getLiveMedia().getValue());
    }

    @Override
    public void onMoreAction(@NonNull PlayerMoreBottomSheetDialog.Action action) {
        Child media = playerBottomSheetViewModel.getLiveMedia().getValue();

        switch (action) {
            case ADD_TO_PLAYLIST: {
                if (media == null) return;
                Bundle bundle = new Bundle();
                bundle.putParcelable(Constants.TRACK_OBJECT, media);

                PlaylistChooserDialog dialog = new PlaylistChooserDialog();
                dialog.setArguments(bundle);
                dialog.show(requireActivity().getSupportFragmentManager(), null);
                break;
            }
            case DOWNLOAD:
                if (media != null) toggleDownload(media);
                break;
            case DISLIKE:
                if (media == null) return;
                playerBottomSheetViewModel.setDislike(media);
                setRatingButtonsState(media);
                activity.showFeedback(PlayerBottomSheetViewModel.isDisliked(media)
                        ? R.string.player_disliked
                        : R.string.player_dislike_removed);
                break;
            case EQUALIZER:
                EqualizerBottomSheetDialog.show(requireActivity().getSupportFragmentManager());
                break;
            case TRACK_INFO:
                TrackInfoDialog.newInstance(currentMetadata).show(activity.getSupportFragmentManager(), null);
                break;
        }
    }

    private void toggleDownload(Child media) {
        if (DownloadUtil.getDownloadTracker(requireContext()).isDownloaded(media.getId())) {
            DownloadUtil.getDownloadTracker(requireContext()).remove(MappingUtil.mapDownload(media), new Download(media));
            activity.showFeedback(R.string.player_download_removed);
        } else {
            DownloadUtil.getDownloadTracker(requireContext()).download(MappingUtil.mapDownload(media), new Download(media));
            activity.showFeedback(R.string.player_download_started);
        }
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
        // A joint credit offers its artists one by one: the joint one the server keeps has no page worth opening.
        playerArtistNameLabel.setOnClickListener(view -> {
            ArtistID3 artist = playerBottomSheetViewModel.getLiveArtist().getValue();
            if (artist == null) return;

            ArtistPickerBottomSheetDialog.openCredit(this, playerArtistNameLabel.getText().toString(), artist, null);
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

    public void goToLyricsPage() {
        fadeToPage(1);
    }

    private void refreshPlaybackSpeedControls() {
        Button playbackSpeedButton = bind.getRoot().findViewById(R.id.player_playback_speed_button);
        float currentSpeed = Preferences.getPlaybackSpeed();
        boolean skipSilence = Preferences.isSkipSilenceMode();

        playbackSpeedButton.setText(getString(R.string.player_playback_speed, currentSpeed));

        // TODO Skippare il silenzio
        skipSilenceToggleButton.setChecked(skipSilence);
    }

}
