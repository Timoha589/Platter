package com.platter.desktop.ui.screens

import com.platter.desktop.ui.rememberPageListState
import com.platter.desktop.i18n.t
import androidx.compose.foundation.layout.Arrangement
import com.platter.desktop.ui.PlatterSpacing
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.platter.desktop.AppController
import com.platter.desktop.AppDialog
import com.platter.desktop.api.PodcastChannel
import com.platter.desktop.api.PodcastEpisode
import com.platter.desktop.ui.Cover
import com.platter.desktop.ui.LoadView
import com.platter.desktop.ui.MenuEntry
import com.platter.desktop.ui.MoreMenu
import com.platter.desktop.ui.OutlinedPill
import com.platter.desktop.ui.PlatterColors
import com.platter.desktop.ui.PlatterShapes
import com.platter.desktop.ui.PlayCircle
import com.platter.desktop.ui.formatSeconds
import com.platter.desktop.ui.rememberLoader

/** A podcast: what it is about, and its episodes, newest first as the server lists them. */
@Composable
fun PodcastPage(app: AppController, id: String) {
    val client = app.client ?: return
    // A download asked for, an episode deleted, a refresh: each bumps the version and the page loads again.
    val loader = rememberLoader(Triple(client, id, app.podcastVersion)) { client.podcastChannel(id) }
    val player by app.player.state.collectAsState()

    LoadView(loader) { channel ->
        val episodes = channel.episodes.orEmpty()
        LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState()) {
            item {
                Column {
                    Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.Bottom) {
                        Cover(app, channel.coverArtId, 200.dp)
                        Spacer(Modifier.width(24.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t("Podcast"), style = MaterialTheme.typography.labelMedium)
                            Text(
                                channel.title ?: channel.url.orEmpty(),
                                style = MaterialTheme.typography.headlineMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                            channel.description?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = PlatterColors.Mist, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PlayCircle { app.playEpisodes(episodes, 0, channel) }
                        OutlinedPill(t("Refresh")) { app.refreshPodcasts() }
                        MoreMenu { dismiss -> MenuEntry(t("Delete podcast…"), dismiss) { app.dialog = AppDialog.DeletePodcast(channel) } }
                    }
                }
            }
            if (episodes.isEmpty()) {
                item {
                    Text(
                        if (channel.status == "error") channel.errorMessage?.let { t("The server could not read this feed: %s.", it) } ?: t("The server could not read this feed.")
                        else t("No episodes yet. If the podcast was only just added, the server is still fetching them."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PlatterColors.Mist,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            itemsIndexed(episodes) { i, episode ->
                EpisodeRow(app, channel, episodes, i, current = episode.isDownloaded && episode.streamId == player.current?.id)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun EpisodeRow(app: AppController, channel: PodcastChannel, episodes: List<PodcastEpisode>, index: Int, current: Boolean) {
    val episode = episodes[index]
    val ready = episode.isDownloaded
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PlatterSpacing.Bleed, vertical = 4.dp).clip(PlatterShapes.Card).padding(horizontal = PlatterSpacing.Bleed, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Green is reserved for playing; an episode that cannot be played yet has a grey button.
        Icon(
            Icons.Filled.PlayArrow, t("Play"),
            tint = if (ready) PlatterColors.Black else PlatterColors.Fog,
            modifier = Modifier.size(40.dp).clip(CircleShape).background(if (ready) PlatterColors.White else PlatterColors.Graphite)
                .clickable(enabled = ready) { app.playEpisodes(episodes, index, channel) }.padding(8.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                episode.title.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                color = if (current) PlatterColors.Green else PlatterColors.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    episode.publishDate?.take(10)?.takeIf { it.length == 10 },
                    episode.duration?.takeIf { it > 0 }?.let(::formatSeconds),
                    when (episode.status) {
                        "downloading" -> t("Downloading…")
                        "error" -> t("Download failed")
                        "new", "skipped" -> t("Not downloaded")
                        else -> null
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = PlatterColors.Mist,
            )
            episode.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = PlatterColors.Mist, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        if (!ready && episode.status != "downloading") {
            OutlinedPill(t("Download")) { app.downloadEpisode(episode) }
            Spacer(Modifier.width(8.dp))
        }
        MoreMenu { dismiss ->
            if (ready) {
                MenuEntry(t("Play next"), dismiss) { app.playNext(listOf(app.songOf(episode, channel))) }
                MenuEntry(t("Add to queue"), dismiss) { app.addToQueue(listOf(app.songOf(episode, channel))) }
            }
            MenuEntry(t("Delete episode"), dismiss) { app.deleteEpisode(episode) }
        }
    }
}
