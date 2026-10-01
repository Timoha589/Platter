package com.cappielloantonio.tempo.ui.fragment;

import static com.cappielloantonio.tempo.helper.view.SkeletonView.finishLoading;
import static com.cappielloantonio.tempo.helper.view.SkeletonView.startLoading;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SnapHelper;
import androidx.viewpager2.widget.ViewPager2;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentHomeTabMusicBinding;
import com.cappielloantonio.tempo.helper.recyclerview.ContentRecyclerView;
import com.cappielloantonio.tempo.helper.recyclerview.CustomLinearSnapHelper;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.interfaces.PlaylistCallback;
import com.cappielloantonio.tempo.model.HomeSector;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.Share;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.adapter.AlbumAdapter;
import com.cappielloantonio.tempo.ui.adapter.AlbumHorizontalAdapter;
import com.cappielloantonio.tempo.ui.adapter.ArtistHorizontalAdapter;
import com.cappielloantonio.tempo.ui.adapter.DiscoverSongAdapter;
import com.cappielloantonio.tempo.ui.adapter.PlaylistHorizontalAdapter;
import com.cappielloantonio.tempo.ui.adapter.ShareHorizontalAdapter;
import com.cappielloantonio.tempo.ui.adapter.SimilarTrackAdapter;
import com.cappielloantonio.tempo.ui.adapter.SongHorizontalAdapter;
import com.cappielloantonio.tempo.ui.adapter.YearAdapter;
import com.cappielloantonio.tempo.ui.dialog.PlaylistEditorDialog;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.Preferences;
import com.cappielloantonio.tempo.util.UIUtil;
import com.cappielloantonio.tempo.viewmodel.HomeViewModel;
import com.cappielloantonio.tempo.wave.WaveBatch;
import com.cappielloantonio.tempo.wave.WaveClient;
import com.cappielloantonio.tempo.wave.WaveResult;
import com.cappielloantonio.tempo.wave.WaveState;
import com.google.android.material.snackbar.Snackbar;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import kotlin.Unit;

@UnstableApi
public class HomeTabMusicFragment extends Fragment implements ClickCallback {
    private static final String TAG = "HomeFragment";

    private FragmentHomeTabMusicBinding bind;
    private MainActivity activity;
    private HomeViewModel homeViewModel;

    private DiscoverSongAdapter discoverSongAdapter;
    private SimilarTrackAdapter similarMusicAdapter;
    private SongHorizontalAdapter starredSongAdapter;
    private SongHorizontalAdapter topSongAdapter;
    private AlbumHorizontalAdapter starredAlbumAdapter;
    private ArtistHorizontalAdapter starredArtistAdapter;
    private AlbumAdapter recentlyAddedAlbumAdapter;
    private AlbumAdapter recentlyPlayedAlbumAdapter;
    private AlbumAdapter mostPlayedAlbumAdapter;
    private AlbumHorizontalAdapter newReleasesAlbumAdapter;
    private YearAdapter yearAdapter;
    private PlaylistHorizontalAdapter playlistHorizontalAdapter;
    private ShareHorizontalAdapter shareHorizontalAdapter;

    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    /* True from the tap on My Wave until its first batch is in (or failed). */
    private boolean waveStarting = false;

    /* Redraws the My Wave card from whatever the player just did. */
    private final Player.Listener waveListener = new Player.Listener() {
        @Override
        public void onEvents(@NonNull Player player, @NonNull Player.Events events) {
            updateWave(player);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        bind = FragmentHomeTabMusicBinding.inflate(inflater, container, false);
        View view = bind.getRoot();
        homeViewModel = new ViewModelProvider(requireActivity()).get(HomeViewModel.class);

        init();

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        homeViewModel.loadHomeSectorList();

        initWave();
        initDiscoverSongSlideView();
        initSimilarSongView();
        initStarredTracksView();
        initStarredAlbumsView();
        initStarredArtistsView();
        initMostPlayedAlbumView();
        initRecentPlayedAlbumView();
        initNewReleasesView();
        initYearSongView();
        initRecentAddedAlbumView();
        initTopSongsView();
        initPinnedPlaylistsView();
        initSharesView();
        preferPageOverRails();

        reorder();
    }

    /*
     * The rails sit inside a vertical page and, at the default slop, claim the
     * gesture as soon as the finger drifts 8dp sideways - so a thumb that arcs
     * while flicking the page, or while landing on it to stop a fling, drags a
     * rail instead. Paging slop makes a rail wait for twice that distance, which
     * leaves the page every gesture that is not clearly horizontal.
     */
    private void preferPageOverRails() {
        RecyclerView[] rails = {
                bind.similarTracksRecyclerView,
                bind.topSongsRecyclerView,
                bind.starredTracksRecyclerView,
                bind.starredAlbumsRecyclerView,
                bind.starredArtistsRecyclerView,
                bind.newReleasesRecyclerView,
                bind.yearsRecyclerView,
                bind.mostPlayedAlbumsRecyclerView,
                bind.recentlyPlayedAlbumsRecyclerView,
                bind.recentlyAddedAlbumsRecyclerView,
                bind.sharesRecyclerView
        };
        for (RecyclerView rail : rails) {
            rail.setScrollingTouchSlop(RecyclerView.TOUCH_SLOP_PAGING);
        }

        // ViewPager2 keeps its own RecyclerView as its only child. With a single
        // suggestion it is a list that fits, and should not wobble sideways.
        View pager = bind.discoverSongViewPager.getChildAt(0);
        if (pager instanceof RecyclerView) {
            ((RecyclerView) pager).setScrollingTouchSlop(RecyclerView.TOUCH_SLOP_PAGING);
            // As the rails' in the layout: a sideways nested scroll started on
            // the pager cut the toolbar out of the page's vertical one, so a
            // drag begun on it scrolled the page without hiding the toolbar.
            pager.setNestedScrollingEnabled(false);
            ContentRecyclerView.stretchOnlyWithContent((RecyclerView) pager);
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        initializeMediaBrowser();
        if (WaveClient.isAvailable()) {
            connectWave();
            WaveClient.INSTANCE.prepare();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshSharesView();
    }

    @Override
    public void onStop() {
        releaseMediaBrowser();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind = null;
    }

    private void init() {
        bind.discoveryTextViewRefreshable.setOnLongClickListener(v -> {
            homeViewModel.refreshDiscoverySongSample(getViewLifecycleOwner());
            return true;
        });

        bind.discoveryTextViewClickable.setOnClickListener(v -> {
            homeViewModel.getRandomShuffleSample().observe(getViewLifecycleOwner(), songs -> {

                if (!songs.isEmpty()) {
                    MediaManager.startQueue(mediaBrowserListenableFuture, songs, 0);
                    activity.setBottomSheetInPeek(true);
                }
            });
        });

        bind.similarTracksTextViewRefreshable.setOnLongClickListener(v -> {
            homeViewModel.refreshSimilarSongSample(getViewLifecycleOwner());
            return true;
        });

        bind.starredTracksTextViewClickable.setOnClickListener(v -> activity.navController.navigate(R.id.action_homeFragment_to_likedTracksFragment));

        bind.starredAlbumsTextViewClickable.setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putString(Constants.ALBUM_STARRED, Constants.ALBUM_STARRED);
            activity.navController.navigate(R.id.action_homeFragment_to_albumListPageFragment, bundle);
        });

        bind.starredArtistsTextViewClickable.setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putString(Constants.ARTIST_STARRED, Constants.ARTIST_STARRED);
            activity.navController.navigate(R.id.action_homeFragment_to_artistListPageFragment, bundle);
        });

        bind.recentlyAddedAlbumsTextViewClickable.setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putString(Constants.ALBUM_RECENTLY_ADDED, Constants.ALBUM_RECENTLY_ADDED);
            activity.navController.navigate(R.id.action_homeFragment_to_albumListPageFragment, bundle);
        });

        bind.recentlyPlayedAlbumsTextViewClickable.setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putString(Constants.ALBUM_RECENTLY_PLAYED, Constants.ALBUM_RECENTLY_PLAYED);
            activity.navController.navigate(R.id.action_homeFragment_to_albumListPageFragment, bundle);
        });

        bind.mostPlayedAlbumsTextViewClickable.setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putString(Constants.ALBUM_MOST_PLAYED, Constants.ALBUM_MOST_PLAYED);
            activity.navController.navigate(R.id.action_homeFragment_to_albumListPageFragment, bundle);
        });

        bind.starredTracksTextViewRefreshable.setOnLongClickListener(v -> {
            homeViewModel.refreshStarredTracks(getViewLifecycleOwner());
            return true;
        });

        bind.starredAlbumsTextViewRefreshable.setOnLongClickListener(v -> {
            homeViewModel.refreshStarredAlbums(getViewLifecycleOwner());
            return true;
        });

        bind.starredArtistsTextViewRefreshable.setOnLongClickListener(v -> {
            homeViewModel.refreshStarredArtists(getViewLifecycleOwner());
            return true;
        });

        bind.recentlyPlayedAlbumsTextViewRefreshable.setOnLongClickListener(v -> {
            homeViewModel.refreshRecentlyPlayedAlbumList(getViewLifecycleOwner());
            return true;
        });

        bind.mostPlayedAlbumsTextViewRefreshable.setOnLongClickListener(v -> {
            homeViewModel.refreshMostPlayedAlbums(getViewLifecycleOwner());
            return true;
        });

        bind.recentlyAddedAlbumsTextViewRefreshable.setOnLongClickListener(v -> {
            homeViewModel.refreshMostRecentlyAddedAlbums(getViewLifecycleOwner());
            return true;
        });

        bind.sharesTextViewRefreshable.setOnLongClickListener(v -> {
            homeViewModel.refreshShares(getViewLifecycleOwner());
            return true;
        });

        bind.gridTracksPreTextView.setOnClickListener(view -> showPopupMenu(view, R.menu.filter_top_songs_popup_menu));
    }

    private void initWave() {
        View card = bind.homeWave.getRoot();
        // My Wave lives on Timoha Premium only; elsewhere there is no card to offer it.
        if (!WaveClient.isAvailable()) {
            card.setVisibility(View.GONE);
            return;
        }
        card.setVisibility(View.VISIBLE);

        // The waves are drawn to the edge; the card's rounded corners cut them.
        card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        card.setClipToOutline(true);

        card.setOnClickListener(v -> onWaveTapped());
        bind.homeWave.wavePlayButton.setOnClickListener(v -> onWaveTapped());

        WaveState.changes().observe(getViewLifecycleOwner(), unit -> updateWave(connectedBrowser()));
        updateWave(null);
    }

    /* Follows the player for as long as the screen is started. */
    private void connectWave() {
        ListenableFuture<MediaBrowser> future = mediaBrowserListenableFuture;
        future.addListener(() -> {
            MediaBrowser browser = connectedBrowser();
            if (browser == null || future != mediaBrowserListenableFuture) return;
            browser.addListener(waveListener);
            updateWave(browser);
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    @Nullable
    private MediaBrowser connectedBrowser() {
        ListenableFuture<MediaBrowser> future = mediaBrowserListenableFuture;
        if (future == null || !future.isDone() || future.isCancelled()) return null;
        try {
            return future.get();
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static String currentId(@Nullable Player player) {
        MediaItem item = player != null ? player.getCurrentMediaItem() : null;
        if (item == null || item.mediaMetadata.extras == null) return null;
        return item.mediaMetadata.extras.getString("id");
    }

    /*
     * The card says what the wave is doing, and nothing else: idle while the
     * player is on something else, the vibe and its tempo while it plays,
     * still while it is paused.
     */
    private void updateWave(@Nullable Player player) {
        if (bind == null) return;

        if (waveStarting) {
            bind.homeWave.waveView.showLoading();
            bind.homeWave.waveSubtitle.setText(R.string.wave_subtitle_loading);
            bind.homeWave.wavePlayButton.setImageDrawable(null);
            bind.homeWave.waveProgress.setVisibility(View.VISIBLE);
            return;
        }
        bind.homeWave.waveProgress.setVisibility(View.GONE);

        String id = currentId(player);
        if (!WaveState.isWaveTrack(id)) {
            bind.homeWave.waveView.showIdle();
            bind.homeWave.waveSubtitle.setText(R.string.wave_subtitle_idle);
            setWaveButton(false);
            return;
        }

        boolean playing = player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED;
        WaveState.Info info = WaveState.info(id);
        if (playing) {
            bind.homeWave.waveView.showPlaying(info != null ? info.getTempo() : null, info != null ? info.getEnergy() : null);
            String vibe = WaveState.vibe();
            bind.homeWave.waveSubtitle.setText(vibe != null && !vibe.isEmpty()
                    ? getString(R.string.wave_subtitle_playing, vibe)
                    : getString(R.string.wave_subtitle_playing_plain));
        } else {
            bind.homeWave.waveView.showPaused();
            bind.homeWave.waveSubtitle.setText(R.string.wave_subtitle_paused);
        }
        setWaveButton(playing);
    }

    private void setWaveButton(boolean playing) {
        bind.homeWave.wavePlayButton.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        bind.homeWave.wavePlayButton.setContentDescription(getString(playing
                ? R.string.wave_pause_description
                : R.string.wave_play_description));
    }

    private void onWaveTapped() {
        MediaBrowser browser = connectedBrowser();

        // The wave is what is playing: the card is its play/pause.
        if (browser != null && WaveState.isWaveTrack(currentId(browser))) {
            if (browser.getPlayWhenReady()) browser.pause();
            else browser.play();
            return;
        }
        if (waveStarting) return;

        waveStarting = true;
        updateWave(browser);
        WaveClient.INSTANCE.start(result -> {
            waveStarting = false;
            if (bind == null) return Unit.INSTANCE;

            if (result instanceof WaveResult.Ok) {
                WaveBatch batch = ((WaveResult.Ok) result).getBatch();
                WaveState.begin(batch);
                MediaManager.startQueue(mediaBrowserListenableFuture, WaveState.songs(batch), 0);
                activity.setBottomSheetInPeek(true);
            } else {
                showWaveFailure((WaveResult.Failed) result);
            }
            updateWave(connectedBrowser());
            return Unit.INSTANCE;
        });
    }

    private void showWaveFailure(WaveResult.Failed failure) {
        String message;
        switch (failure.getReason()) {
            case NOT_CONFIGURED:
                message = getString(R.string.wave_error_not_configured);
                break;
            case UNAVAILABLE:
                message = getString(R.string.wave_error_unavailable,
                        failure.getDetail() != null ? failure.getDetail() : "");
                break;
            default:
                message = getString(R.string.wave_error_unreachable);
                break;
        }
        Snackbar.make(requireView(), message, Snackbar.LENGTH_LONG)
                .setAnchorView(activity.bind.playerBottomSheet)
                .show();
    }

    private void initDiscoverSongSlideView() {
        if (homeViewModel.isHomeSectorHidden(Constants.HOME_SECTOR_DISCOVERY)) return;

        startLoading(bind.homeDiscoverSector, bind.discoverSkeleton, bind.discoverSongViewPager);

        bind.discoverSongViewPager.setOrientation(ViewPager2.ORIENTATION_HORIZONTAL);

        discoverSongAdapter = new DiscoverSongAdapter(this);
        bind.discoverSongViewPager.setAdapter(discoverSongAdapter);
        bind.discoverSongViewPager.setOffscreenPageLimit(1);
        homeViewModel.getDiscoverSongSample(getViewLifecycleOwner()).observe(getViewLifecycleOwner(), songs -> {
            if (bind == null) return;

            discoverSongAdapter.setItems(songs != null ? songs : Collections.emptyList());
            finishLoading(bind.homeDiscoverSector, bind.discoverSkeleton, bind.discoverSongViewPager, songs != null && !songs.isEmpty());
        });

        setSlideViewOffset(bind.discoverSongViewPager, 20, 16);
    }

    private void initSimilarSongView() {
        if (homeViewModel.isHomeSectorHidden(Constants.HOME_SECTOR_MADE_FOR_YOU)) return;

        startLoading(bind.homeSimilarTracksSector, bind.similarTracksSkeleton, bind.similarTracksRecyclerView);

        bind.similarTracksRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        bind.similarTracksRecyclerView.setHasFixedSize(true);

        similarMusicAdapter = new SimilarTrackAdapter(this);
        bind.similarTracksRecyclerView.setAdapter(similarMusicAdapter);
        homeViewModel.getStarredTracksSample(getViewLifecycleOwner()).observe(getViewLifecycleOwner(), songs -> {
            if (bind == null) return;

            similarMusicAdapter.setItems(songs != null ? songs : Collections.emptyList());
            finishLoading(bind.homeSimilarTracksSector, bind.similarTracksSkeleton, bind.similarTracksRecyclerView, songs != null && !songs.isEmpty());
        });

        CustomLinearSnapHelper similarSongSnapHelper = new CustomLinearSnapHelper();
        similarSongSnapHelper.attachToRecyclerView(bind.similarTracksRecyclerView);
    }

    private void initTopSongsView() {
        if (homeViewModel.isHomeSectorHidden(Constants.HOME_SECTOR_TOP_SONGS)) return;

        startLoading(bind.homeGridTracksSector, bind.topSongsSkeleton, bind.topSongsRecyclerView);

        bind.topSongsRecyclerView.setHasFixedSize(true);

        topSongAdapter = new SongHorizontalAdapter(this, true, false, null);
        bind.topSongsRecyclerView.setAdapter(topSongAdapter);
        homeViewModel.getTopSongs().observe(getViewLifecycleOwner(), chronologies -> {
            if (bind == null) return;

            boolean hasContent = chronologies != null && !chronologies.isEmpty();

            // Nothing in the default week yet: the skeleton stays while the
            // next period is asked for.
            if (!hasContent && homeViewModel.widenTopSongsPeriod()) return;

            if (hasContent) {
                bind.topSongsRecyclerView.setLayoutManager(new GridLayoutManager(requireContext(), UIUtil.getSpanCount(chronologies.size(), 5), GridLayoutManager.HORIZONTAL, false));

                List<Child> topSongs = chronologies.stream()
                        .map(cronologia -> (Child) cronologia)
                        .collect(Collectors.toList());

                topSongAdapter.setItems(topSongs);
            } else {
                topSongAdapter.setItems(Collections.emptyList());
            }

            // A period the user picked stays on screen even when empty, with
            // the period picker, so they can pick another one.
            boolean showSector = hasContent || homeViewModel.isTopSongsPeriodChosen();
            finishLoading(bind.homeGridTracksSector, bind.topSongsSkeleton, bind.topSongsRecyclerView, showSector);
            bind.topSongsRecyclerView.setVisibility(hasContent ? View.VISIBLE : View.GONE);
            bind.topSongsEmptyTextView.setVisibility(showSector && !hasContent ? View.VISIBLE : View.GONE);
        });

        homeViewModel.getTopSongsPeriod().observe(getViewLifecycleOwner(), period -> {
            if (bind == null) return;

            bind.gridTracksPreTextView.setText(period == HomeViewModel.TOP_SONGS_LAST_YEAR
                    ? R.string.home_title_last_year
                    : period == HomeViewModel.TOP_SONGS_LAST_MONTH
                    ? R.string.home_title_last_month
                    : R.string.home_title_last_week);
        });

        SnapHelper topTrackSnapHelper = new PagerSnapHelper();
        topTrackSnapHelper.attachToRecyclerView(bind.topSongsRecyclerView);

        bind.topSongsPageDots.attachTo(bind.topSongsRecyclerView);
    }

    private void initStarredTracksView() {
        if (homeViewModel.isHomeSectorHidden(Constants.HOME_SECTOR_STARRED_TRACKS)) return;

        startLoading(bind.starredTracksSector, bind.starredTracksSkeleton, bind.starredTracksRecyclerView);

        bind.starredTracksRecyclerView.setHasFixedSize(true);

        starredSongAdapter = new SongHorizontalAdapter(this, true, false, null);
        bind.starredTracksRecyclerView.setAdapter(starredSongAdapter);
        homeViewModel.getStarredTracks(getViewLifecycleOwner()).observe(getViewLifecycleOwner(), songs -> {
            if (bind == null) return;

            boolean hasContent = songs != null && !songs.isEmpty();

            if (hasContent) {
                bind.starredTracksRecyclerView.setLayoutManager(new GridLayoutManager(requireContext(), UIUtil.getSpanCount(songs.size(), 5), GridLayoutManager.HORIZONTAL, false));
                starredSongAdapter.setItems(songs);
            }

            finishLoading(bind.starredTracksSector, bind.starredTracksSkeleton, bind.starredTracksRecyclerView, hasContent);
        });

        SnapHelper starredTrackSnapHelper = new PagerSnapHelper();
        starredTrackSnapHelper.attachToRecyclerView(bind.starredTracksRecyclerView);

        bind.starredTracksPageDots.attachTo(bind.starredTracksRecyclerView);
    }

    private void initStarredAlbumsView() {
        if (homeViewModel.isHomeSectorHidden(Constants.HOME_SECTOR_STARRED_ALBUMS)) return;

        startLoading(bind.starredAlbumsSector, bind.starredAlbumsSkeleton, bind.starredAlbumsRecyclerView);

        bind.starredAlbumsRecyclerView.setHasFixedSize(true);

        starredAlbumAdapter = new AlbumHorizontalAdapter(this, false);
        bind.starredAlbumsRecyclerView.setAdapter(starredAlbumAdapter);
        homeViewModel.getStarredAlbums(getViewLifecycleOwner()).observe(getViewLifecycleOwner(), albums -> {
            if (bind == null) return;

            boolean hasContent = albums != null && !albums.isEmpty();

            if (hasContent) {
                bind.starredAlbumsRecyclerView.setLayoutManager(new GridLayoutManager(requireContext(), UIUtil.getSpanCount(albums.size(), 5), GridLayoutManager.HORIZONTAL, false));
                starredAlbumAdapter.setItems(albums);
            }

            finishLoading(bind.starredAlbumsSector, bind.starredAlbumsSkeleton, bind.starredAlbumsRecyclerView, hasContent);
        });

        SnapHelper starredAlbumSnapHelper = new PagerSnapHelper();
        starredAlbumSnapHelper.attachToRecyclerView(bind.starredAlbumsRecyclerView);

        bind.starredAlbumsPageDots.attachTo(bind.starredAlbumsRecyclerView);
    }

    private void initStarredArtistsView() {
        if (homeViewModel.isHomeSectorHidden(Constants.HOME_SECTOR_STARRED_ARTISTS)) return;

        startLoading(bind.starredArtistsSector, bind.starredArtistsSkeleton, bind.starredArtistsRecyclerView);

        bind.starredArtistsRecyclerView.setHasFixedSize(true);

        starredArtistAdapter = new ArtistHorizontalAdapter(this);
        bind.starredArtistsRecyclerView.setAdapter(starredArtistAdapter);
        homeViewModel.getStarredArtists(getViewLifecycleOwner()).observe(getViewLifecycleOwner(), artists -> {
            if (bind == null) return;

            boolean hasContent = artists != null && !artists.isEmpty();

            if (hasContent) {
                bind.starredArtistsRecyclerView.setLayoutManager(new GridLayoutManager(requireContext(), UIUtil.getSpanCount(artists.size(), 5), GridLayoutManager.HORIZONTAL, false));
                starredArtistAdapter.setItems(artists);
            }

            finishLoading(bind.starredArtistsSector, bind.starredArtistsSkeleton, bind.starredArtistsRecyclerView, hasContent);
        });

        SnapHelper starredArtistSnapHelper = new PagerSnapHelper();
        starredArtistSnapHelper.attachToRecyclerView(bind.starredArtistsRecyclerView);

        bind.starredArtistsPageDots.attachTo(bind.starredArtistsRecyclerView);
    }

    private void initNewReleasesView() {
        if (homeViewModel.isHomeSectorHidden(Constants.HOME_SECTOR_NEW_RELEASES)) return;

        startLoading(bind.homeNewReleasesSector, bind.newReleasesSkeleton, bind.newReleasesRecyclerView);

        bind.newReleasesRecyclerView.setHasFixedSize(true);

        newReleasesAlbumAdapter = new AlbumHorizontalAdapter(this, false);
        bind.newReleasesRecyclerView.setAdapter(newReleasesAlbumAdapter);
        homeViewModel.getRecentlyReleasedAlbums(getViewLifecycleOwner()).observe(getViewLifecycleOwner(), albums -> {
            if (bind == null) return;

            boolean hasContent = albums != null && !albums.isEmpty();

            if (hasContent) {
                bind.newReleasesRecyclerView.setLayoutManager(new GridLayoutManager(requireContext(), UIUtil.getSpanCount(albums.size(), 5), GridLayoutManager.HORIZONTAL, false));
                newReleasesAlbumAdapter.setItems(albums);
            }

            finishLoading(bind.homeNewReleasesSector, bind.newReleasesSkeleton, bind.newReleasesRecyclerView, hasContent);
        });

        SnapHelper newReleasesSnapHelper = new PagerSnapHelper();
        newReleasesSnapHelper.attachToRecyclerView(bind.newReleasesRecyclerView);

        bind.newReleasesPageDots.attachTo(bind.newReleasesRecyclerView);
    }

    private void initYearSongView() {
        if (homeViewModel.isHomeSectorHidden(Constants.HOME_SECTOR_FLASHBACK)) return;

        startLoading(bind.homeFlashbackSector, bind.yearsSkeleton, bind.yearsRecyclerView);

        bind.yearsRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        bind.yearsRecyclerView.setHasFixedSize(true);

        yearAdapter = new YearAdapter(this);
        bind.yearsRecyclerView.setAdapter(yearAdapter);
        homeViewModel.getYearList(getViewLifecycleOwner()).observe(getViewLifecycleOwner(), years -> {
            if (bind == null) return;

            yearAdapter.setItems(years != null ? years : Collections.emptyList());
            finishLoading(bind.homeFlashbackSector, bind.yearsSkeleton, bind.yearsRecyclerView, years != null && !years.isEmpty());
        });

        CustomLinearSnapHelper yearSnapHelper = new CustomLinearSnapHelper();
        yearSnapHelper.attachToRecyclerView(bind.yearsRecyclerView);
    }

    private void initMostPlayedAlbumView() {
        if (homeViewModel.isHomeSectorHidden(Constants.HOME_SECTOR_MOST_PLAYED)) return;

        startLoading(bind.homeMostPlayedAlbumsSector, bind.mostPlayedAlbumsSkeleton, bind.mostPlayedAlbumsRecyclerView);

        bind.mostPlayedAlbumsRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        bind.mostPlayedAlbumsRecyclerView.setHasFixedSize(true);

        mostPlayedAlbumAdapter = new AlbumAdapter(this);
        bind.mostPlayedAlbumsRecyclerView.setAdapter(mostPlayedAlbumAdapter);
        homeViewModel.getMostPlayedAlbums(getViewLifecycleOwner()).observe(getViewLifecycleOwner(), albums -> {
            if (bind == null) return;

            mostPlayedAlbumAdapter.setItems(albums != null ? albums : Collections.emptyList());
            finishLoading(bind.homeMostPlayedAlbumsSector, bind.mostPlayedAlbumsSkeleton, bind.mostPlayedAlbumsRecyclerView, albums != null && !albums.isEmpty());
        });

        CustomLinearSnapHelper mostPlayedAlbumSnapHelper = new CustomLinearSnapHelper();
        mostPlayedAlbumSnapHelper.attachToRecyclerView(bind.mostPlayedAlbumsRecyclerView);
    }

    private void initRecentPlayedAlbumView() {
        if (homeViewModel.isHomeSectorHidden(Constants.HOME_SECTOR_LAST_PLAYED)) return;

        startLoading(bind.homeRecentlyPlayedAlbumsSector, bind.recentlyPlayedAlbumsSkeleton, bind.recentlyPlayedAlbumsRecyclerView);

        bind.recentlyPlayedAlbumsRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        bind.recentlyPlayedAlbumsRecyclerView.setHasFixedSize(true);

        recentlyPlayedAlbumAdapter = new AlbumAdapter(this);
        bind.recentlyPlayedAlbumsRecyclerView.setAdapter(recentlyPlayedAlbumAdapter);
        homeViewModel.getRecentlyPlayedAlbumList(getViewLifecycleOwner()).observe(getViewLifecycleOwner(), albums -> {
            if (bind == null) return;

            recentlyPlayedAlbumAdapter.setItems(albums != null ? albums : Collections.emptyList());
            finishLoading(bind.homeRecentlyPlayedAlbumsSector, bind.recentlyPlayedAlbumsSkeleton, bind.recentlyPlayedAlbumsRecyclerView, albums != null && !albums.isEmpty());
        });

        CustomLinearSnapHelper recentPlayedAlbumSnapHelper = new CustomLinearSnapHelper();
        recentPlayedAlbumSnapHelper.attachToRecyclerView(bind.recentlyPlayedAlbumsRecyclerView);
    }

    private void initRecentAddedAlbumView() {
        if (homeViewModel.isHomeSectorHidden(Constants.HOME_SECTOR_RECENTLY_ADDED)) return;

        startLoading(bind.homeRecentlyAddedAlbumsSector, bind.recentlyAddedAlbumsSkeleton, bind.recentlyAddedAlbumsRecyclerView);

        bind.recentlyAddedAlbumsRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        bind.recentlyAddedAlbumsRecyclerView.setHasFixedSize(true);

        recentlyAddedAlbumAdapter = new AlbumAdapter(this);
        bind.recentlyAddedAlbumsRecyclerView.setAdapter(recentlyAddedAlbumAdapter);
        homeViewModel.getMostRecentlyAddedAlbums(getViewLifecycleOwner()).observe(getViewLifecycleOwner(), albums -> {
            if (bind == null) return;

            recentlyAddedAlbumAdapter.setItems(albums != null ? albums : Collections.emptyList());
            finishLoading(bind.homeRecentlyAddedAlbumsSector, bind.recentlyAddedAlbumsSkeleton, bind.recentlyAddedAlbumsRecyclerView, albums != null && !albums.isEmpty());
        });

        CustomLinearSnapHelper recentAddedAlbumSnapHelper = new CustomLinearSnapHelper();
        recentAddedAlbumSnapHelper.attachToRecyclerView(bind.recentlyAddedAlbumsRecyclerView);
    }

    private void initPinnedPlaylistsView() {
        if (homeViewModel.isHomeSectorHidden(Constants.HOME_SECTOR_PINNED_PLAYLISTS)) return;

        startLoading(bind.pinnedPlaylistsSector, bind.pinnedPlaylistsSkeleton, bind.pinnedPlaylistsRecyclerView);

        bind.pinnedPlaylistsRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        bind.pinnedPlaylistsRecyclerView.setHasFixedSize(true);

        playlistHorizontalAdapter = new PlaylistHorizontalAdapter(this);
        bind.pinnedPlaylistsRecyclerView.setAdapter(playlistHorizontalAdapter);
        homeViewModel.getPinnedPlaylists(getViewLifecycleOwner()).observe(getViewLifecycleOwner(), playlists -> {
            if (bind == null) return;

            playlistHorizontalAdapter.setItems(playlists != null ? playlists : Collections.emptyList());
            finishLoading(bind.pinnedPlaylistsSector, bind.pinnedPlaylistsSkeleton, bind.pinnedPlaylistsRecyclerView, playlists != null && !playlists.isEmpty());
        });
    }

    private void initSharesView() {
        if (homeViewModel.isHomeSectorHidden(Constants.HOME_SECTOR_SHARED)) return;

        bind.sharesRecyclerView.setHasFixedSize(true);

        shareHorizontalAdapter = new ShareHorizontalAdapter(this);
        bind.sharesRecyclerView.setAdapter(shareHorizontalAdapter);
        if (Preferences.isSharingEnabled()) {
            startLoading(bind.sharesSector, bind.sharesSkeleton, bind.sharesRecyclerView);

            homeViewModel.getShares(getViewLifecycleOwner()).observe(getViewLifecycleOwner(), shares -> {
                if (bind == null) return;

                boolean hasContent = shares != null && !shares.isEmpty();

                if (hasContent) {
                    bind.sharesRecyclerView.setLayoutManager(new GridLayoutManager(requireContext(), UIUtil.getSpanCount(shares.size(), 10), GridLayoutManager.HORIZONTAL, false));
                    shareHorizontalAdapter.setItems(shares);
                }

                finishLoading(bind.sharesSector, bind.sharesSkeleton, bind.sharesRecyclerView, hasContent);
            });
        }

        SnapHelper starredTrackSnapHelper = new PagerSnapHelper();
        starredTrackSnapHelper.attachToRecyclerView(bind.sharesRecyclerView);

        bind.sharesPageDots.attachTo(bind.sharesRecyclerView);
    }

    private void refreshSharesView() {
        final Handler handler = new Handler();
        final Runnable runnable = () -> {
            if (getView() != null && bind != null && Preferences.isSharingEnabled()) {
                homeViewModel.refreshShares(getViewLifecycleOwner());
            }
        };
        handler.postDelayed(runnable, 100);
    }

    private void setSlideViewOffset(ViewPager2 viewPager, float pageOffset, float pageMargin) {
        viewPager.setPageTransformer((page, position) -> {
            float myOffset = position * -(2 * pageOffset + pageMargin);
            if (viewPager.getOrientation() == ViewPager2.ORIENTATION_HORIZONTAL) {
                if (ViewCompat.getLayoutDirection(viewPager) == ViewCompat.LAYOUT_DIRECTION_RTL) {
                    page.setTranslationX(-myOffset);
                } else {
                    page.setTranslationX(myOffset);
                }
            } else {
                page.setTranslationY(myOffset);
            }
        });
    }

    public void reorder() {
        if (bind != null && homeViewModel.getHomeSectorList() != null) {
            bind.homeLinearLayoutContainer.removeAllViews();

            for (HomeSector sector : homeViewModel.getHomeSectorList()) {
                if (!sector.isVisible()) continue;

                switch (sector.getId()) {
                    case Constants.HOME_SECTOR_DISCOVERY:
                        bind.homeLinearLayoutContainer.addView(bind.homeDiscoverSector);
                        break;
                    case Constants.HOME_SECTOR_MADE_FOR_YOU:
                        bind.homeLinearLayoutContainer.addView(bind.homeSimilarTracksSector);
                        break;
                    case Constants.HOME_SECTOR_TOP_SONGS:
                        bind.homeLinearLayoutContainer.addView(bind.homeGridTracksSector);
                        break;
                    case Constants.HOME_SECTOR_STARRED_TRACKS:
                        bind.homeLinearLayoutContainer.addView(bind.starredTracksSector);
                        break;
                    case Constants.HOME_SECTOR_STARRED_ALBUMS:
                        bind.homeLinearLayoutContainer.addView(bind.starredAlbumsSector);
                        break;
                    case Constants.HOME_SECTOR_STARRED_ARTISTS:
                        bind.homeLinearLayoutContainer.addView(bind.starredArtistsSector);
                        break;
                    case Constants.HOME_SECTOR_NEW_RELEASES:
                        bind.homeLinearLayoutContainer.addView(bind.homeNewReleasesSector);
                        break;
                    case Constants.HOME_SECTOR_FLASHBACK:
                        bind.homeLinearLayoutContainer.addView(bind.homeFlashbackSector);
                        break;
                    case Constants.HOME_SECTOR_MOST_PLAYED:
                        bind.homeLinearLayoutContainer.addView(bind.homeMostPlayedAlbumsSector);
                        break;
                    case Constants.HOME_SECTOR_LAST_PLAYED:
                        bind.homeLinearLayoutContainer.addView(bind.homeRecentlyPlayedAlbumsSector);
                        break;
                    case Constants.HOME_SECTOR_RECENTLY_ADDED:
                        bind.homeLinearLayoutContainer.addView(bind.homeRecentlyAddedAlbumsSector);
                        break;
                    case Constants.HOME_SECTOR_PINNED_PLAYLISTS:
                        bind.homeLinearLayoutContainer.addView(bind.pinnedPlaylistsSector);
                        break;
                    case Constants.HOME_SECTOR_SHARED:
                        bind.homeLinearLayoutContainer.addView(bind.sharesSector);
                        break;
                }
            }
        }
    }

    private void showPopupMenu(View view, int menuResource) {
        PopupMenu popup = new PopupMenu(requireContext(), view);
        popup.getMenuInflater().inflate(menuResource, popup.getMenu());

        popup.setOnMenuItemClickListener(menuItem -> {
            if (menuItem.getItemId() == R.id.menu_last_week_name) {
                homeViewModel.chooseTopSongsPeriod(HomeViewModel.TOP_SONGS_LAST_WEEK);
                return true;
            } else if (menuItem.getItemId() == R.id.menu_last_month_name) {
                homeViewModel.chooseTopSongsPeriod(HomeViewModel.TOP_SONGS_LAST_MONTH);
                return true;
            } else if (menuItem.getItemId() == R.id.menu_last_year_name) {
                homeViewModel.chooseTopSongsPeriod(HomeViewModel.TOP_SONGS_LAST_YEAR);
                return true;
            }

            return false;
        });

        popup.show();
    }

    private void refreshPlaylistView() {
        final Handler handler = new Handler();

        final Runnable runnable = () -> {
            if (getView() != null && bind != null && homeViewModel != null)
                homeViewModel.getPinnedPlaylists(getViewLifecycleOwner());
        };

        handler.postDelayed(runnable, 100);
    }

    private void initializeMediaBrowser() {
        mediaBrowserListenableFuture = new MediaBrowser.Builder(requireContext(), new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
    }

    private void releaseMediaBrowser() {
        MediaBrowser.releaseFuture(mediaBrowserListenableFuture);
    }

    @Override
    public void onMediaClick(Bundle bundle) {
        if (bundle.containsKey(Constants.MEDIA_MIX)) {
            MediaManager.startQueue(mediaBrowserListenableFuture, bundle.getParcelable(Constants.TRACK_OBJECT));
            activity.setBottomSheetInPeek(true);

            if (mediaBrowserListenableFuture != null) {
                homeViewModel.getMediaInstantMix(bundle.getParcelable(Constants.TRACK_OBJECT)).observe(getViewLifecycleOwner(), songs -> {

                    if (songs != null && !songs.isEmpty()) {
                        MediaManager.enqueue(mediaBrowserListenableFuture, songs, true);
                    }
                });
            }
        } else if (bundle.containsKey(Constants.MEDIA_CHRONOLOGY)) {
            List<Child> media = bundle.getParcelableArrayList(Constants.TRACKS_OBJECT);
            MediaManager.startQueue(mediaBrowserListenableFuture, media, bundle.getInt(Constants.ITEM_POSITION));
            activity.setBottomSheetInPeek(true);
        } else {
            MediaManager.startQueue(mediaBrowserListenableFuture, bundle.getParcelableArrayList(Constants.TRACKS_OBJECT), bundle.getInt(Constants.ITEM_POSITION));
            activity.setBottomSheetInPeek(true);
        }
    }

    @Override
    public void onMediaLongClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.songBottomSheetDialog, bundle);
    }

    @Override
    public void onAlbumClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.albumPageFragment, bundle);
    }

    @Override
    public void onAlbumLongClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.albumBottomSheetDialog, bundle);
    }

    @Override
    public void onArtistClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.artistPageFragment, bundle);
    }

    @Override
    public void onArtistLongClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.artistBottomSheetDialog, bundle);
    }

    @Override
    public void onYearClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.songListPageFragment, bundle);
    }

    @Override
    public void onShareClick(Bundle bundle) {
        Share share = bundle.getParcelable(Constants.SHARE_OBJECT);
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(share.getUrl())).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    @Override
    public void onPlaylistClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.playlistPageFragment, bundle);
    }

    @Override
    public void onPlaylistLongClick(Bundle bundle) {
        PlaylistEditorDialog dialog = new PlaylistEditorDialog(new PlaylistCallback() {
            @Override
            public void onDismiss() {
                refreshPlaylistView();
            }
        });

        dialog.setArguments(bundle);
        dialog.show(activity.getSupportFragmentManager(), null);
    }

    @Override
    public void onShareLongClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.shareBottomSheetDialog, bundle);
    }
}
