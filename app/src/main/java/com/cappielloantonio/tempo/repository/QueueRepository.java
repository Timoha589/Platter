package com.cappielloantonio.tempo.repository;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.database.dao.QueueDao;
import com.cappielloantonio.tempo.model.Queue;
import com.cappielloantonio.tempo.subsonic.base.ApiResponse;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.PlayQueue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

@OptIn(markerClass = UnstableApi.class)
public class QueueRepository {
    private static final String TAG = "QueueRepository";

    private final QueueDao queueDao = AppDatabase.getInstance().queueDao();

    public LiveData<List<Queue>> getLiveQueue() {
        return queueDao.getAll();
    }

    public List<Child> getMedia() {
        return read(() -> new ArrayList<Child>(queueDao.getAllSimple()), new ArrayList<>());
    }

    public MutableLiveData<PlayQueue> getPlayQueue() {
        MutableLiveData<PlayQueue> playQueue = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getBookmarksClient()
                .getPlayQueue()
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            /*
                             * getPlayQueueByIndex answers under its own key, so
                             * whichever of the two endpoints the client picked,
                             * take the one that came back.
                             */
                            PlayQueue queue = response.body().getSubsonicResponse().getPlayQueueByIndex();
                            if (queue == null) queue = response.body().getSubsonicResponse().getPlayQueue();

                            playQueue.setValue(queue);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        playQueue.setValue(null);
                    }
                });

        return playQueue;
    }

    public void savePlayQueue(List<String> ids, int currentIndex, String current, long position) {
        App.getSubsonicClientInstance(false)
                .getBookmarksClient()
                .savePlayQueue(ids, currentIndex, current, position)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {

                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {

                    }
                });
    }

    public void insert(Child media, boolean reset, int afterIndex) {
        insertAll(Collections.singletonList(media), reset, afterIndex);
    }

    /**
     * Puts tracks into the stored queue at {@code afterIndex}, or makes them
     * the whole queue when {@code reset} is set.
     */
    public void insertAll(List<Child> toAdd, boolean reset, int afterIndex) {
        // Copied here: the list is often the queue screen's own, which goes on changing.
        List<Queue> added = toQueue(toAdd);

        write(() -> {
            List<Queue> media = reset ? new ArrayList<>() : new ArrayList<>(queueDao.getAllSimple());

            // The player and the table can disagree for a moment; an index past the end used to throw here.
            media.addAll(Math.max(0, Math.min(afterIndex, media.size())), added);

            replaceAll(media);
        });
    }

    /**
     * Stores the queue in a new order, or with tracks taken out, keeping what
     * is known about how far each track was played.
     * <p>
     * This used to go through {@link #insertAll} with a reset, which rebuilt
     * every row from the track alone, so one drag in the queue - or a swipe to
     * remove, or a shuffle - wiped last_play from all of them. The restore on
     * the next cold start picks the row played last, and with every row at
     * zero it picked the first: the queue came back on the wrong track.
     */
    public void rearrange(List<Child> media) {
        List<Queue> rows = toQueue(media);

        write(() -> {
            Map<String, Queue> before = new HashMap<>();
            for (Queue row : queueDao.getAllSimple()) {
                Queue known = before.get(row.getId());
                if (known == null || row.getLastPlay() > known.getLastPlay()) before.put(row.getId(), row);
            }

            for (Queue row : rows) {
                Queue known = before.get(row.getId());
                if (known == null) continue;

                row.setLastPlay(known.getLastPlay());
                row.setPlayingChanged(known.getPlayingChanged());
            }

            replaceAll(rows);
        });
    }

    public void deleteAll() {
        write(queueDao::deleteAll);
    }

    public int count() {
        return read(queueDao::count, 0);
    }

    public void setLastPlayedTimestamp(String id) {
        long now = System.currentTimeMillis();
        write(() -> queueDao.setLastPlay(id, now));
    }

    public void setPlayingPausedTimestamp(String id, long ms) {
        write(() -> queueDao.setPlayingChanged(id, ms));
    }

    public int getLastPlayedMediaIndex() {
        return read(() -> {
            Queue lastMediaPlayed = queueDao.getLastPlayed();
            return lastMediaPlayed != null ? lastMediaPlayed.getTrackOrder() : 0;
        }, 0);
    }

    public long getLastPlayedMediaTimestamp() {
        return read(() -> {
            Queue lastMediaPlayed = queueDao.getLastPlayed();
            return lastMediaPlayed != null ? lastMediaPlayed.getPlayingChanged() : 0L;
        }, 0L);
    }

    private static List<Queue> toQueue(List<Child> media) {
        List<Queue> rows = new ArrayList<>(media.size());
        for (Child child : media) rows.add(new Queue(child));
        return rows;
    }

    /* On the queue thread only, inside write(). */
    private void replaceAll(List<Queue> media) {
        for (int i = 0; i < media.size(); i++) {
            media.get(i).setTrackOrder(i);
        }

        /*
         * One transaction, so the queue screen - which watches the table - is
         * told once, about the finished queue. Clearing and refilling as two
         * steps showed it an empty queue in between, and a process that died
         * between them lost the queue altogether.
         */
        AppDatabase.getInstance().runInTransaction(() -> {
            queueDao.deleteAll();
            queueDao.insertAll(media);
        });
    }

    /*
     * Every change to the queue table, one after another, off the main thread.
     * Each of these used to be a thread of its own - the big rewrites were
     * joined on the main thread, so every drag, swipe or "play next" in a long
     * queue stalled the UI on the database, while the small updates were left
     * to run whenever they got scheduled: the timestamp of a track just started
     * could land before the rewrite that replaced its row, and be lost with it.
     */
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static void write(Runnable change) {
        executor.execute(change);
    }

    /*
     * Reads wait on the same thread, so they see every change asked for before
     * them. The callers are the ones that need the answer to go on - restoring
     * the queue at start-up, counting it to decide whether the player shows.
     */
    private static <T> T read(Callable<T> query, T fallback) {
        try {
            T result = executor.submit(query).get();
            return result != null ? result : fallback;
        } catch (ExecutionException e) {
            e.printStackTrace();
            return fallback;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        }
    }
}
