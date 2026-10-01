package com.cappielloantonio.tempo.database.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.cappielloantonio.tempo.model.Rating;

import java.util.List;

@Dao
public interface RatingDao {
    @Query("SELECT * FROM rating")
    List<Rating> getAll();

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insert(Rating rating);

    @Delete
    void delete(Rating rating);

    @Query("DELETE FROM rating")
    void deleteAll();
}
