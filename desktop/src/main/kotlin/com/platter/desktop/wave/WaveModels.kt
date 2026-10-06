package com.platter.desktop.wave

import com.google.gson.annotations.SerializedName
import com.platter.desktop.api.Song

/*
 * The wave service that runs next to the daylist script on the server (see
 * wave/README.md in the Daylist project). Same wire format as the Android
 * app's wave/WaveApi.kt. Gson builds these without running a constructor, so
 * everything read from a response is nullable.
 */

class WaveBatch {
    var session: String? = null
    var batch: Int? = null
    var slot: String? = null
    var vibe: WaveVibe? = null
    var tracks: List<WaveTrack>? = null

    fun songs(): List<Song> = tracks.orEmpty().mapNotNull { it.song }
}

class WaveVibe {
    var label: String? = null
    var next: String? = null
    var transition: Boolean? = null
}

class WaveTrack {
    var id: String? = null
    var song: Song? = null

    /** The track's energy as a quantile of the listener's own likes, 0..1. */
    var energy: Double? = null

    /** Beats per minute. */
    var tempo: Double? = null
    var source: String? = null
}

class WaveEvent(
    val id: String,
    val type: String,
    @SerializedName("position_ms") val positionMs: Long = 0,
    @SerializedName("duration_ms") val durationMs: Long = 0,
) {
    companion object {
        const val COMPLETE = "complete"
        const val SKIP = "skip"
        const val LIKE = "like"
        const val UNLIKE = "unlike"
        const val DISLIKE = "dislike"
    }
}

class WaveNextBody(val session: String, val events: List<WaveEvent>)

/** The library searched by its embedded lyrics. [indexing] is true while the service is still reading them - the first search takes about a minute. */
class WaveLyrics {
    var indexing: Boolean? = null
    var indexed: Int? = null
    var results: List<WaveLyric>? = null
}

class WaveLyric {
    var id: String? = null

    /** The lines the query was found in, joined with " / ". */
    var snippet: String? = null
    var song: Song? = null
}

sealed class WaveResult {
    data class Ok(val batch: WaveBatch) : WaveResult()
    data class Failed(val reason: Reason, val detail: String? = null) : WaveResult()

    enum class Reason {
        /** No wave service where it was looked for: not installed, or not proxied. */
        UNREACHABLE,

        /** The service runs, but has no password for this user. */
        NOT_CONFIGURED,

        /** The service could not build a batch (no likes yet, AudioMuse down...). */
        UNAVAILABLE,
    }
}
