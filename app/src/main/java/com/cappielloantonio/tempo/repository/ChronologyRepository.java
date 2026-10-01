package com.cappielloantonio.tempo.repository;

import androidx.annotation.OptIn;
import androidx.lifecycle.LiveData;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.database.AppDatabase;
import com.cappielloantonio.tempo.database.dao.ChronologyDao;
import com.cappielloantonio.tempo.model.Chronology;

import java.util.List;
import java.util.concurrent.TimeUnit;

@OptIn(markerClass = UnstableApi.class)
public class ChronologyRepository {
    /* The longest window Home's "Your top tracks" offers */
    private static final long KEPT_PLAYS_MS = TimeUnit.DAYS.toMillis(365);

    private final ChronologyDao chronologyDao = AppDatabase.getInstance().chronologyDao();

    /*
     * Open-ended at the recent side, so a play made while the list is on
     * screen counts straight away - Room re-runs the query on every insert.
     */
    public LiveData<List<Chronology>> getTopSongs(String server, long since) {
        return chronologyDao.getTopSince(since, server);
    }

    public void insert(Chronology item) {
        InsertThreadSafe insert = new InsertThreadSafe(chronologyDao, item);
        Thread thread = new Thread(insert);
        thread.start();
    }

    private static class InsertThreadSafe implements Runnable {
        private final ChronologyDao chronologyDao;
        private final Chronology item;

        public InsertThreadSafe(ChronologyDao chronologyDao, Chronology item) {
            this.chronologyDao = chronologyDao;
            this.item = item;
        }

        @Override
        public void run() {
            chronologyDao.insert(item);
            chronologyDao.pruneBefore(System.currentTimeMillis() - KEPT_PLAYS_MS);
        }
    }
}
