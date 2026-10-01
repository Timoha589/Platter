package com.cappielloantonio.tempo.ui.fragment;

import android.content.ComponentName;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentDownloadBinding;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.adapter.SongHorizontalAdapter;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.MusicUtil;
import com.cappielloantonio.tempo.util.Preferences;
import com.cappielloantonio.tempo.viewmodel.DownloadViewModel;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@UnstableApi
public class DownloadFragment extends Fragment implements ClickCallback {
    private static final String TAG = "DownloadFragment";

    private FragmentDownloadBinding bind;
    private MainActivity activity;
    private DownloadViewModel downloadViewModel;

    private SongHorizontalAdapter songHorizontalAdapter;

    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    private MaterialToolbar materialToolbar;

    private final List<Download> allDownloads = new ArrayList<>();

    /* The ones the chips leave on screen, which is what play and shuffle play. */
    private final List<Child> downloadedTracks = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        bind = FragmentDownloadBinding.inflate(inflater, container, false);
        View view = bind.getRoot();
        downloadViewModel = new ViewModelProvider(requireActivity()).get(DownloadViewModel.class);

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        initAppBar();
        initDownloadedView();
    }

    @Override
    public void onStart() {
        super.onStart();

        initializeMediaBrowser();
        activity.setBottomNavigationBarVisibility(true);
        activity.setBottomSheetVisibility(true);
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

    private void initAppBar() {
        materialToolbar = bind.getRoot().findViewById(R.id.toolbar);

        activity.setSupportActionBar(materialToolbar);
        Objects.requireNonNull(materialToolbar.getOverflowIcon()).setTint(requireContext().getResources().getColor(R.color.titleTextColor, null));
    }

    private void initDownloadedView() {
        bind.downloadedRecyclerView.setHasFixedSize(true);
        bind.downloadedRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        songHorizontalAdapter = new SongHorizontalAdapter(this, true, false, null, false);
        bind.downloadedRecyclerView.setAdapter(songHorizontalAdapter);

        bind.downloadFilterChipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            downloadViewModel.setShownSource(checkedIds.isEmpty() ? null : sourceOf(checkedIds.get(0)));
            showDownloads();
        });

        downloadViewModel.getDownloadedTracks().observe(getViewLifecycleOwner(), downloads -> {
            if (downloads == null || bind == null) return;

            allDownloads.clear();
            allDownloads.addAll(downloads);

            bind.emptyDownloadLayout.setVisibility(downloads.isEmpty() ? View.VISIBLE : View.GONE);
            bind.downloadDownloadedSector.setVisibility(downloads.isEmpty() ? View.GONE : View.VISIBLE);

            showDownloads();

            bind.loadingProgressBar.setVisibility(View.GONE);
        });

        bind.playDownloadedButton.setOnClickListener(view -> play(false));
        bind.shuffleDownloadedButton.setOnClickListener(view -> play(true));
    }

    /**
     * The list under the chip picked. The chips only come up when the tracks
     * are here for more than one reason; with the one, what the line under the
     * count says is still worth knowing - unless it is only that the user
     * downloaded them, which they know.
     */
    private void showDownloads() {
        if (allDownloads.isEmpty()) return;

        Set<Integer> present = allDownloads.stream().map(Download::getDownloadSource).collect(Collectors.toSet());
        boolean mixed = present.size() > 1;

        Integer source = downloadViewModel.getShownSource();

        // The last of a kind may have just gone, taking its chip with it.
        if (source != null && (!mixed || !present.contains(source))) {
            source = null;
            downloadViewModel.setShownSource(null);
        }

        bind.downloadFilterScrollView.setVisibility(mixed ? View.VISIBLE : View.GONE);
        bind.downloadFilterManualChip.setVisibility(present.contains(Download.SOURCE_MANUAL) ? View.VISIBLE : View.GONE);
        bind.downloadFilterLikedChip.setVisibility(present.contains(Download.SOURCE_LIKED) ? View.VISIBLE : View.GONE);
        bind.downloadFilterSmartChip.setVisibility(present.contains(Download.SOURCE_SMART) ? View.VISIBLE : View.GONE);

        int chip = chipOf(source);
        if (bind.downloadFilterChipGroup.getCheckedChipId() != chip) {
            // Calls back into here, with the same source.
            bind.downloadFilterChipGroup.check(chip);
            return;
        }

        Integer explained = source != null ? source : mixed ? null : present.iterator().next();
        String explanation = explanationOf(explained);
        bind.downloadedSourceTextView.setVisibility(explanation != null ? View.VISIBLE : View.GONE);
        bind.downloadedSourceTextView.setText(explanation);

        final Integer shown = source;
        List<Child> songs = allDownloads.stream()
                .filter(download -> shown == null || download.getDownloadSource() == shown)
                .collect(Collectors.toList());

        downloadedTracks.clear();
        downloadedTracks.addAll(songs);

        bind.downloadedCountTextView.setText(getString(
                R.string.download_counted_tracks,
                getResources().getQuantityString(R.plurals.download_track_count, songs.size(), songs.size()),
                MusicUtil.getReadableDurationString(totalDuration(songs), false)
        ));

        // In the mixed list, the ones that came on their own say so, and why.
        Map<String, Integer> badges = new HashMap<>();
        if (shown == null && mixed) {
            for (Download download : allDownloads) {
                if (download.getDownloadSource() == Download.SOURCE_LIKED) badges.put(download.getId(), R.drawable.ic_favorite);
                if (download.getDownloadSource() == Download.SOURCE_SMART) badges.put(download.getId(), R.drawable.ic_history);
            }
        }

        songHorizontalAdapter.setBadges(badges);
        songHorizontalAdapter.setItems(songs);
    }

    @Nullable
    private String explanationOf(@Nullable Integer source) {
        if (source == null) return null;

        switch (source) {
            case Download.SOURCE_MANUAL:
                return bind.downloadFilterScrollView.getVisibility() == View.VISIBLE ? getString(R.string.download_source_manual) : null;
            case Download.SOURCE_LIKED:
                return getString(R.string.download_source_liked);
            case Download.SOURCE_SMART:
                if (!Preferences.isSmartDownloadEnabled()) return getString(R.string.download_source_smart_off);
                int size = Preferences.getSmartDownloadCacheSize();
                return getString(R.string.download_source_smart, getResources().getQuantityString(R.plurals.download_track_count, size, size));
            default:
                return null;
        }
    }

    @Nullable
    private static Integer sourceOf(int chipId) {
        if (chipId == R.id.download_filter_manual_chip) return Download.SOURCE_MANUAL;
        if (chipId == R.id.download_filter_liked_chip) return Download.SOURCE_LIKED;
        if (chipId == R.id.download_filter_smart_chip) return Download.SOURCE_SMART;
        return null;
    }

    private static int chipOf(@Nullable Integer source) {
        if (source == null) return R.id.download_filter_all_chip;

        switch (source) {
            case Download.SOURCE_MANUAL:
                return R.id.download_filter_manual_chip;
            case Download.SOURCE_LIKED:
                return R.id.download_filter_liked_chip;
            case Download.SOURCE_SMART:
                return R.id.download_filter_smart_chip;
            default:
                return R.id.download_filter_all_chip;
        }
    }

    private void play(boolean shuffle) {
        if (downloadedTracks.isEmpty()) return;

        List<Child> queue = new ArrayList<>(downloadedTracks);
        if (shuffle) Collections.shuffle(queue);

        MediaManager.startQueue(mediaBrowserListenableFuture, queue, 0);
        activity.setBottomSheetInPeek(true);
    }

    private long totalDuration(List<Child> songs) {
        long seconds = 0;

        for (Child song : songs) {
            if (song.getDuration() != null) seconds += song.getDuration();
        }

        return seconds;
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
