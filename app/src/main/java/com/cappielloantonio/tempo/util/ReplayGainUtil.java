package com.cappielloantonio.tempo.util;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Metadata;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.extractor.metadata.flac.VorbisComment;
import androidx.media3.extractor.metadata.id3.InternalFrame;
import androidx.media3.extractor.metadata.id3.TextInformationFrame;

import com.cappielloantonio.tempo.subsonic.models.ReplayGain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@OptIn(markerClass = UnstableApi.class)
public class ReplayGainUtil {
    private static float volume = 1f;
    private static float fade = 1f;

    private static final Pattern DECIBELS = Pattern.compile("[-+]?\\d+(?:[.,]\\d+)?");

    /*
     * R128_* tags (Opus) are measured against -23 LUFS, REPLAYGAIN_* tags and
     * the server's values against -18 LUFS. Adding the difference makes both
     * bring a track to the same loudness.
     */
    private static final float R128_TO_REPLAY_GAIN_DB = 5f;

    public static void setReplayGain(ExoPlayer player, Tracks tracks) {
        String mode = Preferences.getReplayGainMode();

        if (mode == null || mode.equals("disabled")) {
            setGain(player, 0f);
            return;
        }

        MediaItem mediaItem = player.getCurrentMediaItem();
        ReplayGain gain = ReplayGain.from(mediaItem != null ? mediaItem.mediaMetadata.extras : null);
        if (gain == null) gain = readTags(tracks);

        setGain(player, chooseGain(player, gain, mode));
    }

    private static float chooseGain(ExoPlayer player, @Nullable ReplayGain gain, String mode) {
        if (gain == null) return 0f;

        boolean album = mode.equals("album") || (mode.equals("auto") && isAlbumPlaying(player));
        Float chosen = album
                ? firstPresent(gain.getAlbumGain(), gain.getTrackGain())
                : firstPresent(gain.getTrackGain(), gain.getAlbumGain());

        return chosen != null ? chosen : 0f;
    }

    /*
     * Auto plays album gain while an album is being listened through and track
     * gain otherwise. A neighbour from the same album on either side counts:
     * looking only at the previous track left the first track of every album
     * at its own level and the rest at the album's. The neighbours are taken in
     * play order, so a shuffled queue is judged by what actually plays next.
     */
    private static boolean isAlbumPlaying(ExoPlayer player) {
        MediaItem current = player.getCurrentMediaItem();
        if (current == null) return false;

        return isSameAlbum(current, itemAt(player, player.getPreviousMediaItemIndex()))
                || isSameAlbum(current, itemAt(player, player.getNextMediaItemIndex()));
    }

    @Nullable
    private static MediaItem itemAt(ExoPlayer player, int index) {
        return index != C.INDEX_UNSET ? player.getMediaItemAt(index) : null;
    }

    private static boolean isSameAlbum(MediaItem current, @Nullable MediaItem other) {
        if (other == null) return false;

        String currentAlbumId = current.mediaMetadata.extras != null ? current.mediaMetadata.extras.getString("albumId") : null;
        String otherAlbumId = other.mediaMetadata.extras != null ? other.mediaMetadata.extras.getString("albumId") : null;
        if (currentAlbumId != null && otherAlbumId != null) return currentAlbumId.equals(otherAlbumId);

        return current.mediaMetadata.albumTitle != null
                && other.mediaMetadata.albumTitle != null
                && current.mediaMetadata.albumTitle.toString().equals(other.mediaMetadata.albumTitle.toString());
    }

    /*
     * The fallback for a server that does not report replay gain. Tags are read
     * by key and value rather than from the entry's toString(): that string
     * holds no value at all for an MP4 tag, and for R128 the "128" in the key
     * used to be parsed into the number.
     */
    @Nullable
    private static ReplayGain readTags(Tracks tracks) {
        ReplayGain gain = new ReplayGain();

        for (Tracks.Group group : tracks.getGroups()) {
            for (int i = 0; i < group.length; i++) {
                Metadata metadata = group.getTrackFormat(i).metadata;
                if (metadata == null) continue;

                for (int j = 0; j < metadata.length(); j++) {
                    readTag(metadata.get(j), gain);
                }
            }
        }

        return gain.getTrackGain() != null || gain.getAlbumGain() != null ? gain : null;
    }

    @SuppressWarnings("deprecation") // The Vorbis extractors still emit the flac-package class.
    private static void readTag(Metadata.Entry entry, ReplayGain gain) {
        String key;
        String value;

        if (entry instanceof VorbisComment) {
            key = ((VorbisComment) entry).key;
            value = ((VorbisComment) entry).value;
        } else if (entry instanceof TextInformationFrame) {
            TextInformationFrame frame = (TextInformationFrame) entry;
            key = frame.description;
            value = frame.values.isEmpty() ? null : frame.values.get(0);
        } else if (entry instanceof InternalFrame) {
            key = ((InternalFrame) entry).description;
            value = ((InternalFrame) entry).text;
        } else {
            return;
        }

        if (key == null || value == null) return;

        // Taggers disagree on case: foobar2000 writes these in lower case.
        switch (key.toUpperCase(Locale.ROOT)) {
            case "REPLAYGAIN_TRACK_GAIN":
                if (gain.getTrackGain() == null) gain.setTrackGain(parseDecibels(value));
                break;
            case "REPLAYGAIN_ALBUM_GAIN":
                if (gain.getAlbumGain() == null) gain.setAlbumGain(parseDecibels(value));
                break;
            case "R128_TRACK_GAIN":
                if (gain.getTrackGain() == null) gain.setTrackGain(parseR128(value));
                break;
            case "R128_ALBUM_GAIN":
                if (gain.getAlbumGain() == null) gain.setAlbumGain(parseR128(value));
                break;
        }
    }

    /* "-7.51 dB", "+2,30 dB" */
    @Nullable
    private static Float parseDecibels(String value) {
        Matcher matcher = DECIBELS.matcher(value);
        return matcher.find() ? Float.parseFloat(matcher.group().replace(',', '.')) : null;
    }

    /* A signed Q7.8 integer: dB times 256. */
    @Nullable
    private static Float parseR128(String value) {
        try {
            return Integer.parseInt(value.trim()) / 256f + R128_TO_REPLAY_GAIN_DB;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    @Nullable
    private static Float firstPresent(@Nullable Float preferred, @Nullable Float fallback) {
        return preferred != null ? preferred : fallback;
    }

    /*
     * Applied as the player's volume, which cannot go above unity. A positive
     * gain - a track mastered quieter than the reference - therefore plays as
     * it is, and only louder tracks are brought down.
     */
    private static void setGain(ExoPlayer player, float gain) {
        volume = (float) Math.min(1.0, Math.pow(10f, gain / 20f));
        player.setVolume(volume * fade);
    }

    /*
     * A crossfade fades a track in and out on top of its replay gain, so both
     * go through here: whichever changes, the player gets their product.
     */
    public static void setFade(ExoPlayer player, float fade) {
        ReplayGainUtil.fade = fade;
        player.setVolume(volume * fade);
    }

    /* The volume the replay gain asks for, before any fade. */
    public static float getVolume() {
        return volume;
    }
}
