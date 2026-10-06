package com.platter.desktop.ui

import com.platter.desktop.i18n.t
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.platter.desktop.AppController
import com.platter.desktop.AppDialog
import com.platter.desktop.Screen
import com.platter.desktop.api.Song

/**
 * Every dropdown in the app: a card with the content radius and the overlay shadow, not Material's own. DESIGN.md raises
 * a surface by a step of colour, not by shadow: pages sit on carbon and settings cards on graphite, so a menu opened
 * from either is smoke, the step above both; the row under the pointer steps up once more, to iron.
 */
@Composable
fun PlatterMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = PlatterShapes.Card,
        containerColor = PlatterColors.Smoke,
        tonalElevation = 0.dp,
        shadowElevation = 16.dp,
        content = content,
    )
}

/**
 * A dropdown hangs from the bottom-left corner of the box it is declared in, which for a whole row or card is far from
 * where the pointer is. This reports where the secondary (right) button went down, in the coordinates of the node it is
 * put on, so [AtPointer] can open the menu there.
 */
fun Modifier.onSecondaryPress(onPress: (Offset) -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            // Looked at before the children and left alone: the row's own presses carry on as they were.
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) onPress(event.changes.first().position)
        }
    }
}

/** Puts a menu's anchor at [pointer], a point of the box around it, so the menu opens there. Nothing without a point. */
@Composable
fun AtPointer(pointer: Offset?, content: @Composable () -> Unit) {
    if (pointer == null) return
    Box(Modifier.offset { IntOffset(pointer.x.roundToInt(), pointer.y.roundToInt()) }) { content() }
}

/** One entry of a dropdown: closes the menu, then does its thing. */
@Composable
fun MenuEntry(text: String, onDismiss: () -> Unit, action: () -> Unit) {
    PlatterMenuItem(text, onClick = { onDismiss(); action() })
}

/** A row of a dropdown: smoke at rest like the menu, iron under the pointer. */
@Composable
fun PlatterMenuItem(text: String, onClick: () -> Unit, trailingIcon: (@Composable () -> Unit)? = null) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        modifier = Modifier.hoverFill(rest = Color.Transparent, hover = PlatterColors.Iron),
        trailingIcon = trailingIcon,
    )
}

/**
 * What can be done with a song, from its row, its card or the player. [onPlay] is "Play" where the caller knows
 * what playing it means (the rest of the album, the search results); without it there is no "Play" entry.
 */
@Composable
fun SongMenu(
    app: AppController,
    song: Song,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPlay: (() -> Unit)? = null,
    inPlaylist: PlaylistSlot? = null,
) {
    PlatterMenu(expanded, onDismiss) {
        // Where nothing says what playing it means (the player's own menu), "Play" would only restart the queue.
        if (onPlay != null) MenuEntry(t("Play"), onDismiss) { onPlay() }
        MenuEntry(t("Play radio"), onDismiss) { app.startRadio(song) }
        MenuEntry(t("Add similar songs to queue"), onDismiss) { app.addInstantMix(song) }
        MenuEntry(t("Play next"), onDismiss) { app.playNext(listOf(song)) }
        MenuEntry(t("Add to queue"), onDismiss) { app.addToQueue(listOf(song)) }
        HorizontalDivider(color = PlatterColors.Iron)
        MenuEntry(t("Add to playlist…"), onDismiss) { app.dialog = AppDialog.AddToPlaylist(listOf(song)) }
        if (song.isMusic) {
            if (app.isDownloaded(song.id)) MenuEntry(t("Remove download"), onDismiss) { app.removeDownloads(listOf(song)) }
            else MenuEntry(t("Download"), onDismiss) { app.download(listOf(song)) }
        }
        if (inPlaylist != null) {
            MenuEntry(t("Remove from this playlist"), onDismiss) { app.removeFromPlaylist(inPlaylist.playlistId, inPlaylist.index) }
            if (inPlaylist.index > 0) MenuEntry(t("Move up"), onDismiss) {
                app.moveInPlaylist(inPlaylist.playlistId, inPlaylist.songs, inPlaylist.index, inPlaylist.index - 1)
            }
            if (inPlaylist.index < inPlaylist.songs.lastIndex) MenuEntry(t("Move down"), onDismiss) {
                app.moveInPlaylist(inPlaylist.playlistId, inPlaylist.songs, inPlaylist.index, inPlaylist.index + 1)
            }
        }
        MenuEntry(t("Rate…"), onDismiss) { app.dialog = AppDialog.Rate(song) }
        // Subsonic has no dislike; rating 1 stands in, and on a wave track it steers the wave away.
        if (song.isMusic) MenuEntry(if (app.isDisliked(song)) t("Remove dislike") else t("Dislike"), onDismiss) { app.toggleDislike(song) }
        HorizontalDivider(color = PlatterColors.Iron)
        song.albumId?.let { id -> MenuEntry(t("Go to album"), onDismiss) { app.navigate(Screen.Album(id)) } }
        song.artistId?.let { id -> MenuEntry(t("Go to artist"), onDismiss) { app.navigate(Screen.Artist(id)) } }
        MenuEntry(t("Share"), onDismiss) { app.share(song.id) }
        MenuEntry(t("Track info"), onDismiss) { app.dialog = AppDialog.TrackInfo(song) }
    }
}

/** The "⋯" of a page header: whatever [content] lists, in a dropdown. */
@Composable
fun MoreMenu(content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Icon(
            Icons.Outlined.MoreHoriz, t("More"),
            tint = PlatterColors.White,
            modifier = Modifier.size(32.dp).clip(CircleShape).clickable { open = true },
        )
        PlatterMenu(open, { open = false }) { content { open = false } }
    }
}
