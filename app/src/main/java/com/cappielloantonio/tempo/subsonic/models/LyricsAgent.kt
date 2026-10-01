package com.cappielloantonio.tempo.subsonic.models

import androidx.annotation.Keep

/**
 * songLyrics extension, version 2: who sings a cue line. Roles are
 * `main`, `voice`, `bg` or `group`.
 */
@Keep
class LyricsAgent {
    var id: String? = null
    var role: String? = null
    var name: String? = null
}
