package com.cappielloantonio.tempo.model

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * One name from the library, kept locally so a misspelling can be measured
 * against it.
 *
 * The server matches substrings and nothing else, so it can never answer
 * "Мельнеца" - the query and the tag share no substring long enough to matter.
 * Measuring an edit distance needs the candidate names in hand, which means
 * keeping a copy of them. Only the names: this is a spelling reference, not a
 * cache of the library, and a match here is used to re-ask the server rather
 * than to render anything.
 *
 * `normalized` is the folded form the comparison actually runs against, stored
 * rather than recomputed because it is recomputed for every name on every
 * keystroke otherwise.
 */
@Keep
@Entity(tableName = "catalogue_name", primaryKeys = ["kind", "name"])
data class CatalogueName(
    @ColumnInfo(name = "kind")
    var kind: Int,

    @ColumnInfo(name = "name")
    var name: String,

    @ColumnInfo(name = "normalized")
    var normalized: String
)
