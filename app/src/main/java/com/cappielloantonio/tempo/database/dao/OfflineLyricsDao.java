package com.cappielloantonio.tempo.database.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.cappielloantonio.tempo.model.OfflineLyrics;

@Dao
public interface OfflineLyricsDao {
    @Query("SELECT * FROM offline_lyrics WHERE id = :id")
    OfflineLyrics getOne(String id);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(OfflineLyrics lyrics);

    @Query("DELETE FROM offline_lyrics WHERE id = :id")
    void delete(String id);

    @Query("DELETE FROM offline_lyrics")
    void deleteAll();
}
