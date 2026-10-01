package com.cappielloantonio.tempo.util;

import androidx.annotation.Nullable;

import com.cappielloantonio.tempo.subsonic.models.LyricsList;
import com.cappielloantonio.tempo.subsonic.models.StructuredLyrics;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Picks which lyric layer to show.
 * <p>
 * {@code getLyricsBySongId} answers with a <em>list</em>: a track can carry the
 * sung words, a translation and a pronunciation guide, each possibly in several
 * languages, and since the songLyrics version 2 extension (Navidrome 0.63) they
 * are told apart by {@code kind}. The player used to take element zero, so a
 * track with a translation stored first showed the translation, and a track
 * whose synced layer came second lost the timing. It also called
 * {@code get(0)} on a list a server is free to return empty.
 */
public class LyricsUtil {
    /**
     * @return the layer to display, or null when there is nothing to show.
     */
    @Nullable
    public static StructuredLyrics pick(@Nullable LyricsList lyricsList) {
        if (lyricsList == null) return null;

        List<StructuredLyrics> lyrics = lyricsList.getStructuredLyrics();
        if (lyrics == null || lyrics.isEmpty()) return null;

        return lyrics.stream()
                .filter(entry -> entry != null && entry.getLine() != null && !entry.getLine().isEmpty())
                // Highest score first.
                .max(Comparator.comparingInt(LyricsUtil::score))
                .orElse(null);
    }

    /**
     * @return true when the chosen layer carries per-line timings.
     */
    public static boolean isSynced(@Nullable StructuredLyrics lyrics) {
        return lyrics != null && lyrics.getSynced();
    }

    /**
     * Ranks a layer: the sung words beat a translation, timed lyrics beat
     * untimed ones, and the reader's own language breaks the remaining ties.
     */
    private static int score(StructuredLyrics lyrics) {
        int score = 0;

        String kind = lyrics.getKind();
        if (kind == null || StructuredLyrics.KIND_MAIN.equalsIgnoreCase(kind)) {
            // No kind at all means a version 1 server, where every layer is the
            // main one.
            score += 8;
        } else if (StructuredLyrics.KIND_PRONUNCIATION.equalsIgnoreCase(kind)) {
            score += 1;
        }

        if (lyrics.getSynced()) score += 4;

        if (matchesDeviceLanguage(lyrics.getLang())) score += 2;

        return score;
    }

    private static boolean matchesDeviceLanguage(@Nullable String lang) {
        if (lang == null) return false;

        // "und" and "xxx" are the spec's placeholders for an unknown language.
        if (lang.equalsIgnoreCase("und") || lang.equalsIgnoreCase("xxx")) return false;

        String device = Locale.getDefault().getLanguage();

        return !device.isEmpty() && lang.toLowerCase(Locale.ROOT).startsWith(device.toLowerCase(Locale.ROOT));
    }
}
