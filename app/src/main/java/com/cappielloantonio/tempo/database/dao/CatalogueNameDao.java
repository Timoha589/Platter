package com.cappielloantonio.tempo.database.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.cappielloantonio.tempo.model.CatalogueName;

import java.util.List;

@Dao
public interface CatalogueNameDao {
    @Query("SELECT * FROM catalogue_name")
    List<CatalogueName> getAll();

    @Query("SELECT COUNT(*) FROM catalogue_name")
    int count();

    /*
     * Replacing a kind wholesale rather than merging: an artist deleted from the
     * library has to leave the spelling reference too, or the index starts
     * proposing corrections towards names that are no longer there.
     */
    @Query("DELETE FROM catalogue_name WHERE kind = :kind")
    void deleteKind(int kind);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<CatalogueName> names);
}
