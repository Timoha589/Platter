package com.platter.desktop.ui

import com.platter.desktop.i18n.t
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.platter.desktop.AppController
import com.platter.desktop.api.Song

/** How much wider than the album column the title column is, in the header and in every row alike. */
const val TABLE_TITLE_WEIGHT = 1.5f

/** Below this the album column is left out: there is no room for it beside the titles. */
val TABLE_ALBUM_MIN_WIDTH = 720.dp

/**
 * What stands right of the title column in a row: the heart (20), its gap (8), the mark for a saved song (16),
 * a gap (8), the dots (20), a gap (8) and the length (44). The header's clock sits over the length.
 */
private val TRAILING = 124.dp

/** The line above a table of songs: #, Title, Album and a clock for the length. */
@Composable
fun TrackTableHeader(showAlbum: Boolean) {
    val style = MaterialTheme.typography.labelMedium
    Column(Modifier.fillMaxWidth().background(PlatterColors.Carbon).padding(horizontal = PlatterSpacing.Gutter)) {
        Row(Modifier.height(36.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(32.dp)) { Text("#", style = style, color = PlatterColors.Mist) }
            // The cover and its gap (12) come before a title.
            Spacer(Modifier.width(PlatterSpacing.TrackCover + 12.dp))
            Text(t("Title"), style = style, color = PlatterColors.Mist, modifier = Modifier.weight(if (showAlbum) TABLE_TITLE_WEIGHT else 1f))
            if (showAlbum) {
                Spacer(Modifier.width(16.dp))
                Text(t("Album"), style = style, color = PlatterColors.Mist, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
            }
            Box(Modifier.width(TRAILING), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Outlined.Schedule, t("Length"), tint = PlatterColors.Mist, modifier = Modifier.size(16.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(PlatterColors.Iron))
    }
}

/**
 * Songs as a table, as a playlist or the liked songs show them: a header that stays at the top while the rows
 * scroll under it, and rows with a number, a cover and, where there is room, the album.
 */
@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.trackTable(
    app: AppController,
    songs: List<Song>,
    currentId: String?,
    showAlbum: Boolean,
    playlistId: String? = null,
) {
    if (songs.isEmpty()) return
    stickyHeader { TrackTableHeader(showAlbum) }
    item { Spacer(Modifier.height(8.dp)) }
    itemsIndexed(songs) { i, song ->
        Column(Modifier.padding(horizontal = PlatterSpacing.Bleed)) {
            TrackRow(
                app, song, "${i + 1}",
                isCurrent = song.id != null && song.id == currentId,
                onPlay = { app.player.play(songs, i) },
                inPlaylist = playlistId?.let { PlaylistSlot(it, i, songs) },
                coverArtId = song.coverArtId,
                showNumber = true,
                album = if (showAlbum) song.album.orEmpty() else null,
            )
        }
    }
}
