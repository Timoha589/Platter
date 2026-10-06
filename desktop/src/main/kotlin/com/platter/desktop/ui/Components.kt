@file:OptIn(ExperimentalFoundationApi::class)

package com.platter.desktop.ui

import com.platter.desktop.i18n.t
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.platter.desktop.AppController
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import kotlin.math.roundToInt
import com.platter.desktop.Screen
import com.platter.desktop.api.Album
import com.platter.desktop.api.Artist
import com.platter.desktop.api.Song
import kotlinx.coroutines.CancellationException

// --- loading ---------------------------------------------------------------

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Failed(val message: String) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

class Loader<T>(val state: Load<T>, val retry: () -> Unit)

/** Runs [block] when [key] changes (or on retry), and reports it as loading, failed or ready. */
@Composable
fun <T> rememberLoader(key: Any?, block: suspend () -> T): Loader<T> {
    var attempt by remember { mutableStateOf(0) }
    val state: State<Load<T>> = produceState<Load<T>>(Load.Loading, key, attempt) {
        value = Load.Loading
        value = try {
            Load.Ready(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
    return Loader(state.value) { attempt++ }
}

@Composable
fun <T> LoadView(loader: Loader<T>, modifier: Modifier = Modifier.fillMaxSize(), content: @Composable (T) -> Unit) {
    when (val s = loader.state) {
        Load.Loading -> Box(modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = PlatterColors.Green, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
        }
        is Load.Failed -> Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(t("Could not load this"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(s.message, style = MaterialTheme.typography.bodySmall, color = PlatterColors.Mist, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            OutlinedPill(t("Try again"), onClick = loader.retry)
        }
        is Load.Ready -> content(s.value)
    }
}

// --- buttons ---------------------------------------------------------------

/** The one filled button style: a white pill with black text. */
@Composable
fun PrimaryPill(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .clip(PlatterShapes.Pill)
            .background(if (enabled) PlatterColors.White else PlatterColors.Steel)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = PlatterColors.Black)
    }
}

@Composable
fun OutlinedPill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(PlatterShapes.Pill)
            .border(1.dp, PlatterColors.Steel, PlatterShapes.Pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A surface that lifts to [hover] under the pointer: the one hover every card and row shares. */
fun Modifier.hoverFill(rest: Color = Color.Transparent, hover: Color = PlatterColors.Graphite): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    background(if (hovered) hover else rest).hoverable(source)
}

/** The text-only button: mist at rest, white under the pointer. */
@Composable
fun GhostText(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (hovered) PlatterColors.White else PlatterColors.Mist,
        modifier = modifier.clip(PlatterShapes.Pill).hoverable(source).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** Green is reserved for playing. */
@Composable
fun PlayCircle(size: Dp = 56.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(PlatterColors.Green).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.PlayArrow, t("Play"), tint = PlatterColors.Black, modifier = Modifier.size(size * 0.55f))
    }
}

// --- covers and cards ------------------------------------------------------

@Composable
fun Cover(app: AppController, coverArtId: String?, size: Dp, shape: Shape = PlatterShapes.Card, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    // Asked for at twice the size, rounded to 64px steps so neighbouring sizes share a cached image.
    val px = ((with(density) { size.roundToPx() } * 2 + 63) / 64) * 64
    val url = coverArtId?.let { app.client?.coverArtUrl(it, px) }
    // A cover saved with a download is used first: it is quicker, and it is there with no server.
    val local = remember(coverArtId, app.downloadsVersion) { app.localCover(coverArtId) }
    Box(modifier.size(size).clip(shape).background(PlatterColors.Graphite)) {
        val model: Any? = local ?: url
        if (model != null) AsyncImage(model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun AlbumCard(app: AppController, album: Album, width: Dp = 168.dp) {
    Column(
        Modifier
            .width(width)
            .clip(PlatterShapes.Card)
            .hoverFill()
            .clickable { album.id?.let { app.navigate(Screen.Album(it)) } }
            .padding(12.dp),
    ) {
        Cover(app, album.coverArtId, width - 24.dp)
        Spacer(Modifier.height(12.dp))
        Text(album.name.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(album.year?.takeIf { it > 0 }?.toString(), album.artistLine()).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = PlatterColors.Mist,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Artist photos are circles; album covers are rounded squares. The circle is the card: it has no fill of its own, not even under the pointer. */
@Composable
fun ArtistCard(app: AppController, artist: Artist, width: Dp = 168.dp) {
    Column(
        Modifier
            .width(width)
            .clip(PlatterShapes.Card)
            .clickable { artist.id?.let { app.navigate(Screen.Artist(it)) } }
            .padding(12.dp),
    ) {
        Cover(app, artist.coverArtId, width - 24.dp, CircleShape)
        Spacer(Modifier.height(12.dp))
        Text(artist.name.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t("Artist"), style = MaterialTheme.typography.bodySmall, color = PlatterColors.Mist)
    }
}

/**
 * The liked songs' picture, wherever they are shown as a collection (the page, the sidebar, the library): a white heart
 * on a slope from deep purple to pale blue. The phone's cover_liked_tracks is the same picture.
 */
@Composable
fun LikedCover(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(PlatterShapes.Card).background(Brush.linearGradient(listOf(Color(0xFF4500F7), Color(0xFF8FA6E6)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Favorite, null, tint = PlatterColors.White, modifier = Modifier.size(size * 0.41f))
    }
}

// --- sections --------------------------------------------------------------

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, onSeeAll: (() -> Unit)? = null, actionLabel: String = t("Show all")) {
    Row(modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        // The pill reaches out past the gutter, as every hover surface does, so the words end on it.
        if (onSeeAll != null) GhostText(actionLabel, Modifier.offset(x = PlatterSpacing.Bleed), onClick = onSeeAll)
    }
}

@Composable
fun <T> Rail(title: String, items: List<T>, onSeeAll: (() -> Unit)? = null, actionLabel: String = t("Show all"),itemContent: @Composable (T) -> Unit) {
    if (items.isEmpty()) return
    Column {
        SectionHeader(title, Modifier.padding(horizontal = PlatterSpacing.Gutter), onSeeAll, actionLabel)
        LazyRow(contentPadding = PaddingValues(horizontal = PlatterSpacing.Bleed), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            items(items) { itemContent(it) }
        }
    }
}

/** A card in a grid: its cell is pushed in by the bleed, so the cover (not the hover surface) sits on the gutter. */
fun LazyGridScope.card(content: @Composable () -> Unit) = item { Box(Modifier.padding(start = PlatterSpacing.Bleed)) { content() } }

fun <T> LazyGridScope.cards(list: List<T>, content: @Composable (T) -> Unit) =
    items(list) { Box(Modifier.padding(start = PlatterSpacing.Bleed)) { content(it) } }

/** The pill that switches what a screen shows; the chosen one is white with black text. */
@Composable
fun Chip(label: String, selected: Boolean, rest: Color = PlatterColors.Graphite, onClick: () -> Unit) {
    Box(
        Modifier.height(32.dp).clip(PlatterShapes.Pill)
            .background(if (selected) PlatterColors.White else rest)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) PlatterColors.Black else PlatterColors.White)
    }
}

/** A song as a card in a rail: its cover, its name, its artist. Play on press. */
@Composable
fun SongCard(app: AppController, song: Song, onPlay: () -> Unit, width: Dp = 168.dp) {
    var menu by remember { mutableStateOf(false) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    Box {
        Column(
            Modifier
                .width(width)
                .clip(PlatterShapes.Card)
                .hoverFill()
                .onSecondaryPress { pointer = it; menu = true }
                .clickable(onClick = onPlay)
                .padding(12.dp),
        ) {
            Cover(app, song.coverArtId, width - 24.dp)
            Spacer(Modifier.height(12.dp))
            Text(song.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artistLine().orEmpty(), style = MaterialTheme.typography.bodySmall, color = PlatterColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AtPointer(pointer) { SongMenu(app, song, menu, { menu = false }, onPlay = onPlay) }
    }
}

// --- tracks ----------------------------------------------------------------

/** Where a row sits in a playlist, so its menu can take it out of that playlist or move it. */
/** A song's name in a list: the app's 14 for titles was small to read down a long one. */
val TRACK_TITLE = TextStyle(fontFamily = Inter, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp)

class PlaylistSlot(val playlistId: String, val index: Int, val songs: List<Song>)

@Composable
fun TrackRow(
    app: AppController,
    song: Song,
    label: String,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    showArtist: Boolean = true,
    /** Replaces the artist line - where a search found the song, say. */
    detail: String? = null,
    inPlaylist: PlaylistSlot? = null,
    /** A song that stands alone shows its cover where the number would be. */
    coverArtId: String? = null,
    /** In a table the number stays, and the cover comes after it. */
    showNumber: Boolean = false,
    /** Where the album's name goes in a table; null leaves the column out. */
    album: String? = null,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var menu by remember { mutableStateOf(false) }
    // Where a right-click went down, so the menu opens there; null when the dots asked, and it hangs from them.
    var pointer by remember { mutableStateOf<Offset?>(null) }

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .height(PlatterSpacing.TrackRowHeight)
                .clip(PlatterShapes.Card)
                .background(if (hovered || menu) PlatterColors.Smoke else Color.Transparent)
                .hoverable(hover)
                .onSecondaryPress { pointer = it; menu = true }
                .onClick(onDoubleClick = onPlay) {}
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showNumber || coverArtId == null) Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
                if (hovered) {
                    Icon(Icons.Filled.PlayArrow, t("Play"), Modifier.size(20.dp).clickable(onClick = onPlay))
                } else {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = if (isCurrent) PlatterColors.Green else PlatterColors.Mist)
                }
            }
            if (coverArtId != null) {
                Box(Modifier.size(PlatterSpacing.TrackCover), contentAlignment = Alignment.Center) {
                    Cover(app, coverArtId, PlatterSpacing.TrackCover, PlatterShapes.Card)
                    // With a number beside it, the number is what turns into the play button.
                    if (hovered && !showNumber) {
                        Box(Modifier.size(PlatterSpacing.TrackCover).clip(PlatterShapes.Card).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.PlayArrow, t("Play"), Modifier.size(20.dp).clickable(onClick = onPlay))
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(if (album != null) TABLE_TITLE_WEIGHT else 1f)) {
                Text(
                    song.title.orEmpty(),
                    style = TRACK_TITLE,
                    color = if (isCurrent) PlatterColors.Green else PlatterColors.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showArtist || detail != null) {
                    Text(detail ?: song.artistLine().orEmpty(), style = MaterialTheme.typography.bodyMedium, color = PlatterColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // A long title stops short of the heart, not against it.
            if (album == null) Spacer(Modifier.width(16.dp))
            if (album != null) {
                Spacer(Modifier.width(16.dp))
                Text(
                    album,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PlatterColors.Mist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable(enabled = song.albumId != null) { song.albumId?.let { app.navigate(Screen.Album(it)) } },
                )
                Spacer(Modifier.width(16.dp))
            }
            Heart(app.isLiked(song.id, song.starred), { app.toggleLike(song) })
            Spacer(Modifier.width(8.dp))
            // A song saved on this computer says so, and plays from there. The mark has its place whether or not
            // it is drawn, or the heart would sit differently from one row to the next.
            Box(Modifier.size(16.dp)) {
                if (app.isDownloaded(song.id)) Icon(Icons.Filled.ArrowCircleDown, t("Downloaded"), tint = PlatterColors.Green, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(8.dp))
            // Right-click is not something to discover; the dots are, and they only appear under the pointer.
            Box {
                Icon(
                    Icons.Outlined.MoreHoriz, t("More"),
                    tint = PlatterColors.White,
                    modifier = Modifier.size(20.dp).clip(CircleShape).clickable { pointer = null; menu = true }.alpha(if (hovered || menu) 1f else 0f),
                )
                if (pointer == null) SongMenu(app, song, menu, { menu = false }, onPlay = onPlay, inPlaylist = inPlaylist)
            }
            Spacer(Modifier.width(8.dp))
            Text(formatSeconds(song.duration), style = MaterialTheme.typography.bodyMedium, color = PlatterColors.Mist, modifier = Modifier.width(44.dp), textAlign = TextAlign.End)
        }
        AtPointer(pointer) { SongMenu(app, song, menu, { menu = false }, onPlay = onPlay, inPlaylist = inPlaylist) }
    }
}

// --- progress --------------------------------------------------------------

/** How much one notch of the wheel moves the volume, in percent. */
private const val WHEEL_VOLUME_STEP = 5

/**
 * The wheel over this changes the volume: up is louder, a notch is [WHEEL_VOLUME_STEP] percent. A touchpad sends many
 * small turns, so what does not yet make a whole percent is carried over to the next.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun Modifier.wheelVolume(app: AppController): Modifier {
    var carried by remember { mutableStateOf(0f) }
    return onPointerEvent(PointerEventType.Scroll) { event ->
        val turn = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
        if (turn == 0f) return@onPointerEvent
        carried += -turn * WHEEL_VOLUME_STEP
        val whole = carried.toInt()
        carried -= whole
        if (whole != 0) app.setVolume((app.player.state.value.volume + whole).coerceIn(0, 100))
        event.changes.forEach { it.consume() }
    }
}

/**
 * A thin white bar on a steel track; tap or drag to seek. Under the pointer it turns green and shows the handle,
 * which is what says it can be taken hold of.
 */
@Composable
fun ProgressBar(fraction: Float, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    var width by remember { mutableStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val active = hovered || dragging
    val shown = fraction.coerceIn(0f, 1f)
    Box(
        modifier
            .height(16.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .hoverable(hover)
            .pointerInput(Unit) { detectTapGestures { onSeek((it.x / width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ -> onSeek((change.position.x / width).coerceIn(0f, 1f)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(PlatterShapes.Pill).background(PlatterColors.Steel)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(shown).background(if (active) PlatterColors.Green else PlatterColors.White))
        }
        if (active) {
            Box(
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset((width * shown).toInt() - 6.dp.roundToPx(), 0) }
                    .size(12.dp).clip(CircleShape).background(PlatterColors.White),
            )
        }
    }
}
