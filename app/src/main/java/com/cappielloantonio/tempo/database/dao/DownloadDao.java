package com.cappielloantonio.tempo.database.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.cappielloantonio.tempo.model.Download;

import java.util.List;

@Dao
public interface DownloadDao {
    @Query("SELECT * FROM download WHERE download_state = 1 ORDER BY artist, album, disc_number, track ASC")
    LiveData<List<Download>> getAll();

    /*
     * The downloads screen: the latest download on top. Tracks downloaded
     * together share a timestamp, and fall back to album order among themselves.
     */
    @Query("SELECT * FROM download WHERE download_state = 1 ORDER BY downloaded_at DESC, artist, album, disc_number, track ASC")
    LiveData<List<Download>> getAllNewestFirst();

    /* Only where the time is not known yet, so a download asked for since is never pushed back. */
    @Query("UPDATE download SET downloaded_at = :timestamp WHERE id = :id AND downloaded_at = 0")
    void setDownloadedAtIfUnknown(String id, long timestamp);

    @Query("SELECT * FROM download WHERE id = :id")
    Download getOne(String id);

    /* Ids only: search ranking asks "is this one of mine", not for the rows. */
    @Query("SELECT id FROM download WHERE download_state = 1")
    List<String> getAllIds();

    /* Played longest ago first, which is the order smart download gives tracks up in. */
    @Query("SELECT id FROM download WHERE download_source = :source ORDER BY last_played_at ASC")
    List<String> getIdsBySource(int source);

    /* Downloads whose words have not been kept yet - see OfflineExtras.catchUp(). */
    @Query("SELECT * FROM download WHERE id NOT IN (SELECT id FROM offline_lyrics)")
    List<Download> getWithoutLyrics();

    /* One per album rather than per track: every track on it shares the cover. */
    @Query("SELECT DISTINCT cover_art_id FROM download WHERE cover_art_id IS NOT NULL")
    List<String> getCoverArtIds();

    @Query("UPDATE download SET download_source = :to WHERE id = :id AND download_source = :from")
    void changeSource(String id, int from, int to);

    @Query("UPDATE download SET last_played_at = :timestamp WHERE id = :id")
    void setLastPlayedAt(String id, long timestamp);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(Download download);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<Download> downloads);

    @Query("UPDATE download SET download_state = 1 WHERE id = :id")
    void update(String id);

    @Query("DELETE FROM download WHERE id = :id")
    void delete(String id);

    @Query("DELETE FROM download")
    void deleteAll();
}