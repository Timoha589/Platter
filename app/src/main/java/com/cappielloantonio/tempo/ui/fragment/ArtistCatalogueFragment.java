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
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentArtistCatalogueBinding;
import com.cappielloantonio.tempo.helper.recyclerview.GridItemDecoration;
import com.cappielloantonio.tempo.helper.view.PageHeaderTitle;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.adapter.SkeletonAdapter;
import com.cappielloantonio.tempo.ui.adapter.ArtistCatalogueAdapter;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.viewmodel.ArtistCatalogueViewModel;

@UnstableApi
public class ArtistCatalogueFragment extends Fragment implements ClickCallback {
    private static final String TAG = "ArtistCatalogueFragment";

    private FragmentArtistCatalogueBinding bind;
    private MainActivity activity;
    private ArtistCatalogueViewModel artistCatalogueViewModel;

    private ArtistCatalogueAdapter artistAdapter;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);

        initData();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        bind = FragmentArtistCatalogueBinding.inflate(inflater, container, false);
        View view = bind.getRoot();

        initAppBar();
        initArtistCatalogueView();

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind = null;
    }

    private void initData() {
        artistCatalogueViewModel = new ViewModelProvider(requireActivity()).get(ArtistCatalogueViewModel.class);
        artistCatalogueViewModel.loadArtists();
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

        PageHeaderTitle.attach(bind.appbar, bind.toolbar, bind.artistCatalogueTitle);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initArtistCatalogueView() {
        bind.artistCatalogueRecyclerView.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        int gap = getResources().getDimensionPixelSize(R.dimen.rail_card_gap);
        bind.artistCatalogueRecyclerView.addItemDecoration(new GridItemDecoration(2, gap, false));
        bind.artistCatalogueRecyclerView.setHasFixedSize(true);

        artistAdapter = new ArtistCatalogueAdapter(this);
        artistAdapter.setStateRestorationPolicy(RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY);
        SkeletonAdapter skeleton = new SkeletonAdapter(R.layout.skeleton_item_grid_circle, 8);
        bind.artistCatalogueRecyclerView.setAdapter(new ConcatAdapter(skeleton, artistAdapter));
        artistCatalogueViewModel.getArtistList().observe(getViewLifecycleOwner(), artistList -> {
            // Any answer ends the loading; null is a failed request.
            skeleton.hide();
            if (artistList == null) return;

            artistAdapter.setItems(artistList);

            if (bind != null) {
                bind.artistCatalogueCaption.setText(getResources().getQuantityString(R.plurals.artist_count, artistList.size(), artistList.size()));
            }
        });

        bind.artistCatalogueRecyclerView.setOnTouchListener((v, event) -> {
            hideKeyboard(v);
            return false;
        });
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.catalogue_menu, menu);

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
                artistAdapter.getFilter().filter(newText);
                return false;
            }
        });

        searchView.setPadding(-32, 0, 0, 0);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_sort) {
            View anchor = bind.toolbar.findViewById(R.id.action_sort);
            showPopupMenu(anchor != null ? anchor : bind.toolbar, R.menu.sort_artist_popup_menu);
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
            if (menuItem.getItemId() == R.id.menu_artist_sort_name) {
                artistAdapter.sort(Constants.ARTIST_ORDER_BY_NAME);
                return true;
            } else if (menuItem.getItemId() == R.id.menu_artist_sort_random) {
                artistAdapter.sort(Constants.ARTIST_ORDER_BY_RANDOM);
                return true;
            }

            return false;
        });

        popup.show();
    }

    @Override
    public void onArtistClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.artistPageFragment, bundle);
        hideKeyboard(requireView());
    }

    @Override
    public void onArtistLongClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.artistBottomSheetDialog, bundle);
    }
}