package com.cappielloantonio.tempo.ui.fragment;

import static com.cappielloantonio.tempo.helper.view.SkeletonView.finishLoading;
import static com.cappielloantonio.tempo.helper.view.SkeletonView.startLoading;

import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentLibraryBinding;
import com.cappielloantonio.tempo.databinding.ItemHorizontalPlaylistBinding;
import com.cappielloantonio.tempo.helper.recyclerview.CustomLinearSnapHelper;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.interfaces.PlaylistCallback;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.adapter.AlbumAdapter;
import com.cappielloantonio.tempo.ui.adapter.ArtistAdapter;
import com.cappielloantonio.tempo.ui.adapter.GenreAdapter;
import com.cappielloantonio.tempo.ui.adapter.PlaylistHorizontalAdapter;
import com.cappielloantonio.tempo.ui.dialog.PlaylistEditorDialog;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.viewmodel.LibraryViewModel;
import com.google.android.material.appbar.MaterialToolbar;

import java.util.Collections;
import java.util.Objects;

@UnstableApi
public class LibraryFragment extends Fragment implements ClickCallback {
    private static final String TAG = "LibraryFragment";

    private FragmentLibraryBinding bind;
    private MainActivity activity;
    private LibraryViewModel libraryViewModel;

    private AlbumAdapter albumAdapter;
    private ArtistAdapter artistAdapter;
    private GenreAdapter genreAdapter;
    private PlaylistHorizontalAdapter playlistHorizontalAdapter;

    private MaterialToolbar materialToolbar;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        bind = FragmentLibraryBinding.inflate(inflater, container, false);
        View view = bind.getRoot();
        libraryViewModel = new ViewModelProvider(requireActivity()).get(LibraryViewModel.class);

        init();

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        initAppBar();
        initLikedTracksView();
        initAlbumView();
        initArtistView();
        initGenreView();
        initPlaylistView();
    }

    @Override
    public void onStart() {
        super.onStart();
        activity.setBottomNavigationBarVisibility(true);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshPlaylistView();

        // A like set on the player since the last visit changes the count.
        libraryViewModel.refreshLikedTracks(getViewLifecycleOwner());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind = null;
    }

    private void init() {
        bind.albumCatalogueTextViewClickable.setOnClickListener(v -> activity.navController.navigate(R.id.action_libraryFragment_to_albumCatalogueFragment));
        bind.artistCatalogueTextViewClickable.setOnClickListener(v -> activity.navController.navigate(R.id.action_libraryFragment_to_artistCatalogueFragment));
        bind.genreCatalogueTextViewClickable.setOnClickListener(v -> activity.navController.navigate(R.id.action_libraryFragment_to_genreCatalogueFragment));
        bind.playlistCatalogueTextViewClickable.setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putString(Constants.PLAYLIST_ALL, Constants.PLAYLIST_ALL);
            activity.navController.navigate(R.id.action_libraryFragment_to_playlistCatalogueFragment, bundle);
        });

        bind.albumCatalogueSampleTextViewRefreshable.setOnLongClickListener(view -> {
            libraryViewModel.refreshAlbumSample(getViewLifecycleOwner());
            return true;
        });
        bind.artistCatalogueSampleTextViewRefreshable.setOnLongClickListener(view -> {
            libraryViewModel.refreshArtistSample(getViewLifecycleOwner());
            return true;
        });
        bind.genreCatalogueSampleTextViewRefreshable.setOnLongClickListener(view -> {
            libraryViewModel.refreshGenreSample(getViewLifecycleOwner());
            return true;
        });
        bind.playlistCatalogueSampleTextViewRefreshable.setOnLongClickListener(view -> {
            libraryViewModel.refreshPlaylistSample(getViewLifecycleOwner());
            return true;
        });
    }

    private void initAppBar() {
        materialToolbar = bind.getRoot().findViewById(R.id.toolbar);

        activity.setSupportActionBar(materialToolbar);
        Objects.requireNonNull(materialToolbar.getOverflowIcon()).setTint(requireContext().getResources().getColor(R.color.titleTextColor, null));
    }

    private void initLikedTracksView() {
        ItemHorizontalPlaylistBinding row = bind.libraryLikedTracksRow;

        row.playlistMoreButton.setVisibility(View.GONE);
        row.playlistCoverImageView.setImageResource(R.drawable.cover_liked_tracks);
        row.playlistTitleTextView.setText(R.string.library_title_liked_tracks);
        row.playlistSubtitleTextView.setText(null);

        row.getRoot().setOnClickListener(v -> activity.navController.navigate(R.id.action_libraryFragment_to_likedTracksFragment));

        libraryViewModel.getLikedTracks().observe(getViewLifecycleOwner(), songs -> {
            // null is a failed request: leave the count off rather than claim there are none.
            if (bind == null || songs == null) return;

            bind.libraryLikedTracksRow.playlistSubtitleTextView.setText(
                    getResources().getQuantityString(R.plurals.download_track_count, songs.size(), songs.size()));
        });
    }

    private void initAlbumView() {
        startLoading(bind.libraryAlbumSector, bind.albumSkeleton, bind.albumRecyclerView);

        bind.albumRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        bind.albumRecyclerView.setHasFixedSize(true);

        albumAdapter = new AlbumAdapter(this);
        bind.albumRecyclerView.setAdapter(albumAdapter);
        libraryViewModel.getAlbumSample(getViewLifecycleOwner()).observe(getViewLifecycleOwner(), albums -> {
            if (bind == null) return;

            albumAdapter.setItems(albums != null ? albums : Collections.emptyList());
            finishLoading(bind.libraryAlbumSector, bind.albumSkeleton, bind.albumRecyclerView, albums != null && !albums.isEmpty());
        });

        CustomLinearSnapHelper albumSnapHelper = new CustomLinearSnapHelper();
        albumSnapHelper.attachToRecyclerView(bind.albumRecyclerView);
    }

    private void initArtistView() {
        startLoading(bind.libraryArtistSector, bind.artistSkeleton, bind.artistRecyclerView);

        bind.artistRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        bind.artistRecyclerView.setHasFixedSize(true);

        artistAdapter = new ArtistAdapter(this);
        bind.artistRecyclerView.setAdapter(artistAdapter);
        libraryViewModel.getArtistSample(getViewLifecycleOwner()).observe(getViewLifecycleOwner(), artists -> {
            if (bind == null) return;

            artistAdapter.setItems(artists != null ? artists : Collections.emptyList());
            finishLoading(bind.libraryArtistSector, bind.artistSkeleton, bind.artistRecyclerView, artists != null && !artists.isEmpty());
        });

        CustomLinearSnapHelper artistSnapHelper = new CustomLinearSnapHelper();
        artistSnapHelper.attachToRecyclerView(bind.artistRecyclerView);
    }

    private void initGenreView() {
        startLoading(bind.libraryGenresSector, bind.genreSkeleton, bind.genreRecyclerView);

        bind.genreRecyclerView.setLayoutManager(new GridLayoutManager(requireContext(), 2, GridLayoutManager.HORIZONTAL, false));
        bind.genreRecyclerView.setHasFixedSize(true);

        genreAdapter = new GenreAdapter(this);
        bind.genreRecyclerView.setAdapter(genreAdapter);

        libraryViewModel.getGenreSample(getViewLifecycleOwner()).observe(getViewLifecycleOwner(), genres -> {
            if (bind == null) return;

            genreAdapter.setItems(genres != null ? genres : Collections.emptyList());
            finishLoading(bind.libraryGenresSector, bind.genreSkeleton, bind.genreRecyclerView, genres != null && !genres.isEmpty());
        });

        CustomLinearSnapHelper genreSnapHelper = new CustomLinearSnapHelper();
        genreSnapHelper.attachToRecyclerView(bind.genreRecyclerView);
    }

    private void initPlaylistView() {
        startLoading(bind.libraryPlaylistSector, bind.playlistSkeleton, bind.playlistRecyclerView);

        bind.playlistRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        bind.playlistRecyclerView.setHasFixedSize(true);

        playlistHorizontalAdapter = new PlaylistHorizontalAdapter(this);
        bind.playlistRecyclerView.setAdapter(playlistHorizontalAdapter);
        libraryViewModel.getPlaylistSample(getViewLifecycleOwner()).observe(getViewLifecycleOwner(), playlists -> {
            if (bind == null) return;

            playlistHorizontalAdapter.setItems(playlists != null ? playlists : Collections.emptyList());
            finishLoading(bind.libraryPlaylistSector, bind.playlistSkeleton, bind.playlistRecyclerView, playlists != null && !playlists.isEmpty());
        });
    }

    private void refreshPlaylistView() {
        final Handler handler = new Handler();

        final Runnable runnable = () -> {
            if (getView() != null && bind != null && libraryViewModel != null)
                libraryViewModel.refreshPlaylistSample(getViewLifecycleOwner());
        };

        handler.postDelayed(runnable, 100);
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
    public void onGenreClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.songListPageFragment, bundle);
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

}
