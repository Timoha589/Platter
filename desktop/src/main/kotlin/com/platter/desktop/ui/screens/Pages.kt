package com.platter.desktop.ui.screens

import com.platter.desktop.ui.rememberPageListState
import com.platter.desktop.ui.rememberPageGridState
import com.platter.desktop.i18n.t
import com.platter.desktop.i18n.tn
import androidx.compose.foundation.clickable
import com.platter.desktop.ui.cards
import com.platter.desktop.ui.PlatterSpacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.platter.desktop.AppController
import com.platter.desktop.AppDialog
import com.platter.desktop.Screen
import com.platter.desktop.api.Album
import com.platter.desktop.api.Song
import com.platter.desktop.ui.AlbumCard
import com.platter.desktop.ui.ArtistCard
import com.platter.desktop.ui.Chip
import com.platter.desktop.ui.Cover
import com.platter.desktop.ui.Heart
import com.platter.desktop.ui.Load
import com.platter.desktop.ui.LoadView
import com.platter.desktop.ui.MenuEntry
import com.platter.desktop.ui.MoreMenu
import com.platter.desktop.ui.OutlinedPill
import com.platter.desktop.ui.PlatterColors
import com.platter.desktop.ui.PlayCircle
import com.platter.desktop.ui.PlaylistSlot
import com.platter.desktop.ui.Rail
import com.platter.desktop.ui.SectionHeader
import com.platter.desktop.ui.TrackRow
import com.platter.desktop.ui.formatTotal
import com.platter.desktop.ui.rememberLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.awt.Desktop
import java.net.URI

@Composable
private fun PageHeader(
    app: AppController,
    coverArtId: String?,
    kind: String,
    title: String,
    subtitle: @Composable () -> Unit,
    songs: List<Song>,
    like: (@Composable () -> Unit)? = null,
    menu: (@Composable ColumnScope.(dismiss: () -> Unit) -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.Bottom) {
        Cover(app, coverArtId, 200.dp)
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(kind, style = MaterialTheme.typography.labelMedium)
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 8.dp))
            subtitle()
        }
    }
    Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PlayCircle { app.player.play(songs) }
        OutlinedPill(t("Shuffle")) { app.player.play(songs.shuffled()) }
        like?.invoke()
        if (menu != null) MoreMenu(menu)
    }
}

private fun LazyListScope.trackItems(
    app: AppController,
    songs: List<Song>,
    currentId: String?,
    labelFor: (Int, Song) -> String,
) {
    itemsIndexed(songs) { i, song ->
        Column(Modifier.padding(horizontal = PlatterSpacing.Bleed)) {
            TrackRow(
                app, song, labelFor(i, song),
                isCurrent = song.id != null && song.id == currentId,
                onPlay = { app.player.play(songs, i) },
            )
        }
    }
}

@Composable
fun AlbumPage(app: AppController, id: String) {
    val client = app.client ?: return
    val loader = rememberLoader(client to id) { client.album(id) }
    val player by app.player.state.collectAsState()

    LoadView(loader) { album ->
        val songs = album.songs.orEmpty()
        LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState()) {
            item {
                PageHeader(
                    app, album.coverArtId, t("Album"), album.name.orEmpty(),
                    subtitle = {
                        val artistId = album.artistId
                        Row {
                            Text(
                                album.artistLine().orEmpty(),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = if (artistId != null) Modifier.clickable { app.navigate(Screen.Artist(artistId)) } else Modifier,
                            )
                            Text(
                                listOfNotNull(
                                    album.year?.takeIf { it > 0 }?.toString(),
                                    tn(songs.size, "%d song", "%d songs"),
                                    album.duration?.takeIf { it > 0 }?.let(::formatTotal),
                                ).joinToString(" · ", prefix = " · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = PlatterColors.Mist,
                            )
                        }
                    },
                    songs = songs,
                    like = { Heart(app.isLiked(album.id, album.starred), { app.toggleLikeAlbum(id, album.starred) }) },
                    menu = { dismiss ->
                        MenuEntry(t("Play next"), dismiss) { app.playNext(songs) }
                        MenuEntry(t("Add to queue"), dismiss) { app.addToQueue(songs) }
                        MenuEntry(t("Add to playlist…"), dismiss) { app.dialog = AppDialog.AddToPlaylist(songs) }
                        if (songs.any { !app.isDownloaded(it.id) }) MenuEntry(t("Download album"), dismiss) { app.download(songs) }
                        if (songs.any { app.isDownloaded(it.id) }) MenuEntry(t("Remove downloads"), dismiss) { app.removeDownloads(songs) }
                        MenuEntry(t("Start radio"), dismiss) { app.startRadioFor(id, "album") }
                        MenuEntry(t("Share"), dismiss) { app.share(id) }
                    },
                )
            }
            trackItems(app, songs, player.current?.id) { i, song -> (song.track ?: (i + 1)).toString() }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** What the artist page needs from the server beyond the artist: the best-known songs, and the agent's notes. */
private class ArtistExtras(val top: List<Song>, val bio: String?, val lastFm: String?, val similar: List<com.platter.desktop.api.Artist>)

@Composable
fun ArtistPage(app: AppController, id: String) {
    val client = app.client ?: return
    val loader = rememberLoader(client to id) { client.artist(id) }
    val player by app.player.state.collectAsState()

    LoadView(loader) { artist ->
        // Albums newest first; the rest of the page arrives on its own, and a server without agents just has less.
        val albums = artist.albums.orEmpty().sortedByDescending { it.year ?: 0 }
        val extras = rememberLoader(client to id) {
            coroutineScope {
                val top = async { runCatching { client.topSongs(artist.name.orEmpty(), 10) }.getOrDefault(emptyList()) }
                val info = async { runCatching { client.artistInfo(id) }.getOrNull() }
                val infoValue = info.await()
                ArtistExtras(top.await(), cleanBiography(infoValue?.biography), infoValue?.lastFmUrl, infoValue?.similarArtists.orEmpty())
            }
        }.state.let { (it as? Load.Ready)?.value }
        var showAllTop by remember(id) { mutableStateOf(false) }
        var bioOpen by remember(id) { mutableStateOf(false) }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(PlatterSpacing.CardCell),
            modifier = Modifier.fillMaxSize(),
            state = rememberPageGridState(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.Bottom) {
                        // An artist is a circle; an album a rounded square.
                        Cover(app, artist.coverArtId, 192.dp, CircleShape)
                        Spacer(Modifier.width(24.dp))
                        Column {
                            Text(t("Artist"), style = MaterialTheme.typography.labelMedium)
                            Text(artist.name.orEmpty(), style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 8.dp))
                            Text(tn(albums.size, "%d album", "%d albums"), style = MaterialTheme.typography.bodyMedium, color = PlatterColors.Mist)
                        }
                    }
                    Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PlayCircle { app.playArtist(artist.name.orEmpty(), albums) }
                        OutlinedPill(t("Shuffle")) { app.playArtist(artist.name.orEmpty(), albums, shuffle = true) }
                        OutlinedPill(t("Radio")) { app.startRadioFor(id, "artist") }
                        Heart(app.isArtistLiked(id, artist.starred), { app.toggleLikeArtist(id, artist.starred) })
                    }
                }
            }

            val top = extras?.top.orEmpty()
            if (top.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        SectionHeader(t("Popular"), Modifier.padding(horizontal = PlatterSpacing.Bleed))
                        val shown = if (showAllTop) top else top.take(TOP_AT_FIRST)
                        shown.forEachIndexed { i, song ->
                            TrackRow(app, song, "${i + 1}", isCurrent = song.id != null && song.id == player.current?.id, onPlay = { app.player.play(top, i) }, coverArtId = song.coverArtId)
                        }
                        if (top.size > TOP_AT_FIRST) {
                            Row(Modifier.padding(top = 8.dp, start = 12.dp)) { Chip(if (showAllTop) t("Show fewer") else t("Show more"), false) { showAllTop = !showAllTop } }
                        }
                    }
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(t("Albums"), Modifier.padding(horizontal = PlatterSpacing.Gutter)) }
            cards(albums) { AlbumCard(app, it) }

            val similar = extras?.similar.orEmpty()
            if (similar.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Rail(t("Fans also like"), similar) { ArtistCard(app, it) } }
            }

            val bio = extras?.bio
            if (!bio.isNullOrBlank()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        SectionHeader(t("About"), Modifier.padding(horizontal = PlatterSpacing.Bleed))
                        Text(
                            bio,
                            style = MaterialTheme.typography.bodyMedium,
                            color = PlatterColors.Mist,
                            maxLines = if (bioOpen) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = PlatterSpacing.Bleed).fillMaxWidth(0.8f),
                        )
                        Row(Modifier.padding(top = 12.dp, start = PlatterSpacing.Bleed), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip(if (bioOpen) t("Show less") else t("Show more"), false) { bioOpen = !bioOpen }
                            extras.lastFm?.let { url -> Chip("Last.fm", false) { openInBrowser(url) } }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private const val TOP_AT_FIRST = 5

/** Last.fm's biography comes with HTML in it, and ends with a "Read more" link of its own. */
internal fun cleanBiography(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val text = raw
        .replace(Regex("<a [^>]*>.*?</a>", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n\\s*\\n+"), "\n\n")
        .trim()
    return text.ifEmpty { null }
}

internal fun openInBrowser(url: String) {
    runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url)) }
}
