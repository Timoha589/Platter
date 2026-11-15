package com.cappielloantonio.tempo.viewmodel;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.helper.search.FuzzyIndex;
import com.cappielloantonio.tempo.helper.search.LibrarySignals;
import com.cappielloantonio.tempo.helper.search.QueryVariants;
import com.cappielloantonio.tempo.helper.search.RankedResults;
import com.cappielloantonio.tempo.helper.search.SearchRanker;
import com.cappielloantonio.tempo.helper.search.TrackCatalogue;
import com.cappielloantonio.tempo.model.RecentSearch;
import com.cappielloantonio.tempo.repository.CatalogueIndexRepository;
import com.cappielloantonio.tempo.repository.SearchingRepository;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.SearchResult3;
import com.cappielloantonio.tempo.util.NetworkUtil;
import com.cappielloantonio.tempo.util.Preferences;
import com.platter.search.TrackIndex;
import com.platter.search.TrackSearch;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

@UnstableApi
public class SearchViewModel extends AndroidViewModel {
    private static final String TAG = "SearchViewModel";

    /** How many library names a misspelling may be corrected towards at once. */
    private static final int MAX_CORRECTIONS = 2;

    /**
     * How much of the query the prefix fallback keeps.
     * <p>
     * A typo is usually in what was typed last, so cutting the tail off often
     * lands back on something real - "Мельнеца" cut to "Мельн" finds Мельница.
     * Cutting harder would find more and mean less: at three characters the
     * query stops being about anything.
     */
    private static final double PREFIX_FALLBACK_RATIO = 0.6;
    private static final int SHORTEST_PREFIX = 4;

    private final SearchingRepository searchingRepository;
    private final CatalogueIndexRepository catalogueIndexRepository;

    /**
     * The one stream the search screen watches. Searches replace each other by
     * swapping the source under it, so the screen subscribes once and never has
     * to unpick observers from queries the user has already moved past.
     */
    private final MediatorLiveData<RankedResults> results = new MediatorLiveData<>();
    private LiveData<List<SearchResult3>> currentSource;

    /*
     * Ranking reads the local database and sorts a few dozen items; correcting a
     * misspelling measures the query against every name in the library. Neither
     * belongs on the main thread. One thread, so two searches can never rank out
     * of order.
     */
    private final ExecutorService rankingExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /* Touched only from rankingExecutor; the flags are set from the main thread. */
    private LibrarySignals librarySignals;
    private FuzzyIndex fuzzyIndex;
    private volatile boolean librarySignalsStale = true;
    private volatile boolean fuzzyIndexStale = true;

    /**
     * Which search is current. A second round of requests for a query the user
     * has already typed past must not reach the screen, and neither must its
     * results.
     */
    private volatile int searchGeneration = 0;

    private String query = "";
    private TrackCatalogue trackCatalogue;
    private String trackServer;
    private String trackUser;

    public SearchViewModel(@NonNull Application application) {
        super(application);

        searchingRepository = new SearchingRepository();
        catalogueIndexRepository = new CatalogueIndexRepository();
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query == null ? "" : query;
    }

    /*
     * A tap on the Search tab asks for the keyboard; coming back to search from
     * an artist or an album does not. The tap happens in the bottom bar, before
     * the screen exists, so the request waits here until the screen answers it.
     */
    private final MutableLiveData<Boolean> keyboardRequested = new MutableLiveData<>(false);

    public LiveData<Boolean> getKeyboardRequested() {
        return keyboardRequested;
    }

    public void requestKeyboard() {
        keyboardRequested.setValue(true);
    }

    public void keyboardAnswered() {
        keyboardRequested.setValue(false);
    }

    /**
     * Records the current query as a recent search.
     * <p>
     * Called when the user acts on what came back - submits from the keyboard,
     * or opens a result - rather than on every search that runs. With results
     * arriving as the query is typed, every prefix of every word is a search,
     * and a history of "e", "el", "elk", "elka" is not a history of anything.
     */
    public void commitQuery() {
        if (query.trim().isEmpty()) return;

        searchingRepository.insert(new RecentSearch(query.trim(), System.currentTimeMillis()));
    }

    public LiveData<RankedResults> getResults() {
        return results;
    }

    /**
     * Runs {@code query}, and every rewrite of it worth trying, replacing
     * whatever search was in progress.
     */
    public void search(String query) {
        setQuery(query);
        int generation = ++searchGeneration;
        String typed = this.query;
        TrackCatalogue tracks = refreshTrackCatalogue();
        List<QueryVariants.Variant> variants = QueryVariants.of(typed);
        runSearch(typed, variants, generation, tracks);
    }

    private void runSearch(String query, List<QueryVariants.Variant> variants, int generation, TrackCatalogue tracks) {
        TrackSearch trackSearch = new TrackSearch(query);
        // Both tasks run on the ranking executor. Local candidates can reach the UI while Retrofit is still fetching.
        List<TrackIndex.Match<Child>> local = new ArrayList<>();
        rankingExecutor.execute(() -> {
            if (tracks == null) return;
            for (int i = 0; i < variants.size(); i++) {
                if (generation != searchGeneration) return;
                local.addAll(tracks.find(variants.get(i).text(), i == 0 && trackSearch.allowsCorrections()));
            }
            if (!local.isEmpty()) {
                publish(SearchRanker.rank(variants, Collections.emptyList(), librarySignals(), null, local), generation);
            }
        });

        fetch(variants, true, raw -> {
            if (generation != searchGeneration) return;
            RankedResults ranked = SearchRanker.rank(variants, raw, librarySignals(), null, local);
            if (!ranked.hasStrongMatch() && trackSearch.allowsCorrections()
                    && raw.stream().anyMatch(Objects::nonNull)) {
                Set<String> tried = new HashSet<>();
                for (QueryVariants.Variant variant : variants) tried.add(variant.text().toLowerCase(Locale.ROOT));
                List<QueryVariants.Variant> guesses = guessesFor(query);
                guesses.removeIf(variant -> !tried.add(variant.text().toLowerCase(Locale.ROOT)));
                if (!guesses.isEmpty()) {
                    List<QueryVariants.Variant> allVariants = new ArrayList<>(variants);
                    allVariants.addAll(guesses);
                    mainHandler.post(() -> {
                        if (generation != searchGeneration) return;
                        fetch(guesses, false, retry -> {
                            if (generation != searchGeneration) return;
                            if (retry.stream().allMatch(Objects::isNull)) {
                                publish(ranked, generation);
                                return;
                            }
                            List<SearchResult3> allResults = new ArrayList<>(raw);
                            allResults.addAll(retry);
                            publish(SearchRanker.rank(allVariants, allResults, librarySignals(), query, local), generation);
                        });
                    });
                    return;
                }
            }

            publish(ranked, generation);
        });
    }

    private void fetch(List<QueryVariants.Variant> variants, boolean expandTracks, Consumer<List<SearchResult3>> onResults) {
        if (currentSource != null) results.removeSource(currentSource);
        currentSource = searchingRepository.search3(variants, expandTracks);
        results.addSource(currentSource, raw -> rankingExecutor.execute(() -> onResults.accept(raw)));
    }

    private void publish(RankedResults ranked, int generation) {
        mainHandler.post(() -> {
            if (generation == searchGeneration) results.setValue(ranked);
        });
    }

    /**
     * What the query might have been, when as typed it was nothing at all.
     * <p>
     * Three kinds of guess, and they cover different mistakes. A correction from
     * the library index catches a misspelling anywhere in the word, including
     * the middle, but only for an artist or an album, since those are the names
     * held locally. Dropping words catches a phrase with one word wrong - "Love
     * in the Bottle" - which the server, wanting every word, refuses outright.
     * A truncation catches a mistake near the end of anything at all, tracks
     * included, by asking for less than was typed.
     * <p>
     * Runs on {@link #rankingExecutor}.
     */
    private List<QueryVariants.Variant> guessesFor(String query) {
        List<QueryVariants.Variant> guesses = new ArrayList<>();

        for (String correction : fuzzyIndex().correctionsFor(query, MAX_CORRECTIONS)) {
            guesses.add(QueryVariants.Variant.guessed(correction));
        }

        for (String fewerWords : QueryVariants.withWordsDropped(query)) {
            guesses.add(QueryVariants.Variant.guessed(fewerWords));
        }

        String prefix = prefixOf(query);
        if (prefix != null) guesses.add(QueryVariants.Variant.guessed(prefix));

        return guesses;
    }

    private String prefixOf(String query) {
        String trimmed = query.trim();

        int length = (int) Math.ceil(trimmed.length() * PREFIX_FALLBACK_RATIO);
        if (length < SHORTEST_PREFIX || length >= trimmed.length()) return null;

        /* Cutting mid-phrase can leave the query ending in a space, which some
           servers treat as a token boundary and match nothing across. */
        String prefix = trimmed.substring(0, length).trim();

        return prefix.length() < SHORTEST_PREFIX ? null : prefix;
    }

    /** Drops the current search and whatever it is still fetching. */
    public void clearResults() {
        searchGeneration++;
        searchingRepository.cancelPendingSearch();

        if (currentSource != null) {
            results.removeSource(currentSource);
            currentSource = null;
        }
    }

    /**
     * Marks the starred / downloaded / played sets as worth re-reading, and
     * refreshes the library name index if it has gone stale. Called when the
     * search screen opens, because everything that changes either of them - a
     * star tapped on an album page, a download finishing, a track played, a new
     * album appearing on the server - happens while this screen is not on top.
     */
    public void refreshSearchInputs() {
        librarySignalsStale = true;

        refreshTrackCatalogue();

        catalogueIndexRepository.syncIfStale(() -> fuzzyIndexStale = true);
    }

    private TrackCatalogue refreshTrackCatalogue() {
        String server = Preferences.getServer();
        String user = Preferences.getUser();
        if (!Objects.equals(server, trackServer) || !Objects.equals(user, trackUser)) {
            if (trackCatalogue != null) trackCatalogue.close();
            trackServer = server;
            trackUser = user;
            trackCatalogue = server == null || user == null ? null : new TrackCatalogue(
                    new File(getApplication().getNoBackupFilesDir(), "search"), server, user);
        }
        TrackCatalogue tracks = trackCatalogue;
        if (tracks != null) {
            tracks.refresh(App.getSubsonicClientInstance(false).getSearchingClient(),
                    () -> !NetworkUtil.isOffline() && !Preferences.isDataSavingMode(), discarded -> {
                        if (trackCatalogue != tracks || !Objects.equals(server, Preferences.getServer())
                                || !Objects.equals(user, Preferences.getUser())) return;
                        if (discarded) results.setValue(RankedResults.empty());
                        if (currentSource != null && !query.trim().isEmpty()) search(query);
                    });
        }
        return tracks;
    }

    /** Runs on {@link #rankingExecutor} only, which is what makes this safe. */
    private LibrarySignals librarySignals() {
        if (librarySignalsStale || librarySignals == null) {
            librarySignals = LibrarySignals.load();
            librarySignalsStale = false;
        }

        return librarySignals;
    }

    /** Runs on {@link #rankingExecutor} only. */
    private FuzzyIndex fuzzyIndex() {
        if (fuzzyIndexStale || fuzzyIndex == null) {
            fuzzyIndex = FuzzyIndex.load();
            fuzzyIndexStale = false;
        }

        return fuzzyIndex;
    }

    public void deleteRecentSearch(String search) {
        searchingRepository.delete(new RecentSearch(search, 0));
    }

    public List<String> getRecentSearchSuggestion() {
        return new ArrayList<>(searchingRepository.getRecentSearchSuggestion());
    }

    @Override
    protected void onCleared() {
        super.onCleared();

        searchGeneration++;
        searchingRepository.cancelPendingSearch();
        if (trackCatalogue != null) trackCatalogue.close();
        rankingExecutor.shutdown();
    }
}
