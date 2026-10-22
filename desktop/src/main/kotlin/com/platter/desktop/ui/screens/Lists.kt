package com.platter.desktop.ui.screens

import com.platter.desktop.ui.rememberPageListState
import com.platter.desktop.ui.rememberPageGridState
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import com.platter.desktop.ui.Cover
import com.platter.desktop.ui.TRACK_TITLE
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
import com.platter.desktop.api.Song
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
 * A long list as it arrives, a page at a time; [more] asks for the next page unless one is on its way or all are in.
 * [first] is a page already fetched, if any. An item already shown is not shown again: a history can move on between
 * two pages, pushing a song from the end of one onto the start of the next.
 */
private class Pages<T>(first: List<T>?, private val identity: (T) -> Any?) {
    var items by mutableStateOf(first.orEmpty())
    var finished by mutableStateOf(first != null && first.size < PAGE)
    var failure by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var attempt by mutableStateOf(0)

    suspend fun more(fetch: suspend (offset: Int, size: Int) -> List<T>) {
        if (loading || finished) return
        loading = true
        failure = null
        try {
            val batch = fetch(items.size, PAGE)
            val seen = items.mapTo(HashSet(), identity)
            items = items + batch.filter { identity(it) !in seen }
            if (batch.size < PAGE) finished = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
        } finally {
            loading = false
        }
    }
}

/**
 * Loads [Pages] for [key]: the first page at once (unless [first] is it), the next whenever the last item shown, from
 * [lastShown], comes near the end - so a library of thousands opens as fast as one of dozens.
 */
@Composable
private fun <T> rememberPages(
    key: Any,
    lastShown: () -> Int,
    first: List<T>? = null,
    identity: (T) -> Any? = { it },
    fetch: suspend (offset: Int, size: Int) -> List<T>,
): Pages<T> {
    val pages = remember(key) { Pages(first, identity) }
    LaunchedEffect(key, pages.attempt) { if (pages.items.isEmpty()) pages.more(fetch) }
    LaunchedEffect(key, pages.attempt) {
        snapshotFlow(lastShown).collect { last ->
            if (pages.items.isNotEmpty() && last >= pages.items.size - 12) pages.more(fetch)
        }
    }
    return pages
}

/** Under the list: the spinner while a page is on its way, or what went wrong with a way to try again. */
@Composable
private fun PagesFooter(pages: Pages<*>) {
    Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
        when {
            pages.failure != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(t("Could not load this"), style = MaterialTheme.typography.titleSmall)
                Text(pages.failure.orEmpty(), style = MaterialTheme.typography.bodySmall, color = PlatterColors.Mist)
                Spacer(Modifier.height(8.dp))
                OutlinedPill(t("Try again")) { pages.attempt++ }
            }
            pages.loading -> CircularProgressIndicator(color = PlatterColors.Green, modifier = Modifier.padding(8.dp), strokeWidth = 3.dp)
            pages.items.isEmpty() && pages.finished -> Text(t("Nothing here yet"), style = MaterialTheme.typography.bodyMedium, color = PlatterColors.Mist)
        }
    }
}

/** Albums of one kind as a grid of cards, a page at a time. [header] sits above the grid and scrolls with it. */
@Composable
private fun PagedAlbumGrid(
    app: AppController,
    key: Any,
    header: @Composable () -> Unit,
    fetch: suspend (offset: Int, size: Int) -> List<Album>,
) {
    val grid = rememberPageGridState()
    val pages = rememberPages(key, { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }, identity = { it.id }, fetch = fetch)

    LazyVerticalGrid(
        columns = GridCells.Adaptive(PlatterSpacing.CardCell),
        state = grid,
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { header() }
        cards(pages.items) { AlbumCard(app, it) }
        item(span = { GridItemSpan(maxLineSpan) }) { PagesFooter(pages) }
    }
}

/** Albums of one kind as rows, one under the other, a page at a time - for a list read in order, like what was played last. */
@Composable
private fun PagedAlbumList(
    app: AppController,
    key: Any,
    header: @Composable () -> Unit,
    fetch: suspend (offset: Int, size: Int) -> List<Album>,
) {
    val list = rememberPageListState()
    val pages = rememberPages(key, { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }, identity = { it.id }, fetch = fetch)

    LazyColumn(Modifier.fillMaxSize(), state = list) {
        item { header() }
        items(pages.items) { album ->
            Box(Modifier.padding(horizontal = PlatterSpacing.Bleed)) { AlbumRow(app, album) }
        }
        item { PagesFooter(pages) }
    }
}

/**
 * The songs played last, newest first, as a table like the liked songs': play or shuffle what is loaded, and more
 * comes as the end nears. [first] is the page that showed there is a history to read.
 */
@Composable
private fun RecentSongsList(app: AppController, key: Any, title: String, first: List<Song>) {
    val list = rememberPageListState()
    val player by app.player.state.collectAsState()
    val pages = rememberPages(key, { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }, first, identity = { it.id }) { offset, size ->
        // Null now means the login has gone since the first page: nothing more to read.
        app.recentlyPlayedSongs(offset, size).orEmpty()
    }
    val songs = pages.items

    BoxWithConstraints(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            item {
                Title(title)
                Row(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PlayCircle { app.player.play(songs) }
                    OutlinedPill(t("Shuffle")) { app.player.play(songs.shuffled()) }
                }
            }
            trackTable(app, songs, player.current?.id, showAlbum = maxWidth >= TABLE_ALBUM_MIN_WIDTH)
            item { PagesFooter(pages) }
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

/**
 * A rail's "See all": every album of one kind. What was played last is a history, read top to bottom, so it is a list -
 * of songs where the server keeps them (Navidrome), else of albums, which is all Subsonic knows. The rest are browsed
 * by their covers.
 */
@Composable
fun AlbumListPage(app: AppController, list: Screen.AlbumList) {
    val client = app.client ?: return
    val fetch: suspend (Int, Int) -> List<Album> = { offset, size ->
        client.albumList(list.type, size, offset, fromYear = list.fromYear, toYear = list.toYear)
    }
    if (list.type != "recent") {
        PagedAlbumGrid(app, key = client to list, header = { Title(t(list.title)) }, fetch = fetch)
        return
    }
    val history = rememberLoader(client to list) { app.recentlyPlayedSongs(0, PAGE) }
    LoadView(history) { songs ->
        if (songs != null) RecentSongsList(app, client to list, t(list.title), songs)
        else PagedAlbumList(app, key = client to list, header = { Title(t(list.title)) }, fetch = fetch)
    }
}

/**
 * An album as a row, built like a song's: its cover, its name over its artist and year, and how long it is. A press
 * opens it.
 */
@Composable
private fun AlbumRow(app: AppController, album: Album) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(PlatterSpacing.TrackRowHeight)
            .clip(PlatterShapes.Card)
            .background(if (hovered) PlatterColors.Smoke else Color.Transparent)
            .hoverable(hover)
            .clickable { album.id?.let { app.navigate(Screen.Album(it)) } }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(app, album.coverArtId, PlatterSpacing.TrackCover, PlatterShapes.Card)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(album.name.orEmpty(), style = TRACK_TITLE, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(album.artistLine(), album.year?.takeIf { it > 0 }?.toString()).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = PlatterColors.Mist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            listOfNotNull(album.songCount?.let { tn(it, "%d song", "%d songs") }, album.duration?.takeIf { it >= 60 }?.let(::formatTotal)).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = PlatterColors.Mist,
            maxLines = 1,
        )
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
        LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState()) {
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
        LazyVerticalGrid(columns = GridCells.Adaptive(PlatterSpacing.CardCell), modifier = Modifier.fillMaxSize(), state = rememberPageGridState()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Title(t("Liked artists")) }
            cards(artists) { ArtistCard(app, it) }
        }
    }
}

/** Spotify's own purple for the liked songs: they have no cover to take a colour from. */
private val LikedTint = Color(0xFF3F3590)

