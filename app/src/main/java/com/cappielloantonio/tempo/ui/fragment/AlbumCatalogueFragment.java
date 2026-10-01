package com.cappielloantonio.tempo.ui.fragment;

import android.annotation.SuppressLint;
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
import androidx.annotation.OptIn;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentAlbumCatalogueBinding;
import com.cappielloantonio.tempo.helper.recyclerview.GridItemDecoration;
import com.cappielloantonio.tempo.helper.view.PageHeaderTitle;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.adapter.SkeletonAdapter;
import com.cappielloantonio.tempo.ui.adapter.AlbumCatalogueAdapter;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.viewmodel.AlbumCatalogueViewModel;

import java.util.List;

@OptIn(markerClass = UnstableApi.class)
public class AlbumCatalogueFragment extends Fragment implements ClickCallback {
    private static final String TAG = "ArtistCatalogueFragment";

    private FragmentAlbumCatalogueBinding bind;
    private MainActivity activity;
    private AlbumCatalogueViewModel albumCatalogueViewModel;

    private AlbumCatalogueAdapter albumAdapter;
    private SkeletonAdapter skeletonAdapter;

    @Nullable
    private MenuItem sortItem;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);

        initData();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        albumCatalogueViewModel.stopLoading();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        bind = FragmentAlbumCatalogueBinding.inflate(inflater, container, false);
        View view = bind.getRoot();

        initAppBar();
        initAlbumCatalogueView();
        initProgressLoader();

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind = null;
    }

    private void initData() {
        albumCatalogueViewModel = new ViewModelProvider(requireActivity()).get(AlbumCatalogueViewModel.class);
        albumCatalogueViewModel.loadAlbums();
    }

    private void initAppBar() {
        activity.setSupportActionBar(bind.toolbar);

        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            activity.getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        bind.toolbar.setNavigationOnClickListener(v -> {
            hideKeyboard(v);
            activity.navController.navigateUp();
        });

        PageHeaderTitle.attach(bind.appbar, bind.toolbar, bind.albumCatalogueTitle);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initAlbumCatalogueView() {
        bind.albumCatalogueRecyclerView.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        int gap = getResources().getDimensionPixelSize(R.dimen.rail_card_gap);
        bind.albumCatalogueRecyclerView.addItemDecoration(new GridItemDecoration(2, gap, false));
        bind.albumCatalogueRecyclerView.setHasFixedSize(true);

        albumAdapter = new AlbumCatalogueAdapter(this, true);
        albumAdapter.setStateRestorationPolicy(RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY);
        skeletonAdapter = new SkeletonAdapter(R.layout.skeleton_item_grid_square, 8);
        bind.albumCatalogueRecyclerView.setAdapter(new ConcatAdapter(skeletonAdapter, albumAdapter));
        albumCatalogueViewModel.getAlbumList().observe(getViewLifecycleOwner(), albums -> {
            albumAdapter.setItems(albums);

            if (bind != null && albums != null) {
                bind.albumCatalogueCaption.setText(getResources().getQuantityString(R.plurals.album_count, albums.size(), albums.size()));
            }

            showLoader();
        });

        bind.albumCatalogueRecyclerView.setOnTouchListener((v, event) -> {
            hideKeyboard(v);
            return false;
        });
    }

    private void initProgressLoader() {
        albumCatalogueViewModel.getLoadingStatus().observe(getViewLifecycleOwner(), isLoading -> {
            if (bind == null) return;

            // Sorting a list still arriving would be undone by the next read.
            if (sortItem != null) sortItem.setEnabled(!isLoading);
            showLoader();
        });
    }

    /*
     * Placeholder covers stand in for the grid only while there is nothing in
     * it. Once the first read has landed the covers are the page, and the
     * count in the header climbing with each read says the rest is on its way.
     * The list starts out empty rather than without a value, so it is the
     * loading flag that tells an empty library from one still being read.
     */
    private void showLoader() {
        if (bind == null) return;

        Boolean loading = albumCatalogueViewModel.getLoadingStatus().getValue();
        List<AlbumID3> albums = albumCatalogueViewModel.getAlbumList().getValue();
        boolean empty = albums == null || albums.isEmpty();
        if (loading != null && loading && empty) skeletonAdapter.show();
        else skeletonAdapter.hide();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.catalogue_menu, menu);

        sortItem = menu.findItem(R.id.action_sort);
        Boolean loading = albumCatalogueViewModel.getLoadingStatus().getValue();
        sortItem.setEnabled(loading == null || !loading);

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
                albumAdapter.getFilter().filter(newText);
                return false;
            }
        });

        searchView.setPadding(-32, 0, 0, 0);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_sort) {
            View anchor = bind.toolbar.findViewById(R.id.action_sort);
            showPopupMenu(anchor != null ? anchor : bind.toolbar, R.menu.sort_album_popup_menu);
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
            if (menuItem.getItemId() == R.id.menu_album_sort_name) {
                albumAdapter.sort(Constants.ALBUM_ORDER_BY_NAME);
                return true;
            } else if (menuItem.getItemId() == R.id.menu_album_sort_artist) {
                albumAdapter.sort(Constants.ALBUM_ORDER_BY_ARTIST);
                return true;
            } else if (menuItem.getItemId() == R.id.menu_album_sort_year) {
                albumAdapter.sort(Constants.ALBUM_ORDER_BY_YEAR);
                return true;
            } else if (menuItem.getItemId() == R.id.menu_album_sort_random) {
                albumAdapter.sort(Constants.ALBUM_ORDER_BY_RANDOM);
                return true;
            } else if (menuItem.getItemId() == R.id.menu_album_sort_recently_added) {
                albumAdapter.sort(Constants.ALBUM_ORDER_BY_RECENTLY_ADDED);
                return true;
            } else if (menuItem.getItemId() == R.id.menu_album_sort_recently_played) {
                albumAdapter.sort(Constants.ALBUM_ORDER_BY_RECENTLY_PLAYED);
                return true;
            } else if (menuItem.getItemId() == R.id.menu_album_sort_most_played) {
                albumAdapter.sort(Constants.ALBUM_ORDER_BY_MOST_PLAYED);
                return true;
            }

            return false;
        });

        popup.show();
    }

    @Override
    public void onAlbumClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.albumPageFragment, bundle);
        hideKeyboard(requireView());
    }

    @Override
    public void onAlbumLongClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.albumBottomSheetDialog, bundle);
    }
}