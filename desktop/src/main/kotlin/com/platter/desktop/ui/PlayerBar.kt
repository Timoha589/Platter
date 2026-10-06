package com.platter.desktop.ui

import com.platter.desktop.i18n.t
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.platter.desktop.AppController
import com.platter.desktop.AppDialog
import com.platter.desktop.Screen
import com.platter.desktop.SidePanel
import com.platter.desktop.player.RepeatMode

/** A button that is only an icon: grey, white under the pointer; [tint] is for one that says it is on. */
@Composable
fun IconAction(icon: ImageVector, description: String, tint: Color = PlatterColors.Mist, size: Dp = 20.dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Icon(
        icon, description,
        tint = if (hovered && tint == PlatterColors.Mist) PlatterColors.White else tint,
        modifier = modifier.size(size).clip(CircleShape).hoverable(hover).clickable(onClick = onClick),
    )
}

/** Spotify's player bar: 90 with the buttons 32 and 8 above the bar, which leaves the same 16 over them as under the bar. */
val PLAYER_BAR_HEIGHT = 90.dp

/** The widest the transport and the progress bar grow, however wide the window: past it a bar is hard to aim at. */
private val TRANSPORT_MAX_WIDTH = 620.dp

@Composable
fun PlayerBar(app: AppController) {
    val s by app.player.state.collectAsState()
    val song = s.current

    Row(
        Modifier.fillMaxWidth().height(PLAYER_BAR_HEIGHT).background(PlatterColors.Black).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: what is playing, and what can be done with it. The title gives way when the window is narrow.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (song != null) {
                // The cover opens the player at full size.
                Cover(app, song.coverArtId, 56.dp, modifier = Modifier.testTag("open-full-player").pointerHoverIcon(PointerIcon.Hand).clickable { app.fullPlayer = true })
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(
                        song.title.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = song.albumId != null) { song.albumId?.let { app.navigate(Screen.Album(it)) } },
                    )
                    Text(
                        song.artistLine().orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = PlatterColors.Mist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = song.artistId != null) { song.artistId?.let { app.navigate(Screen.Artist(it)) } },
                    )
                }
                // Music can be liked, rated and filed; an episode or a station is not a song.
                if (song.isMusic) {
                    Spacer(Modifier.width(16.dp))
                    Heart(app.isLiked(song.id, song.starred), { app.toggleLike(song) })
                    Spacer(Modifier.width(12.dp))
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconAction(Icons.Outlined.MoreHoriz, t("More")) { menu = true }
                        SongMenu(app, song, menu, { menu = false })
                    }
                }
            }
        }

        // Centre: transport above, progress below, the two as wide as each other and never wider than the maximum.
        Box(Modifier.weight(1.6f), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = TRANSPORT_MAX_WIDTH).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    IconAction(
                        Icons.Outlined.Shuffle, t("Shuffle"),
                        tint = if (s.queue.shuffle) PlatterColors.Green else PlatterColors.Mist,
                    ) { app.player.toggleShuffle() }
                    IconAction(Icons.Filled.SkipPrevious, t("Previous"), size = 28.dp) { app.player.previous() }
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(PlatterColors.White).clickable { app.player.toggle() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (s.isPlaying) t("Pause") else t("Play"),
                            tint = PlatterColors.Black,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconAction(Icons.Filled.SkipNext, t("Next"), size = 28.dp) { app.player.next() }
                    val repeat = s.queue.repeat
                    IconAction(
                        if (repeat == RepeatMode.One) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
                        t("Repeat"),
                        tint = if (repeat == RepeatMode.Off) PlatterColors.Mist else PlatterColors.Green,
                    ) { app.player.cycleRepeat() }
                }
                // As far under the buttons as Spotify leaves it: the bar is a row of its own, not a caption to them.
                Spacer(Modifier.height(8.dp))
                if (song?.isLive == true) {
                    // A station has no length and nowhere to seek to; saying so beats a bar that never moves.
                    Text(t("LIVE"), style = MaterialTheme.typography.labelMedium, color = PlatterColors.Green, modifier = Modifier.height(16.dp))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatClock(s.positionMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = PlatterColors.Mist,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(40.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        ProgressBar(
                            fraction = if (s.durationMs > 0) s.positionMs.toFloat() / s.durationMs else 0f,
                            onSeek = { app.player.seek(it) },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(formatClock(s.durationMs), style = MaterialTheme.typography.labelSmall, color = PlatterColors.Mist, modifier = Modifier.width(40.dp))
                    }
                }
            }
        }

        // Right: the panels, then the volume.
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (app.equalizerButton) {
                IconAction(Icons.Outlined.Tune, t("Equalizer"), if (app.equalizer.enabled) PlatterColors.Green else PlatterColors.Mist) { app.dialog = AppDialog.Equalizer }
                Spacer(Modifier.width(16.dp))
            }
            IconAction(Icons.Outlined.Title, t("Lyrics"), if (app.sidePanel == SidePanel.Lyrics) PlatterColors.Green else PlatterColors.Mist) {
                app.sidePanel = if (app.sidePanel == SidePanel.Lyrics) null else SidePanel.Lyrics
            }
            Spacer(Modifier.width(16.dp))
            IconAction(Icons.Outlined.QueueMusic, t("Queue"), if (app.sidePanel == SidePanel.Queue) PlatterColors.Green else PlatterColors.Mist) {
                app.sidePanel = if (app.sidePanel == SidePanel.Queue) null else SidePanel.Queue
            }
            Spacer(Modifier.width(16.dp))
            // The wheel works over the speaker as well as the bar: the whole of the volume control is one thing to point at.
            Row(Modifier.wheelVolume(app), verticalAlignment = Alignment.CenterVertically) {
                IconAction(
                    if (s.volume == 0) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                    t("Mute"),
                ) { app.setVolume(if (s.volume == 0) 100 else 0) }
                Spacer(Modifier.width(4.dp))
                ProgressBar(s.volume / 100f, { app.setVolume((it * 100).toInt()) }, Modifier.width(96.dp))
            }
        }
    }
}

@Composable
fun QueuePanel(app: AppController) {
    val s by app.player.state.collectAsState()
    Column(Modifier.fillMaxSize().background(PlatterColors.Carbon).padding(top = 8.dp)) {
        Text(t("Queue"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
        if (s.queue.songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(t("Nothing queued"), color = PlatterColors.Mist, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                itemsIndexed(s.queue.songs) { i, song ->
                    val current = i == s.queue.index
                    Row(
                        Modifier.fillMaxWidth().height(PlatterSpacing.TrackRowHeight).clip(PlatterShapes.Card).clickable { app.player.jumpTo(i) }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(app, song.coverArtId, PlatterSpacing.TrackCover, PlatterShapes.Card)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                song.title.orEmpty(),
                                style = TRACK_TITLE,
                                color = if (current) PlatterColors.Green else PlatterColors.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(song.artistLine().orEmpty(), style = MaterialTheme.typography.bodyMedium, color = PlatterColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconAction(Icons.Outlined.Close, t("Remove from queue"), size = 20.dp) { app.player.removeAt(i) }
                    }
                }
            }
        }
    }
}
