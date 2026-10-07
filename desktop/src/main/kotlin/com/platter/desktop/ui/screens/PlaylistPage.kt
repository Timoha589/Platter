package com.platter.desktop.ui.screens

import com.platter.desktop.ui.rememberPageListState
import com.platter.desktop.i18n.t
import com.platter.desktop.i18n.tn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.platter.desktop.AppController
import com.platter.desktop.AppDialog
import com.platter.desktop.api.Playlist
import com.platter.desktop.api.Song
import com.platter.desktop.ui.Cover
import com.platter.desktop.ui.Inter
import com.platter.desktop.ui.LoadView
import com.platter.desktop.ui.MenuEntry
import com.platter.desktop.ui.MoreMenu
import com.platter.desktop.ui.PlatterColors
import com.platter.desktop.ui.PlatterShapes
import com.platter.desktop.ui.PlatterSpacing
import com.platter.desktop.ui.PlayCircle
import com.platter.desktop.ui.TABLE_ALBUM_MIN_WIDTH
import com.platter.desktop.ui.formatTotal
import com.platter.desktop.ui.rememberCoverTint
import com.platter.desktop.ui.rememberLoader
import com.platter.desktop.ui.trackTable

/**
 * A playlist as Spotify draws it: a header in the colour of its cover with the name large, the play and shuffle
 * and download buttons under it, and the songs as a table.
 */
@Composable
fun PlaylistPage(app: AppController, id: String) {
    val client = app.client ?: return
    // A change made anywhere - a song added, one taken out, a rename - bumps the version and the page loads again.
    val loader = rememberLoader(Triple(client, id, app.playlistVersion)) { client.playlist(id) }
    val player by app.player.state.collectAsState()

    LoadView(loader) { playlist ->
        val songs = playlist.entries.orEmpty()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val showAlbum = maxWidth >= TABLE_ALBUM_MIN_WIDTH
            LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState()) {
                item { PlaylistHeader(app, id, playlist, songs) }
                if (songs.isEmpty()) {
                    item {
                        Text(
                            t("This playlist is empty. Add songs from the ⋯ menu of any track."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PlatterColors.Mist,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
                trackTable(app, songs, player.current?.id, showAlbum, playlistId = id)
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/**
 * The name is the one thing on the app's pages larger than a heading (DESIGN.md stops those at 24): it is the
 * page's picture. A long name steps down so it keeps to two lines.
 */
internal fun titleStyle(name: String): TextStyle {
    val size = when {
        name.length <= 12 -> 72
        name.length <= 22 -> 56
        name.length <= 36 -> 40
        else -> 28
    }
    return TextStyle(fontFamily = Inter, fontSize = size.sp, fontWeight = FontWeight.Bold, lineHeight = (size * 1.1f).sp)
}

@Composable
private fun PlaylistHeader(app: AppController, id: String, playlist: Playlist, songs: List<Song>) {
    val tint = rememberCoverTint(app, playlist.coverArtId) ?: PlatterColors.Steel
    val pinned = id in app.pinned
    TracksHeader(
        app, tint, t("Playlist"), playlist.name.orEmpty(), playlist.comment, songs,
        cover = { Cover(app, playlist.coverArtId, 232.dp) },
        meta = {
            playlist.owner?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.titleSmall)
                Text(" · ", style = MaterialTheme.typography.bodyMedium, color = PlatterColors.Bone)
            }
            Text(
                listOfNotNull(tn(songs.size, "%d song", "%d songs"), playlist.duration?.takeIf { it > 0 }?.let(::formatTotal)).joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
                color = PlatterColors.Bone,
            )
        },
    ) { dismiss ->
        MenuEntry(t("Play next"), dismiss) { app.playNext(songs) }
        MenuEntry(t("Add to queue"), dismiss) { app.addToQueue(songs) }
        if (songs.any { !app.isDownloaded(it.id) }) MenuEntry(t("Download playlist"), dismiss) { app.download(songs) }
        if (songs.any { app.isDownloaded(it.id) }) MenuEntry(t("Remove downloads"), dismiss) { app.removeDownloads(songs) }
        MenuEntry(if (pinned) t("Unpin from home") else t("Pin to home"), dismiss) { app.togglePin(id) }
        MenuEntry(t("Rename…"), dismiss) { app.dialog = AppDialog.RenamePlaylist(playlist) }
        MenuEntry(t("Share"), dismiss) { app.share(id) }
        MenuEntry(t("Delete playlist…"), dismiss) { app.dialog = AppDialog.DeletePlaylist(playlist) }
    }
}

/**
 * The top of a page of songs: the tint behind it, the picture, the name, and under them the buttons. A playlist
 * and the liked songs are two pages with one header; [tint] is the colour of what is on the picture.
 */
@Composable
internal fun TracksHeader(
    app: AppController,
    tint: Color,
    kind: String,
    name: String,
    description: String?,
    songs: List<Song>,
    cover: @Composable () -> Unit,
    meta: @Composable RowScope.() -> Unit,
    menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    // The colour is strongest behind the name and gives way to the page's own by the time the songs begin.
    Column(
        Modifier.fillMaxWidth().background(
            Brush.verticalGradient(listOf(tint, lerp(tint, PlatterColors.Carbon, 0.6f), PlatterColors.Carbon)),
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = PlatterSpacing.Gutter, end = PlatterSpacing.Gutter, top = 48.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.shadow(24.dp, PlatterShapes.Card, ambientColor = Color.Black, spotColor = Color.Black)) { cover() }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(kind, style = MaterialTheme.typography.labelMedium)
                Text(name, style = titleStyle(name), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 8.dp))
                description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = PlatterColors.Bone, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, content = meta)
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = PlatterSpacing.Gutter, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            PlayCircle(56.dp) { app.player.play(songs) }
            Icon(
                Icons.Outlined.Shuffle, t("Shuffle"), tint = PlatterColors.Bone,
                modifier = Modifier.size(32.dp).clip(CircleShape).clickable { app.player.play(songs.shuffled()) }.padding(2.dp),
            )
            // Green once everything is on this computer; pressing it then lets it go again.
            val saved = songs.isNotEmpty() && songs.all { app.isDownloaded(it.id) }
            Icon(
                if (saved) Icons.Filled.ArrowCircleDown else Icons.Outlined.ArrowCircleDown,
                if (saved) t("Remove downloads") else t("Download"),
                tint = if (saved) PlatterColors.Green else PlatterColors.Bone,
                modifier = Modifier.size(32.dp).clip(CircleShape).clickable { if (saved) app.removeDownloads(songs) else app.download(songs) }.padding(2.dp),
            )
            MoreMenu(menu)
        }
    }
}
