package com.cappielloantonio.tempo.subsonic.models

import androidx.annotation.Keep

/**
 * songLyrics extension, version 2: one word or syllable inside a cue line.
 * `byteStart` / `byteEnd` are offsets into the UTF-8 encoding of the line text.
 */
@Keep
class LyricsCue {
    var start: Int? = null
    var end: Int? = null
    var value: String? = null
    var byteStart: Int? = null
    var byteEnd: Int? = null
}
