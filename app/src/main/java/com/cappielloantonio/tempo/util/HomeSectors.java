package com.cappielloantonio.tempo.util;

import android.content.Context;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.model.HomeSector;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/*
 * The sections home can show, and the one place their order is read from.
 *
 * Home and the rearrange dialog used to read the saved order each on their
 * own, and with nothing saved home had no order at all: it fell back to the
 * layout's, which is not the default the dialog offered.
 */
public final class HomeSectors {
    private static final class Sector {
        final String id;
        final int title;
        final boolean visible;

        Sector(String id, int title, boolean visible) {
            this.id = id;
            this.title = title;
            this.visible = visible;
        }
    }

    /* The default home: this order, with these shown. */
    private static final Sector[] SECTORS = {
            new Sector(Constants.HOME_SECTOR_DISCOVERY, R.string.home_title_discovery, false),
            new Sector(Constants.HOME_SECTOR_PINNED_PLAYLISTS, R.string.home_title_pinned_playlists, true),
            new Sector(Constants.HOME_SECTOR_MADE_FOR_YOU, R.string.home_title_made_for_you, true),
            new Sector(Constants.HOME_SECTOR_STARRED_TRACKS, R.string.home_title_starred_tracks, true),
            new Sector(Constants.HOME_SECTOR_STARRED_ALBUMS, R.string.home_title_starred_albums, false),
            new Sector(Constants.HOME_SECTOR_STARRED_ARTISTS, R.string.home_title_starred_artists, false),
            new Sector(Constants.HOME_SECTOR_FLASHBACK, R.string.home_title_flashback, false),
            new Sector(Constants.HOME_SECTOR_MOST_PLAYED, R.string.home_title_most_played, false),
            new Sector(Constants.HOME_SECTOR_LAST_PLAYED, R.string.home_title_last_played, true),
            new Sector(Constants.HOME_SECTOR_NEW_RELEASES, R.string.home_title_new_releases, true),
            new Sector(Constants.HOME_SECTOR_RECENTLY_ADDED, R.string.home_title_recently_added, true),
            new Sector(Constants.HOME_SECTOR_SHARED, R.string.home_title_shares, false),
            new Sector(Constants.HOME_SECTOR_TOP_SONGS, R.string.home_title_top_songs, false),
    };

    private HomeSectors() {
    }

    public static List<HomeSector> defaults(Context context) {
        List<HomeSector> sectors = new ArrayList<>();

        for (Sector sector : SECTORS) {
            sectors.add(new HomeSector(sector.id, context.getString(sector.title), sector.visible, sectors.size() + 1));
        }

        return sectors;
    }

    /*
     * The saved order, or the default one. A saved order carries the titles as
     * they read when it was saved, and may name sections home no longer has
     * or miss ones added since: the titles are taken fresh from the ids, the
     * retired sections are dropped and the new ones go at the end.
     */
    public static List<HomeSector> load(Context context) {
        String json = Preferences.getHomeSectorList();
        if (json == null || json.equals("null")) return defaults(context);

        List<HomeSector> saved = new Gson().fromJson(json, new TypeToken<List<HomeSector>>() {
        }.getType());
        if (saved == null) return defaults(context);

        List<HomeSector> sectors = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (HomeSector sector : saved) {
            Sector known = find(sector.getId());
            if (known == null || !seen.add(known.id)) continue;
            sectors.add(new HomeSector(known.id, context.getString(known.title), sector.isVisible(), sectors.size() + 1));
        }

        for (Sector known : SECTORS) {
            if (seen.contains(known.id)) continue;
            sectors.add(new HomeSector(known.id, context.getString(known.title), known.visible, sectors.size() + 1));
        }

        return sectors;
    }

    private static Sector find(String id) {
        for (Sector sector : SECTORS) {
            if (sector.id.equals(id)) return sector;
        }
        return null;
    }
}
