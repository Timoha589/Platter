package com.platter.desktop.ui

import com.platter.desktop.i18n.t
import com.platter.desktop.i18n.tn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import kotlin.math.roundToInt
import java.awt.Cursor
import com.platter.desktop.SIDEBAR_DEFAULT
import com.platter.desktop.PANEL_DEFAULT
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.unit.Dp
import com.platter.desktop.PANEL_HEIGHT_DEFAULT
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.platter.desktop.AppController
import com.platter.desktop.AppDialog
import com.platter.desktop.Screen
import com.platter.desktop.SidePanel
import com.platter.desktop.api.Playlist
import com.platter.desktop.ui.screens.AlbumPage
import com.platter.desktop.ui.screens.ArtistPage
import com.platter.desktop.ui.screens.AlbumListPage
import com.platter.desktop.ui.screens.DeezerScreen
import com.platter.desktop.ui.screens.DownloadsScreen
import com.platter.desktop.ui.screens.GenrePage
import com.platter.desktop.ui.screens.HomeScreen
import com.platter.desktop.ui.screens.LikedArtistsPage
import com.platter.desktop.ui.screens.LikedPage
import com.platter.desktop.ui.screens.LibraryScreen
import com.platter.desktop.ui.screens.PlaylistPage
import com.platter.desktop.ui.screens.PodcastPage
import com.platter.desktop.ui.screens.SearchScreen
import com.platter.desktop.ui.screens.SettingsScreen

/** The cover sits as far from the row's left edge as from its top and bottom: (row height - cover) / 2. */
private val SIDEBAR_ROW_INSET = (PlatterSpacing.SidebarRowHeight - PlatterSpacing.SidebarCover) / 2

/**
 * Sidebar and top bar around the page, player along the bottom, queue on the
 * right when asked for - the desktop layout DESIGN.md describes.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Shell(app: AppController) {
    LaunchedEffect(app.client) { app.refreshPlaylists() }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(PlatterColors.Black)
            // The side buttons of a mouse go back and forward, as in a browser. Looked at before the children, which would not hear them anyway.
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
                when (event.button) {
                    PointerButton.Back -> if (app.fullPlayer) app.fullPlayer = false else app.back()
                    PointerButton.Forward -> app.forward()
                    else -> {}
                }
            },
    ) {
    // A window taller than it is wide is held upright: the lyrics and the queue go under the page instead of beside it.
    val portrait = maxHeight > maxWidth
    val windowHeight = maxHeight
    Column(Modifier.fillMaxSize()) {
        TopBar(app)
        Row(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Sidebar(app)
            ResizeHandle(
                "resize-sidebar", width = { app.sidebarWidth }, onResize = app::resizeSidebar, growsForward = true,
                onDone = app::saveLayout, onReset = { app.resizeSidebar(SIDEBAR_DEFAULT); app.saveLayout() },
            )
            Column(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.weight(1f).fillMaxWidth().clip(PlatterShapes.Card).background(PlatterColors.Carbon)) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (val screen = app.screen) {
                        Screen.Home -> HomeScreen(app)
                        Screen.Library -> LibraryScreen(app)
                        Screen.Search -> SearchScreen(app)
                        is Screen.Album -> AlbumPage(app, screen.id)
                        is Screen.Artist -> ArtistPage(app, screen.id)
                        is Screen.Playlist -> PlaylistPage(app, screen.id)
                        Screen.Settings -> SettingsScreen(app)
                        is Screen.Podcast -> PodcastPage(app, screen.id)
                        Screen.Downloads -> DownloadsScreen(app)
                        is Screen.Deezer -> DeezerScreen(app, screen.kind, screen.id)
                        Screen.Liked -> LikedPage(app)
                        Screen.LikedArtists -> LikedArtistsPage(app)
                        is Screen.Genre -> GenrePage(app, screen.name)
                        is Screen.AlbumList -> AlbumListPage(app, screen)
                    }
                }
            }
            if (portrait) SidePanelHost(app, portrait = true, maxHeight = windowHeight)
            }
            if (!portrait) SidePanelHost(app, portrait = false, maxHeight = windowHeight)
        }
        PlayerBar(app)
    }
    // Over the whole window but under what pops up: it rises from the bar whose cover opened it, and goes back down into it.
    AnimatedVisibility(
        visible = app.fullPlayer,
        enter = slideInVertically(tween(FULL_PLAYER_MS, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(FULL_PLAYER_MS / 2)),
        exit = slideOutVertically(tween(FULL_PLAYER_MS, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(FULL_PLAYER_MS / 2, delayMillis = FULL_PLAYER_MS / 2)),
    ) { FullPlayer(app) }
    ResumeBanner(app)
    ToastHost(app)
    DialogHost(app)
    }
}

/**
 * The panel on the right. Opened, it takes its width out of the page, which narrows as it comes - revealed from the
 * right edge it is anchored to, its own content never squeezed. Closed, it goes back the same way. Swapping lyrics
 * for the queue (or back) happens in place at the same width, so the one cross-fades into the other.
 */
@Composable
private fun SidePanelHost(app: AppController, portrait: Boolean, maxHeight: Dp) {
    AnimatedContent(
        targetState = app.sidePanel,
        modifier = if (portrait) Modifier.fillMaxWidth() else Modifier.fillMaxHeight(),
        // Anchored to the edge it grows from: the right one, or the bottom one when the window is upright.
        contentAlignment = if (portrait) Alignment.BottomStart else Alignment.TopEnd,
        transitionSpec = {
            val spec = tween<Float>(PANEL_MS)
            val size = SizeTransform(clip = true) { _, _ -> tween(PANEL_MS) }
            when {
                initialState == null -> (fadeIn(spec) togetherWith ExitTransition.None) using size
                targetState == null -> (EnterTransition.None togetherWith fadeOut(spec)) using size
                else -> (fadeIn(spec) togetherWith fadeOut(spec)) using size
            }
        },
        label = "side-panel",
    ) { panel ->
        if (panel != null) {
            val body: @Composable (Modifier) -> Unit = { size ->
                Box(size.clip(PlatterShapes.Card)) {
                    when (panel) {
                        SidePanel.Queue -> QueuePanel(app)
                        SidePanel.Lyrics -> LyricsPanel(app, centered = portrait)
                    }
                }
            }
            if (portrait) {
                // Under the page and as wide as it. Its height is the listener's, though never so much that the page is squeezed away.
                Column(Modifier.fillMaxWidth().height(app.panelHeight.dp.coerceAtMost(maxHeight * PORTRAIT_PANEL_MAX_SHARE))) {
                    // The 8px gap above it is the one the right panel has beside it, and the same kind of handle.
                    ResizeHandle(
                        "resize-panel", width = { app.panelHeight }, onResize = app::resizePanelHeight, growsForward = false, vertical = true,
                        onDone = app::saveLayout, onReset = { app.resizePanelHeight(PANEL_HEIGHT_DEFAULT); app.saveLayout() },
                    )
                    body(Modifier.weight(1f).fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxHeight()) {
                    ResizeHandle(
                        "resize-panel", width = { app.panelWidth }, onResize = app::resizePanel, growsForward = false,
                        onDone = app::saveLayout, onReset = { app.resizePanel(PANEL_DEFAULT); app.saveLayout() },
                    )
                    body(Modifier.width(app.panelWidth.dp).fillMaxHeight())
                }
            }
        }
    }
}

/** How long the side panel takes to open, close or give way to the other one. */
private const val PANEL_MS = 260

/** The most of the window's height the panel under the page may take, so the page keeps some. */
private const val PORTRAIT_PANEL_MAX_SHARE = 0.7f

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(PlatterShapes.Card)
            .hoverFill(rest = if (selected) PlatterColors.Smoke else Color.Transparent, hover = if (selected) PlatterColors.Smoke else PlatterColors.Graphite)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The active place sits on smoke with a white icon; the others have a fog icon and lift to graphite under the pointer.
        Icon(icon, null, tint = if (selected) PlatterColors.White else PlatterColors.Fog, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) PlatterColors.White else PlatterColors.Mist,
        )
    }
}

/**
 * The 8px gap between two panels, which is also where one is dragged wider or narrower - or, [vertical], taller or
 * shorter. [width] is the size of the panel it sets, in dp; [growsForward] says whether a drag to the right (down, when
 * vertical) makes it larger: the sidebar grows to the right, the right panel shrinks, the one under the page shrinks
 * as it is dragged down. The size follows the pointer from where the drag began, so a drag past the limit and back
 * does not drift; a double press puts it back to its usual size.
 */
@Composable
internal fun ResizeHandle(
    tag: String, width: () -> Int, onResize: (Int) -> Unit, growsForward: Boolean, onDone: () -> Unit, onReset: () -> Unit,
    vertical: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val density = LocalDensity.current.density
    val drag = Modifier
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onReset() }) }
        .pointerInput(vertical) {
            var start = 0
            var travelled = 0f
            val begin = { _: androidx.compose.ui.geometry.Offset -> dragging = true; start = width(); travelled = 0f }
            val end = { dragging = false; onDone() }
            val move = { _: androidx.compose.ui.input.pointer.PointerInputChange, delta: Float ->
                travelled += delta / density
                onResize(start + (if (growsForward) travelled else -travelled).roundToInt())
            }
            if (vertical) detectVerticalDragGestures(begin, end, end, move) else detectHorizontalDragGestures(begin, end, end, move)
        }
    val size = if (vertical) Modifier.height(8.dp).fillMaxWidth() else Modifier.width(8.dp).fillMaxHeight()
    Box(
        size
            .testTag(tag)
            .hoverable(source)
            .pointerHoverIcon(PointerIcon(Cursor(if (vertical) Cursor.N_RESIZE_CURSOR else Cursor.E_RESIZE_CURSOR)))
            .then(drag),
        contentAlignment = Alignment.Center,
    ) {
        if (hovered || dragging) {
            val line = if (vertical) Modifier.height(2.dp).fillMaxWidth() else Modifier.width(2.dp).fillMaxHeight()
            Box(line.clip(PlatterShapes.Pill).background(if (dragging) PlatterColors.Fog else PlatterColors.Steel))
        }
    }
}

@Composable
private fun Sidebar(app: AppController) {
    Column(Modifier.width(app.sidebarWidth.dp).fillMaxHeight().clip(PlatterShapes.Card).background(PlatterColors.Carbon).padding(8.dp)) {
        // A gap between rows, so a selected one and a hovered one read as two surfaces, not one slab.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NavItem(Icons.Outlined.Home, t("Home"), app.screen == Screen.Home) { app.navigate(Screen.Home) }
            NavItem(Icons.Outlined.Search, t("Search"), app.screen == Screen.Search) { app.navigate(Screen.Search) }
            NavItem(Icons.Outlined.LibraryMusic, t("Your Library"), app.screen == Screen.Library) { app.navigate(Screen.Library) }
            NavItem(Icons.Outlined.Download, t("Downloads"), app.screen == Screen.Downloads) { app.navigate(Screen.Downloads) }
        }

        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Playlists"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(
                Icons.Outlined.Add, t("New playlist"), tint = PlatterColors.White,
                modifier = Modifier.size(24.dp).clip(CircleShape).clickable { app.dialog = AppDialog.NewPlaylist(emptyList()) },
            )
        }
        // One list for all of them, so it takes the whole of what is left under "Playlists": the public ones follow the
        // listener's own and, opened, run down to the bottom, scrolling together.
        val public = app.publicPlaylists
        LazyColumn(Modifier.weight(1f)) {
            item {
                val selected = app.screen == Screen.Liked
                Row(
                    Modifier.fillMaxWidth().height(PlatterSpacing.SidebarRowHeight).clip(PlatterShapes.Card)
                        .hoverFill(rest = if (selected) PlatterColors.Smoke else Color.Transparent, hover = if (selected) PlatterColors.Smoke else PlatterColors.Graphite)
                        .clickable { app.navigate(Screen.Liked) }.padding(horizontal = SIDEBAR_ROW_INSET),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LikedCover(PlatterSpacing.SidebarCover)
                    Spacer(Modifier.width(12.dp))
                    Text(t("Liked Songs"), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            items(app.ownPlaylists) { Box(Modifier.padding(top = 4.dp)) { SidebarPlaylist(app, it) } }

            // Other people's public playlists, which fold away.
            if (public.isNotEmpty()) {
                item {
                    // The arrow turns with the list: the fold is what the press did, and the arrow says which way it went.
                    val turn by animateFloatAsState(if (app.publicCollapsed) -90f else 0f, tween(FOLD_MS))
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp).clip(PlatterShapes.Card).testTag("public-playlists-header")
                            .clickable { app.togglePublicCollapsed() }.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(t("Public playlists"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Icon(
                            Icons.Filled.ExpandMore,
                            if (app.publicCollapsed) t("Show public playlists") else t("Hide public playlists"),
                            tint = PlatterColors.White, modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = turn },
                        )
                    }
                }
                item {
                    // It unfolds down from its header and folds back up into it; the rows under it follow.
                    AnimatedVisibility(
                        visible = !app.publicCollapsed,
                        enter = expandVertically(tween(FOLD_MS)) + fadeIn(tween(FOLD_MS)),
                        exit = shrinkVertically(tween(FOLD_MS)) + fadeOut(tween(FOLD_MS)),
                    ) {
                        Column {
                            public.forEach { Box(Modifier.padding(top = 4.dp)) { SidebarPlaylist(app, it) } }
                        }
                    }
                }
            }
        }
    }
}

/** How long the full-screen player takes to rise or sink. */
private const val FULL_PLAYER_MS = 380

/** How long the public playlists take to fold or unfold. */
private const val FOLD_MS = 220

@Composable
private fun SidebarPlaylist(app: AppController, playlist: Playlist) {
    val id = playlist.id ?: return
    val selected = (app.screen as? Screen.Playlist)?.id == id
    Row(
        Modifier.fillMaxWidth().height(PlatterSpacing.SidebarRowHeight).clip(PlatterShapes.Card)
            .hoverFill(rest = if (selected) PlatterColors.Smoke else Color.Transparent, hover = if (selected) PlatterColors.Smoke else PlatterColors.Graphite)
            .clickable { app.navigate(Screen.Playlist(id)) }.padding(horizontal = SIDEBAR_ROW_INSET),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(app, playlist.coverArtId, PlatterSpacing.SidebarCover, PlatterShapes.Card)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(playlist.name.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tn(playlist.songCount ?: 0, "%d song", "%d songs"), style = MaterialTheme.typography.bodySmall, color = PlatterColors.Mist)
        }
    }
}

/** A circular 32px hit area with one white glyph: the back and forward arrows, and the account icons. */
@Composable
private fun RoundButton(icon: ImageVector, description: String, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(if (selected) PlatterColors.Smoke else Color.Transparent).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (enabled) PlatterColors.White else PlatterColors.Steel, modifier = Modifier.size(24.dp))
    }
}

/** The black strip across the top: the name over the sidebar, the arrows and search over the page, the account at the far end. */
@Composable
private fun TopBar(app: AppController) {
    Row(Modifier.fillMaxWidth().height(64.dp).background(PlatterColors.Black).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(app.sidebarWidth.dp).padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            PlatterMark(28.dp)
            Spacer(Modifier.width(8.dp))
            Text("Platter", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(8.dp + PlatterSpacing.Gutter))
        RoundButton(Icons.Filled.ChevronLeft, t("Back"), enabled = app.canGoBack) { app.back() }
        Spacer(Modifier.width(8.dp))
        RoundButton(Icons.Filled.ChevronRight, t("Forward"), enabled = app.canGoForward) { app.forward() }
        Spacer(Modifier.width(16.dp))
        Row(
            Modifier.width(360.dp).height(36.dp).clip(PlatterShapes.Pill).background(PlatterColors.Graphite).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, null, tint = PlatterColors.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (app.searchQuery.isEmpty()) Text(t("What do you want to play?"), style = MaterialTheme.typography.bodyMedium, color = PlatterColors.Bone)
                BasicTextField(
                    value = app.searchQuery,
                    onValueChange = {
                        app.searchQuery = it
                        if (it.isNotBlank() && app.screen != Screen.Search) app.navigate(Screen.Search)
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = PlatterColors.White),
                    cursorBrush = SolidColor(PlatterColors.White),
                    modifier = Modifier.fillMaxWidth().onPreviewKeyEvent {
                        // Enter is the listener saying "this is the search" - the moment worth remembering it. Previewed: the field itself
                        // takes Enter as its own "done" and would not pass it on.
                        if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) {
                            app.commitSearch()
                            true
                        } else {
                            false
                        }
                    },
                )
            }
            if (app.searchQuery.isNotEmpty()) {
                Icon(Icons.Outlined.Close, t("Clear"), tint = PlatterColors.White, modifier = Modifier.size(20.dp).clip(CircleShape).clickable { app.searchQuery = "" })
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            app.client?.credentials?.username.orEmpty(),
            style = MaterialTheme.typography.labelLarge, color = PlatterColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp).padding(end = 8.dp),
        )
        RoundButton(Icons.Outlined.Settings, t("Settings"), selected = app.screen == Screen.Settings) { app.navigate(Screen.Settings) }
        Spacer(Modifier.width(PlatterSpacing.Gutter))
    }
}
