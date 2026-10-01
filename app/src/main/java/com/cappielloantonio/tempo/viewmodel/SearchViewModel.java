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

import com.cappielloantonio.tempo.helper.search.FuzzyIndex;
import com.cappielloantonio.tempo.helper.search.LibrarySignals;
import com.cappielloantonio.tempo.helper.search.QueryVariants;
import com.cappielloantonio.tempo.helper.search.RankedResults;
import com.cappielloantonio.tempo.helper.search.SearchRanker;
import com.cappielloantonio.tempo.model.RecentSearch;
import com.cappielloantonio.tempo.repository.CatalogueIndexRepository;
import com.cappielloantonio.tempo.repository.SearchingRepository;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.SearchResult3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private int searchGeneration = 0;

    private String query = "";

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

        runRound(query, QueryVariants.of(query), ++searchGeneration, true);
    }

    /**
     * One round of requests, ranked when they all report.
     *
     * @param mayFallBack whether an empty result should be retried with guessed
     *                    spellings rather than shown as nothing found
     */
    private void runRound(String query, List<QueryVariants.Variant> variants, int generation, boolean mayFallBack) {
        if (currentSource != null) results.removeSource(currentSource);

        currentSource = searchingRepository.search3(variants);
        results.addSource(currentSource, raw -> rankingExecutor.execute(() -> {
            /*
             * A round of guesses hands the ranker the query as typed as well.
             * The spellings that fetched these results are approximations, so
             * they cannot tell which result is nearest to what was actually
             * meant - only the original can.
             */
            RankedResults ranked = SearchRanker.rank(variants, raw, librarySignals(), mayFallBack ? null : query);

            if (ranked.isEmpty() && mayFallBack) {
                List<QueryVariants.Variant> guesses = guessesFor(query);

                if (!guesses.isEmpty()) {
                    /*
                     * Deliberately nothing is published here. Posting the empty
                     * result first would put "nothing found" on screen for as
                     * long as the second round takes, and then take it back -
                     * the screen would be reporting a failure that has not
                     * happened yet.
                     */
                    mainHandler.post(() -> {
                        if (generation == searchGeneration) runRound(query, guesses, generation, false);
                    });
                    return;
                }
            }

            results.postValue(ranked);
        }));
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

        catalogueIndexRepository.syncIfStale(() -> fuzzyIndexStale = true);
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

    public LiveData<List<Child>> findSongs(String query) {
        return searchingRepository.findSongs(query);
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

        searchingRepository.cancelPendingSearch();
        rankingExecutor.shutdown();
    }
}
