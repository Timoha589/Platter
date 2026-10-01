package com.cappielloantonio.tempo.database;

import androidx.annotation.NonNull;
import androidx.media3.common.util.UnstableApi;
import androidx.room.AutoMigration;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.database.converter.DateConverters;
import com.cappielloantonio.tempo.database.dao.CatalogueNameDao;
import com.cappielloantonio.tempo.database.dao.ChronologyDao;
import com.cappielloantonio.tempo.database.dao.DownloadDao;
import com.cappielloantonio.tempo.database.dao.FavoriteDao;
import com.cappielloantonio.tempo.database.dao.OfflineLyricsDao;
import com.cappielloantonio.tempo.database.dao.PlaylistDao;
import com.cappielloantonio.tempo.database.dao.QueueDao;
import com.cappielloantonio.tempo.database.dao.RatingDao;
import com.cappielloantonio.tempo.database.dao.RecentSearchDao;
import com.cappielloantonio.tempo.database.dao.ServerDao;
import com.cappielloantonio.tempo.database.dao.SessionMediaItemDao;
import com.cappielloantonio.tempo.model.CatalogueName;
import com.cappielloantonio.tempo.model.Chronology;
import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.model.Favorite;
import com.cappielloantonio.tempo.model.OfflineLyrics;
import com.cappielloantonio.tempo.model.Queue;
import com.cappielloantonio.tempo.model.Rating;
import com.cappielloantonio.tempo.model.RecentSearch;
import com.cappielloantonio.tempo.model.Server;
import com.cappielloantonio.tempo.model.SessionMediaItem;
import com.cappielloantonio.tempo.subsonic.models.Playlist;

@UnstableApi
@Database(
        version = 19,
        entities = {Queue.class, Server.class, RecentSearch.class, Download.class, Chronology.class, Favorite.class, Rating.class, SessionMediaItem.class, Playlist.class, CatalogueName.class, OfflineLyrics.class},
        // 10 -> 11 only adds nullable OpenSubsonic columns to the tables backed
        // by Child, so Room can write the migration itself and the queue,
        // downloads and history survive the upgrade. 11 -> 12 only adds the
        // rating table, which is likewise nothing Room cannot do on its own.
        // 12 -> 13 adds a timestamp to recent_search so the list can be ordered
        // by recency instead of alphabetically; the column has a default, so
        // existing searches survive the upgrade and simply sort last. 13 -> 14
        // only adds the catalogue_name table, which Room can create on its own
        // and which refills itself from the server on first use. 14 -> 15 adds
        // why a download is on the device and when it last played, both with
        // defaults - so every existing download reads as one the user made,
        // which is what it was. 15 -> 16 adds the server's replay gain as
        // nullable columns next to the track detail; rows written before it
        // simply have none and fall back to the stream's tags. 16 -> 17 only
        // adds offline_lyrics, the words kept with downloaded tracks; downloads
        // made before it fill it in the next time the server can be reached.
        // 17 -> 18 adds when a download was asked for, defaulting to zero;
        // DownloaderManager dates the old records from the download index.
        // 18 -> 19 is MIGRATION_18_19 below: a primary key change, which Room
        // cannot write itself.
        autoMigrations = {@AutoMigration(from = 9, to = 10), @AutoMigration(from = 10, to = 11), @AutoMigration(from = 11, to = 12), @AutoMigration(from = 12, to = 13), @AutoMigration(from = 13, to = 14), @AutoMigration(from = 14, to = 15), @AutoMigration(from = 15, to = 16), @AutoMigration(from = 16, to = 17), @AutoMigration(from = 17, to = 18)}
)
@TypeConverters({DateConverters.class})
public abstract class AppDatabase extends RoomDatabase {
    private final static String DB_NAME = "tempo_db";
    private static AppDatabase instance;

    /*
     * 18 -> 19: chronology is keyed by a play id instead of the song id, so
     * every play is a row of its own and "Your top tracks" can count them.
     * Room cannot migrate a primary key change by itself, so the table is
     * rebuilt; the history so far comes across as one play per song, in the
     * order it was heard.
     */
    private static final String CHRONOLOGY_18_COLUMNS = "`id`, `timestamp`, `server`, `parent_id`, `is_dir`, `title`, `album`, `artist`, "
            + "`track`, `year`, `genre`, `cover_art_id`, `size`, `content_type`, `suffix`, "
            + "`transcoding_content_type`, `transcoded_suffix`, `duration`, `bitrate`, `path`, "
            + "`is_video`, `user_rating`, `average_rating`, `play_count`, `disc_number`, "
            + "`created`, `starred`, `album_id`, `artist_id`, `type`, `bookmark_position`, "
            + "`original_width`, `original_height`, `media_type`, `display_artist`, "
            + "`display_album_artist`, `display_composer`, `music_brainz_id`, "
            + "`explicit_status`, `sort_name`, `comment`, `bpm`, `bit_depth`, `sampling_rate`, "
            + "`channel_count`, `played`, `replay_gain_track_gain`, `replay_gain_album_gain`, "
            + "`replay_gain_track_peak`, `replay_gain_album_peak`";

    static final Migration MIGRATION_18_19 = new Migration(18, 19) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE `chronology_new` (`id` TEXT NOT NULL, "
                    + "`play_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`timestamp` INTEGER NOT NULL, `server` TEXT, `parent_id` TEXT, "
                    + "`is_dir` INTEGER NOT NULL, `title` TEXT, `album` TEXT, `artist` TEXT, "
                    + "`track` INTEGER, `year` INTEGER, `genre` TEXT, `cover_art_id` TEXT, "
                    + "`size` INTEGER, `content_type` TEXT, `suffix` TEXT, "
                    + "`transcoding_content_type` TEXT, `transcoded_suffix` TEXT, `duration` INTEGER, "
                    + "`bitrate` INTEGER, `path` TEXT, `is_video` INTEGER NOT NULL, "
                    + "`user_rating` INTEGER, `average_rating` REAL, `play_count` INTEGER, "
                    + "`disc_number` INTEGER, `created` INTEGER, `starred` INTEGER, `album_id` TEXT, "
                    + "`artist_id` TEXT, `type` TEXT, `bookmark_position` INTEGER, "
                    + "`original_width` INTEGER, `original_height` INTEGER, `media_type` TEXT, "
                    + "`display_artist` TEXT, `display_album_artist` TEXT, `display_composer` TEXT, "
                    + "`music_brainz_id` TEXT, `explicit_status` TEXT, `sort_name` TEXT, "
                    + "`comment` TEXT, `bpm` INTEGER, `bit_depth` INTEGER, `sampling_rate` INTEGER, "
                    + "`channel_count` INTEGER, `played` INTEGER, `replay_gain_track_gain` REAL, "
                    + "`replay_gain_album_gain` REAL, `replay_gain_track_peak` REAL, "
                    + "`replay_gain_album_peak` REAL)");
            db.execSQL("INSERT INTO `chronology_new` (" + CHRONOLOGY_18_COLUMNS + ") "
                    + "SELECT " + CHRONOLOGY_18_COLUMNS + " FROM `chronology` ORDER BY `timestamp`");
            db.execSQL("DROP TABLE `chronology`");
            db.execSQL("ALTER TABLE `chronology_new` RENAME TO `chronology`");
        }
    };

    public static synchronized AppDatabase getInstance() {
        if (instance == null) {
            instance = Room.databaseBuilder(App.getContext(), AppDatabase.class, DB_NAME)
                    .addMigrations(MIGRATION_18_19)
                    .fallbackToDestructiveMigration()
                    .build();
        }

        return instance;
    }

    public abstract QueueDao queueDao();

    public abstract ServerDao serverDao();

    public abstract RecentSearchDao recentSearchDao();

    public abstract DownloadDao downloadDao();

    public abstract ChronologyDao chronologyDao();

    public abstract FavoriteDao favoriteDao();

    public abstract RatingDao ratingDao();

    public abstract SessionMediaItemDao sessionMediaItemDao();

    public abstract PlaylistDao playlistDao();

    public abstract CatalogueNameDao catalogueNameDao();

    public abstract OfflineLyricsDao offlineLyricsDao();
}
