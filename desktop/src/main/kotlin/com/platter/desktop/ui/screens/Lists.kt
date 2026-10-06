package com.platter.desktop.ui.screens

import com.platter.desktop.i18n.t
import com.platter.desktop.i18n.tn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.platter.desktop.ui.PlatterShapes
import com.platter.desktop.ui.formatTotal
import com.platter.desktop.ui.cards
import com.platter.desktop.ui.PlatterSpacing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import com.platter.desktop.ui.TABLE_ALBUM_MIN_WIDTH
import com.platter.desktop.ui.trackTable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.platter.desktop.AppController
import com.platter.desktop.Screen
import com.platter.desktop.api.Album
import com.platter.desktop.ui.AlbumCard
import com.platter.desktop.ui.ArtistCard
import com.platter.desktop.ui.LoadView
import com.platter.desktop.ui.MenuEntry
import com.platter.desktop.ui.MoreMenu
import com.platter.desktop.ui.OutlinedPill
import com.platter.desktop.ui.LikedCover
import com.platter.desktop.ui.PlatterColors
import com.platter.desktop.ui.PlayCircle
import com.platter.desktop.ui.TrackRow
import com.platter.desktop.ui.rememberLoader
import kotlinx.coroutines.CancellationException

private const val PAGE = 60

/**
 * Albums of one kind, a page at a time: the next page is asked for when the end of the grid comes near, so a library
 * of thousands opens as fast as one of dozens. [header] sits above the grid and scrolls with it.
 */
@Composable
private fun PagedAlbumGrid(
    app: AppController,
    key: Any,
    header: @Composable () -> Unit,
    fetch: suspend (offset: Int, size: Int) -> List<Album>,
) {
    var albums by remember(key) { mutableStateOf<List<Album>>(emptyList()) }
    var finished by remember(key) { mutableStateOf(false) }
    var failure by remember(key) { mutableStateOf<String?>(null) }
    var loading by remember(key) { mutableStateOf(false) }
    var attempt by remember(key) { mutableStateOf(0) }
    val grid = rememberLazyGridState()

    suspend fun more() {
        if (loading || finished) return
        loading = true
        failure = null
        try {
            val batch = fetch(albums.size, PAGE)
            albums = albums + batch
            if (batch.size < PAGE) finished = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
        } finally {
            loading = false
        }
    }

    LaunchedEffect(key, attempt) { more() }
    LaunchedEffect(key, attempt) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (albums.isNotEmpty() && last >= albums.size - 12) more()
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(PlatterSpacing.CardCell),
        state = grid,
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { header() }
        cards(albums) { AlbumCard(app, it) }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                when {
                    failure != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(t("Could not load this"), style = MaterialTheme.typography.titleSmall)
                        Text(failure.orEmpty(), style = MaterialTheme.typography.bodySmall, color = PlatterColors.Mist)
                        Spacer(Modifier.height(8.dp))
                        OutlinedPill(t("Try again")) { attempt++ }
                    }
                    loading -> CircularProgressIndicator(color = PlatterColors.Green, modifier = Modifier.padding(8.dp), strokeWidth = 3.dp)
                    albums.isEmpty() && finished -> Text(t("Nothing here yet"), style = MaterialTheme.typography.bodyMedium, color = PlatterColors.Mist)
                }
            }
        }
    }
}

@Composable
private fun Title(text: String, subtitle: String? = null) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 24.dp)) {
        Text(text, style = MaterialTheme.typography.headlineMedium)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = PlatterColors.Mist, modifier = Modifier.padding(top = 4.dp)) }
    }
}

/** A rail's "See all": every album of one kind. */
@Composable
fun AlbumListPage(app: AppController, list: Screen.AlbumList) {
    val client = app.client ?: return
    PagedAlbumGrid(app, key = client to list, header = { Title(t(list.title)) }) { offset, size ->
        client.albumList(list.type, size, offset, fromYear = list.fromYear, toYear = list.toYear)
    }
}

/** A genre: its albums, and a button that plays a handful of its songs. */
@Composable
fun GenrePage(app: AppController, genre: String) {
    val client = app.client ?: return
    PagedAlbumGrid(
        app, key = client to genre,
        header = {
            Column {
                Title(genre, t("Genre"))
                Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PlayCircle { app.playGenre(genre, shuffle = false) }
                    OutlinedPill(t("Shuffle")) { app.playGenre(genre, shuffle = true) }
                }
                Spacer(Modifier.height(8.dp))
            }
        },
    ) { offset, size -> client.albumList("byGenre", size, offset, genre = genre) }
}

/** The songs the listener has liked, as a list, with the same play and shuffle as an album. */
@Composable
fun LikedPage(app: AppController) {
    val client = app.client ?: return
    val loader = rememberLoader(client) { app.liked().songs.orEmpty() }
    val player by app.player.state.collectAsState()

    LoadView(loader) { songs ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                TracksHeader(
                    app, LikedTint, t("Playlist"), t("Liked Songs"), null, songs,
                    cover = { LikedCover(232.dp) },
                    meta = {
                        Text(
                            listOfNotNull(tn(songs.size, "%d song", "%d songs"), songs.sumOf { it.duration ?: 0 }.takeIf { it > 0 }?.let(::formatTotal)).joinToString(", "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PlatterColors.Bone,
                        )
                    },
                ) { dismiss ->
                    if (songs.any { !app.isDownloaded(it.id) }) MenuEntry(t("Download all"), dismiss) { app.download(songs) }
                    if (songs.any { app.isDownloaded(it.id) }) MenuEntry(t("Remove downloads"), dismiss) { app.removeDownloads(songs) }
                }
            }
            if (songs.isEmpty()) {
                item { Text(t("Songs you like with the heart show up here."), style = MaterialTheme.typography.bodyMedium, color = PlatterColors.Mist, modifier = Modifier.padding(24.dp)) }
            }
            trackTable(app, songs, player.current?.id, showAlbum = maxWidth >= TABLE_ALBUM_MIN_WIDTH)
            item { Spacer(Modifier.height(24.dp)) }
        }
        }
    }
}

@Composable
fun LikedArtistsPage(app: AppController) {
    val client = app.client ?: return
    val loader = rememberLoader(client) { app.liked().artists.orEmpty() }
    LoadView(loader) { artists ->
        LazyVerticalGrid(columns = GridCells.Adaptive(PlatterSpacing.CardCell), modifier = Modifier.fillMaxSize()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Title(t("Liked artists")) }
            cards(artists) { ArtistCard(app, it) }
        }
    }
}

/** Spotify's own purple for the liked songs: they have no cover to take a colour from. */
private val LikedTint = Color(0xFF3F3590)

