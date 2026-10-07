package com.platter.desktop.ui.screens

import com.platter.desktop.ui.rememberPageScrollState
import com.platter.desktop.i18n.t
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.platter.desktop.AppController
import com.platter.desktop.Screen
import com.platter.desktop.api.Song
import com.platter.desktop.deemix.DeezerHits
import com.platter.desktop.search.RankedResults
import com.platter.desktop.search.SearchEngine
import com.platter.desktop.ui.AlbumCard
import com.platter.desktop.ui.ArtistCard
import com.platter.desktop.ui.Chip
import com.platter.desktop.ui.Cover
import com.platter.desktop.ui.LoadView
import com.platter.desktop.ui.PlatterColors
import com.platter.desktop.ui.PlatterShapes
import com.platter.desktop.ui.PlatterSpacing
import com.platter.desktop.ui.Rail
import com.platter.desktop.ui.SectionHeader
import com.platter.desktop.ui.TrackRow
import com.platter.desktop.ui.rememberLoader
import com.platter.desktop.wave.WaveLyrics
import kotlinx.coroutines.delay

/** How many songs sit beside the top result before "Show all". */
private const val SONGS_AT_FIRST = 4

@Composable
fun SearchScreen(app: AppController) {
    val client = app.client ?: return
    // Wait for a pause in typing before asking the server.
    var query by remember { mutableStateOf(app.searchQuery.trim()) }
    LaunchedEffect(app.searchQuery) {
        delay(250)
        query = app.searchQuery.trim()
    }
    // Names and starred ids are fetched ahead, so the first keystrokes do not wait for them.
    LaunchedEffect(client) { app.prepareSearch() }
    // Opening a result is acting on the search, which is what makes it worth remembering.
    DisposableEffect(Unit) {
        onDispose { if (app.screen is Screen.Album || app.screen is Screen.Artist) app.commitSearch(app.searchQuery) }
    }

    if (query.length < SearchEngine.MIN_QUERY_LENGTH) {
        Recent(app, typed = query.isNotEmpty())
        return
    }

    val loader = rememberLoader(client to query) { app.search(query) }
    val player by app.player.state.collectAsState()

    // The library's own lyrics, searched by the wave service: a line of a song finds the song. Alongside, never in the way.
    var lyricHits by remember { mutableStateOf<WaveLyrics?>(null) }
    LaunchedEffect(client to query) {
        lyricHits = null
        if (app.waveAvailable) lyricHits = app.searchLyrics(query)
    }
    val lyricMatches = lyricHits?.results.orEmpty().filter { it.song?.id != null }

    val deezer by produceState<DeezerHits?>(null, client to query) {
        value = null
        value = app.deezerSearch(query)
    }

    LoadView(loader) { result ->
        if (result.isEmpty && lyricMatches.isEmpty() && deezer?.isEmpty != false) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(t("No results found for “%s”", query), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(t("Check the spelling, or try a different word."), style = MaterialTheme.typography.bodyMedium, color = PlatterColors.Mist)
            }
            return@LoadView
        }
        val currentId = player.current?.id
        Column(Modifier.fillMaxSize().verticalScroll(rememberPageScrollState("results")).padding(bottom = PlatterSpacing.Section)) {
            TopAndSongs(app, result, currentId, query)

            if (lyricMatches.isNotEmpty()) {
                SectionHeader(t("In lyrics"), Modifier.padding(horizontal = PlatterSpacing.Gutter))
                Column(Modifier.padding(horizontal = PlatterSpacing.Bleed)) {
                    val songsFound = lyricMatches.mapNotNull { it.song }
                    lyricMatches.forEachIndexed { i, match ->
                        val song = match.song ?: return@forEachIndexed
                        TrackRow(
                            app, song, "${i + 1}",
                            isCurrent = song.id == currentId,
                            onPlay = { app.commitSearch(query); app.player.play(songsFound, songsFound.indexOf(song)) },
                            detail = listOfNotNull(song.artistLine(), match.snippet?.takeIf { it.isNotBlank() }).joinToString(" · "),
                            coverArtId = song.coverArtId,
                        )
                    }
                }
            } else if (lyricHits?.indexing == true) {
                Text(
                    t("Reading your library's lyrics, matches by words will appear here"),
                    style = MaterialTheme.typography.bodySmall,
                    color = PlatterColors.Mist,
                    modifier = Modifier.padding(horizontal = PlatterSpacing.Gutter, vertical = 8.dp),
                )
            }
            Rail(t("Artists"), result.artists) { ArtistCard(app, it) }
            Rail(t("Albums"), result.albums) { AlbumCard(app, it) }
            // Past the library: what Deezer has, through Deemix plus. With no account or no site the section is just not there.
            deezer?.takeIf { !it.isEmpty }?.let { DeezerSection(app, it) }
        }
    }
}

/** The best hit as a card, with the best songs beside it; songs alone when nothing matched well enough to lead. */
@Composable
private fun TopAndSongs(app: AppController, result: RankedResults, currentId: String?, query: String) {
    val songs = result.songs
    var all by remember(query) { mutableStateOf(false) }
    val shown = if (all) songs else songs.take(SONGS_AT_FIRST)

    val play: (Int) -> Unit = { i -> app.commitSearch(query); app.player.play(songs, i) }
    val songRows: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier) {
            SectionHeader(t("Songs"), Modifier.padding(horizontal = PlatterSpacing.Bleed))
            shown.forEachIndexed { i, song ->
                TrackRow(app, song, "${i + 1}", isCurrent = song.id != null && song.id == currentId, onPlay = { play(i) }, coverArtId = song.coverArtId)
            }
            if (songs.size > SONGS_AT_FIRST) {
                Row(Modifier.padding(top = 8.dp, start = 12.dp)) { Chip(if (all) t("Show fewer") else t("Show all %d", songs.size), false) { all = !all } }
            }
        }
    }

    val top = result.top
    if (top == null) {
        if (songs.isNotEmpty()) songRows(Modifier.padding(horizontal = PlatterSpacing.Bleed))
        return
    }
    Row(Modifier.padding(horizontal = PlatterSpacing.Gutter)) {
        Column(Modifier.width(360.dp)) {
            SectionHeader(t("Top result"))
            TopCard(app, top, onOpen = {
                when (top) {
                    is RankedResults.Top.OfArtist -> top.artist.id?.let { app.navigate(Screen.Artist(it)) }
                    is RankedResults.Top.OfAlbum -> top.album.id?.let { app.navigate(Screen.Album(it)) }
                    // Inside the queue the songs list would have built, so playing from the card and from the list agree.
                    is RankedResults.Top.OfSong -> play(songs.indexOfFirst { it.id == top.song.id }.coerceAtLeast(0))
                }
            })
        }
        Spacer(Modifier.width(PlatterSpacing.Bleed))
        if (songs.isNotEmpty()) songRows(Modifier.weight(1f))
    }
}

@Composable
private fun TopCard(app: AppController, top: RankedResults.Top, onOpen: () -> Unit) {
    val role = when (top) {
        is RankedResults.Top.OfArtist -> t("Artist")
        is RankedResults.Top.OfAlbum -> t("Album")
        is RankedResults.Top.OfSong -> t("Song")
    }
    Column(
        Modifier.fillMaxWidth().clip(PlatterShapes.Card).background(PlatterColors.Graphite).clickable(onClick = onOpen).padding(20.dp),
    ) {
        // An artist is a circle; an album or a song a rounded square.
        Cover(app, top.coverArtId, 92.dp, if (top is RankedResults.Top.OfArtist) CircleShape else PlatterShapes.Card)
        Spacer(Modifier.height(20.dp))
        Text(top.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            top.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = PlatterColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                role,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clip(PlatterShapes.Pill).background(PlatterColors.Black).padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/** Before anything is typed: the searches worth repeating. */
@Composable
private fun Recent(app: AppController, typed: Boolean) {
    if (app.recentSearches.isEmpty() || typed) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (typed) t("Enter at least three characters") else t("Search for songs, albums and artists"),
                style = MaterialTheme.typography.bodyMedium,
                color = PlatterColors.Mist,
            )
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberPageScrollState("recent")).padding(horizontal = PlatterSpacing.Bleed)) {
        SectionHeader(t("Recent searches"), Modifier.padding(horizontal = PlatterSpacing.Bleed))
        app.recentSearches.forEach { recent ->
            Row(
                Modifier.fillMaxWidth().height(48.dp).clip(PlatterShapes.Card).clickable { app.searchQuery = recent }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.History, null, tint = PlatterColors.Mist, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(16.dp))
                Text(recent, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(
                    Icons.Outlined.Close, t("Forget “%s”", recent),
                    tint = PlatterColors.Mist,
                    modifier = Modifier.size(24.dp).clip(CircleShape).clickable { app.forgetSearch(recent) }.padding(4.dp),
                )
            }
        }
    }
}
