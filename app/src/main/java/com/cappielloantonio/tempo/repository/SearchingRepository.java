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
import com.cappielloantonio.tempo.subsonic.models.SubsonicResponse;
import com.platter.search.TrackSearch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

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
     * Runs every spelling of the query at once and answers once they have all
     * reported, with one slot per spelling in the order they were given.
     * <p>
     * A call that fails still has to answer: the search screen waits on this to
     * decide what to draw, so a request that never emits would leave it on a
     * loading state it can never come out of. A failed spelling contributes a
     * null slot rather than silence.
     */
    public MutableLiveData<List<SearchResult3>> search3(List<QueryVariants.Variant> variants, boolean expandTracks) {
        MutableLiveData<List<SearchResult3>> results = new MutableLiveData<>();

        cancelPendingSearch();

        int currentGeneration = generation;

        if (variants.isEmpty()) {
            results.setValue(Collections.emptyList());
            return results;
        }

        SearchResult3[] collected = new SearchResult3[variants.size()];
        int[] outstanding = {variants.size()};

        for (int index = 0; index < variants.size(); index++) {
            int slot = index;
            String query = variants.get(index).text();

            Call<ApiResponse> call = App.getSubsonicClientInstance(false)
                    .getSearchingClient()
                    .search3(query, TrackSearch.PAGE_SIZE, 20, 20);

            enqueueSearch(call, currentGeneration, result -> {
                Runnable complete = () -> {
                    collected[slot] = result;
                    if (--outstanding[0] == 0) results.setValue(Arrays.asList(collected));
                };
                if (slot == 0 && expandTracks && result != null) {
                    addPhraseMatches(query, result, currentGeneration, complete);
                } else {
                    complete.run();
                }
            });
        }

        return results;
    }

    private void addPhraseMatches(String query, SearchResult3 result, int currentGeneration, Runnable complete) {
        List<Child> first = result.getSongs() == null ? Collections.emptyList() : result.getSongs();
        TrackSearch trackSearch = new TrackSearch(query);
        boolean exact = first.stream().anyMatch(song -> trackSearch.match(song.getTitle(), song.artistLine(), song.getArtist()) > 0);
        String phrase = trackSearch.phrase(first.size(), exact);
        if (phrase == null) {
            complete.run();
            return;
        }

        List<Child> songs = new ArrayList<>(first);
        int offset = phrase.equals(query) ? first.size() : 0;
        loadSongPages(phrase, offset, songs, currentGeneration, () -> {
            result.setSongs(songs);
            complete.run();
        });
    }

    private void loadSongPages(String phrase, int offset, List<Child> songs, int currentGeneration, Runnable complete) {
        Call<ApiResponse> call = App.getSubsonicClientInstance(false).getSearchingClient()
                .searchSongs(phrase, TrackSearch.PAGE_SIZE, offset);
        enqueueSearch(call, currentGeneration, response -> {
            List<Child> page = response == null ? null : response.getSongs();
            if (page == null) {
                complete.run();
                return;
            }

            songs.addAll(page);
            int nextOffset = offset + page.size();
            if (page.size() < TrackSearch.PAGE_SIZE || nextOffset >= TrackSearch.MAX_SONGS_PER_PHRASE) {
                complete.run();
            } else {
                loadSongPages(phrase, nextOffset, songs, currentGeneration, complete);
            }
        });
    }

    private void enqueueSearch(Call<ApiResponse> call, int currentGeneration, Consumer<SearchResult3> complete) {
        pendingCalls.add(call);
        call.enqueue(new Callback<ApiResponse>() {
            @Override
            public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                if (currentGeneration != generation) return;
                ApiResponse body = response.isSuccessful() ? response.body() : null;
                SubsonicResponse subsonic = body == null ? null : body.getSubsonicResponse();
                if (subsonic == null || !"ok".equals(subsonic.getStatus())) {
                    complete.accept(null);
                    return;
                }
                SearchResult3 result = subsonic.getSearchResult3();
                complete.accept(result == null ? new SearchResult3() : result);
            }

            @Override
            public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                if (currentGeneration == generation) complete.accept(null);
            }
        });
    }

    /**
     * Stops whatever the last search was still fetching. Callers that start a
     * new search need not call this - {@link #search3} does - but a screen going
     * away should, so a search nobody is waiting for stops using the radio.
     */
    public void cancelPendingSearch() {
        generation++;
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
