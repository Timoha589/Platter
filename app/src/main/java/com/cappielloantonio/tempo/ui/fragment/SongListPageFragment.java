package com.cappielloantonio.tempo.ui.fragment;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.PopupMenu;
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

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentSongListPageBinding;
import com.cappielloantonio.tempo.helper.recyclerview.PaginationScrollListener;
import com.cappielloantonio.tempo.helper.view.PageHeaderTitle;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.adapter.SkeletonAdapter;
import com.cappielloantonio.tempo.ui.adapter.PageActionsAdapter;
import com.cappielloantonio.tempo.ui.adapter.SongHorizontalAdapter;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.GenreNames;
import com.cappielloantonio.tempo.viewmodel.SongListPageViewModel;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@UnstableApi
public class SongListPageFragment extends Fragment implements ClickCallback {
    private static final String TAG = "SongListPageFragment";

    private FragmentSongListPageBinding bind;
    private MainActivity activity;
    private SongListPageViewModel songListPageViewModel;

    private SongHorizontalAdapter songHorizontalAdapter;
    private PageActionsAdapter actionsAdapter;

    /** The tracks as last read, in the list's own order. */
    @Nullable
    private List<Child> songs;

    @Nullable
    private MenuItem sortItem;

    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    private boolean isLoading = true;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        bind = FragmentSongListPageBinding.inflate(inflater, container, false);
        View view = bind.getRoot();
        songListPageViewModel = new ViewModelProvider(requireActivity()).get(SongListPageViewModel.class);

        init();
        initAppBar();
        initSongListView();

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
        if (requireArguments().getString(Constants.MEDIA_RECENTLY_PLAYED) != null) {
            songListPageViewModel.title = Constants.MEDIA_RECENTLY_PLAYED;
            songListPageViewModel.toolbarTitle = getString(R.string.song_list_page_recently_played);
            bind.pageTitleLabel.setText(R.string.song_list_page_recently_played);
        } else if (requireArguments().getString(Constants.MEDIA_MOST_PLAYED) != null) {
            songListPageViewModel.title = Constants.MEDIA_MOST_PLAYED;
            songListPageViewModel.toolbarTitle = getString(R.string.song_list_page_most_played);
            bind.pageTitleLabel.setText(R.string.song_list_page_most_played);
        } else if (requireArguments().getString(Constants.MEDIA_RECENTLY_ADDED) != null) {
            songListPageViewModel.title = Constants.MEDIA_RECENTLY_ADDED;
            songListPageViewModel.toolbarTitle = getString(R.string.song_list_page_recently_added);
            bind.pageTitleLabel.setText(R.string.song_list_page_recently_added);
        } else if (requireArguments().getString(Constants.MEDIA_BY_GENRE) != null) {
            songListPageViewModel.title = Constants.MEDIA_BY_GENRE;
            songListPageViewModel.genre = requireArguments().getParcelable(Constants.GENRE_OBJECT);
            songListPageViewModel.toolbarTitle = GenreNames.label(requireContext(), songListPageViewModel.genre.getGenre());
            bind.pageTitleLabel.setText(songListPageViewModel.toolbarTitle);

            String about = GenreNames.about(requireContext(), songListPageViewModel.genre.getGenre());
            bind.pageDescriptionLabel.setText(about);
            bind.pageDescriptionLabel.setVisibility(about != null ? View.VISIBLE : View.GONE);
        } else if (requireArguments().getString(Constants.MEDIA_BY_ARTIST) != null) {
            songListPageViewModel.title = Constants.MEDIA_BY_ARTIST;
            songListPageViewModel.artist = requireArguments().getParcelable(Constants.ARTIST_OBJECT);
            songListPageViewModel.toolbarTitle = getString(R.string.song_list_page_top, songListPageViewModel.artist.getName());
            bind.pageTitleLabel.setText(getString(R.string.song_list_page_top, songListPageViewModel.artist.getName()));
        } else if (requireArguments().getString(Constants.MEDIA_BY_GENRES) != null) {
            songListPageViewModel.title = Constants.MEDIA_BY_GENRES;
            songListPageViewModel.filters = requireArguments().getStringArrayList("filters_list");
            songListPageViewModel.filterNames = requireArguments().getStringArrayList("filter_name_list");
            songListPageViewModel.toolbarTitle = songListPageViewModel.getFiltersTitle();
            bind.pageTitleLabel.setText(songListPageViewModel.getFiltersTitle());
        } else if (requireArguments().getString(Constants.MEDIA_BY_YEAR) != null) {
            songListPageViewModel.title = Constants.MEDIA_BY_YEAR;
            songListPageViewModel.year = requireArguments().getInt("year_object");
            songListPageViewModel.toolbarTitle = getString(R.string.song_list_page_year, songListPageViewModel.year);
            bind.pageTitleLabel.setText(getString(R.string.song_list_page_year, songListPageViewModel.year));
        } else if (requireArguments().getString(Constants.MEDIA_DOWNLOADED) != null) {
            songListPageViewModel.title = Constants.MEDIA_DOWNLOADED;
            songListPageViewModel.toolbarTitle = getString(R.string.song_list_page_downloaded);
            bind.pageTitleLabel.setText(getString(R.string.song_list_page_downloaded));
        } else if (requireArguments().getParcelable(Constants.ALBUM_OBJECT) != null) {
            songListPageViewModel.album = requireArguments().getParcelable(Constants.ALBUM_OBJECT);
            songListPageViewModel.title = Constants.MEDIA_FROM_ALBUM;
            songListPageViewModel.toolbarTitle = songListPageViewModel.album.getName();
            bind.pageTitleLabel.setText(songListPageViewModel.album.getName());
        }
    }

    private void initAppBar() {
        activity.setSupportActionBar(bind.toolbar);

        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            activity.getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        if (bind != null)
            bind.toolbar.setNavigationOnClickListener(v -> {
                hideKeyboard(v);
                activity.navController.navigateUp();
            });

        PageHeaderTitle.attach(bind.appbar, bind.toolbar, bind.pageTitleLabel);
    }

    /*
     * Play and Shuffle, as the first row of the list - the one a playlist
     * opens with. The page used to offer only a round shuffle button in its
     * header, which started 25 random tracks: Play is the list in its order.
     */
    private void play(boolean shuffled) {
        if (songs == null || songs.isEmpty()) return;

        List<Child> queue = new ArrayList<>(songs);
        if (shuffled) Collections.shuffle(queue);

        MediaManager.startQueue(mediaBrowserListenableFuture, queue.subList(0, Math.min(Constants.PLAYABLE_MEDIA_LIMIT, queue.size())), 0);
        activity.setBottomSheetInPeek(true);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initSongListView() {
        bind.songListRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        bind.songListRecyclerView.setHasFixedSize(true);

        actionsAdapter = new PageActionsAdapter(v -> play(false), v -> play(true));
        actionsAdapter.setEnabled(false);

        songHorizontalAdapter = new SongHorizontalAdapter(this, true, false, null);
        SkeletonAdapter skeleton = new SkeletonAdapter(R.layout.skeleton_item_row, 10);
        bind.songListRecyclerView.setAdapter(new ConcatAdapter(
                new ConcatAdapter.Config.Builder().setIsolateViewTypes(true).build(),
                actionsAdapter,
                skeleton,
                songHorizontalAdapter));

        songListPageViewModel.getSongList().observe(getViewLifecycleOwner(), songs -> {
            isLoading = false;
            if (bind == null) return;
            skeleton.hide();

            /* null is a failed request, not an empty library - leave the page as it stands */
            if (songs == null) return;

            this.songs = songs;

            songHorizontalAdapter.setItems(songs);
            actionsAdapter.setEnabled(!songs.isEmpty());
            bind.pageCaptionLabel.setText(caption(songs));
            updateSortItem();
        });

        bind.songListRecyclerView.addOnScrollListener(new PaginationScrollListener((LinearLayoutManager) bind.songListRecyclerView.getLayoutManager()) {
            @Override
            protected void loadMoreItems() {
                isLoading = true;
                songListPageViewModel.getSongsByPage(getViewLifecycleOwner());
            }

            @Override
            public boolean isLoading() {
                return isLoading;
            }
        });

        bind.songListRecyclerView.setOnTouchListener((v, event) -> {
            hideKeyboard(v);
            return false;
        });
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.catalogue_menu, menu);

        sortItem = menu.findItem(R.id.action_sort);
        updateSortItem();

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
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_sort) {
            View anchor = bind.toolbar.findViewById(R.id.action_sort);
            showPopupMenu(anchor != null ? anchor : bind.toolbar, R.menu.sort_song_popup_menu);
            return true;
        }

        return false;
    }

    private void hideKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private void showPopupMenu(View view, int menuResource) {
        PopupMenu popup = new PopupMenu(requireContext(), view);
        popup.getMenuInflater().inflate(menuResource, popup.getMenu());

        popup.setOnMenuItemClickListener(menuItem -> {
            if (menuItem.getItemId() == R.id.menu_song_sort_name) {
                songHorizontalAdapter.sort(Constants.MEDIA_BY_TITLE);
                return true;
            } else if (menuItem.getItemId() == R.id.menu_song_sort_most_recently_starred) {
                songHorizontalAdapter.sort(Constants.MEDIA_MOST_RECENTLY_STARRED);
                return true;
            } else if (menuItem.getItemId() == R.id.menu_song_sort_least_recently_starred) {
                songHorizontalAdapter.sort(Constants.MEDIA_LEAST_RECENTLY_STARRED);
                return true;
            }

            return false;
        });

        popup.show();
    }

    /**
     * How many tracks the list holds, as "124 трека". A genre knows its own
     * count; a list read a page at a time that has filled its page says there
     * are more ("100+ треков"). It used to read "(+100)" beside the title,
     * and nothing at all on most of the lists this page shows.
     */
    private String caption(List<Child> children) {
        int count = children.size();

        switch (songListPageViewModel.title) {
            case Constants.MEDIA_BY_GENRE:
                int genreCount = songListPageViewModel.genre.getSongCount();
                if (genreCount > 0) count = genreCount;
                else if (count >= songListPageViewModel.maxNumberByGenre) return getString(R.string.song_list_count_more, count);
                break;
            case Constants.MEDIA_BY_YEAR:
                if (count >= songListPageViewModel.maxNumberByYear) return getString(R.string.song_list_count_more, count);
                break;
        }

        return getResources().getQuantityString(R.plurals.album_page_tracks_count, count, count);
    }

    /** Sorting is offered where the order is the page's own to change: an artist's tracks, a pick of genres. */
    private void updateSortItem() {
        if (sortItem == null || songListPageViewModel.title == null) return;

        switch (songListPageViewModel.title) {
            case Constants.MEDIA_BY_ARTIST:
            case Constants.MEDIA_BY_GENRES:
                sortItem.setVisible(true);
                break;
            default:
                sortItem.setVisible(false);
                break;
        }
    }

    private void initializeMediaBrowser() {
        mediaBrowserListenableFuture = new MediaBrowser.Builder(requireContext(), new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
    }

    private void releaseMediaBrowser() {
        MediaBrowser.releaseFuture(mediaBrowserListenableFuture);
    }

    @Override
    public void onMediaClick(Bundle bundle) {
        hideKeyboard(requireView());
        MediaManager.startQueue(mediaBrowserListenableFuture, bundle.getParcelableArrayList(Constants.TRACKS_OBJECT), bundle.getInt(Constants.ITEM_POSITION));
        activity.setBottomSheetInPeek(true);
    }

    @Override
    public void onMediaLongClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.songBottomSheetDialog, bundle);
    }
}