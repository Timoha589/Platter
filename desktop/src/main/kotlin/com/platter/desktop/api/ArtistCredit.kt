package com.platter.desktop.api

/**
 * A credit line taken apart into the artists in it. The same rules as the phone's helper/ArtistCredit.
 *
 * Navidrome does not split a joint credit: "Illenium/Thirty Seconds to Mars"
 * on the track and "Illenium, Thirty Seconds to Mars" on the album are two
 * artists of their own, with no albums or no songs of the people in them. Its
 * page is empty, and the song is missing from both of theirs.
 *
 * A slash, a semicolon or a "feat." always joins names. A comma may be part
 * of one - "Earth, Wind & Fire", "Tyler, The Creator" - and such a name gives
 * itself away by turning up whole between slashes in other credits
 * ("Earth, Wind & Fire/Kenny G"). And a separator alone does not make a
 * collaboration - "AC/DC" - so a credit counts as joint only when the library
 * knows one of its parts on its own: as an artist, or inside another credit.
 */
object ArtistCredit {
    private val JOINS = Regex("\\s*/\\s*|\\s*;\\s*|\\s+(?:feat\\.?|ft\\.?|featuring)\\s+", RegexOption.IGNORE_CASE)
    private val COMMA = Regex(",\\s+")

    /** [credit] cut where names are always joined: the commas are left in. */
    fun pieces(credit: String?): List<String> = clean(credit?.split(JOINS))

    /** Every name [credit] may hold, commas cut too, in order and each once: what to search for. */
    fun split(credit: String?): List<String> = clean(pieces(credit).flatMap { it.split(COMMA) })

    private fun clean(names: List<String>?): List<String> =
        names.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

    /** Whether [credit] names [name] together with someone else. */
    fun isCollaboration(credit: String?, name: String?): Boolean {
        if (credit.isNullOrBlank() || name.isNullOrBlank()) return false
        val who = name.trim()
        if (credit.trim().equals(who, ignoreCase = true)) return false
        return (pieces(credit) + split(credit)).any { it.equals(who, ignoreCase = true) }
    }

    /** Of [songs], the ones [name] made with someone else, each once, newest first. */
    fun collaborations(songs: List<Song>?, name: String?): List<Song> =
        songs.orEmpty()
            .filter { isCollaboration(it.artistLine(), name) }
            .distinctBy { it.id }
            .sortedByDescending { it.year ?: 0 }

    /**
     * The artists behind [credit], or null when it is one artist after all.
     *
     * [found] holds the artists searches for the names in it ([split]) came
     * back with. A part found under its own name opens that artist; one found
     * only inside other credits has no page of its own on the server, and is
     * returned without an id - its page is made of its collaborations.
     */
    fun resolve(credit: String?, found: List<List<Artist>?>): List<Artist>? {
        val hits = found.filterNotNull().flatten()
        val parts = clean(pieces(credit).flatMap { piece ->
            val named = hits.any { hit ->
                val theirs = pieces(hit.name)
                theirs.size > 1 && theirs.any { it.equals(piece, ignoreCase = true) }
            }
            if (named) listOf(piece) else piece.split(COMMA)
        })
        if (parts.size < 2) return null

        val whole = split(credit).map { it.lowercase() }.toSet()
        var known = false
        val artists = parts.map { part ->
            val own = hits.firstOrNull { it.name?.trim().equals(part, ignoreCase = true) }
            val elsewhere = hits.any { hit ->
                split(hit.name).size > 1 &&
                    (pieces(hit.name) + split(hit.name)).any { it.equals(part, ignoreCase = true) } &&
                    split(hit.name).map { it.lowercase() }.toSet() != whole
            }
            if (own != null || elsewhere) known = true
            Artist().apply {
                id = own?.id
                name = own?.name ?: part
                coverArtId = own?.coverArtId
                albumCount = own?.albumCount
            }
        }
        return if (known) artists else null
    }
}
