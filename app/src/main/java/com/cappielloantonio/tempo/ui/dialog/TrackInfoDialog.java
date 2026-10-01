package com.cappielloantonio.tempo.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.fragment.app.DialogFragment;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.DialogTrackInfoBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.subsonic.models.ReplayGain;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.GenreNames;
import com.cappielloantonio.tempo.util.MusicUtil;
import com.cappielloantonio.tempo.util.Preferences;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Locale;

@OptIn(markerClass = UnstableApi.class)
public class TrackInfoDialog extends DialogFragment {
    private static final String ARG_MEDIA_METADATA = "media_metadata";

    private DialogTrackInfoBinding bind;

    private MediaMetadata mediaMetadata;

    /*
     * The track travels in the arguments. It used to be a constructor
     * parameter, and the system rebuilds a fragment through the empty
     * constructor - so turning the phone with this dialog open, or coming back
     * to it after the process had been let go, crashed the app.
     */
    public static TrackInfoDialog newInstance(MediaMetadata mediaMetadata) {
        Bundle args = new Bundle();
        args.putBundle(ARG_MEDIA_METADATA, mediaMetadata.toBundle());

        TrackInfoDialog dialog = new TrackInfoDialog();
        dialog.setArguments(args);
        return dialog;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Bundle metadata = requireArguments().getBundle(ARG_MEDIA_METADATA);
        mediaMetadata = metadata != null ? MediaMetadata.fromBundle(metadata) : MediaMetadata.EMPTY;

        bind = DialogTrackInfoBinding.inflate(getLayoutInflater());

        return new MaterialAlertDialogBuilder(requireActivity())
                .setView(bind.getRoot())
                .setPositiveButton(R.string.track_info_dialog_positive_button, (dialog, id) -> dialog.cancel())
                .create();
    }

    @Override
    public void onStart() {
        super.onStart();

        setTrackInfo();
        setTrackTranscodingInfo();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind = null;
    }

    private void setTrackInfo() {
        bind.trakTitleInfoTextView.setText(mediaMetadata.title);
        bind.trakArtistInfoTextView.setText(mediaMetadata.artist);

        if (mediaMetadata.extras != null) {
            CustomGlideRequest.Builder
                    .from(requireContext(), mediaMetadata.extras.getString("coverArtId", ""), CustomGlideRequest.ResourceType.Song)
                    .build()
                    .into(bind.trackCoverInfoImageView);

            bind.titleValueSector.setText(mediaMetadata.extras.getString("title", getString(R.string.label_placeholder)));
            bind.albumValueSector.setText(mediaMetadata.extras.getString("album", getString(R.string.label_placeholder)));
            bind.artistValueSector.setText(mediaMetadata.extras.getString("artist", getString(R.string.label_placeholder)));
            bind.trackNumberValueSector.setText(String.valueOf(mediaMetadata.extras.getInt("track", 0)));
            bind.yearValueSector.setText(String.valueOf(mediaMetadata.extras.getInt("year", 0)));
            String genre = mediaMetadata.extras.getString("genre");
            bind.genreValueSector.setText(genre != null ? GenreNames.label(requireContext(), genre) : getString(R.string.label_placeholder));
            bind.sizeValueSector.setText(MusicUtil.getReadableByteCount(mediaMetadata.extras.getLong("size", 0)));
            bind.contentTypeValueSector.setText(mediaMetadata.extras.getString("contentType", getString(R.string.label_placeholder)));
            bind.suffixValueSector.setText(mediaMetadata.extras.getString("suffix", getString(R.string.label_placeholder)));
            bind.transcodedContentTypeValueSector.setText(mediaMetadata.extras.getString("transcodedContentType", getString(R.string.label_placeholder)));
            bind.transcodedSuffixValueSector.setText(mediaMetadata.extras.getString("transcodedSuffix", getString(R.string.label_placeholder)));
            bind.durationValueSector.setText(MusicUtil.getReadableDurationString(mediaMetadata.extras.getInt("duration", 0), false));
            bind.bitrateValueSector.setText(mediaMetadata.extras.getInt("bitrate", 0) + " kbps");
            bind.pathValueSector.setText(mediaMetadata.extras.getString("path", getString(R.string.label_placeholder)));
            bind.discNumberValueSector.setText(String.valueOf(mediaMetadata.extras.getInt("discNumber", 0)));

            /*
             * OpenSubsonic detail. Every one of these is optional - a server
             * that does not report it, or a track that has no value for it,
             * would otherwise show a row reading "-", so each row appears only
             * when it has something to say.
             */
            bind.artistValueSector.setText(text(mediaMetadata.extras.getString("displayArtist"), mediaMetadata.extras.getString("artist")));

            int samplingRate = mediaMetadata.extras.getInt("samplingRate", 0);
            row(bind.sampleRateInfoSector, bind.sampleRateValueSector, samplingRate > 0 ? String.format(Locale.getDefault(), "%.1f kHz", samplingRate / 1000f) : null);

            int bitDepth = mediaMetadata.extras.getInt("bitDepth", 0);
            row(bind.bitDepthInfoSector, bind.bitDepthValueSector, bitDepth > 0 ? bitDepth + " bit" : null);

            int channelCount = mediaMetadata.extras.getInt("channelCount", 0);
            row(bind.channelCountInfoSector, bind.channelCountValueSector, channelCount > 0 ? String.valueOf(channelCount) : null);

            int bpm = mediaMetadata.extras.getInt("bpm", 0);
            row(bind.bpmInfoSector, bind.bpmValueSector, bpm > 0 ? String.valueOf(bpm) : null);

            ReplayGain replayGain = ReplayGain.from(mediaMetadata.extras);
            row(bind.trackGainInfoSector, bind.trackGainValueSector, decibels(replayGain != null ? replayGain.getTrackGain() : null));
            row(bind.albumGainInfoSector, bind.albumGainValueSector, decibels(replayGain != null ? replayGain.getAlbumGain() : null));

            row(bind.commentInfoSector, bind.commentValueSector, mediaMetadata.extras.getString("comment"));
            row(bind.musicBrainzIdInfoSector, bind.musicBrainzIdValueSector, mediaMetadata.extras.getString("musicBrainzId"));
        }
    }

    private String text(String preferred, String fallback) {
        if (preferred != null && !preferred.trim().isEmpty()) return preferred;
        if (fallback != null && !fallback.trim().isEmpty()) return fallback;

        return getString(R.string.label_placeholder);
    }

    private String decibels(Float gain) {
        return gain != null ? String.format(Locale.getDefault(), "%+.2f dB", gain) : null;
    }

    private void row(View sector, TextView value, String content) {
        boolean present = content != null && !content.trim().isEmpty();

        sector.setVisibility(present ? View.VISIBLE : View.GONE);
        if (present) value.setText(content);
    }

    private void setTrackTranscodingInfo() {
        StringBuilder info = new StringBuilder();

        boolean prioritizeServerTranscoding = Preferences.isServerPrioritized();

        String transcodingExtension = MusicUtil.getTranscodingFormatPreference();
        String transcodingBitrate = Integer.parseInt(MusicUtil.getBitratePreference()) != 0 ? Integer.parseInt(MusicUtil.getBitratePreference()) + "kbps" : "Original";

        if (mediaMetadata.extras != null && mediaMetadata.extras.getString("uri", "").contains(Constants.DOWNLOAD_URI)) {
            info.append(getString(R.string.track_info_summary_downloaded_file));

            bind.trakTranscodingInfoTextView.setText(info);
            return;
        }

        if (prioritizeServerTranscoding) {
            info.append(getString(R.string.track_info_summary_server_prioritized));

            bind.trakTranscodingInfoTextView.setText(info);
            return;
        }

        if (!prioritizeServerTranscoding && transcodingExtension.equals("raw") && transcodingBitrate.equals("Original")) {
            info.append(getString(R.string.track_info_summary_original_file));

            bind.trakTranscodingInfoTextView.setText(info);
            return;
        }

        if (!prioritizeServerTranscoding && !transcodingExtension.equals("raw") && transcodingBitrate.equals("Original")) {
            info.append(getString(R.string.track_info_summary_transcoding_codec, transcodingExtension));

            bind.trakTranscodingInfoTextView.setText(info);
            return;
        }

        if (!prioritizeServerTranscoding && transcodingExtension.equals("raw") && !transcodingBitrate.equals("Original")) {
            info.append(getString(R.string.track_info_summary_transcoding_bitrate, transcodingBitrate));

            bind.trakTranscodingInfoTextView.setText(info);
            return;
        }

        if (!prioritizeServerTranscoding && !transcodingExtension.equals("raw") && !transcodingBitrate.equals("Original")) {
            info.append(getString(R.string.track_info_summary_full_transcode, transcodingExtension, transcodingBitrate));

            bind.trakTranscodingInfoTextView.setText(info);
        }
    }
}
