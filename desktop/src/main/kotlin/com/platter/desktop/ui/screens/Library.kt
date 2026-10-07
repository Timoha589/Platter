@file:OptIn(ExperimentalFoundationApi::class)

package com.platter.desktop.ui.screens

import com.platter.desktop.ui.rememberPageGridState
import com.platter.desktop.i18n.t
import com.platter.desktop.i18n.tn
import androidx.compose.foundation.ExperimentalFoundationApi
import com.platter.desktop.ui.cards
import com.platter.desktop.ui.card
import com.platter.desktop.ui.PlatterSpacing
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.onClick
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.platter.desktop.AppController
import com.platter.desktop.AppDialog
import com.platter.desktop.Screen
import com.platter.desktop.enumChoice
import com.platter.desktop.api.Genre
import com.platter.desktop.api.Playlist
import com.platter.desktop.search.QueryVariants
import com.platter.desktop.ui.AlbumCard
import com.platter.desktop.ui.ArtistCard
import com.platter.desktop.ui.Chip
import com.platter.desktop.ui.Cover
import com.platter.desktop.ui.LoadView
import com.platter.desktop.ui.PlatterColors
import com.platter.desktop.ui.LikedCover
import com.platter.desktop.ui.PlatterMenu
import com.platter.desktop.ui.PlatterMenuItem
import com.platter.desktop.ui.hoverFill
import com.platter.desktop.ui.PlatterShapes
import com.platter.desktop.ui.TextInput
import com.platter.desktop.ui.rememberLoader

private enum class LibraryTab(val label: String, val filter: String) {
    Albums("Albums", "Filter albums"), Artists("Artists", "Filter artists"), Playlists("Playlists", "Filter playlists"),
    Genres("Genres", "Filter genres"),
}

private enum class AlbumSort(val label: String) { Name("Name"), Artist("Artist"), Year("Year"), Added("Recently added") }

private enum class ArtistSort(val label: String) { Name("Name"), Albums("Most albums") }

@Composable
fun LibraryScreen(app: AppController) {
    val client = app.client ?: return
    // The tab and the sortings are the listener's own and come back after a restart; the filter text is for the moment.
    val tab = app.enumChoice("library.tab", LibraryTab.Albums)
    var filter by remember { mutableStateOf("") }
    val albumSort = app.enumChoice("library.albumSort", AlbumSort.Name)
    val artistSort = app.enumChoice("library.artistSort", ArtistSort.Name)
    val wanted = QueryVariants.normalize(filter)
    // A filtered grid is not the one that comes back on return (the filter does not), so its place is kept apart.
    val slot = if (wanted.isEmpty()) tab.name else "${tab.name}?"

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = PlatterSpacing.Gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            LibraryTab.entries.forEach { Chip(t(it.label), it == tab) { app.choose("library.tab", it.name); filter = "" } }
            Spacer(Modifier.weight(1f))
            when (tab) {
                LibraryTab.Albums -> SortMenu(t(albumSort.label), AlbumSort.entries.map { t(it.label) }) { app.choose("library.albumSort", AlbumSort.entries[it].name) }
                LibraryTab.Artists -> SortMenu(t(artistSort.label), ArtistSort.entries.map { t(it.label) }) { app.choose("library.artistSort", ArtistSort.entries[it].name) }
                else -> {}
            }
            TextInput(filter, { filter = it }, t(tab.filter), Modifier.width(220.dp))
        }
        when (tab) {
            LibraryTab.Albums -> {
                val loader = rememberLoader(client) { app.catalogue() }
                LoadView(loader) { catalogue ->
                    val albums = remember(catalogue, wanted, albumSort) {
                        catalogue.albums
                            .filter { wanted.isEmpty() || QueryVariants.normalize(it.name).contains(wanted) || QueryVariants.normalize(it.artistLine()).contains(wanted) }
                            .let { list ->
                                when (albumSort) {
                                    AlbumSort.Name -> list
                                    AlbumSort.Artist -> list.sortedBy { QueryVariants.normalize(it.artistLine()) }
                                    AlbumSort.Year -> list.sortedByDescending { it.year ?: 0 }
                                    AlbumSort.Added -> list.sortedByDescending { it.created.orEmpty() }
                                }
                            }
                    }
                    LibraryGrid(slot) { cards(albums) { AlbumCard(app, it) } }
                }
            }
            LibraryTab.Artists -> {
                val loader = rememberLoader(client) { app.catalogue() }
                LoadView(loader) { catalogue ->
                    val artists = remember(catalogue, wanted, artistSort) {
                        catalogue.artists
                            .filter { wanted.isEmpty() || QueryVariants.normalize(it.name).contains(wanted) }
                            .let { list -> if (artistSort == ArtistSort.Albums) list.sortedByDescending { it.albumCount ?: 0 } else list }
                    }
                    LibraryGrid(slot) { cards(artists) { ArtistCard(app, it) } }
                }
            }
            LibraryTab.Playlists -> {
                val playlists = app.playlists.filter { wanted.isEmpty() || QueryVariants.normalize(it.name).contains(wanted) }
                LibraryGrid(slot) {
                    if (wanted.isEmpty()) {
                        card { TextTile(t("Liked Songs"), t("Playlist"), Icons.Filled.Favorite) { app.navigate(Screen.Liked) } }
                        card { TextTile(t("New playlist"), t("Start an empty one"), Icons.Outlined.Add) { app.dialog = AppDialog.NewPlaylist(emptyList()) } }
                    }
                    cards(playlists) { PlaylistCard(app, it) }
                }
            }
            LibraryTab.Genres -> {
                val loader = rememberLoader(client) { client.genres().filter { !it.value.isNullOrBlank() }.sortedByDescending { it.albumCount ?: 0 } }
                LoadView(loader) { genres ->
                    val shown = genres.filter { wanted.isEmpty() || QueryVariants.normalize(it.value).contains(wanted) }
                    LibraryGrid(slot) { cards(shown) { GenreCard(app, it) } }
                }
            }
        }
    }
}

/** "Sort: Name ▾", a pill that opens the choices. */
@Composable
private fun SortMenu(current: String, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.height(32.dp).clip(PlatterShapes.Pill).clickable { open = true }.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current, style = MaterialTheme.typography.labelLarge, color = PlatterColors.Mist)
            Icon(Icons.Filled.ArrowDropDown, t("Sort"), tint = PlatterColors.Mist, modifier = Modifier.size(24.dp))
        }
        PlatterMenu(open, { open = false }) {
            options.forEachIndexed { i, label -> PlatterMenuItem(label, onClick = { open = false; onPick(i) }) }
        }
    }
}

@Composable
private fun LibraryGrid(slot: String, content: LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(PlatterSpacing.CardCell),
        modifier = Modifier.fillMaxSize(),
        state = rememberPageGridState(slot),
        contentPadding = PaddingValues(vertical = 12.dp),
        content = content,
    )
}

@Composable
fun PlaylistCard(app: AppController, playlist: Playlist, width: Dp = 168.dp) {
    Column(
        Modifier.width(width).clip(PlatterShapes.Card).hoverFill().clickable { playlist.id?.let { app.navigate(Screen.Playlist(it)) } }.padding(12.dp),
    ) {
        Cover(app, playlist.coverArtId, width - 24.dp)
        Spacer(Modifier.height(12.dp))
        Text(playlist.name.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(tn(playlist.songCount ?: 0, "%d song", "%d songs"), style = MaterialTheme.typography.bodySmall, color = PlatterColors.Mist)
    }
}

/** A card with an icon where a cover would be: Liked Songs, a new playlist. */
@Composable
private fun TextTile(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val width = 168.dp
    Column(Modifier.width(width).clip(PlatterShapes.Card).hoverFill().clickable(onClick = onClick).padding(12.dp)) {
        if (icon == Icons.Filled.Favorite) LikedCover(width - 24.dp)
        else Box(Modifier.size(width - 24.dp).clip(PlatterShapes.Card).background(PlatterColors.Smoke), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = PlatterColors.Mist)
    }
}

@Composable
private fun GenreCard(app: AppController, genre: Genre) {
    val width = 168.dp
    Column(Modifier.width(width).clip(PlatterShapes.Card).hoverFill().clickable { genre.value?.let { app.navigate(Screen.Genre(it)) } }.padding(12.dp)) {
        Box(
            Modifier.size(width - 24.dp).clip(PlatterShapes.Card).background(PlatterColors.Smoke).padding(16.dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            Text(genre.value.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            listOfNotNull(genre.albumCount?.let { tn(it, "%d album", "%d albums") }, genre.songCount?.let { tn(it, "%d song", "%d songs") }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = PlatterColors.Mist,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
