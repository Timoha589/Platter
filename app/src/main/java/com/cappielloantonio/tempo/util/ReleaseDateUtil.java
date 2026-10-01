package com.cappielloantonio.tempo.util;

import androidx.annotation.Nullable;

import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.subsonic.models.ItemDate;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Locale;

/**
 * When an album came out, as precisely as the server knows it.
 * <p>
 * The label under an album and the order of a discography are both read from
 * here, so the list can never be sorted by one date and captioned with another.
 */
public final class ReleaseDateUtil {
    private ReleaseDateUtil() {
    }

    /**
     * Newest first; albums with no date at all go last, after the oldest.
     * <p>
     * Year alone used to decide this, so two albums from the same year came
     * out in whatever order the server listed them. Month and day break the
     * tie wherever the tags carry them.
     */
    public static final Comparator<AlbumID3> NEWEST_FIRST = (a, b) -> Long.compare(sortKey(b), sortKey(a));

    /**
     * The release of this edition, then the original release, then the bare
     * year - the first of those the server actually filled in.
     * <p>
     * The edition comes first because it is what the rest of the card is
     * about: a 2005 remaster has the 2005 cover and the 2005 track list.
     */
    @Nullable
    private static ItemDate dateOf(AlbumID3 album) {
        if (hasYear(album.getReleaseDate())) return album.getReleaseDate();
        if (hasYear(album.getOriginalReleaseDate())) return album.getOriginalReleaseDate();

        if (album.getYear() > 0) {
            ItemDate date = new ItemDate();
            date.setYear(album.getYear());
            return date;
        }

        return null;
    }

    private static boolean hasYear(@Nullable ItemDate date) {
        return date != null && date.getYear() != null && date.getYear() > 0;
    }

    /**
     * yyyymmdd as a number. A missing month or day counts as zero, so an album
     * known only by its year sorts after everything dated within that year -
     * it may well be the oldest of them, and nothing says it is the newest.
     */
    private static long sortKey(AlbumID3 album) {
        ItemDate date = dateOf(album);
        if (date == null) return -1;

        return date.getYear() * 10000L + valid(date.getMonth(), 12) * 100L + valid(date.getDay(), 31);
    }

    private static int valid(@Nullable Integer value, int max) {
        return value != null && value >= 1 && value <= max ? value : 0;
    }

    /**
     * "12 марта 2021", "март 2021" or "2021", depending on how much of the
     * date is known; empty when none of it is.
     */
    public static String format(AlbumID3 album) {
        ItemDate date = dateOf(album);
        if (date == null) return "";

        int month = valid(date.getMonth(), 12);
        int day = valid(date.getDay(), 31);

        if (month == 0) return String.valueOf(date.getYear());

        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(date.getYear(), month - 1, day == 0 ? 1 : day);

        // MMMM is the month as it reads inside a date ("марта"), LLLL as it
        // reads on its own ("март").
        String pattern = day == 0 ? "LLLL yyyy" : "d MMMM yyyy";

        return new SimpleDateFormat(pattern, Locale.getDefault()).format(calendar.getTime());
    }
}
