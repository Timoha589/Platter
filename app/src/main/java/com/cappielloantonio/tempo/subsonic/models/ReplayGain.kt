package com.cappielloantonio.tempo.subsonic.models

import android.os.Bundle
import android.os.Parcelable
import androidx.annotation.Keep
import androidx.room.ColumnInfo
import kotlinx.parcelize.Parcelize

/**
 * The OpenSubsonic replayGain object: gains in dB relative to the ReplayGain
 * reference level, peaks as a fraction of full scale. Every value is optional -
 * an untagged track reports none of them.
 */
@Keep
@Parcelize
class ReplayGain(
    @ColumnInfo(name = "track_gain")
    var trackGain: Float? = null,
    @ColumnInfo(name = "album_gain")
    var albumGain: Float? = null,
    @ColumnInfo(name = "track_peak")
    var trackPeak: Float? = null,
    @ColumnInfo(name = "album_peak")
    var albumPeak: Float? = null
) : Parcelable {
    /** Only the values that are there, so a missing one stays missing on the other side. */
    fun writeTo(bundle: Bundle) {
        trackGain?.let { bundle.putFloat(TRACK_GAIN, it) }
        albumGain?.let { bundle.putFloat(ALBUM_GAIN, it) }
        trackPeak?.let { bundle.putFloat(TRACK_PEAK, it) }
        albumPeak?.let { bundle.putFloat(ALBUM_PEAK, it) }
    }

    companion object {
        private const val TRACK_GAIN = "replayGainTrackGain"
        private const val ALBUM_GAIN = "replayGainAlbumGain"
        private const val TRACK_PEAK = "replayGainTrackPeak"
        private const val ALBUM_PEAK = "replayGainAlbumPeak"

        /** What [writeTo] put in the bundle, or null when there is no gain to apply. */
        @JvmStatic
        fun from(bundle: Bundle?): ReplayGain? {
            if (bundle == null) return null

            val gain = ReplayGain(
                bundle.float(TRACK_GAIN),
                bundle.float(ALBUM_GAIN),
                bundle.float(TRACK_PEAK),
                bundle.float(ALBUM_PEAK)
            )

            return if (gain.trackGain == null && gain.albumGain == null) null else gain
        }

        private fun Bundle.float(key: String): Float? = if (containsKey(key)) getFloat(key) else null
    }
}
