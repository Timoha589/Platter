package com.cappielloantonio.tempo.ui.fragment;

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
import com.cappielloantonio.tempo.databinding.FragmentLikedTracksBinding;
import com.cappielloantonio.tempo.helper.view.PageHeaderTitle;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.service.LikedTracksCache;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.adapter.SkeletonAdapter;
import com.cappielloantonio.tempo.ui.adapter.PageActionsAdapter;
import com.cappielloantonio.tempo.ui.adapter.SongHorizontalAdapter;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.MusicUtil;
import com.cappielloantonio.tempo.util.Preferences;
import com.cappielloantonio.tempo.viewmodel.LikedTracksViewModel;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The liked tracks, laid out as a playlist is: cover, count and length, play
 * and shuffle - since to the user they are just that, one long list played
 * from.
 */
@UnstableApi
public class LikedTracksFragment extends Fragment implements ClickCallback {
    private FragmentLikedTracksBinding bind;
    private MainActivity activity;
    private LikedTracksViewModel likedTracksViewModel;

    private SongHorizontalAdapter songHorizontalAdapter;
    private PageActionsAdapter actionsAdapter;

    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    /* What play and shuffle start from: the whole list, not what a search has filtered it to. */
    private final List<Child> likedTracks = new ArrayList<>();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.liked_tracks_page_menu, menu);

        menu.findItem(R.id.action_cache_liked_tracks).setChecked(Preferences.isLikedTracksCacheEnabled());

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

    /**
     * "Cache liked tracks" is kept in the overflow, where a playlist keeps its
     * "Download all". Ticking it fetches every liked track not yet on the
     * device, and every like after that is downloaded as it happens - see
     * LikedTracksCache.
     */
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_cache_liked_tracks) {
            boolean isEnabled = !item.isChecked();

            item.setChecked(isEnabled);
            Preferences.setLikedTracksCacheEnabled(isEnabled);

            if (isEnabled) LikedTracksCache.sync(requireContext());

            return true;
        }

        return false;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        bind = FragmentLikedTracksBinding.inflate(inflater, container, false);
        View view = bind.getRoot();
        likedTracksViewModel = new ViewModelProvider(requireActivity()).get(LikedTracksViewModel.class);

        initAppBar();
        initTracksView();

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

        Objects.requireNonNull(bind.toolbar.getOverflowIcon()).setTint(requireContext().getResources().getColor(R.color.titleTextColor, null));

        PageHeaderTitle.attach(bind.appbar, bind.toolbar, bind.likedTracksNameLabel);
    }

    private void play(boolean shuffle) {
        if (likedTracks.isEmpty()) return;

        // A copy: shuffling the list itself would reorder the page under the user.
        List<Child> queue = new ArrayList<>(likedTracks);
        if (shuffle) Collections.shuffle(queue);

        MediaManager.startQueue(mediaBrowserListenableFuture, queue.subList(0, Math.min(Constants.PLAYABLE_MEDIA_LIMIT, queue.size())), 0);
        activity.setBottomSheetInPeek(true);
    }

    private void initTracksView() {
        bind.likedTracksRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        bind.likedTracksRecyclerView.setHasFixedSize(true);

        actionsAdapter = new PageActionsAdapter(v -> play(false), v -> play(true));
        actionsAdapter.setEnabled(false);

        songHorizontalAdapter = new SongHorizontalAdapter(this, true, false, null);
        SkeletonAdapter skeleton = new SkeletonAdapter(R.layout.skeleton_item_row, 10);
        bind.likedTracksRecyclerView.setAdapter(new ConcatAdapter(
                new ConcatAdapter.Config.Builder().setIsolateViewTypes(true).build(),
                actionsAdapter,
                skeleton,
                songHorizontalAdapter));

        likedTracksViewModel.getLikedTracks(getViewLifecycleOwner()).observe(getViewLifecycleOwner(), songs -> {
            if (bind == null) return;
            skeleton.hide();

            // null is a failed request, not an empty list: leave the page as it stands.
            if (songs == null) return;

            likedTracks.clear();
            likedTracks.addAll(songs);

            songHorizontalAdapter.setItems(songs);
            actionsAdapter.setEnabled(!songs.isEmpty());

            bind.likedTracksCaptionLabel.setText(MusicUtil.getPlaylistCaption(requireContext(), songs.size(), totalDuration(songs)));
        });
    }

    private long totalDuration(List<Child> songs) {
        long seconds = 0;

        for (Child song : songs) {
            if (song.getDuration() != null) seconds += song.getDuration();
        }

        return seconds;
    }

    private void hideKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
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
