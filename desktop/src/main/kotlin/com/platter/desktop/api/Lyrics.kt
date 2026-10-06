package com.platter.desktop.api

import java.util.Locale

/** What the lyrics panel shows: timed or untimed lines of one layer, or plain text from the old endpoint. */
class SongLyrics(val layer: StructuredLyrics?, val plain: String?) {
    val lines: List<String>
        get() = layer?.line?.map { it.value.orEmpty() } ?: plain?.lines().orEmpty()

    val isSynced: Boolean get() = LyricsPicker.isSynced(layer)
    val isEmpty: Boolean get() = lines.none { it.isNotBlank() }
}

/**
 * Picks which lyric layer to show - the Android app's LyricsUtil.
 *
 * `getLyricsBySongId` answers with a list: the sung words, a translation, a
 * pronunciation guide, each possibly in several languages. Taking element zero
 * shows a translation whenever the server happened to put it first, and loses
 * the timing when the synced layer comes second.
 */
object LyricsPicker {
    fun pick(list: LyricsList?, language: String = Locale.getDefault().language): StructuredLyrics? =
        list?.structuredLyrics.orEmpty()
            .filter { !it.line.isNullOrEmpty() }
            .maxByOrNull { score(it, language) }

    fun isSynced(layer: StructuredLyrics?): Boolean = layer?.synced == true && layer.line.orEmpty().any { it.start != null }

    /** The sung words beat a translation, timed beats untimed, and the reader's own language breaks ties. */
    private fun score(layer: StructuredLyrics, language: String): Int {
        var score = 0
        val kind = layer.kind
        if (kind == null || kind.equals(StructuredLyrics.KIND_MAIN, ignoreCase = true)) score += 8
        else if (kind.equals(StructuredLyrics.KIND_PRONUNCIATION, ignoreCase = true)) score += 1
        if (layer.synced == true) score += 4
        if (matchesLanguage(layer.lang, language)) score += 2
        return score
    }

    private fun matchesLanguage(lang: String?, language: String): Boolean {
        if (lang == null || language.isEmpty()) return false
        // "und" and "xxx" are the spec's placeholders for an unknown language.
        if (lang.equals("und", true) || lang.equals("xxx", true)) return false
        return lang.lowercase(Locale.ROOT).startsWith(language.lowercase(Locale.ROOT))
    }

    /**
     * The line being sung at [positionMs]: the last one that has started. The
     * clock the lines are matched against is the playback position plus the
     * file's offset. -1 before the first line.
     */
    fun activeLine(lines: List<LyricLine>, positionMs: Long, offsetMs: Int = 0): Int {
        val clock = positionMs + offsetMs
        var active = -1
        for ((i, line) in lines.withIndex()) {
            val start = line.start ?: continue
            if (start <= clock) active = i else break
        }
        return active
    }

    /** A song that opens with this much silence before its first word gets a row of its own for it. */
    const val INTRO_GAP_MS = 4000

    /**
     * [lines] with an empty line at 0 when the first real one comes late: the panel draws an empty line as a row of dots that
     * fill up as the wait runs out, so the opening bars are not a blank screen with nothing to say how long it will last.
     */
    fun withIntro(lines: List<LyricLine>, gapMs: Int = INTRO_GAP_MS): List<LyricLine> {
        val first = lines.firstOrNull() ?: return lines
        val start = first.start ?: return lines
        if (start < gapMs || first.value.isNullOrBlank()) return lines
        return listOf(LyricLine().apply { this.start = 0; value = "" }) + lines
    }

    /** The position that lands on [line]: its own start less the offset. Null for a line with no start. */
    fun seekPosition(line: LyricLine, offsetMs: Int = 0): Long? = line.start?.let { (it - offsetMs).toLong().coerceAtLeast(0) }
}
