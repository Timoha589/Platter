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
import com.cappielloantonio.tempo.databinding.FragmentGenreCatalogueBinding;
import com.cappielloantonio.tempo.helper.recyclerview.GridItemDecoration;
import com.cappielloantonio.tempo.helper.view.PageHeaderTitle;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.adapter.SkeletonAdapter;
import com.cappielloantonio.tempo.ui.adapter.GenreCatalogueAdapter;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.viewmodel.GenreCatalogueViewModel;

@OptIn(markerClass = UnstableApi.class)
public class GenreCatalogueFragment extends Fragment implements ClickCallback {
    private FragmentGenreCatalogueBinding bind;
    private MainActivity activity;
    private GenreCatalogueViewModel genreCatalogueViewModel;

    private GenreCatalogueAdapter genreCatalogueAdapter;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        bind = FragmentGenreCatalogueBinding.inflate(inflater, container, false);
        View view = bind.getRoot();
        genreCatalogueViewModel = new ViewModelProvider(requireActivity()).get(GenreCatalogueViewModel.class);

        initAppBar();
        initGenreCatalogueView();

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind = null;
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

        PageHeaderTitle.attach(bind.appbar, bind.toolbar, bind.genreCatalogueTitle);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initGenreCatalogueView() {
        bind.genreCatalogueRecyclerView.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        int gap = getResources().getDimensionPixelSize(R.dimen.rail_card_gap);
        bind.genreCatalogueRecyclerView.addItemDecoration(new GridItemDecoration(2, gap, false));
        bind.genreCatalogueRecyclerView.setHasFixedSize(true);

        genreCatalogueAdapter = new GenreCatalogueAdapter(requireContext(), this);
        genreCatalogueAdapter.setStateRestorationPolicy(RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY);
        // Tiles in the grid's own cells until the genres come.
        SkeletonAdapter skeleton = new SkeletonAdapter(R.layout.skeleton_item_genre_tile, 12);
        bind.genreCatalogueRecyclerView.setAdapter(new ConcatAdapter(skeleton, genreCatalogueAdapter));

        genreCatalogueViewModel.getGenreList().observe(getViewLifecycleOwner(), genres -> {
            if (bind == null) return;
            skeleton.hide();

            // A failed request leaves whatever the list already shows.
            if (genres == null) return;

            genreCatalogueAdapter.setItems(genres);

            int count = genreCatalogueAdapter.getFullCount();
            bind.genreCatalogueCaption.setText(getResources().getQuantityString(R.plurals.genre_count, count, count));
        });

        bind.genreCatalogueRecyclerView.setOnTouchListener((v, event) -> {
            hideKeyboard(v);
            return false;
        });
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.genre_catalogue_menu, menu);

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
                genreCatalogueAdapter.getFilter().filter(newText);
                return false;
            }
        });

        searchView.setPadding(-32, 0, 0, 0);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_filter_genres) {
            activity.navController.navigate(R.id.action_genreCatalogueFragment_to_filterFragment);
            return true;
        } else if (item.getItemId() == R.id.action_sort_genres) {
            View anchor = bind.toolbar.findViewById(R.id.action_sort_genres);
            showPopupMenu(anchor != null ? anchor : bind.toolbar, R.menu.sort_genre_popup_menu);
            return true;
        }

        return false;
    }

    private void hideKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private void showPopupMenu(View view, int menuResource) {
        PopupMenu popup = new PopupMenu(requireContext(), view);
        popup.getMenuInflater().inflate(menuResource, popup.getMenu());

        popup.setOnMenuItemClickListener(menuItem -> {
            if (menuItem.getItemId() == R.id.menu_genre_sort_name) {
                genreCatalogueAdapter.sort(Constants.GENRE_ORDER_BY_NAME);
                return true;
            } else if (menuItem.getItemId() == R.id.menu_genre_sort_song_count) {
                genreCatalogueAdapter.sort(Constants.GENRE_ORDER_BY_SONG_COUNT);
                return true;
            } else if (menuItem.getItemId() == R.id.menu_genre_sort_random) {
                genreCatalogueAdapter.sort(Constants.GENRE_ORDER_BY_RANDOM);
                return true;
            }

            return false;
        });

        popup.show();
    }

    @Override
    public void onGenreClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.songListPageFragment, bundle);
        hideKeyboard(requireView());
    }
}