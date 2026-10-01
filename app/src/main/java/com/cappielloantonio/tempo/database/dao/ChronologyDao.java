package com.cappielloantonio.tempo.database.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.cappielloantonio.tempo.model.Chronology;

import java.util.List;

@Dao
public interface ChronologyDao {
    @Query("SELECT * FROM chronology WHERE server == :server GROUP BY id ORDER BY MAX(timestamp) DESC LIMIT :count")
    LiveData<List<Chronology>> getLastPlayed(String server, int count);

    /*
     * The most played songs since a moment, by plays in that window. Ties go to
     * the song the server has counted more plays of overall, then to the one
     * heard last - so history written before plays were kept one row each,
     * where every song counts once, still comes out in a meaningful order.
     */
    @Query("SELECT * FROM chronology WHERE timestamp >= :since AND server == :server GROUP BY id ORDER BY COUNT(*) DESC, MAX(play_count) DESC, MAX(timestamp) DESC LIMIT 20")
    LiveData<List<Chronology>> getTopSince(long since, String server);

    /*
     * What the listener has actually played, as ids, for search ranking. Grouped
     * rather than DISTINCT because SQLite will not order a DISTINCT select by a
     * column it does not return, and the limit has to keep the newest rows.
     */
    @Query("SELECT id FROM chronology WHERE server == :server GROUP BY id ORDER BY MAX(timestamp) DESC LIMIT :count")
    List<String> getPlayedSongIds(String server, int count);

    @Query("SELECT album_id FROM chronology WHERE server == :server AND album_id IS NOT NULL GROUP BY album_id ORDER BY MAX(timestamp) DESC LIMIT :count")
    List<String> getPlayedAlbumIds(String server, int count);

    @Query("SELECT artist_id FROM chronology WHERE server == :server AND artist_id IS NOT NULL GROUP BY artist_id ORDER BY MAX(timestamp) DESC LIMIT :count")
    List<String> getPlayedArtistIds(String server, int count);

    @Insert
    void insert(Chronology chronologyObject);

    /*
     * Plays older than the longest period Home can show are only kept as each
     * song's latest one, which is all the recency signals above look at, so
     * the table does not grow by a row per play forever.
     */
    @Query("DELETE FROM chronology WHERE timestamp < :before AND play_id NOT IN (SELECT MAX(play_id) FROM chronology GROUP BY id, server)")
    void pruneBefore(long before);
}
