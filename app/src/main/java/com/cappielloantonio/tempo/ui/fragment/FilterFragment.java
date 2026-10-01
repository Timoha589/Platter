package com.cappielloantonio.tempo.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentFilterBinding;
import com.cappielloantonio.tempo.subsonic.models.Genre;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.GenreNames;
import com.cappielloantonio.tempo.util.MusicUtil;
import com.cappielloantonio.tempo.viewmodel.FilterViewModel;
import com.google.android.material.chip.Chip;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@OptIn(markerClass = UnstableApi.class)
public class FilterFragment extends Fragment {
    private static final String TAG = "FilterFragment";

    private MainActivity activity;
    private FragmentFilterBinding bind;
    private FilterViewModel filterViewModel;


    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();
        bind = FragmentFilterBinding.inflate(inflater, container, false);
        View view = bind.getRoot();
        filterViewModel = new ViewModelProvider(requireActivity()).get(FilterViewModel.class);

        init();
        initAppBar();
        setFilterChips();
        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind = null;
    }

    private void init() {
        Bundle bundle = new Bundle();
        bundle.putString(Constants.MEDIA_BY_GENRES, Constants.MEDIA_BY_GENRES);
        bundle.putStringArrayList("filters_list", filterViewModel.getFilters());
        bundle.putStringArrayList("filter_name_list", filterViewModel.getFilterNames());
        bind.finishFilteringTextViewClickable.setOnClickListener(v -> {
            if (filterViewModel.getFilterNames().size() > 1)
                activity.navController.navigate(R.id.action_filterFragment_to_songListPageFragment, bundle);
            else
                Toast.makeText(requireContext(), getString(R.string.filter_info_selection), Toast.LENGTH_SHORT).show();
        });
    }

    private void initAppBar() {
        activity.setSupportActionBar(bind.toolbar);

        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            activity.getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        bind.toolbar.setNavigationOnClickListener(v -> activity.navController.navigateUp());


        bind.appBarLayout.addOnOffsetChangedListener((appBarLayout, verticalOffset) -> {
            if ((bind.genreFilterInfoSector.getHeight() + verticalOffset) < (2 * ViewCompat.getMinimumHeight(bind.toolbar))) {
                bind.toolbar.setTitle(R.string.filter_title);
            } else {
                bind.toolbar.setTitle(R.string.empty_string);
            }
        });
    }

    private void setFilterChips() {
        filterViewModel.getGenreList().observe(getViewLifecycleOwner(), genres -> {
            // A failed request: nothing to offer, as before it reported one.
            if (genres == null) return;

            bind.loadingProgressBar.setVisibility(View.GONE);
            bind.filterContainer.setVisibility(View.VISIBLE);
            // In the order of the names shown, not the server's.
            Collator collator = Collator.getInstance(Locale.getDefault());
            collator.setStrength(Collator.PRIMARY);
            List<Genre> sorted = new ArrayList<>(genres);
            sorted.sort((a, b) -> collator.compare(GenreNames.label(requireContext(), a.getGenre()), GenreNames.label(requireContext(), b.getGenre())));

            for (Genre genre : sorted) {
                Chip chip = (Chip) requireActivity().getLayoutInflater().inflate(R.layout.chip_search_filter_genre, null, false);
                chip.setText(GenreNames.label(requireContext(), genre.getGenre()));
                chip.setChecked(filterViewModel.getFilters().contains(genre.getGenre()));
                chip.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isChecked)
                        filterViewModel.addFilter(genre.names(), buttonView.getText().toString());
                    else
                        filterViewModel.removeFilter(genre.names(), buttonView.getText().toString());
                });
                bind.filtersChipsGroup.addView(chip);
            }
        });
    }
}
