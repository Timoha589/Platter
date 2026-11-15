package com.platter.search

import kotlin.math.abs

internal data class RecordingMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val duration: Int?,
    val track: Int?,
    val disc: Int?,
    val artistLine: String? = artist,
    val id: String? = null,
)

/** Matches catalogue entries to a unique recording in the signed-in library. */
internal class RecordingMatcher<T>(
    songs: List<T>,
    metadata: (T) -> RecordingMetadata,
    private val normalize: (String?) -> String,
    private val creditPieces: (String?) -> List<String>,
) {
    private val songsByTitle = songs.asSequence().map { song ->
        val tags = metadata(song)
        Entry(song, tags, title(tags.title))
    }.filter { !it.tags.id.isNullOrBlank() && it.title.base.isNotEmpty() }
        .groupBy { it.title.base }
    private val byConfidence = compareByDescending<Candidate<T>> { it.sameAlbum }
        .thenByDescending { it.sameTitle }
        .thenByDescending { it.wholeArtist }

    fun match(requested: RecordingMetadata): T? {
        val title = title(requested.title)
        val artist = key(requested.artist)
        if (title.base.isEmpty() || artist.isEmpty()) return null
        val album = key(requested.album)
        val duration = requested.duration?.takeIf { it > 0 }

        val candidates = songsByTitle[title.base].orEmpty().asSequence().mapNotNull { entry ->
            val tags = entry.tags
            val localTitle = entry.title
            if (title.featured.isNotEmpty() && localTitle.featured.isNotEmpty() &&
                title.featured != localTitle.featured) return@mapNotNull null

            val wholeArtist = artist == key(tags.artist) || artist == key(tags.artistLine)
            if (!wholeArtist && creditPieces(tags.artistLine).none { key(it) == artist }) return@mapNotNull null

            val localAlbum = key(tags.album)
            val sameAlbum = album.isNotEmpty() && album == localAlbum
            if (!sameAlbum && album.isNotEmpty() && localAlbum.isNotEmpty()) return@mapNotNull null

            val localDuration = tags.duration?.takeIf { it > 0 }
            val sameDuration = duration != null && localDuration != null &&
                abs(duration - localDuration) <= DURATION_TOLERANCE_SECONDS
            if (duration != null && localDuration != null && !sameDuration) return@mapNotNull null
            if (!sameAlbum && !sameDuration) return@mapNotNull null

            if (sameAlbum && (differentPosition(requested.track, tags.track) ||
                    differentPosition(requested.disc, tags.disc))) return@mapNotNull null

            Candidate(entry, sameAlbum, title.full == localTitle.full, wholeArtist)
        }.sortedWith(byConfidence)
            .distinctBy { it.entry.tags.id }
            .take(2)
            .toList()

        val best = candidates.firstOrNull() ?: return null
        val next = candidates.getOrNull(1)
        if (next != null && byConfidence.compare(best, next) == 0) return null
        return best.entry.song
    }

    private data class Entry<T>(val song: T, val tags: RecordingMetadata, val title: Title)
    private data class Candidate<T>(val entry: Entry<T>, val sameAlbum: Boolean, val sameTitle: Boolean, val wholeArtist: Boolean)
    private data class Title(val full: String, val base: String, val featured: Set<String>)

    private fun title(value: String?): Title {
        val full = key(value)
        // Preserve version markers and strip only explicit featured-artist credits.
        return Title(full, key(featuring.replace(full, " ")), featuring.findAll(full).map { key(it.groupValues[1]) }.toSet())
    }

    private fun key(value: String?): String = normalize(value).replace(whitespace, " ")

    private companion object {
        const val DURATION_TOLERANCE_SECONDS = 2
        val whitespace = Regex("\\s+")
        val featuring = Regex("\\s*[(\\[]\\s*(?:feat|ft|featuring)\\.?\\s+([^)\\]]+)[)\\]]")

        fun differentPosition(expected: Int?, actual: Int?): Boolean =
            expected != null && expected > 0 && actual != null && actual > 0 && expected != actual
    }
}
