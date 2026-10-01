package com.cappielloantonio.tempo.subsonic.models

import androidx.annotation.Keep

@Keep
class StructuredLyrics {
    var displayArtist: String? = null
    var displayTitle: String? = null
    var lang: String? = null
    var offset: Int = 0
    var synced: Boolean = false
    var line: List<Line>? = null

    /*
     * songLyrics version 2 (Navidrome 0.63+). A track can now come back as
     * several independent layers - the sung words, a translation, a
     * pronunciation guide - and they arrive in one list. Without `kind` a
     * client cannot tell them apart and ends up showing whichever the server
     * happened to put first.
     */
    var kind: String? = null
    var agents: List<LyricsAgent>? = null
    var cueLine: List<LyricsCueLine>? = null

    companion object {
        const val KIND_MAIN = "main"
        const val KIND_TRANSLATION = "translation"
        const val KIND_PRONUNCIATION = "pronunciation"
    }
}
