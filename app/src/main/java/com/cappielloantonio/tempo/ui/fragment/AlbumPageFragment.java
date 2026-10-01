package com.cappielloantonio.tempo.ui.fragment;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

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

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentAlbumPageBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.helper.view.PageHeaderTitle;
import com.cappielloantonio.tempo.helper.view.TextFold;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.subsonic.models.ArtistID3;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.ItemDate;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.adapter.SkeletonAdapter;
import com.cappielloantonio.tempo.ui.adapter.SongHorizontalAdapter;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.DownloadUtil;
import com.cappielloantonio.tempo.util.GenreNames;
import com.cappielloantonio.tempo.util.MappingUtil;
import com.cappielloantonio.tempo.util.MusicUtil;
import com.cappielloantonio.tempo.viewmodel.AlbumPageViewModel;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@UnstableApi
public class AlbumPageFragment extends Fragment implements ClickCallback {
    private FragmentAlbumPageBinding bind;
    private MainActivity activity;
    private AlbumPageViewModel albumPageViewModel;
    private SongHorizontalAdapter songHorizontalAdapter;
    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;
    private TextFold notesFold;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.album_page_menu, menu);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        bind = FragmentAlbumPageBinding.inflate(inflater, container, false);
        View view = bind.getRoot();
        albumPageViewModel = new ViewModelProvider(requireActivity()).get(AlbumPageViewModel.class);

        init();
        initAppBar();
        initAlbumInfoTextButton();
        initAlbumNotes();
        initMusicButton();
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
        if (item.getItemId() == R.id.action_download_album) {
            albumPageViewModel.getAlbumSongLiveList().observe(getViewLifecycleOwner(), songs -> {
                if (songs == null) return;
                DownloadUtil.getDownloadTracker(requireContext()).download(MappingUtil.mapDownloads(songs), songs.stream().map(Download::new).collect(Collectors.toList()));
            });
            return true;
        }

        return false;
    }

    private void init() {
        albumPageViewModel.setAlbum(getViewLifecycleOwner(), requireArguments().getParcelable(Constants.ALBUM_OBJECT));
    }

    private void initAppBar() {
        activity.setSupportActionBar(bind.animToolbar);

        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            activity.getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        bind.animToolbar.setNavigationOnClickListener(v -> activity.navController.navigateUp());
        Objects.requireNonNull(bind.animToolbar.getOverflowIcon()).setTint(requireContext().getResources().getColor(R.color.titleTextColor, null));

        PageHeaderTitle.attach(bind.appbar, bind.animToolbar, bind.albumNameLabel);

        albumPageViewModel.getAlbum().observe(getViewLifecycleOwner(), album -> {
            if (bind == null || album == null) return;

            bind.albumNameLabel.setText(album.getName());

            String artist = album.artistLine();
            bind.albumArtistLabel.setText(artist);
            bind.albumArtistLabel.setVisibility(artist != null && !artist.isBlank() ? View.VISIBLE : View.GONE);

            bind.albumCaptionLabel.setText(caption(album));

            String release = releaseLine(album);
            bind.albumReleaseLabel.setText(release);
            bind.albumReleaseLabel.setVisibility(release != null ? View.VISIBLE : View.GONE);

            String genre = album.getGenre();
            bind.albumGenreLabel.setText(GenreNames.label(requireContext(), genre));
            bind.albumGenreLabel.setVisibility(genre != null && !genre.isBlank() ? View.VISIBLE : View.GONE);
        });
    }

    /** "Альбом • 2026 • 8 треков • 26 мин" - what it is, when, and how long. */
    private String caption(AlbumID3 album) {
        List<String> parts = new ArrayList<>();
        parts.add(getString(R.string.label_role_album));

        if (album.getYear() != 0) parts.add(String.valueOf(album.getYear()));

        Integer songCount = album.getSongCount();
        if (songCount != null && songCount > 0) {
            parts.add(getResources().getQuantityString(R.plurals.album_page_tracks_count, songCount, songCount));
        }

        if (album.getDuration() != null && album.getDuration() > 0) {
            parts.add(MusicUtil.getReadableLength(requireContext(), album.getDuration()));
        }

        return String.join(" • ", parts);
    }

    /** The release date, and the original one when the record is a reissue. */
    @Nullable
    private String releaseLine(AlbumID3 album) {
        ItemDate released = album.getReleaseDate();
        ItemDate original = album.getOriginalReleaseDate();

        String releasedOn = released != null ? released.getFormattedDate() : "";
        String originallyOn = original != null ? original.getFormattedDate() : "";

        if (!releasedOn.isEmpty() && !originallyOn.isEmpty() && !releasedOn.equals(originallyOn)) {
            return getString(R.string.album_page_release_dates_label, releasedOn, originallyOn);
        }

        if (!releasedOn.isEmpty()) return getString(R.string.album_page_release_date_label, releasedOn);
        if (!originallyOn.isEmpty()) return getString(R.string.album_page_release_date_label, originallyOn);

        return null;
    }

    /*
     * The artist page needs only the artist's id and name, and the album
     * carries both. The link used to look the artist up first - by the
     * album's id, which no artist has - so a tap on the name did nothing.
     */
    private void initAlbumInfoTextButton() {
        bind.albumArtistLabel.setOnClickListener(v -> {
            AlbumID3 album = albumPageViewModel.getAlbum().getValue();
            ArtistID3 artist = album != null ? albumArtist(album) : null;

            if (artist == null) {
                Toast.makeText(requireContext(), getString(R.string.album_error_retrieving_artist), Toast.LENGTH_SHORT).show();
                return;
            }

            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.ARTIST_OBJECT, artist);
            activity.navController.navigate(R.id.action_albumPageFragment_to_artistPageFragment, bundle);
        });
    }

    /** The album's lead artist: the first credited one, or the one it is filed under. */
    @Nullable
    private static ArtistID3 albumArtist(AlbumID3 album) {
        if (album.getArtists() != null) {
            for (ArtistID3 credited : album.getArtists()) {
                if (credited.getId() != null) return credited;
            }
        }

        if (album.getArtistId() == null) return null;

        ArtistID3 artist = new ArtistID3();
        artist.setId(album.getArtistId());
        artist.setName(album.getArtist());
        return artist;
    }

    private void initAlbumNotes() {
        notesFold = new TextFold(bind.albumNotesTextView, bind.albumNotesExpandClickable,
                (ViewGroup) bind.fragmentAlbumPageNestedScrollView.getChildAt(0),
                R.string.artist_page_bio_expand, R.string.artist_page_bio_collapse);

        albumPageViewModel.getAlbumInfo().observe(getViewLifecycleOwner(), albumInfo -> {
            if (bind == null) return;

            String notes = albumInfo != null ? MusicUtil.forceReadableString(albumInfo.getNotes()) : "";

            if (notes.trim().isEmpty()) {
                bind.albumAboutSector.setVisibility(View.GONE);
                return;
            }

            bind.albumAboutSector.setVisibility(View.VISIBLE);
            notesFold.show(notes);

            String lastFm = albumInfo.getLastFmUrl();
            bind.albumNotesLastfmClickable.setVisibility(lastFm != null && !lastFm.isEmpty() ? View.VISIBLE : View.GONE);
            bind.albumNotesLastfmClickable.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse(lastFm));
                startActivity(intent);
            });
        });
    }

    private void initMusicButton() {
        Button play = bind.albumPageActions.pagePlayButton;
        Button shuffle = bind.albumPageActions.pageShuffleButton;

        play.setEnabled(false);
        shuffle.setEnabled(false);

        albumPageViewModel.getAlbumSongLiveList().observe(getViewLifecycleOwner(), songs -> {
            if (bind == null || songs == null) return;

            play.setEnabled(!songs.isEmpty());
            shuffle.setEnabled(!songs.isEmpty());

            play.setOnClickListener(v -> {
                MediaManager.startQueue(mediaBrowserListenableFuture, songs, 0);
                activity.setBottomSheetInPeek(true);
            });

            // A shuffled copy: shuffling the list itself reordered the
            // tracks the page was showing the next time it was bound.
            shuffle.setOnClickListener(v -> {
                List<Child> shuffled = new ArrayList<>(songs);
                Collections.shuffle(shuffled);
                MediaManager.startQueue(mediaBrowserListenableFuture, shuffled, 0);
                activity.setBottomSheetInPeek(true);
            });
        });
    }

    private void initBackCover() {
        albumPageViewModel.getAlbum().observe(getViewLifecycleOwner(), album -> {
            if (bind != null && album != null) {
                CustomGlideRequest.Builder.from(requireContext(), album.getCoverArtId(), CustomGlideRequest.ResourceType.Album).build().into(bind.albumCoverImageView);
            }
        });
    }

    private void initSongsView() {
        albumPageViewModel.getAlbum().observe(getViewLifecycleOwner(), album -> {
            if (bind != null && album != null) {
                bind.songRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
                bind.songRecyclerView.setHasFixedSize(true);

                songHorizontalAdapter = new SongHorizontalAdapter(this, false, false, album);

                // As many placeholder rows as the album says it has, so the
                // page is the length it will be before the tracks arrive.
                Integer tracks = album.getSongCount();
                SkeletonAdapter skeleton = new SkeletonAdapter(R.layout.skeleton_item_row_numbered,
                        tracks != null && tracks > 0 ? Math.min(tracks, 20) : 8);
                bind.songRecyclerView.setAdapter(new ConcatAdapter(skeleton, songHorizontalAdapter));

                albumPageViewModel.getAlbumSongLiveList().observe(getViewLifecycleOwner(), songs -> {
                    skeleton.hide();
                    songHorizontalAdapter.setItems(songs);
                });
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
        Navigation.findNavController(requireView()).navigate(R.id.songBottomSheetDialog, bundle);
    }
}