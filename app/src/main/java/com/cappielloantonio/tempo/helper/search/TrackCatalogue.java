package com.cappielloantonio.tempo.helper.search;

import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.system.ErrnoException;
import android.util.Log;

import com.cappielloantonio.tempo.subsonic.api.searching.SearchingClient;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.SearchResult3;
import com.cappielloantonio.tempo.subsonic.models.SubsonicResponse;
import com.platter.search.TrackIndex;
import com.platter.search.TrackChanges;
import com.platter.search.TrackSnapshot;
import com.platter.search.TrackStore;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import retrofit2.Call;
import retrofit2.Response;

public final class TrackCatalogue {
    private static final String TAG = "TrackCatalogue";
    private final TrackStore<Child> store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private volatile Call<ApiResponse> pending;
    private boolean running;
    private volatile long refreshAfter;

    public TrackCatalogue(File directory, String server, String user) {
        TrackSnapshot<Child> snapshot = new TrackSnapshot<>(Child.class, Child::getId, Child::getTitle,
                Child::artistLine, Child::getArtist, Child::getAlbum);
        store = new TrackStore<>(new File(directory, TrackSnapshot.filename(server, user)), snapshot, (source, destination) -> {
            try {
                Os.rename(source.getPath(), destination.getPath());
            } catch (ErrnoException e) {
                throw new IOException("Could not replace the track index", e);
            }
        });
    }

    public List<TrackIndex.Match<Child>> find(String query, boolean allowCorrections) {
        if (!store.ready()) return Collections.emptyList();
        try {
            return store.find(query, allowCorrections ? EditDistance::within : null);
        } catch (IOException | RuntimeException invalidCache) {
            Log.d(TAG, "Could not read a cached track", invalidCache);
            store.invalidate();
            refreshAfter = 0;
            return Collections.emptyList();
        }
    }

    /** Starts a refresh on the main thread. The callback reports whether the old catalogue was discarded. */
    public void refresh(SearchingClient network, BooleanSupplier canSync, Consumer<Boolean> onChanged) {
        if (closed || running || System.currentTimeMillis() < refreshAfter) return;
        running = true;
        worker.execute(() -> {
            if (!store.ready() && store.exists()) {
                try {
                    store.load();
                    notifyChanged(onChanged, false);
                } catch (IOException | RuntimeException invalidCache) {
                    Log.d(TAG, "Could not open the track index", invalidCache);
                }
            }
            long retry = System.currentTimeMillis() + TrackSnapshot.RETRY_AFTER_MS;
            try {
                if (!closed && canSync.getAsBoolean()) {
                    retry = synchronize(network, canSync, onChanged);
                }
            } catch (Exception failed) {
                // Retry after a pause.
            } finally {
                long next = retry;
                main.post(() -> {
                    refreshAfter = next;
                    running = false;
                });
            }
        });
    }

    private long synchronize(SearchingClient network, BooleanSupplier canSync, Consumer<Boolean> onChanged) throws IOException {
        String cursor = store.cursor();
        TrackChanges page = fetchChanges(network, cursor);
        if (page == null) {
            long now = System.currentTimeMillis();
            long staleAt = store.modifiedAt() + TrackSnapshot.MAX_AGE_MS;
            if (store.ready() && now < staleAt) return Math.min(staleAt, now + TrackStore.DISCOVERY_POLL_MS);
            rebuild(network, canSync, null, onChanged);
            notifyChanged(onChanged, false);
            return System.currentTimeMillis() + TrackStore.DISCOVERY_POLL_MS;
        }

        boolean changed = false;
        while (true) {
            checkSync(canSync);
            page.validate(cursor);
            if (cursor == null || page.reset) {
                discardIfRequired(page, onChanged);
                TrackChanges checkpoint = cursor == null ? page : requireChanges(fetchChanges(network, null));
                rebuild(network, canSync, checkpoint.unchangedCheckpoint(null), onChanged);
                changed = true;
                break;
            }
            changed = store.apply(page) || changed;
            if (!page.hasMore) break;
            cursor = page.cursor;
            page = requireChanges(fetchChanges(network, cursor));
        }
        if (changed) notifyChanged(onChanged, false);
        return System.currentTimeMillis() + TrackStore.JOURNAL_POLL_MS;
    }

    private void rebuild(SearchingClient network, BooleanSupplier canSync, String checkpoint, Consumer<Boolean> onChanged) throws IOException {
        File temporary = store.temporary();
        try {
            TrackSnapshot<Child>.Index fresh;
            try (TrackSnapshot<Child>.Writer writer = store.writer(temporary)) {
                do {
                    checkSync(canSync);
                } while (writer.add(fetch(network, writer.offset())));
                fresh = writer.finish();
            }
            checkSync(canSync);
            String confirmed = checkpoint == null ? null : verifyCheckpoint(network, checkpoint, onChanged);
            checkSync(canSync);
            store.commitBaseline(temporary, fresh, confirmed);
        } finally {
            temporary.delete();
        }
    }

    private String verifyCheckpoint(SearchingClient network, String checkpoint, Consumer<Boolean> onChanged) throws IOException {
        // Asking without a cursor forces the journal to observe the library again.
        String head = requireChanges(fetchChanges(network, null)).unchangedCheckpoint(null);
        if (head.equals(checkpoint)) return head;
        TrackChanges changes = requireChanges(fetchChanges(network, checkpoint));
        changes.validate(checkpoint);
        discardIfRequired(changes, onChanged);
        String confirmed = changes.unchangedCheckpoint(checkpoint);
        if (confirmed.equals(checkpoint)) throw new IOException("Track journal did not reach the observed checkpoint");
        return confirmed;
    }

    private void discardIfRequired(TrackChanges page, Consumer<Boolean> onChanged) throws IOException {
        if (!page.discardsBaseline()) return;
        try {
            store.discardBaseline();
        } finally {
            notifyChanged(onChanged, true);
        }
    }

    private void checkSync(BooleanSupplier canSync) throws IOException {
        if (closed || !canSync.getAsBoolean()) throw new IOException("Track catalogue sync interrupted");
    }

    private TrackChanges requireChanges(TrackChanges page) throws IOException {
        if (page == null) throw new IOException("Track journal disappeared during sync");
        return page;
    }

    private List<Child> fetch(SearchingClient network, int offset) throws IOException {
        SubsonicResponse response = exchange(network.searchSongs("", TrackSnapshot.PAGE_SIZE, offset), false);
        SearchResult3 result = response.getSearchResult3();
        List<Child> page = result == null ? null : result.getSongs();
        return page == null ? Collections.emptyList() : page;
    }

    private TrackChanges fetchChanges(SearchingClient network, String cursor) throws IOException {
        SubsonicResponse response = exchange(network.trackChanges(cursor), true);
        return response == null ? null : requireChanges(response.getPlatterTrackChanges());
    }

    private SubsonicResponse exchange(Call<ApiResponse> call, boolean optional) throws IOException {
        pending = call;
        try {
            if (closed) call.cancel();
            Response<ApiResponse> response = call.execute();
            if (!response.isSuccessful() && response.errorBody() != null) response.errorBody().close();
            if (optional && (response.code() == 404 || response.code() == 405 || response.code() == 501)) return null;
            ApiResponse body = response.isSuccessful() ? response.body() : null;
            SubsonicResponse subsonic = body == null ? null : body.getSubsonicResponse();
            if (subsonic == null || !"ok".equals(subsonic.getStatus())) throw new IOException("Could not fetch the track catalogue");
            return subsonic;
        } finally {
            pending = null;
        }
    }

    private void notifyChanged(Consumer<Boolean> onChanged, boolean discarded) {
        main.post(() -> {
            if (!closed) onChanged.accept(discarded);
        });
    }

    public void close() {
        closed = true;
        Call<ApiResponse> call = pending;
        if (call != null) call.cancel();
        worker.shutdownNow();
    }
}
