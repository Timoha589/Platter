package com.cappielloantonio.tempo.ui.fragment;

import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentSearchBinding;
import com.cappielloantonio.tempo.deemix.DeemixQuota;
import com.cappielloantonio.tempo.deemix.DeezerPreviewPlayer;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.helper.recyclerview.CustomLinearSnapHelper;
import com.cappielloantonio.tempo.helper.search.QueryVariants;
import com.cappielloantonio.tempo.helper.search.RankedResults;
import com.cappielloantonio.tempo.helper.view.SkeletonView;
import com.cappielloantonio.tempo.lyricsearch.LyricMatch;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.service.MediaManager;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.cappielloantonio.tempo.ui.adapter.AlbumAdapter;
import com.cappielloantonio.tempo.ui.adapter.ArtistAdapter;
import com.cappielloantonio.tempo.ui.adapter.DeezerArtistAdapter;
import com.cappielloantonio.tempo.ui.adapter.DeezerResultAdapter;
import com.cappielloantonio.tempo.ui.adapter.LyricMatchAdapter;
import com.cappielloantonio.tempo.ui.adapter.SongHorizontalAdapter;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.MusicUtil;
import com.cappielloantonio.tempo.viewmodel.DeezerHit;
import com.cappielloantonio.tempo.viewmodel.DeezerSearchViewModel;
import com.cappielloantonio.tempo.viewmodel.LyricSearchViewModel;
import com.cappielloantonio.tempo.viewmodel.SearchViewModel;
import com.cappielloantonio.tempo.ui.dialog.DeezerDownloadDialog;
import com.google.android.material.shape.RelativeCornerSize;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@UnstableApi
public class SearchFragment extends Fragment implements ClickCallback {
    private static final String TAG = "SearchFragment";

    /**
     * Results arrive while the query is being typed, so the threshold is what
     * makes a search worth running at all rather than what makes it valid. One
     * character matches most of the library and answers nothing.
     */
    private static final int MINIMUM_QUERY_LENGTH = 2;

    /**
     * How long the typing has to stop before the query is worth sending. Short
     * enough to feel like the screen is keeping up, long enough that typing a
     * word at speed costs one search and not six.
     */
    private static final long TYPING_PAUSE_MS = 250;

    private FragmentSearchBinding bind;
    private MainActivity activity;
    private SearchViewModel searchViewModel;
    private DeezerSearchViewModel deezerViewModel;
    private LyricSearchViewModel lyricViewModel;

    private ArtistAdapter artistAdapter;
    private AlbumAdapter albumAdapter;
    private SongHorizontalAdapter songHorizontalAdapter;
    private DeezerArtistAdapter deezerArtistAdapter;
    private DeezerResultAdapter deezerAdapter;
    private DeezerPreviewPlayer previewPlayer;
    private LyricMatchAdapter lyricAdapter;

    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    private final Handler typingHandler = new Handler(Looper.getMainLooper());
    private Runnable scheduledSearch;

    /** What is currently on screen, so the top card can play into the same queue. */
    private RankedResults currentResults = RankedResults.empty();

    /** Everything the lyrics search found, before what the songs section already shows is taken out. */
    private List<LyricMatch> lyricMatches = new ArrayList<>();

    /** Deezer's tracks and albums, before the songs the songs section already shows are taken out. */
    private List<DeezerHit> deezerRows = new ArrayList<>();

    /* Whether the library has answered the current query - with results or with nothing. */
    private boolean answered = false;

    /*
     * Set while the fragment writes into the field itself - restoring the last
     * query, or filling it in from a tapped suggestion. Both are answers, not
     * questions, so the watcher must not treat them as typing and schedule a
     * search for what it just put there.
     */
    private boolean fillingQuery = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        bind = FragmentSearchBinding.inflate(inflater, container, false);
        searchViewModel = new ViewModelProvider(requireActivity()).get(SearchViewModel.class);
        deezerViewModel = new ViewModelProvider(requireActivity()).get(DeezerSearchViewModel.class);
        lyricViewModel = new ViewModelProvider(requireActivity()).get(LyricSearchViewModel.class);

        return bind.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        /*
         * Everything the search leans on - the starred, downloaded and played
         * sets, and the local list of library names used to correct a
         * misspelling - changes while this screen is not on top. Both are
         * refreshed on the way in rather than cached for the life of the
         * process.
         */
        searchViewModel.refreshSearchInputs();

        // The quota moves with every download, from here or from the site.
        deezerViewModel.refreshUsage();

        initSearchResultView();
        observeResults();
        initSearchInput();
        restoreLastSearch();

        searchViewModel.getKeyboardRequested().observe(getViewLifecycleOwner(), requested -> {
            if (!Boolean.TRUE.equals(requested)) return;

            focusInput();
            searchViewModel.keyboardAnswered();
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        initializeMediaBrowser();

        previewPlayer = new DeezerPreviewPlayer(requireContext(), (key, state) -> {
            if (bind != null) {
                deezerAdapter.setPreview(key, state);
                lyricAdapter.setPreview(key, state);
            }
            return kotlin.Unit.INSTANCE;
        });
    }

    /*
     * A preview belongs to this screen: leaving search - for an artist, for the
     * player, out of the app - ends it, rather than leaving a stray half-minute
     * playing that nothing on screen can stop.
     */
    @Override
    public void onStop() {
        previewPlayer.release();
        releaseMediaBrowser();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        cancelScheduledSearch();
        bind = null;
    }

    private void initSearchResultView() {
        // Artists
        bind.searchResultArtistRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        bind.searchResultArtistRecyclerView.setHasFixedSize(true);

        artistAdapter = new ArtistAdapter(this);
        bind.searchResultArtistRecyclerView.setAdapter(artistAdapter);

        CustomLinearSnapHelper artistSnapHelper = new CustomLinearSnapHelper();
        artistSnapHelper.attachToRecyclerView(bind.searchResultArtistRecyclerView);

        // Albums
        bind.searchResultAlbumRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        bind.searchResultAlbumRecyclerView.setHasFixedSize(true);

        albumAdapter = new AlbumAdapter(this);
        bind.searchResultAlbumRecyclerView.setAdapter(albumAdapter);

        CustomLinearSnapHelper albumSnapHelper = new CustomLinearSnapHelper();
        albumSnapHelper.attachToRecyclerView(bind.searchResultAlbumRecyclerView);

        // Songs
        bind.searchResultTracksRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        bind.searchResultTracksRecyclerView.setHasFixedSize(true);

        songHorizontalAdapter = new SongHorizontalAdapter(this, true, false, null);
        bind.searchResultTracksRecyclerView.setAdapter(songHorizontalAdapter);

        // Songs the query is a line of
        bind.searchLyricsRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        lyricAdapter = new LyricMatchAdapter(match -> {
            playLyricMatch(match);
            return kotlin.Unit.INSTANCE;
        }, match -> {
            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.TRACK_OBJECT, match.getSong());
            onMediaLongClick(bundle);
            return kotlin.Unit.INSTANCE;
        }, hit -> {
            // Not in the library, but on Deezer: the same preview and download as the Deezer section.
            previewPlayer.toggle(hit.getKey(), hit.getId());
            return kotlin.Unit.INSTANCE;
        }, hit -> {
            confirmDownload(hit);
            return kotlin.Unit.INSTANCE;
        });
        bind.searchLyricsRecyclerView.setAdapter(lyricAdapter);

        // Deezer, through Deemix plus: artists in a rail, then tracks and albums
        bind.searchDeezerArtistRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        bind.searchDeezerArtistRecyclerView.setHasFixedSize(true);

        deezerArtistAdapter = new DeezerArtistAdapter(hit -> {
            openDeezer(hit);
            return kotlin.Unit.INSTANCE;
        }, hit -> {
            confirmDownload(hit);
            return kotlin.Unit.INSTANCE;
        });
        bind.searchDeezerArtistRecyclerView.setAdapter(deezerArtistAdapter);

        CustomLinearSnapHelper deezerArtistSnapHelper = new CustomLinearSnapHelper();
        deezerArtistSnapHelper.attachToRecyclerView(bind.searchDeezerArtistRecyclerView);

        bind.searchDeezerRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        deezerAdapter = new DeezerResultAdapter(hit -> {
            playDeezerTrack(hit);
            return kotlin.Unit.INSTANCE;
        }, hit -> {
            confirmDownload(hit);
            return kotlin.Unit.INSTANCE;
        }, hit -> {
            openDeezer(hit);
            return kotlin.Unit.INSTANCE;
        });
        bind.searchDeezerRecyclerView.setAdapter(deezerAdapter);
    }

    /**
     * One subscription for the life of the view. Searches replace each other
     * inside the view model, so the screen never has to unpick observers from
     * queries the user has already typed past.
     */
    private void observeResults() {
        searchViewModel.getResults().observe(getViewLifecycleOwner(), ranked -> {
            if (bind == null || ranked == null) return;

            showResults(ranked);
        });

        lyricViewModel.getMatches().observe(getViewLifecycleOwner(), matches -> {
            if (bind == null) return;

            lyricMatches = matches;
            showLyrics();
        });

        lyricViewModel.getPending().observe(getViewLifecycleOwner(), pending -> {
            if (bind == null) return;

            showLyrics();
        });

        deezerViewModel.getArtists().observe(getViewLifecycleOwner(), artists -> {
            if (bind == null) return;

            deezerArtistAdapter.setArtists(artists);
            bind.searchDeezerArtistRecyclerView.scrollToPosition(0);
            bind.searchDeezerArtistRecyclerView.setVisibility(artists.isEmpty() ? View.GONE : View.VISIBLE);
            showDeezer();
        });

        deezerViewModel.getRows().observe(getViewLifecycleOwner(), rows -> {
            if (bind == null) return;

            deezerRows = rows;
            showDeezer();
        });

        deezerViewModel.getStates().observe(getViewLifecycleOwner(), states -> {
            if (bind == null) return;

            deezerArtistAdapter.setStates(states);
            deezerAdapter.setStates(states);
            lyricAdapter.setStates(states);
        });

        deezerViewModel.getUsage().observe(getViewLifecycleOwner(), usage -> {
            if (bind == null) return;

            String quota = DeemixQuota.describe(requireContext(), usage);
            bind.searchDeezerQuota.setText(quota);
            bind.searchDeezerQuota.setVisibility(quota.isEmpty() ? View.GONE : View.VISIBLE);
        });
    }

    private void initSearchInput() {
        bind.searchClearButton.setOnClickListener(view -> {
            bind.searchInput.setText("");
            focusInput();
        });

        /*
         * The keyboard's search key no longer starts anything - the results for
         * what is in the field are already on their way. It commits the query to
         * the history and gets the keyboard out of the way, which is what the
         * gesture means once the search itself is continuous.
         */
        bind.searchInput.setOnEditorActionListener((textView, actionId, keyEvent) -> {
            if (!isSubmit(actionId, keyEvent)) return false;

            String query = bind.searchInput.getText().toString().trim();

            if (isQueryValid(query)) {
                cancelScheduledSearch();
                runSearch(query);
                searchViewModel.commitQuery();
            }

            hideKeyboard();

            return true;
        });

        bind.searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int start, int before, int count) {
                bind.searchClearButton.setVisibility(charSequence.length() > 0 ? View.VISIBLE : View.GONE);

                if (fillingQuery) return;

                String query = charSequence.toString().trim();
                searchViewModel.setQuery(query);

                if (isQueryValid(query)) {
                    scheduleSearch(query);
                } else {
                    cancelScheduledSearch();
                    searchViewModel.clearResults();
                    deezerViewModel.clear();
                    lyricViewModel.clear();
                    setRecentSuggestions();
                }
            }

            @Override
            public void afterTextChanged(Editable editable) {

            }
        });
    }

    /**
     * Whether the user just said "that is my query".
     * <p>
     * Not one action but four. A soft keyboard reports whichever action the
     * field asked for, and not every IME honours the request - some send DONE or
     * GO where SEARCH was configured. A hardware Enter reports none of them:
     * TextView delivers it as {@code IME_NULL} carrying the key event, so a
     * listener that only recognises {@code IME_ACTION_SEARCH} silently does
     * nothing on every keyboard with a real Enter key.
     */
    private boolean isSubmit(int actionId, @Nullable KeyEvent keyEvent) {
        if (actionId == EditorInfo.IME_ACTION_SEARCH) return true;
        if (actionId == EditorInfo.IME_ACTION_DONE) return true;
        if (actionId == EditorInfo.IME_ACTION_GO) return true;

        return actionId == EditorInfo.IME_NULL
                && (keyEvent == null || keyEvent.getAction() == KeyEvent.ACTION_UP);
    }

    /**
     * Backing out of an artist or an album has to land on the results the user
     * left rather than on an empty field. The query outlives the fragment in the
     * activity-scoped view model, so it is re-run instead of re-typed, and the
     * keyboard stays down - only a genuinely new search opens with it up, or a
     * tap on the Search tab, which asks for it whatever the field holds.
     */
    private void restoreLastSearch() {
        String query = searchViewModel.getQuery();

        if (isQueryValid(query)) {
            fillQuery(query);
            runSearch(query);
        } else {
            setRecentSuggestions();
            focusInput();
        }
    }

    private void scheduleSearch(String query) {
        cancelScheduledSearch();

        scheduledSearch = () -> runSearch(query);
        typingHandler.postDelayed(scheduledSearch, TYPING_PAUSE_MS);
    }

    private void cancelScheduledSearch() {
        if (scheduledSearch == null) return;

        typingHandler.removeCallbacks(scheduledSearch);
        scheduledSearch = null;
    }

    private void runSearch(String query) {
        /*
         * The skeleton is for a screen with nothing on it. Once results are up,
         * refining the query leaves them standing until the next set lands:
         * blanking the screen on every pause in typing reads as the search
         * breaking and recovering, over and over.
         */
        if (bind.searchResultLayout.getVisibility() != View.VISIBLE) showLoading();

        searchViewModel.search(query);
        deezerViewModel.search(query);
        lyricViewModel.search(query);
    }

    public void setRecentSuggestions() {
        List<String> suggestions = searchViewModel.getRecentSearchSuggestion();

        bind.searchSuggestionContainer.removeAllViews();

        for (String suggestion : suggestions) {
            View view = inflateSuggestion(suggestion);

            view.findViewById(R.id.search_suggestion_delete_icon).setOnClickListener(v -> {
                searchViewModel.deleteRecentSearch(suggestion);
                setRecentSuggestions();
            });

            bind.searchSuggestionContainer.addView(view);
        }

        showSuggestions(!suggestions.isEmpty());
    }

    private View inflateSuggestion(String suggestion) {
        View view = LayoutInflater.from(bind.searchSuggestionContainer.getContext()).inflate(R.layout.item_search_suggestion, bind.searchSuggestionContainer, false);

        ImageView leadingImageView = view.findViewById(R.id.search_suggestion_icon);
        TextView titleView = view.findViewById(R.id.search_suggestion_title);

        leadingImageView.setImageResource(R.drawable.ic_history);
        titleView.setText(suggestion);

        view.setOnClickListener(v -> search(suggestion));

        return view;
    }

    /** Runs a query the user picked rather than typed. */
    public void search(String query) {
        cancelScheduledSearch();

        fillQuery(query);
        searchViewModel.setQuery(query);

        /*
         * Only the keyboard goes away. clearFocus() would hand focus to the next
         * focusable view, which then draws itself focused - a control lit up
         * for no reason the moment a search ran.
         */
        hideKeyboard();

        runSearch(query);
        searchViewModel.commitQuery();
    }

    private void showResults(RankedResults ranked) {
        currentResults = ranked;

        artistAdapter.setItems(ranked.getArtists());
        albumAdapter.setItems(ranked.getAlbums());
        songHorizontalAdapter.setItems(ranked.getSongs());

        bind.searchArtistSector.setVisibility(ranked.getArtists().isEmpty() ? View.GONE : View.VISIBLE);
        bind.searchAlbumSector.setVisibility(ranked.getAlbums().isEmpty() ? View.GONE : View.VISIBLE);
        bind.searchSongSector.setVisibility(ranked.getSongs().isEmpty() ? View.GONE : View.VISIBLE);

        bindTopResult(ranked.getTop());

        boolean hasResults = !ranked.isEmpty();

        answered = true;
        bind.searchSuggestionSector.setVisibility(View.GONE);
        bind.searchResultLayout.setVisibility(hasResults ? View.VISIBLE : View.GONE);

        showLyrics();

        bind.searchScrollView.scrollTo(0, 0);
    }

    /**
     * The songs the query is a line of, less the ones the songs section above
     * already lists - "love in a bottle" is a title and a lyric at once, and one
     * row for the song is enough.
     * <p>
     * While the lyrics are still being searched the section stands with its
     * header over a skeleton, from the moment the library has answered - under
     * its results, or in place of "nothing found", which is not established
     * until the lyrics have answered too.
     */
    private void showLyrics() {
        Set<String> listed = new HashSet<>();
        for (Child song : currentResults.getSongs()) listed.add(song.getId());

        List<LyricMatch> fresh = new ArrayList<>();
        for (LyricMatch match : lyricMatches) {
            if (match.getSong() == null || !listed.contains(match.getSong().getId())) fresh.add(match);
        }

        lyricAdapter.setMatches(fresh);

        boolean found = !fresh.isEmpty();
        boolean pending = lyricViewModel.isPending();

        if (!answered) {
            bind.searchLyricsSector.setVisibility(View.GONE);
            return;
        }

        if (pending) {
            SkeletonView.startLoading(bind.searchLyricsSector, bind.searchLyricsSkeleton, bind.searchLyricsRecyclerView);
        } else {
            SkeletonView.finishLoading(bind.searchLyricsSector, bind.searchLyricsSkeleton, bind.searchLyricsRecyclerView, found);
        }

        bind.searchResultsSkeleton.setVisibility(View.GONE);
        bind.searchEmptySector.setVisibility(currentResults.isEmpty() && !found && !pending ? View.VISIBLE : View.GONE);

        showDeezer();
    }

    /** A library track found by its lyrics, played in the queue of the ones found with it. */
    private void playLyricMatch(LyricMatch match) {
        List<Child> songs = new ArrayList<>();
        int position = 0;

        for (LyricMatch candidate : lyricAdapter.getMatches()) {
            if (candidate.getSong() == null) continue;

            if (candidate == match) position = songs.size();
            songs.add(candidate.getSong());
        }

        Bundle bundle = new Bundle();
        bundle.putParcelableArrayList(Constants.TRACKS_OBJECT, new ArrayList<>(MusicUtil.limitPlayableMedia(songs, position)));
        bundle.putInt(Constants.ITEM_POSITION, MusicUtil.getPlayableMediaPosition(songs, position));

        onMediaClick(bundle);
    }

    /**
     * The Deezer rows go wherever the library's answer is - under its results,
     * or under "nothing found" - and never over the suggestions or the skeleton.
     * They usually land a moment after the library's, so this runs on both.
     * <p>
     * A song the songs section already lists is left out here, as the lyrics
     * section leaves it out: "antivi" found ANTIVILLAIN in the library and then
     * offered it again under Deezer. A track the library has but its search
     * did not turn up still stays, with its tick.
     */
    private void showDeezer() {
        List<DeezerHit> fresh = new ArrayList<>();
        for (DeezerHit hit : deezerRows) {
            boolean listed = hit.getKind() == DeezerHit.Kind.TRACK && libraryCopyOf(hit, currentResults.getSongs()) != null;
            if (!listed) fresh.add(hit);
        }
        deezerAdapter.setHits(fresh);

        boolean found = deezerArtistAdapter.getItemCount() > 0 || deezerAdapter.getItemCount() > 0;

        bind.searchDeezerSector.setVisibility(answered && found ? View.VISIBLE : View.GONE);
    }

    /**
     * A Deezer track that is already in the library plays from the library, in
     * full. Only a track the library does not have is worth a thirty-second
     * preview - the tick on the row says it is there, and a tap that then
     * plays a clip of it reads as the tick being wrong.
     */
    private void playDeezerTrack(DeezerHit hit) {
        if (!hit.getInLibrary()) {
            previewPlayer.toggle(hit.getKey(), hit.getId());
            return;
        }

        Child listed = libraryCopyOf(hit, currentResults.getSongs());
        if (listed != null) {
            playFromResults(listed);
            return;
        }

        /*
         * The library's own results do not have it - Deezer matches more
         * loosely than the server does - so the library is asked for the title
         * itself. Only the first answer counts: this is a one-off lookup.
         */
        LiveData<List<Child>> lookup = searchViewModel.findSongs(hit.getTitle());
        lookup.observe(getViewLifecycleOwner(), new Observer<List<Child>>() {
            @Override
            public void onChanged(List<Child> songs) {
                lookup.removeObserver(this);
                if (bind == null) return;

                Child song = libraryCopyOf(hit, songs);

                if (song == null) {
                    // Deemix plus says it is there but the server cannot find
                    // it (not scanned yet, tagged differently): the clip is
                    // still better than nothing.
                    previewPlayer.toggle(hit.getKey(), hit.getId());
                    return;
                }

                previewPlayer.stop();

                Bundle bundle = new Bundle();
                bundle.putParcelableArrayList(Constants.TRACKS_OBJECT, new ArrayList<>(Collections.singletonList(song)));
                bundle.putInt(Constants.ITEM_POSITION, 0);
                onMediaClick(bundle);
            }
        });
    }

    /**
     * The library track a Deezer track is, matched on title and artist with
     * case, accents and ё folded away. A library title may carry more than
     * Deezer's - "(Remastered)", a featured artist - so a title that starts
     * with Deezer's still counts; the artist has to agree either way.
     */
    @Nullable
    private static Child libraryCopyOf(DeezerHit hit, @Nullable List<Child> songs) {
        if (songs == null) return null;

        String title = QueryVariants.normalize(hit.getTitle());
        String artist = QueryVariants.normalize(hit.getArtist());

        for (Child song : songs) {
            String songTitle = QueryVariants.normalize(song.getTitle());
            String songArtist = QueryVariants.normalize(song.artistLine());

            boolean sameTitle = songTitle.equals(title) || songTitle.startsWith(title);
            boolean sameArtist = artist.isEmpty() || songArtist.contains(artist) || artist.contains(songArtist);

            if (sameTitle && sameArtist && !songArtist.isEmpty()) return song;
        }

        return null;
    }

    private void confirmDownload(DeezerHit hit) {
        DeezerDownloadDialog.confirm(requireContext(), deezerViewModel, hit);
    }

    /** A Deezer artist or album, on its own page. */
    private void openDeezer(DeezerHit hit) {
        searchViewModel.commitQuery();

        Navigation.findNavController(requireView()).navigate(R.id.deezerPageFragment, DeezerPageFragment.args(hit));
    }

    private void bindTopResult(@Nullable RankedResults.TopResult top) {
        if (top == null) {
            bind.searchTopSector.setVisibility(View.GONE);
            return;
        }

        bind.searchTopSector.setVisibility(View.VISIBLE);
        bind.searchTopTitle.setText(top.getTitle());

        String role = getString(roleLabel(top.getKind()));
        String credit = top.getSubtitle();
        bind.searchTopSubtitle.setText(TextUtils.isEmpty(credit) ? role : getString(R.string.search_top_result_subtitle, role, credit));

        /*
         * DESIGN.md "Geometry Rhythm": an artist is a circle, everything else is
         * a square at the image radius. The card shows whichever the top hit
         * turned out to be, so the shape is set per result rather than in XML.
         */
        boolean isArtist = top.getKind() == RankedResults.TopResult.Kind.ARTIST;

        bind.searchTopCover.setShapeAppearanceModel(isArtist
                ? ShapeAppearanceModel.builder().setAllCornerSizes(new RelativeCornerSize(0.5f)).build()
                : ShapeAppearanceModel.builder().setAllCornerSizes(getResources().getDimension(R.dimen.radius_image)).build());

        CustomGlideRequest.Builder
                .from(requireContext(), top.getCoverArtId(), isArtist ? CustomGlideRequest.ResourceType.Artist : CustomGlideRequest.ResourceType.Album)
                .build()
                .into(bind.searchTopCover);

        bind.searchTopResult.setOnClickListener(view -> openTopResult(top));
    }

    private int roleLabel(RankedResults.TopResult.Kind kind) {
        switch (kind) {
            case ARTIST:
                return R.string.label_role_artist;
            case ALBUM:
                return R.string.label_role_album;
            default:
                return R.string.label_role_song;
        }
    }

    private void openTopResult(RankedResults.TopResult top) {
        Bundle bundle = new Bundle();

        switch (top.getKind()) {
            case ARTIST:
                bundle.putParcelable(Constants.ARTIST_OBJECT, top.getArtist());
                onArtistClick(bundle);
                break;
            case ALBUM:
                bundle.putParcelable(Constants.ALBUM_OBJECT, top.getAlbum());
                onAlbumClick(bundle);
                break;
            default:
                playFromResults(top.getSong());
                break;
        }
    }

    /**
     * Starts the top hit inside the queue the songs section would have built, so
     * playing from the card and playing from the list below it leave the player
     * in the same state.
     */
    private void playFromResults(Child song) {
        List<Child> songs = currentResults.getSongs();

        int position = 0;
        for (int index = 0; index < songs.size(); index++) {
            if (songs.get(index).getId().equals(song.getId())) {
                position = index;
                break;
            }
        }

        Bundle bundle = new Bundle();
        bundle.putParcelableArrayList(Constants.TRACKS_OBJECT, new ArrayList<>(MusicUtil.limitPlayableMedia(songs, position)));
        bundle.putInt(Constants.ITEM_POSITION, MusicUtil.getPlayableMediaPosition(songs, position));

        onMediaClick(bundle);
    }

    /*
     * The three states the surface under the field can be in. Only one of them
     * is ever on screen, and every switch between them is a plain visibility
     * change: the screen is not meant to move while the user reads it.
     */
    private void showSuggestions(boolean hasSuggestions) {
        currentResults = RankedResults.empty();
        answered = false;

        bind.searchResultsSkeleton.setVisibility(View.GONE);
        bind.searchSuggestionSector.setVisibility(hasSuggestions ? View.VISIBLE : View.GONE);
        bind.searchResultLayout.setVisibility(View.GONE);
        bind.searchEmptySector.setVisibility(View.GONE);
        bind.searchDeezerSector.setVisibility(View.GONE);
        bind.searchLyricsSector.setVisibility(View.GONE);
    }

    private void showLoading() {
        answered = false;

        bind.searchResultsSkeleton.setVisibility(View.VISIBLE);
        bind.searchSuggestionSector.setVisibility(View.GONE);
        bind.searchResultLayout.setVisibility(View.GONE);
        bind.searchEmptySector.setVisibility(View.GONE);
        bind.searchDeezerSector.setVisibility(View.GONE);
        bind.searchLyricsSector.setVisibility(View.GONE);
    }

    private void fillQuery(String query) {
        fillingQuery = true;
        bind.searchInput.setText(query);
        bind.searchInput.setSelection(query.length());
        fillingQuery = false;
    }

    private boolean isQueryValid(String query) {
        return !TextUtils.isEmpty(query) && query.trim().length() >= MINIMUM_QUERY_LENGTH;
    }

    /**
     * Focuses the field and brings the keyboard up for it.
     * <p>
     * This runs as the screen is built, before the field is attached to the
     * window, and a keyboard asked for then is dropped: the field took focus but
     * the keyboard stayed down. Both are posted so they happen once the field is
     * on screen - focus included, since tapping Search while it is showing
     * builds a new screen over the old one, and the old field hands focus away
     * as it goes. The keyboard is asked for through the window's insets
     * controller: an implicit request, as it used to be, is one the system may
     * turn down.
     */
    private void focusInput() {
        bind.searchInput.post(() -> {
            if (bind == null || !isAdded()) return;

            bind.searchInput.requestFocus();
            WindowCompat.getInsetsController(requireActivity().getWindow(), bind.searchInput)
                    .show(WindowInsetsCompat.Type.ime());
        });
    }

    private void hideKeyboard() {
        InputMethodManager inputMethodManager = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null) inputMethodManager.hideSoftInputFromWindow(bind.searchInput.getWindowToken(), 0);
    }

    private void initializeMediaBrowser() {
        mediaBrowserListenableFuture = new MediaBrowser.Builder(requireContext(), new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
    }

    private void releaseMediaBrowser() {
        MediaBrowser.releaseFuture(mediaBrowserListenableFuture);
    }

    @Override
    public void onMediaClick(Bundle bundle) {
        searchViewModel.commitQuery();

        MediaManager.startQueue(mediaBrowserListenableFuture, bundle.getParcelableArrayList(Constants.TRACKS_OBJECT), bundle.getInt(Constants.ITEM_POSITION));
        activity.setBottomSheetInPeek(true);
    }

    @Override
    public void onMediaLongClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.songBottomSheetDialog, bundle);
    }

    @Override
    public void onAlbumClick(Bundle bundle) {
        searchViewModel.commitQuery();

        Navigation.findNavController(requireView()).navigate(R.id.albumPageFragment, bundle);
    }

    @Override
    public void onAlbumLongClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.albumBottomSheetDialog, bundle);
    }

    @Override
    public void onArtistClick(Bundle bundle) {
        searchViewModel.commitQuery();

        Navigation.findNavController(requireView()).navigate(R.id.artistPageFragment, bundle);
    }

    @Override
    public void onArtistLongClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.artistBottomSheetDialog, bundle);
    }
}
