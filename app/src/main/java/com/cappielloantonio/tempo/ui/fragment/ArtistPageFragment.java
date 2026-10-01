package com.cappielloantonio.tempo.ui.fragment;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentArtistPageBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.helper.recyclerview.CustomLinearSnapHelper;
import com.cappielloantonio.tempo.helper.view.PageHeaderTitle;
import com.cappielloantonio.tempo.helper.view.SkeletonView;
import com.cappielloantonio.tempo.helper.view.TextFold;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.subsonic.models.ArtistID3;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.adapter.AlbumAdapter;
import com.cappielloantonio.tempo.ui.adapter.ArtistAdapter;
import com.cappielloantonio.tempo.ui.adapter.SongHorizontalAdapter;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.MusicUtil;
import com.cappielloantonio.tempo.util.Preferences;
import com.cappielloantonio.tempo.viewmodel.ArtistPageViewModel;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@UnstableApi
public class ArtistPageFragment extends Fragment implements ClickCallback {
    private FragmentArtistPageBinding bind;
    private MainActivity activity;
    private ArtistPageViewModel artistPageViewModel;

    private SongHorizontalAdapter songHorizontalAdapter;
    private AlbumAdapter albumAdapter;
    private ArtistAdapter artistAdapter;

    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    private TextFold bioFold;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        bind = FragmentArtistPageBinding.inflate(inflater, container, false);
        View view = bind.getRoot();
        artistPageViewModel = new ViewModelProvider(requireActivity()).get(ArtistPageViewModel.class);

        init();
        initAppBar();
        initArtistInfo();
        initPlayButtons();
        initTopSongsView();
        initAlbumsView();
        initSimilarArtistsView();

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

    private void init() {
        artistPageViewModel.setArtist(requireArguments().getParcelable(Constants.ARTIST_OBJECT));

        bind.mostStreamedSongTextViewClickable.setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putString(Constants.MEDIA_BY_ARTIST, Constants.MEDIA_BY_ARTIST);
            bundle.putParcelable(Constants.ARTIST_OBJECT, artistPageViewModel.getArtist());
            activity.navController.navigate(R.id.action_artistPageFragment_to_songListPageFragment, bundle);
        });
    }

    private void initAppBar() {
        activity.setSupportActionBar(bind.animToolbar);
        if (activity.getSupportActionBar() != null)
            activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        bind.artistNameLabel.setText(artistPageViewModel.getArtist().getName());
        bind.animToolbar.setNavigationOnClickListener(v -> activity.navController.navigateUp());

        PageHeaderTitle.attach(bind.appbar, bind.animToolbar, bind.artistNameLabel);
    }

    private void initArtistInfo() {
        bioFold = new TextFold(bind.bioTextView, bind.bioExpandTextViewClickable,
                (ViewGroup) bind.fragmentArtistPageNestedScrollView.getChildAt(0),
                R.string.artist_page_bio_expand, R.string.artist_page_bio_collapse);

        /*
         * The portrait is loaded here, not inside the getArtistInfo observer:
         * a server that returns no artist info (no biography, no Last.fm link)
         * used to leave the header empty even though the cover art endpoint had
         * a perfectly good photo. The photo does not depend on the info call.
         */
        CustomGlideRequest.Builder
                .from(requireContext(), artistPageViewModel.getArtist().getId(), CustomGlideRequest.ResourceType.Artist)
                .build()
                .into(bind.artistBackdropImageView);

        artistPageViewModel.getArtistInfo(artistPageViewModel.getArtist().getId()).observe(getViewLifecycleOwner(), artistInfo -> {
            if (artistInfo == null) {
                if (bind != null) bind.artistPageBioSector.setVisibility(View.GONE);
            } else {
                String normalizedBio = MusicUtil.forceReadableString(artistInfo.getBiography());

                if (bind != null)
                    bind.artistPageBioSector.setVisibility(!normalizedBio.trim().isEmpty() ? View.VISIBLE : View.GONE);
                if (bind != null)
                    bind.bioMoreTextViewClickable.setVisibility(artistInfo.getLastFmUrl() != null ? View.VISIBLE : View.GONE);

                if (bind != null) bioFold.show(normalizedBio);

                if (bind != null) bind.bioMoreTextViewClickable.setOnClickListener(v -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse(artistInfo.getLastFmUrl()));
                    startActivity(intent);
                });

            }
        });
    }

    private void initPlayButtons() {
        bind.artistPageShuffleButton.setOnClickListener(v -> {
            artistPageViewModel.getArtistShuffleList().observe(getViewLifecycleOwner(), songs -> {
                if (!songs.isEmpty()) {
                    MediaManager.startQueue(mediaBrowserListenableFuture, songs, 0);
                    activity.setBottomSheetInPeek(true);
                } else {
                    Toast.makeText(requireContext(), getString(R.string.artist_error_retrieving_tracks), Toast.LENGTH_SHORT).show();
                }
            });
        });

        bind.artistPageRadioButton.setOnClickListener(v -> {
            artistPageViewModel.getArtistInstantMix().observe(getViewLifecycleOwner(), songs -> {
                if (!songs.isEmpty()) {
                    MediaManager.startQueue(mediaBrowserListenableFuture, songs, 0);
                    activity.setBottomSheetInPeek(true);
                } else {
                    Toast.makeText(requireContext(), getString(R.string.artist_error_retrieving_radio), Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void initTopSongsView() {
        bind.mostStreamedSongRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        songHorizontalAdapter = new SongHorizontalAdapter(this, true, true, null);
        bind.mostStreamedSongRecyclerView.setAdapter(songHorizontalAdapter);

        // Each section holds its place with a skeleton until its request comes
        // back, then shows what came or folds away once (SkeletonView).
        SkeletonView.startLoading(bind.artistPageTopSongsSector, bind.topSongsSkeleton, bind.mostStreamedSongRecyclerView);
        artistPageViewModel.getArtistTopSongList().observe(getViewLifecycleOwner(), songs -> {
            if (bind == null) return;
            SkeletonView.finishLoading(bind.artistPageTopSongsSector, bind.topSongsSkeleton, bind.mostStreamedSongRecyclerView, songs != null && !songs.isEmpty());

            if (songs != null) {
                bind.artistPageShuffleButton.setEnabled(!songs.isEmpty());
                // Asked of the server as well, but not every server honours the count.
                songHorizontalAdapter.setItems(songs.size() > ArtistPageViewModel.TOP_SONGS_COUNT
                        ? new ArrayList<>(songs.subList(0, ArtistPageViewModel.TOP_SONGS_COUNT))
                        : songs);
            }
        });
    }

    /*
     * A rail, not a grid.
     *
     * Both of the grids on this page sat inside the NestedScrollView with
     * wrap_content height, which measures them UNSPECIFIED: the layout manager
     * has to lay out every single item to work out how tall it is, so nothing
     * ever recycles. An artist with 171 albums inflated 171 cards and fired 171
     * cover loads the moment the page opened - measured at 51% janky frames and
     * a 1050ms 90th-percentile frame, i.e. a visible freeze of about a second.
     *
     * A horizontal rail is bounded on its scroll axis by the screen width, so
     * RecyclerView only builds the handful of cards that fit. It is also what
     * the home screen and search already use, and the snap helper that was
     * still attached to the similar-artists list below is a leftover from when
     * this page worked the same way.
     *
     * The full discography stays one tap away, in the album list page - a
     * plain vertical list, which recycles properly.
     */
    private void initAlbumsView() {
        bind.albumsRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        bind.albumsRecyclerView.setHasFixedSize(true);

        albumAdapter = new AlbumAdapter(this);
        albumAdapter.setShowReleaseDate(true);
        bind.albumsRecyclerView.setAdapter(albumAdapter);

        SkeletonView.startLoading(bind.artistPageAlbumsSector, bind.albumsSkeleton, bind.albumsRecyclerView);
        artistPageViewModel.getAlbumList().observe(getViewLifecycleOwner(), albums -> {
            if (bind == null) return;
            SkeletonView.finishLoading(bind.artistPageAlbumsSector, bind.albumsSkeleton, bind.albumsRecyclerView, albums != null && !albums.isEmpty());

            if (albums != null) albumAdapter.setItems(albums);
        });

        bind.albumsTextViewClickable.setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.ARTIST_OBJECT, artistPageViewModel.getArtist());
            activity.navController.navigate(R.id.albumListPageFragment, bundle);
        });

        CustomLinearSnapHelper albumSnapHelper = new CustomLinearSnapHelper();
        albumSnapHelper.attachToRecyclerView(bind.albumsRecyclerView);
    }

    private void initSimilarArtistsView() {
        // Same reason as the album rail above; this one already had the snap
        // helper a rail needs, attached to a grid that could not use it.
        bind.similarArtistsRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        bind.similarArtistsRecyclerView.setHasFixedSize(true);

        artistAdapter = new ArtistAdapter(this);
        bind.similarArtistsRecyclerView.setAdapter(artistAdapter);

        SkeletonView.startLoading(bind.similarArtistSector, bind.similarArtistsSkeleton, bind.similarArtistsRecyclerView);
        artistPageViewModel.getArtistInfo(artistPageViewModel.getArtist().getId()).observe(getViewLifecycleOwner(), artist -> {
            if (bind == null) return;

            List<ArtistID3> artists = new ArrayList<>();
            if (artist != null && artist.getSimilarArtists() != null) {
                artists.addAll(artist.getSimilarArtists());
            }

            SkeletonView.finishLoading(bind.similarArtistSector, bind.similarArtistsSkeleton, bind.similarArtistsRecyclerView, !artists.isEmpty());
            artistAdapter.setItems(artists);
        });

        CustomLinearSnapHelper similarArtistSnapHelper = new CustomLinearSnapHelper();
        similarArtistSnapHelper.attachToRecyclerView(bind.similarArtistsRecyclerView);
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
}