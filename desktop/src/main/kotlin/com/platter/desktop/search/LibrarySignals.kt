package com.platter.desktop.search

/**
 * What this listener has already done with the library, as sets of ids.
 *
 * The server ranks nothing: `search3` answers with whatever matched, in whatever order it stored it, identically for
 * every user. But the answer to "which of these twenty albums did you mean" is usually in what was starred and
 * played. Ids are namespaced by kind: a classic Subsonic server numbers songs, albums and artists in separate
 * sequences, so id "42" can name all three, and a starred song must not vouch for an unrelated album.
 */
class LibrarySignals(starred: Collection<Pair<Kind, String>> = emptyList(), played: Collection<Pair<Kind, String>> = emptyList()) {
    enum class Kind { SONG, ALBUM, ARTIST }

    private val starred = starred.mapTo(HashSet()) { key(it.first, it.second) }
    private val played = played.mapTo(HashSet()) { key(it.first, it.second) }

    fun isStarred(kind: Kind, id: String?): Boolean = id != null && key(kind, id) in starred
    fun wasPlayed(kind: Kind, id: String?): Boolean = id != null && key(kind, id) in played

    private fun key(kind: Kind, id: String) = "${kind.ordinal}:$id"

    companion object {
        val Empty = LibrarySignals()
    }
}
