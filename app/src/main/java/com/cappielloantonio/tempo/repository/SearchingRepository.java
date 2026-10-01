package com.cappielloantonio.tempo.repository;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.lifecycle.MutableLiveData;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.database.dao.RecentSearchDao;
import com.cappielloantonio.tempo.helper.search.QueryVariants;
import com.cappielloantonio.tempo.model.RecentSearch;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.SearchResult3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

@OptIn(markerClass = UnstableApi.class)
public class SearchingRepository {
    /** How many recent searches the screen offers before the list stops being a shortcut. */
    private static final int RECENT_SEARCH_LIMIT = 12;

    private final RecentSearchDao recentSearchDao = AppDatabase.getInstance().recentSearchDao();

    /*
     * The requests behind the search currently on screen. Results arrive while
     * the user is still typing, so every new search has to disown the one before
     * it: the calls are cancelled so the bytes stop coming, and the generation is
     * bumped so any callback already in flight - cancelled calls still report
     * back - cannot write into the answer for a query that has moved on.
     */
    private final List<Call<ApiResponse>> pendingCalls = new ArrayList<>();
    private int generation = 0;

    /**
     * The library's songs for one query, outside the search in progress: this
     * neither cancels that search nor is cancelled by the next one.
     * <p>
     * Always answers - an empty list when the request fails - so a caller
     * waiting on it can fall back rather than wait forever. It used to read
     * the search2 slot of a search3 response, which is never filled, and so
     * never answered with anything.
     */
    public MutableLiveData<List<Child>> findSongs(String query) {
        MutableLiveData<List<Child>> result = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getSearchingClient()
                .search3(query, 20, 0, 0)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        SearchResult3 found = response.isSuccessful() && response.body() != null
                                ? response.body().getSubsonicResponse().getSearchResult3()
                                : null;

                        result.setValue(found != null && found.getSongs() != null ? found.getSongs() : Collections.emptyList());
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        result.setValue(Collections.emptyList());
                    }
                });

        return result;
    }

    /**
     * Runs every spelling of the query at once and answers once they have all
     * reported, with one slot per spelling in the order they were given.
     * <p>
     * A call that fails still has to answer: the search screen waits on this to
     * decide what to draw, so a request that never emits would leave it on a
     * loading state it can never come out of. A failed spelling contributes a
     * null slot rather than silence.
     */
    public MutableLiveData<List<SearchResult3>> search3(List<QueryVariants.Variant> variants) {
        MutableLiveData<List<SearchResult3>> results = new MutableLiveData<>();

        cancelPendingSearch();

        int currentGeneration = ++generation;

        if (variants.isEmpty()) {
            results.setValue(Collections.emptyList());
            return results;
        }

        SearchResult3[] collected = new SearchResult3[variants.size()];
        int[] outstanding = {variants.size()};

        for (int index = 0; index < variants.size(); index++) {
            int slot = index;

            Call<ApiResponse> call = App.getSubsonicClientInstance(false)
                    .getSearchingClient()
                    .search3(variants.get(index).text(), 20, 20, 20);

            pendingCalls.add(call);

            call.enqueue(new Callback<ApiResponse>() {
                @Override
                public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        complete(response.body().getSubsonicResponse().getSearchResult3());
                    } else {
                        complete(null);
                    }
                }

                @Override
                public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                    complete(null);
                }

                private void complete(SearchResult3 result) {
                    if (currentGeneration != generation) return;

                    collected[slot] = result;

                    if (--outstanding[0] == 0) results.setValue(Arrays.asList(collected));
                }
            });
        }

        return results;
    }

    /**
     * Stops whatever the last search was still fetching. Callers that start a
     * new search need not call this - {@link #search3} does - but a screen going
     * away should, so a search nobody is waiting for stops using the radio.
     */
    public void cancelPendingSearch() {
        for (Call<ApiResponse> call : pendingCalls) {
            if (!call.isCanceled()) call.cancel();
        }

        pendingCalls.clear();
    }

    public void insert(RecentSearch recentSearch) {
        InsertThreadSafe insert = new InsertThreadSafe(recentSearchDao, recentSearch);
        Thread thread = new Thread(insert);
        thread.start();
    }

    public void delete(RecentSearch recentSearch) {
        DeleteThreadSafe delete = new DeleteThreadSafe(recentSearchDao, recentSearch);
        Thread thread = new Thread(delete);
        thread.start();
    }

    public List<String> getRecentSearchSuggestion() {
        List<String> recent = new ArrayList<>();

        RecentThreadSafe suggestionsThread = new RecentThreadSafe(recentSearchDao);
        Thread thread = new Thread(suggestionsThread);
        thread.start();

        try {
            thread.join();
            recent = suggestionsThread.getRecent();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return recent;
    }

    private static class DeleteThreadSafe implements Runnable {
        private final RecentSearchDao recentSearchDao;
        private final RecentSearch recentSearch;

        public DeleteThreadSafe(RecentSearchDao recentSearchDao, RecentSearch recentSearch) {
            this.recentSearchDao = recentSearchDao;
            this.recentSearch = recentSearch;
        }

        @Override
        public void run() {
            recentSearchDao.delete(recentSearch);
        }
    }

    private static class InsertThreadSafe implements Runnable {
        private final RecentSearchDao recentSearchDao;
        private final RecentSearch recentSearch;

        public InsertThreadSafe(RecentSearchDao recentSearchDao, RecentSearch recentSearch) {
            this.recentSearchDao = recentSearchDao;
            this.recentSearch = recentSearch;
        }

        @Override
        public void run() {
            recentSearchDao.insert(recentSearch);
        }
    }

    private static class RecentThreadSafe implements Runnable {
        private final RecentSearchDao recentSearchDao;
        private List<String> recent = new ArrayList<>();

        public RecentThreadSafe(RecentSearchDao recentSearchDao) {
            this.recentSearchDao = recentSearchDao;
        }

        @Override
        public void run() {
            recent = recentSearchDao.getRecent(RECENT_SEARCH_LIMIT);
        }

        public List<String> getRecent() {
            return recent;
        }
    }
}
