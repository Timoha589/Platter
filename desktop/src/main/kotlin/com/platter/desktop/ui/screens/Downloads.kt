package com.platter.desktop.ui.screens

import com.platter.desktop.i18n.t
import com.platter.desktop.i18n.tn
import androidx.compose.foundation.background
import com.platter.desktop.ui.PlatterSpacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.platter.desktop.AppController
import com.platter.desktop.enumChoice
import com.platter.desktop.api.Song
import com.platter.desktop.offline.DownloadedSong
import com.platter.desktop.offline.Source
import com.platter.desktop.offline.Transfer
import com.platter.desktop.ui.Chip
import com.platter.desktop.ui.Cover
import com.platter.desktop.ui.MenuEntry
import com.platter.desktop.ui.MoreMenu
import com.platter.desktop.ui.OutlinedPill
import com.platter.desktop.ui.PlatterColors
import com.platter.desktop.ui.PlatterShapes
import com.platter.desktop.ui.PlayCircle
import com.platter.desktop.ui.TrackRow
import com.platter.desktop.ui.formatBytes
import java.awt.Desktop

private enum class Filter(val label: String, val matches: (String) -> Boolean) {
    All("All", { true }),
    Mine("Downloaded by me", { it == Source.MANUAL }),
    Liked("Kept for likes", { it == Source.LIKED }),
    Smart("Saved while listening", { it == Source.SMART }),
}

/**
 * What is saved for offline play, grouped by album: it plays with no server, and this screen needs none either -
 * it is built from the index on the disk, tags, covers and all.
 */
@Composable
fun DownloadsScreen(app: AppController) {
    val player by app.player.state.collectAsState()
    val filter = app.enumChoice("downloads.filter", Filter.All)
    val all = app.downloadedSongs()
    val shown = all.filter { filter.matches(it.source) }
        .sortedWith(compareBy({ (it.displayArtist ?: it.artist).orEmpty().lowercase() }, { it.album.orEmpty().lowercase() }, { it.discNumber ?: 1 }, { it.track ?: 0 }))
    val songs = shown.map { it.toSong() }
    val transfers = app.downloader.transfers.entries.toList()

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 24.dp)) {
                Text(t("Downloads"), style = MaterialTheme.typography.headlineMedium)
                Text(
                    listOfNotNull(tn(all.size, "%d song", "%d songs"), formatBytes(app.downloadedBytes).takeIf { all.isNotEmpty() }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PlatterColors.Mist,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PlayCircle { app.player.play(songs) }
                OutlinedPill(t("Shuffle")) { app.player.play(songs.shuffled()) }
                OutlinedPill(t("Open folder")) { openFolder(app) }
            }
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Filter.entries.forEach { Chip(t(it.label), it == filter) { app.choose("downloads.filter", it.name) } }
            }
        }

        if (transfers.isNotEmpty()) {
            item {
                Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t("In progress"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (transfers.any { it.value is Transfer.Failed }) Chip(t("Retry failed"), false) { app.retryFailedDownloads() }
                    Chip(t("Cancel all"), false) { app.downloader.cancelAll() }
                }
            }
            items(transfers, key = { it.key }) { (id, transfer) ->
                TransferRow(app, id, transfer)
            }
        }

        if (all.isEmpty() && transfers.isEmpty()) {
            item {
                Text(
                    t("Nothing is saved yet. Use “Download” in the ⋯ menu of a song, an album or a playlist, or switch on saving as you listen in Settings."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PlatterColors.Mist,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else if (shown.isEmpty()) {
            item { Text(t("Nothing here."), style = MaterialTheme.typography.bodyMedium, color = PlatterColors.Mist, modifier = Modifier.padding(24.dp)) }
        }

        item { Spacer(Modifier.height(12.dp)) }

        // An album with several songs gets a header and its songs numbered 1..n; a song alone is one row with its cover.
        // Only some of an album is usually saved, so its own track numbers (3, 14) would read as a bug.
        shown.indices.groupBy { "${shown[it].displayArtist ?: shown[it].artist}\u0000${shown[it].album}" }.forEach { (albumKey, indices) ->
            if (indices.size > 1) item(key = "album:$albumKey") { AlbumHeader(app, indices.map { shown[it] }) }
            indices.forEachIndexed { n, i ->
                val record = shown[i]
                item(key = "song:${record.id}") {
                    Column(Modifier.padding(horizontal = PlatterSpacing.Bleed)) {
                        TrackRow(
                            app, songs[i], (n + 1).toString(),
                            isCurrent = record.id == player.current?.id,
                            onPlay = { app.player.play(songs, i) },
                            detail = if (indices.size == 1) listOfNotNull(songs[i].artistLine(), record.album).joinToString(" · ") else null,
                            coverArtId = record.coverArtId.takeIf { indices.size == 1 },
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun AlbumHeader(app: AppController, group: List<DownloadedSong>) {
    val first = group.first()
    val songs = group.sortedWith(compareBy({ it.discNumber ?: 1 }, { it.track ?: 0 })).map { it.toSong() }
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(app, first.coverArtId, 56.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(first.album ?: t("Unknown album"), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(first.displayArtist ?: first.artist, tn(group.size, "%d song", "%d songs"), formatBytes(group.sumOf { it.bytes })).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = PlatterColors.Mist,
            )
        }
        MoreMenu { dismiss ->
            MenuEntry(t("Play album"), dismiss) { app.player.play(songs) }
            MenuEntry(t("Remove from downloads"), dismiss) { app.removeDownloads(songs) }
        }
    }
}

@Composable
private fun TransferRow(app: AppController, id: String, transfer: Transfer) {
    val song: Song? = app.downloader.songFor(id)
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(song?.title ?: id, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (transfer) {
                Transfer.Queued -> Text(t("Waiting"), style = MaterialTheme.typography.bodySmall, color = PlatterColors.Mist)
                is Transfer.Active -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Row(Modifier.weight(1f).height(4.dp).clip(PlatterShapes.Pill).background(PlatterColors.Steel)) {
                        // A transcode has no length to measure against: a bar a third full says "working" without lying.
                        val fraction = if (transfer.fraction >= 0) transfer.fraction else 0.33f
                        Row(Modifier.weight(fraction.coerceIn(0.01f, 1f)).height(4.dp).background(PlatterColors.White)) {}
                        if (fraction < 1f) Spacer(Modifier.weight((1f - fraction).coerceAtLeast(0.001f)))
                    }
                    Text(
                        if (transfer.fraction >= 0) "${(transfer.fraction * 100).toInt()}%" else "…",
                        style = MaterialTheme.typography.labelSmall,
                        color = PlatterColors.Mist,
                        modifier = Modifier.padding(start = 8.dp).width(36.dp),
                    )
                }
                is Transfer.Failed -> Text(transfer.message, style = MaterialTheme.typography.bodySmall, color = PlatterColors.Liked, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        Chip(t("Cancel"), false) { app.downloader.cancel(id) }
    }
}

private fun openFolder(app: AppController) {
    val folder = app.downloadRoot().toFile()
    runCatching {
        folder.mkdirs()
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(folder)
    }
}
