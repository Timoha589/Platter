package com.platter.desktop.ui.screens

import com.platter.desktop.ui.rememberPageGridState
import com.platter.desktop.i18n.t
import androidx.compose.foundation.background
import com.platter.desktop.ui.cards
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.platter.desktop.AppController
import com.platter.desktop.AppDialog
import com.platter.desktop.Screen
import com.platter.desktop.deemix.DeezerHit
import com.platter.desktop.deemix.DeezerHits
import com.platter.desktop.deemix.DeezerPage
import com.platter.desktop.ui.Chip
import com.platter.desktop.ui.LoadView
import com.platter.desktop.ui.PlatterColors
import com.platter.desktop.ui.TRACK_TITLE
import com.platter.desktop.ui.PlatterShapes
import com.platter.desktop.ui.PlatterSpacing
import com.platter.desktop.ui.PrimaryPill
import com.platter.desktop.ui.Rail
import com.platter.desktop.ui.SectionHeader
import com.platter.desktop.ui.hoverFill
import com.platter.desktop.ui.rememberLoader
import java.io.IOException

/** How many popular tracks an artist's page shows before "Show all". */
private const val TRACKS_AT_FIRST = 5

/** A picture from Deezer's own servers, on the graphite a missing one would show. */
@Composable
private fun DeezerImage(url: String?, size: Dp, shape: Shape) {
    Box(Modifier.size(size).clip(shape).background(PlatterColors.Graphite)) {
        if (url != null) AsyncImage(url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

private fun open(app: AppController, hit: DeezerHit) = app.navigate(Screen.Deezer(hit.kind, hit.id))

/** What search found on Deezer: artists on a rail, then tracks, then albums, each with its way to be downloaded. */
@Composable
fun DeezerSection(app: AppController, hits: DeezerHits) {
    Row(Modifier.fillMaxWidth().padding(horizontal = PlatterSpacing.Gutter), verticalAlignment = Alignment.Bottom) {
        SectionHeader(t("Get from Deezer"), Modifier.weight(1f))
        app.deemixQuotaText().takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = PlatterColors.Mist, modifier = Modifier.padding(bottom = 12.dp))
        }
    }
    if (hits.artists.isNotEmpty()) {
        androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = PlatterSpacing.Bleed)) {
            items(hits.artists.size) { DeezerArtistCard(app, hits.artists[it]) }
        }
    }
    Column(Modifier.padding(horizontal = PlatterSpacing.Bleed)) {
        hits.rows.forEach { hit ->
            if (hit.kind == DeezerHit.Kind.TRACK) DeezerTrackRow(app, hit, caption = listOf(hit.artist, hit.detail).filter { it.isNotBlank() }.joinToString(" · "))
            else DeezerAlbumRow(app, hit)
        }
    }
}

@Composable
private fun DeezerArtistCard(app: AppController, hit: DeezerHit) {
    Column(Modifier.width(168.dp).clip(PlatterShapes.Card).clickable { open(app, hit) }.padding(12.dp)) {
        DeezerImage(hit.image, 144.dp, CircleShape)
        Spacer(Modifier.height(12.dp))
        Text(hit.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t("Artist"), style = MaterialTheme.typography.bodySmall, color = PlatterColors.Mist)
    }
}

@Composable
private fun DeezerAlbumCard(app: AppController, hit: DeezerHit) {
    Column(Modifier.width(168.dp).clip(PlatterShapes.Card).hoverFill().clickable { open(app, hit) }.padding(12.dp)) {
        DeezerImage(hit.image, 144.dp, PlatterShapes.Card)
        Spacer(Modifier.height(12.dp))
        Text(hit.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(hit.caption.orEmpty(), style = MaterialTheme.typography.bodySmall, color = PlatterColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A track on Deezer: press the play button for 30 seconds of it, the arrow to have the library fetch it. */
@Composable
fun DeezerTrackRow(app: AppController, hit: DeezerHit, caption: String) {
    val previewing = app.previewKey == hit.key
    Row(Modifier.fillMaxWidth().height(PlatterSpacing.TrackRowHeight).clip(PlatterShapes.Card).hoverFill(hover = PlatterColors.Smoke).padding(horizontal = PlatterSpacing.Bleed), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).clickable { app.togglePreview(hit) },
            contentAlignment = Alignment.Center,
        ) {
            when {
                previewing && app.previewState == AppController.PreviewState.LOADING ->
                    CircularProgressIndicator(Modifier.size(20.dp), color = PlatterColors.Green, strokeWidth = 2.dp)
                previewing -> Icon(Icons.Filled.Stop, t("Stop the preview"), tint = PlatterColors.Green, modifier = Modifier.size(24.dp))
                else -> Icon(Icons.Filled.PlayArrow, t("Preview"), tint = PlatterColors.White, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        DeezerImage(hit.image, PlatterSpacing.TrackCover, PlatterShapes.Card)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(hit.title, style = TRACK_TITLE, color = if (previewing) PlatterColors.Green else PlatterColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(caption, style = MaterialTheme.typography.bodyMedium, color = PlatterColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        DownloadControl(app, hit)
    }
}

@Composable
private fun DeezerAlbumRow(app: AppController, hit: DeezerHit) {
    Row(
        Modifier.fillMaxWidth().height(PlatterSpacing.TrackRowHeight).clip(PlatterShapes.Card).hoverFill(hover = PlatterColors.Smoke).clickable { open(app, hit) }.padding(horizontal = PlatterSpacing.Bleed),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(44.dp))
        DeezerImage(hit.image, PlatterSpacing.TrackCover, PlatterShapes.Card)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(hit.title, style = TRACK_TITLE, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t("Album · %s", hit.artist), style = MaterialTheme.typography.bodyMedium, color = PlatterColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        DownloadControl(app, hit)
    }
}

/** The end of a row: what the library already has, or the way to ask for it, or how that is going. */
@Composable
private fun DownloadControl(app: AppController, hit: DeezerHit) {
    val state = app.deezerStates[hit.key] ?: DeezerHit.State.IDLE
    when {
        hit.inLibrary -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Check, null, tint = PlatterColors.Green, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(t("In your library"), style = MaterialTheme.typography.labelSmall, color = PlatterColors.Mist)
        }
        state == DeezerHit.State.WORKING -> CircularProgressIndicator(Modifier.size(20.dp), color = PlatterColors.Mist, strokeWidth = 2.dp)
        state == DeezerHit.State.QUEUED -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Check, null, tint = PlatterColors.Green, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(t("In the queue"), style = MaterialTheme.typography.labelSmall, color = PlatterColors.Mist)
        }
        else -> Icon(
            Icons.Outlined.Download, t("Download"),
            tint = PlatterColors.Mist,
            modifier = Modifier.size(32.dp).clip(CircleShape).clickable { askToDownload(app, hit) }.padding(4.dp),
        )
    }
}

/** A track costs one download and goes straight to the queue; an album or an artist can spend much of the day's quota, so those ask first. */
private fun askToDownload(app: AppController, hit: DeezerHit) {
    if (hit.kind == DeezerHit.Kind.TRACK) app.deezerDownload(hit) else app.dialog = AppDialog.ConfirmDeezer(hit)
}

/** One Deezer artist's or album's page: what is there, and the way to download what the library does not have. */
@Composable
fun DeezerScreen(app: AppController, kind: DeezerHit.Kind, id: Long) {
    val client = app.client ?: return
    val loader = rememberLoader(Triple(client, kind, id)) { app.deezerPage(kind, id) ?: throw IOException(t("Could not open this page")) }
    var allTracks by remember(kind, id) { mutableStateOf(false) }

    LoadView(loader) { page: DeezerPage ->
        val own = DeezerHit(kind, id, page.title, "", "", page.image, null, false)
        val state = app.deezerStates[own.key] ?: DeezerHit.State.IDLE
        LazyVerticalGrid(
            columns = GridCells.Adaptive(PlatterSpacing.CardCell),
            modifier = Modifier.fillMaxSize(),
            state = rememberPageGridState(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.Bottom) {
                        DeezerImage(page.image, 200.dp, if (kind == DeezerHit.Kind.ARTIST) CircleShape else PlatterShapes.Card)
                        Spacer(Modifier.width(24.dp))
                        Column(Modifier.weight(1f)) {
                            Text(page.caption, style = MaterialTheme.typography.labelMedium, color = PlatterColors.Mist)
                            Text(page.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 8.dp))
                        }
                    }
                    Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PrimaryPill(
                            when (state) {
                                DeezerHit.State.WORKING -> t("Adding…")
                                DeezerHit.State.QUEUED -> t("In the queue")
                                else -> if (kind == DeezerHit.Kind.ARTIST) t("Download what's missing") else t("Download album")
                            },
                            enabled = state == DeezerHit.State.IDLE,
                        ) { askToDownload(app, own) }
                        app.deemixQuotaText().takeIf { it.isNotEmpty() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = PlatterColors.Mist, modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
            }

            if (page.tracks.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        if (kind == DeezerHit.Kind.ARTIST) SectionHeader(t("Popular tracks"), Modifier.padding(horizontal = PlatterSpacing.Bleed))
                        val shown = if (kind == DeezerHit.Kind.ARTIST && !allTracks) page.tracks.take(TRACKS_AT_FIRST) else page.tracks
                        shown.forEach { DeezerTrackRow(app, it, caption = it.caption.orEmpty()) }
                        if (kind == DeezerHit.Kind.ARTIST && page.tracks.size > TRACKS_AT_FIRST) {
                            Row(Modifier.padding(top = 8.dp, start = PlatterSpacing.Bleed)) { Chip(if (allTracks) t("Show less") else t("Show all"), false) { allTracks = !allTracks } }
                        }
                    }
                }
            }

            if (page.albums.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(t("Releases"), Modifier.padding(horizontal = PlatterSpacing.Gutter)) }
                cards(page.albums) { DeezerAlbumCard(app, it) }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(24.dp)) }
        }
    }
}
