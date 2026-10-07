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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.platter.desktop.AppController
import com.platter.desktop.AppDialog
import com.platter.desktop.HomeSection
import com.platter.desktop.Screen
import com.platter.desktop.api.Album
import com.platter.desktop.api.SubsonicClient
import com.platter.desktop.ui.AlbumCard
import com.platter.desktop.ui.ArtistCard
import com.platter.desktop.ui.Chip
import com.platter.desktop.ui.Load
import com.platter.desktop.ui.LoadView
import com.platter.desktop.ui.OutlinedPill
import com.platter.desktop.ui.PlatterColors
import com.platter.desktop.ui.hoverFill
import com.platter.desktop.ui.PlatterShapes
import com.platter.desktop.ui.PlatterSpacing
import com.platter.desktop.ui.Rail
import com.platter.desktop.ui.SongCard
import com.platter.desktop.ui.WaveCard
import com.platter.desktop.ui.rememberLoader
import java.util.Calendar

/** How many cards a home rail holds; the "See all" page has the rest. */
private const val RAIL = 20

@Composable
fun HomeScreen(app: AppController) {
    val client = app.client ?: return
    // One ping decides whether the server answers at all; the sections after it may be empty or fail on their own.
    val reachable = rememberLoader(client) { client.ping() }

    // Asking early lets the server have a first batch ready by the time the card is pressed.
    LaunchedEffect(client) { app.prepareWave() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberPageScrollState()).padding(bottom = PlatterSpacing.Section)) {
        if (app.waveAvailable) WaveCard(app)
        if (reachable.state is Load.Failed) {
            LoadView(reachable, Modifier.fillMaxWidth().height(240.dp)) {}
            // Without the server there is still what was saved: it plays, and the Downloads screen needs no server.
            Row(Modifier.padding(horizontal = PlatterSpacing.Gutter), horizontalArrangement = Arrangement.Center) {
                OutlinedPill(t("Open downloads")) { app.navigate(Screen.Downloads) }
            }
        } else {
            app.homeSections.forEach { section -> HomeSectionView(app, client, section) }
            Row(Modifier.padding(horizontal = PlatterSpacing.Gutter, vertical = 24.dp)) {
                Chip(t("Arrange home"), false) { app.dialog = AppDialog.ArrangeHome }
            }
        }
    }
}

/** Runs [fetch] for one section; a section with nothing to show, or that failed, is simply not there. */
@Composable
private fun <T> Section(client: SubsonicClient, key: Any, fetch: suspend (SubsonicClient) -> List<T>, content: @Composable (List<T>) -> Unit) {
    val loader = rememberLoader(client to key) { fetch(client) }
    val state = loader.state
    if (state is Load.Ready && state.value.isNotEmpty()) content(state.value)
}

@Composable
private fun HomeSectionView(app: AppController, client: SubsonicClient, section: HomeSection) {
    when (section) {
        HomeSection.Discovery -> Section(client, section, { it.randomSongs(10) }) { songs ->
            Rail(
                t("Discovery"), songs,
                onSeeAll = { app.shuffleAll() }, actionLabel = t("Shuffle all"),
            ) { song -> SongCard(app, song, onPlay = { app.player.play(songs, songs.indexOf(song)) }) }
        }

        HomeSection.Pinned -> {
            // Playlists are already in hand; pinning only chooses among them.
            val pinned = app.pinned.mapNotNull { id -> app.playlists.firstOrNull { it.id == id } }
            Rail(t("Playlists"), pinned) { PlaylistCard(app, it) }
        }

        HomeSection.MadeForYou -> Section(client, section, { app.liked().songs.orEmpty().shuffled().take(10) }) { songs ->
            // Each liked song is a seed: pressing one plays it and songs like it.
            Rail(t("Made for you"), songs) { song -> SongCard(app, song, onPlay = { app.startRadio(song) }) }
        }

        HomeSection.StarredTracks -> Section(client, section, { app.liked().songs.orEmpty().take(RAIL) }) { songs ->
            Rail(t("Liked songs"), songs, onSeeAll = { app.navigate(Screen.Liked) }) { song ->
                SongCard(app, song, onPlay = { app.player.play(songs, songs.indexOf(song)) })
            }
        }

        HomeSection.StarredAlbums -> Section(client, section, { app.liked().albums.orEmpty().take(RAIL) }) { albums ->
            Rail(t("Liked albums"), albums, onSeeAll = { app.navigate(Screen.AlbumList("Liked albums", "starred")) }) { AlbumCard(app, it) }
        }

        HomeSection.StarredArtists -> Section(client, section, { app.liked().artists.orEmpty().take(RAIL) }) { artists ->
            Rail(t("Liked artists"), artists, onSeeAll = { app.navigate(Screen.LikedArtists) }) { ArtistCard(app, it) }
        }

        HomeSection.Flashback -> Section(client, section, { decades(it) }) { decades ->
            Rail(t("Flashback"), decades) { decade -> DecadeTile(decade) { app.navigate(Screen.AlbumList("${decade}s", "byYear", decade, decade + 9)) } }
        }

        HomeSection.MostPlayed -> AlbumsRail(app, client, section, "Most played", "frequent")
        HomeSection.LastPlayed -> AlbumsRail(app, client, section, "Last played", "recent")
        HomeSection.RecentlyAdded -> AlbumsRail(app, client, section, "Recently added", "newest")

        HomeSection.NewestPodcasts -> Section(client, Pair(section, app.podcastVersion), { runCatching { it.newestEpisodes(RAIL) }.getOrDefault(emptyList()) }) { episodes ->
            // Only what the server has downloaded can be played, so only that is offered.
            val ready = episodes.filter { it.isDownloaded }
            Rail(t("New episodes"), ready) { episode ->
                SongCard(app, app.songOf(episode), onPlay = { app.playEpisodes(ready, ready.indexOf(episode)) })
            }
        }

        HomeSection.NewReleases -> Section(client, section, { newReleases(it) }) { albums ->
            Rail(t("New releases"), albums) { AlbumCard(app, it) }
        }
    }
}

/** A rail of albums of one `getAlbumList2` kind, with its full list one press away. */
@Composable
private fun AlbumsRail(app: AppController, client: SubsonicClient, section: HomeSection, title: String, type: String) {
    Section(client, section, { it.albumList(type, RAIL) }) { albums ->
        Rail(t(title), albums, onSeeAll = { app.navigate(Screen.AlbumList(title, type)) }) { AlbumCard(app, it) }
    }
}

/**
 * The library's decades, from its oldest album to its newest. An album the server has no year for reads as 0, which
 * would otherwise turn the rail into two hundred decades starting at the year nought.
 */
private suspend fun decades(client: SubsonicClient): List<Int> {
    val now = Calendar.getInstance().get(Calendar.YEAR)
    val first = client.albumList("byYear", 1, 0, fromYear = 1900, toYear = now).firstOrNull()?.year ?: return emptyList()
    val last = client.albumList("byYear", 1, 0, fromYear = now, toYear = 1900).firstOrNull()?.year ?: return emptyList()
    if (first <= 0 || last <= 0) return emptyList()
    return (first - first % 10..last - last % 10 step 10).toList().reversed()
}

/**
 * This year's albums and last year's, newest first. This year alone is empty every January, and for most of the
 * year on a library whose newest tags stop a year short.
 */
private suspend fun newReleases(client: SubsonicClient): List<Album> {
    val now = Calendar.getInstance().get(Calendar.YEAR)
    return client.albumList("byYear", 500, 0, fromYear = now, toYear = now - 1)
        .sortedByDescending { it.created.orEmpty() }
        .take(RAIL)
}

@Composable
private fun DecadeTile(decade: Int, onClick: () -> Unit) {
    Box(Modifier.padding(12.dp)) {
        Box(
            Modifier.width(144.dp).height(96.dp).clip(PlatterShapes.Card).hoverFill(rest = PlatterColors.Graphite, hover = PlatterColors.Smoke).clickable(onClick = onClick).padding(16.dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            Text("${decade}s", style = MaterialTheme.typography.headlineMedium)
        }
    }
}
