package com.cappielloantonio.tempo.repository;

import androidx.annotation.OptIn;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.database.dao.DownloadDao;
import com.cappielloantonio.tempo.database.dao.FavoriteDao;
import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.model.Favorite;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@OptIn(markerClass = UnstableApi.class)
public class DownloadRepository {
    private final DownloadDao downloadDao = AppDatabase.getInstance().downloadDao();

    public LiveData<List<Download>> getLiveDownload() {
        return downloadDao.getAll();
    }

    public LiveData<List<Download>> getLiveDownloadNewestFirst() {
        return downloadDao.getAllNewestFirst();
    }

    /*
     * Every write to the download table, in the order it was asked for. Each
     * used to be a thread of its own, so nothing kept them in order: taking a
     * download off and asking for it again straight after could have the
     * delete land last and leave a track downloading that no list showed.
     */
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    /* Dates the records written before downloads kept a time of their own. */
    public void fillInDownloadedAt(Map<String, Long> startedAt) {
        executor.execute(() -> startedAt.forEach(downloadDao::setDownloadedAtIfUnknown));
    }

    /* Waits for the writes asked for before it, so it never reads a record about to change. */
    public Download getDownload(String id) {
        try {
            return executor.submit(() -> downloadDao.getOne(id)).get();
        } catch (ExecutionException e) {
            e.printStackTrace();
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public void insert(Download download) {
        executor.execute(() -> downloadDao.insert(download));
    }

    public void update(String id) {
        executor.execute(() -> downloadDao.update(id));
    }

    public void insertAll(List<Download> downloads) {
        executor.execute(() -> downloadDao.insertAll(downloads));
    }

    /* Blocking - for callers already off the main thread. */
    @WorkerThread
    public List<String> getIdsBySource(int source) {
        return downloadDao.getIdsBySource(source);
    }

    /* Blocking - for callers already off the main thread. */
    @WorkerThread
    public void changeSource(List<String> ids, int from, int to) {
        for (String id : ids) downloadDao.changeSource(id, from, to);
    }

    public void setLastPlayedAt(String id, long timestamp) {
        executor.execute(() -> downloadDao.setLastPlayedAt(id, timestamp));
    }

    public void deleteAll() {
        executor.execute(downloadDao::deleteAll);
    }

    public void delete(String id) {
        executor.execute(() -> downloadDao.delete(id));
    }
}
