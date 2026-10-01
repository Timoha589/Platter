package com.cappielloantonio.tempo.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.text.Html;
import android.util.Log;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.repository.DownloadRepository;
import com.cappielloantonio.tempo.subsonic.models.Child;

import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class MusicUtil {
    private static final String TAG = "MusicUtil";

    public static Uri getStreamUri(String id) {
        Map<String, String> params = App.getSubsonicClientInstance(false).getParams();

        StringBuilder query = Util.authenticationQuery(params);

        if (!Preferences.isServerPrioritized()) {
            Util.appendQueryParam(query, "maxBitRate", getBitratePreference());
            Util.appendQueryParam(query, "format", getTranscodingFormatPreference());
        }
        if (Preferences.askForEstimateContentLength())
            Util.appendQueryParam(query, "estimateContentLength", "true");

        Util.appendQueryParam(query, "id", id);

        String uri = App.getSubsonicClientInstance(false).getUrl() + "stream" + query;

        Log.d(TAG, "getStreamUri: " + uri);

        return Uri.parse(uri);
    }

    public static Uri getDownloadUri(String id) {
        String uri;

        Download download = new DownloadRepository().getDownload(id);

        if (download == null || download.getDownloadUri().isEmpty()) {
            Map<String, String> params = App.getSubsonicClientInstance(false).getParams();

            StringBuilder query = Util.authenticationQuery(params);
            Util.appendQueryParam(query, "id", id);

            uri = App.getSubsonicClientInstance(false).getUrl() + "download" + query;
        } else {
            uri = download.getDownloadUri();
        }

        Log.d(TAG, "getDownloadUri: " + uri);

        return Uri.parse(uri);
    }

    public static Uri getTranscodedDownloadUri(String id) {
        Map<String, String> params = App.getSubsonicClientInstance(false).getParams();

        StringBuilder query = Util.authenticationQuery(params);

        if (!Preferences.isServerPrioritizedInTranscodedDownload()) {
            Util.appendQueryParam(query, "maxBitRate", getBitratePreferenceForDownload());
            Util.appendQueryParam(query, "format", getTranscodingFormatPreferenceForDownload());
        }

        Util.appendQueryParam(query, "id", id);

        String uri = App.getSubsonicClientInstance(false).getUrl() + "stream" + query;

        Log.d(TAG, "getTranscodedDownloadUri: " + uri);

        return Uri.parse(uri);
    }


    public static String getReadableDurationString(Long duration, boolean millis) {
        long lenght = duration != null ? duration : 0;

        long minutes;
        long seconds;

        if (millis) {
            minutes = (lenght / 1000) / 60;
            seconds = (lenght / 1000) % 60;
        } else {
            minutes = lenght / 60;
            seconds = lenght % 60;
        }

        if (minutes < 60) {
            return String.format(Locale.getDefault(), "%01d:%02d", minutes, seconds);
        } else {
            long hours = minutes / 60;
            minutes = minutes % 60;
            return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
        }
    }

    public static String getReadableDurationString(Integer duration, boolean millis) {
        long lenght = duration != null ? duration : 0;
        return getReadableDurationString(lenght, millis);
    }

    /**
     * How long an album or a playlist runs, as its page header says it:
     * "26 мин", "2 ч 58 мин". A clock reading ("2:58:58") is a track's length;
     * a whole record is told in minutes, and the album and playlist pages
     * used to say it one way each.
     */
    public static String getReadableLength(Context context, Integer seconds) {
        int minutes = seconds != null ? Math.round(seconds / 60f) : 0;

        if (minutes < 60) return context.getString(R.string.page_duration_minutes, minutes);

        return context.getString(R.string.page_duration_hours_minutes, minutes / 60, minutes % 60);
    }

    /**
     * The caption under a playlist's name - "Плейлист • 50 треков • 2 ч 59
     * мин" - shared by the playlist page and the liked tracks, which are laid
     * out as one.
     */
    public static String getPlaylistCaption(Context context, int count, long seconds) {
        List<String> parts = new ArrayList<>();
        parts.add(context.getString(R.string.label_role_playlist));
        parts.add(context.getResources().getQuantityString(R.plurals.album_page_tracks_count, count, count));

        if (seconds > 0) parts.add(getReadableLength(context, (int) seconds));

        return String.join(" • ", parts);
    }

    /**
     * The one-line quality badge under a track title.
     * <p>
     * OpenSubsonic reports bit depth and sampling rate per track, so a lossless
     * file can say what it actually is - "24/96 flac" - instead of a bitrate
     * that means very little for lossless audio. Servers that do not report
     * them fall back to the bitrate as before.
     */
    public static String getReadableAudioQualityString(Child child) {
        if (!Preferences.showAudioQuality()) return "";

        Integer bitDepth = child.getBitDepth();
        Integer samplingRate = child.getSamplingRate();

        if (bitDepth != null && bitDepth > 0 && samplingRate != null && samplingRate > 0) {
            return String.format(
                    Locale.getDefault(),
                    "• %d/%s %s",
                    bitDepth,
                    trimTrailingZero(samplingRate / 1000f),
                    child.getSuffix() != null ? child.getSuffix() : ""
            ).trim();
        }

        if (child.getBitrate() == null) return "";

        return "•" +
                " " +
                child.getBitrate() +
                "kbps" +
                " " +
                child.getSuffix();
    }

    private static String trimTrailingZero(float kHz) {
        return kHz == Math.rint(kHz)
                ? String.valueOf((int) kHz)
                : String.format(Locale.getDefault(), "%.1f", kHz);
    }

    public static String getReadablePodcastDurationString(long duration) {
        long minutes = duration / 60;

        if (minutes < 60) {
            return String.format(Locale.getDefault(), "%01d min", minutes);
        } else {
            long hours = minutes / 60;
            minutes = minutes % 60;
            return String.format(Locale.getDefault(), "%d h %02d min", hours, minutes);
        }
    }

    public static String getReadableTrackNumber(Context context, Integer trackNumber) {
        if (trackNumber != null) {
            return String.valueOf(trackNumber);
        }

        return context.getString(R.string.label_placeholder);
    }

    public static String getReadableString(String string) {
        if (string != null) {
            return Html.fromHtml(string, Html.FROM_HTML_MODE_COMPACT).toString();
        }

        return "";
    }

    public static String forceReadableString(String string) {
        if (string != null) {
            return getReadableString(string)
                    .replaceAll("&#34;", "\"")
                    .replaceAll("&#39;", "'")
                    .replaceAll("&amp;", "'")
                    .replaceAll("<a\\s+([^>]+)>((?:.(?!</a>))*.)</a>", "");
        }

        return "";
    }

    public static String getReadableLyrics(String string) {
        if (string != null) {
            return string
                    .replaceAll("&#34;", "\"")
                    .replaceAll("&#39;", "'")
                    .replaceAll("&amp;", "'")
                    .replaceAll("&#xA;", "\n");
        }

        return "";
    }

    public static String getReadableByteCount(long bytes) {
        long absB = bytes == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(bytes);

        if (absB < 1024) {
            return bytes + " B";
        }

        long value = absB;

        CharacterIterator ci = new StringCharacterIterator("KMGTPE");

        for (int i = 40; i >= 0 && absB > 0xfffccccccccccccL >> i; i -= 10) {
            value >>= 10;
            ci.next();
        }

        value *= Long.signum(bytes);

        return String.format("%.1f %ciB", value / 1024.0, ci.current());
    }

    public static String passwordHexEncoding(String plainPassword) {
        return "enc:" + plainPassword.chars().mapToObj(Integer::toHexString).collect(Collectors.joining());
    }

    public static String getBitratePreference() {
        Network network = getConnectivityManager().getActiveNetwork();
        NetworkCapabilities networkCapabilities = getConnectivityManager().getNetworkCapabilities(network);
        String audioTranscodeFormat = getTranscodingFormatPreference();

        if (audioTranscodeFormat.equals("raw") || network == null || networkCapabilities == null)
            return "0";

        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return Preferences.getMaxBitrateWifi();
        } else if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return Preferences.getMaxBitrateMobile();
        } else {
            return Preferences.getMaxBitrateWifi();
        }
    }

    public static String getTranscodingFormatPreference() {
        Network network = getConnectivityManager().getActiveNetwork();
        NetworkCapabilities networkCapabilities = getConnectivityManager().getNetworkCapabilities(network);

        if (network == null || networkCapabilities == null) return "raw";

        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return Preferences.getAudioTranscodeFormatWifi();
        } else if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return Preferences.getAudioTranscodeFormatMobile();
        } else {
            return Preferences.getAudioTranscodeFormatWifi();
        }
    }

    public static String getBitratePreferenceForDownload() {
        String audioTranscodeFormat = getTranscodingFormatPreferenceForDownload();

        if (audioTranscodeFormat.equals("raw"))
            return "0";

        return Preferences.getBitrateTranscodedDownload();
    }

    public static String getTranscodingFormatPreferenceForDownload() {
        return Preferences.getAudioTranscodeFormatTranscodedDownload();
    }

    public static List<Child> limitPlayableMedia(List<Child> toLimit, int position) {
        if (!toLimit.isEmpty() && toLimit.size() > Constants.PLAYABLE_MEDIA_LIMIT) {
            int from = position < Constants.PRE_PLAYABLE_MEDIA ? 0 : position - Constants.PRE_PLAYABLE_MEDIA;
            int to = Math.min(from + Constants.PLAYABLE_MEDIA_LIMIT, toLimit.size());

            return toLimit.subList(from, to);
        }

        return toLimit;
    }

    public static int getPlayableMediaPosition(List<Child> toLimit, int position) {
        if (!toLimit.isEmpty() && toLimit.size() > Constants.PLAYABLE_MEDIA_LIMIT) {
            return Math.min(position, Constants.PRE_PLAYABLE_MEDIA);
        }

        return position;
    }

    private static ConnectivityManager getConnectivityManager() {
        return (ConnectivityManager) App.getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
    }

}