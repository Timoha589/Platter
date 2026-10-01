package com.cappielloantonio.tempo.subsonic.models

import androidx.annotation.Keep

/**
 * songLyrics extension, version 2: karaoke timing for the line at [index].
 */
@Keep
class LyricsCueLine {
    var index: Int? = null
    var start: Int? = null
    var end: Int? = null
    var value: String? = null
    var agentId: String? = null
    var cue: List<LyricsCue>? = null
}
