package com.cappielloantonio.tempo.ui.fragment;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.SearchView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.GranularRoundedCorners;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentPlaylistPageBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.helper.view.PageHeaderTitle;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.Playlist;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.adapter.SkeletonAdapter;
import com.cappielloantonio.tempo.ui.adapter.PageActionsAdapter;
import com.cappielloantonio.tempo.ui.adapter.SongHorizontalAdapter;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.DownloadUtil;
import com.cappielloantonio.tempo.util.MappingUtil;
import com.cappielloantonio.tempo.util.MusicUtil;
import com.cappielloantonio.tempo.viewmodel.PlaylistPageViewModel;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@UnstableApi
public class PlaylistPageFragment extends Fragment implements ClickCallback {
    private FragmentPlaylistPageBinding bind;
    private MainActivity activity;
    private PlaylistPageViewModel playlistPageViewModel;

    private SongHorizontalAdapter songHorizontalAdapter;
    private PageActionsAdapter actionsAdapter;
    private SkeletonAdapter skeletonAdapter;

    /** The playlist's tracks as last read from the server, in its own order. */
    @Nullable
    private List<Child> songs;

    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    /**
     * Whether the server answered with artwork for the playlist itself. Until
     * it says otherwise the stitched cover stays out of sight, so a playlist
     * that has its own picture never flashes a mosaic on the way to it.
     */
    private boolean playlistHasOwnCover = true;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.playlist_page_menu, menu);

        MenuItem searchItem = menu.findItem(R.id.action_search);

        SearchView searchView = (SearchView) searchItem.getActionView();
        searchView.setImeOptions(EditorInfo.IME_ACTION_DONE);
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                searchView.clearFocus();
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                songHorizontalAdapter.getFilter().filter(newText);
                return false;
            }
        });

        searchView.setPadding(-32, 0, 0, 0);

        initMenuOption(menu);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        bind = FragmentPlaylistPageBinding.inflate(inflater, container, false);
        View view = bind.getRoot();
        playlistPageViewModel = new ViewModelProvider(requireActivity()).get(PlaylistPageViewModel.class);

        init();
        initAppBar();
        initBackCover();
        initSongsView();

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();

        initializeMediaBrowser();
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

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_download_playlist) {
            playlistPageViewModel.getPlaylistSongLiveList().observe(getViewLifecycleOwner(), songs -> {
                if (songs != null && isVisible() && getActivity() != null) {
                    DownloadUtil.getDownloadTracker(requireContext()).download(
                            MappingUtil.mapDownloads(songs),
                            songs.stream().map(child -> {
                                Download toDownload = new Download(child);
                                toDownload.setPlaylistId(playlistPageViewModel.getPlaylist().getId());
                                toDownload.setPlaylistName(playlistPageViewModel.getPlaylist().getName());
                                return toDownload;
                            }).collect(Collectors.toList())
                    );
                }
            });
            return true;
        } else if (item.getItemId() == R.id.action_pin_playlist) {
            playlistPageViewModel.setPinned(true);
            return true;
        } else if (item.getItemId() == R.id.action_unpin_playlist) {
            playlistPageViewModel.setPinned(false);
            return true;
        }

        return false;
    }

    private void init() {
        playlistPageViewModel.setPlaylist(requireArguments().getParcelable(Constants.PLAYLIST_OBJECT));
    }

    private void initMenuOption(Menu menu) {
        playlistPageViewModel.isPinned(getViewLifecycleOwner()).observe(getViewLifecycleOwner(), isPinned -> {
            menu.findItem(R.id.action_unpin_playlist).setVisible(isPinned);
            menu.findItem(R.id.action_pin_playlist).setVisible(!isPinned);
        });
    }

    private void initAppBar() {
        activity.setSupportActionBar(bind.animToolbar);

        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            activity.getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        bind.playlistNameLabel.setText(playlistPageViewModel.getPlaylist().getName());
        Playlist playlist = playlistPageViewModel.getPlaylist();
        bind.playlistCaptionLabel.setText(MusicUtil.getPlaylistCaption(requireContext(), playlist.getSongCount(), playlist.getDuration()));

        bind.animToolbar.setNavigationOnClickListener(v -> {
            hideKeyboard(v);
            activity.navController.navigateUp();
        });

        Objects.requireNonNull(bind.animToolbar.getOverflowIcon()).setTint(requireContext().getResources().getColor(R.color.titleTextColor, null));

        PageHeaderTitle.attach(bind.appbar, bind.animToolbar, bind.playlistNameLabel);
    }

    private void hideKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    /* Play and Shuffle start from the tracks the page is showing. */
    private void play(boolean shuffled) {
        if (songs == null || songs.isEmpty()) return;

        List<Child> queue = new ArrayList<>(songs);
        if (shuffled) Collections.shuffle(queue);

        MediaManager.startQueue(mediaBrowserListenableFuture, queue.subList(0, Math.min(100, queue.size())), 0);
        activity.setBottomSheetInPeek(true);
    }

    /**
     * A playlist wears the artwork the server holds for it. Only when there is
     * none does the page fall back to stitching one out of the covers of four
     * of its tracks, so a playlist someone has given a picture to no longer has
     * that picture ignored in favour of a mosaic.
     */
    private void initBackCover() {
        // The page is re-entered with the same fragment instance, and the
        // playlist opened this time may not be the one that had no artwork.
        playlistHasOwnCover = true;

        CustomGlideRequest.Builder
                .from(requireContext(), playlistPageViewModel.getPlaylist().getCoverArtId(), CustomGlideRequest.ResourceType.Playlist)
                .build()
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException exception, @Nullable Object model, @NonNull Target<Drawable> target, boolean isFirstResource) {
                        // Returning true keeps the placeholder off the view:
                        // what belongs there is the mosaic underneath it.
                        playlistHasOwnCover = false;

                        if (bind != null) {
                            bind.playlistCoverImageView.setVisibility(View.GONE);
                            showGeneratedCover();
                        }

                        return true;
                    }

                    @Override
                    public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model, Target<Drawable> target, @NonNull DataSource dataSource, boolean isFirstResource) {
                        return false;
                    }
                })
                .into(bind.playlistCoverImageView);
    }

    /**
     * The fallback cover: four track covers stitched into one square. Each tile
     * is centre-cropped first and then rounded on the one corner of it that is
     * a corner of the finished square - rounding a tile on all four would put a
     * notch in the middle of the cover.
     */
    private void fillGeneratedCover(List<Child> playlistSongs) {
        if (bind != null && playlistSongs != null && !playlistSongs.isEmpty()) {
            // Four covers picked at random, from a copy: the list itself is
            // the one the page shows, in the playlist's own order.
            List<Child> songs = new ArrayList<>(playlistSongs);
            Collections.shuffle(songs);

            // Pic top-left
            CustomGlideRequest.Builder
                    .from(requireContext(), songs.get(0).getCoverArtId(), CustomGlideRequest.ResourceType.Song)
                    .build()
                    .transform(new CenterCrop(), new GranularRoundedCorners(CustomGlideRequest.CORNER_RADIUS, 0, 0, 0))
                    .into(bind.playlistCoverImageViewTopLeft);

            // Pic top-right
            CustomGlideRequest.Builder
                    .from(requireContext(), songs.size() > 1 ? songs.get(1).getCoverArtId() : songs.get(0).getCoverArtId(), CustomGlideRequest.ResourceType.Song)
                    .build()
                    .transform(new CenterCrop(), new GranularRoundedCorners(0, CustomGlideRequest.CORNER_RADIUS, 0, 0))
                    .into(bind.playlistCoverImageViewTopRight);

            // Pic bottom-left
            CustomGlideRequest.Builder
                    .from(requireContext(), songs.size() > 2 ? songs.get(2).getCoverArtId() : songs.get(0).getCoverArtId(), CustomGlideRequest.ResourceType.Song)
                    .build()
                    .transform(new CenterCrop(), new GranularRoundedCorners(0, 0, 0, CustomGlideRequest.CORNER_RADIUS))
                    .into(bind.playlistCoverImageViewBottomLeft);

            // Pic bottom-right
            CustomGlideRequest.Builder
                    .from(requireContext(), songs.size() > 3 ? songs.get(3).getCoverArtId() : songs.get(0).getCoverArtId(), CustomGlideRequest.ResourceType.Song)
                    .build()
                    .transform(new CenterCrop(), new GranularRoundedCorners(0, 0, CustomGlideRequest.CORNER_RADIUS, 0))
                    .into(bind.playlistCoverImageViewBottomRight);

            showGeneratedCover();
        }
    }

    /**
     * The tiles are filled as soon as the track list lands, whether or not they
     * are ever needed - they are the same covers the list below is loading - but
     * they are only uncovered once the playlist is known to have no artwork of
     * its own.
     */
    private void showGeneratedCover() {
        if (bind == null || playlistHasOwnCover) return;

        bind.playlistCoverImageViewTopLeft.setVisibility(View.VISIBLE);
        bind.playlistCoverImageViewTopRight.setVisibility(View.VISIBLE);
        bind.playlistCoverImageViewBottomLeft.setVisibility(View.VISIBLE);
        bind.playlistCoverImageViewBottomRight.setVisibility(View.VISIBLE);
    }

    private void initSongsView() {
        bind.songRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        bind.songRecyclerView.setHasFixedSize(true);

        actionsAdapter = new PageActionsAdapter(v -> play(false), v -> play(true));
        actionsAdapter.setEnabled(false);

        songHorizontalAdapter = new SongHorizontalAdapter(this, true, false, null);
        bind.songRecyclerView.setAdapter(new ConcatAdapter(
                new ConcatAdapter.Config.Builder().setIsolateViewTypes(true).build(),
                actionsAdapter,
                skeletonAdapter = new SkeletonAdapter(R.layout.skeleton_item_row, 10),
                songHorizontalAdapter));

        loadSongs();

        // A track taken out of the playlist from its own menu is taken out on
        // the server; this is the page hearing about it and reading the
        // playlist back.
        getParentFragmentManager().setFragmentResultListener(Constants.PLAYLIST_EDITED, getViewLifecycleOwner(), (key, result) -> loadSongs());
    }

    private void loadSongs() {
        /*
         * One request feeds the list, the buttons and the mosaic. Each used to
         * ask the server for the playlist on its own - three reads of it per
         * visit - and the mosaic's observer was tied to the activity, so every
         * visit left one more behind.
         */
        playlistPageViewModel.getPlaylistSongLiveList().observe(getViewLifecycleOwner(), songs -> {
            this.songs = songs;

            skeletonAdapter.hide();
            songHorizontalAdapter.setItems(songs);
            actionsAdapter.setEnabled(songs != null && !songs.isEmpty());
            fillGeneratedCover(songs);

            // A playlist read back from the local pins carries no count or
            // length, which showed as "0 tracks"; the list itself has both.
            if (songs != null && bind != null) {
                long seconds = 0;
                for (Child song : songs) seconds += song.getDuration() != null ? song.getDuration() : 0;
                bind.playlistCaptionLabel.setText(MusicUtil.getPlaylistCaption(requireContext(), songs.size(), seconds));
            }
        });
    }

    private void initializeMediaBrowser() {
        mediaBrowserListenableFuture = new MediaBrowser.Builder(requireContext(), new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
    }

    private void releaseMediaBrowser() {
        MediaBrowser.releaseFuture(mediaBrowserListenableFuture);
    }

    @Override
    public void onMediaClick(Bundle bundle) {
        MediaManager.startQueue(mediaBrowserListenableFuture, bundle.getParcelableArrayList(Constants.TRACKS_OBJECT), bundle.getInt(Constants.ITEM_POSITION));
        activity.setBottomSheetInPeek(true);
    }

    @Override
    public void onMediaLongClick(Bundle bundle) {
        // The track list is shared with every other page that shows tracks, so
        // the playlist a track is being looked at from is something only this
        // page can add.
        bundle.putParcelable(Constants.PLAYLIST_OBJECT, playlistPageViewModel.getPlaylist());

        Navigation.findNavController(requireView()).navigate(R.id.songBottomSheetDialog, bundle);
    }
}