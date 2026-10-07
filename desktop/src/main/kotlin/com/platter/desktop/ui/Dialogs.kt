package com.platter.desktop.ui

import com.platter.desktop.i18n.t
import com.platter.desktop.i18n.tn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import com.platter.desktop.UpdateState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.zIndex
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.platter.desktop.AppController
import com.platter.desktop.AppDialog
import com.platter.desktop.HomeSection
import com.platter.desktop.api.RadioStation
import com.platter.desktop.deemix.DeezerHit
import com.platter.desktop.player.EqPresets
import com.platter.desktop.player.EqSettings
import com.platter.desktop.api.Song

// --- a text field --------------------------------------------------------------

/** One line of text on a graphite field: what the dialogs and settings ask names and addresses with. */
@Composable
fun TextInput(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    onEnter: (() -> Unit)? = null,
    /** Shows dots instead of what is typed. */
    password: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        modifier.height(40.dp).clip(PlatterShapes.Card).background(PlatterColors.Iron).padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = PlatterColors.Fog)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = PlatterColors.White),
            cursorBrush = SolidColor(PlatterColors.White),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent {
                if (onEnter != null && it.type == KeyEventType.KeyDown && it.key == Key.Enter) {
                    onEnter()
                    true
                } else {
                    false
                }
            },
        )
    }
}

// --- the host ------------------------------------------------------------------------

/** Lays the open dialog over the window, on a dimmed scrim; a press outside it closes it. */
@Composable
fun DialogHost(app: AppController) {
    val dialog = app.dialog ?: return
    val close = { app.dialog = null }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = close),
        contentAlignment = Alignment.Center,
    ) {
        // Swallows presses so the card is not "outside" itself.
        Box(Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
            when (dialog) {
                is AppDialog.AddToPlaylist -> AddToPlaylistDialog(app, dialog.songs, close)
                is AppDialog.NewPlaylist -> NameDialog(t("New playlist"), t("Playlist name"), "", t("Create"), close) { app.createPlaylist(it, dialog.songs); close() }
                is AppDialog.RenamePlaylist -> NameDialog(t("Rename playlist"), t("Playlist name"), dialog.playlist.name.orEmpty(), t("Save"), close) { app.renamePlaylist(dialog.playlist, it); close() }
                is AppDialog.DeletePlaylist -> ConfirmDialog(
                    t("Delete playlist?"), t("“%s” will be deleted from your server. Its songs stay in your library.", dialog.playlist.name.orEmpty()), t("Delete"), close,
                ) { app.deletePlaylist(dialog.playlist); close() }
                is AppDialog.TrackInfo -> TrackInfoDialog(app, dialog.song, close)
                AppDialog.ArrangeHome -> ArrangeHomeDialog(app, close)
                is AppDialog.ConfirmDeezer -> ConfirmDialog(
                    if (dialog.hit.kind == DeezerHit.Kind.ALBUM) t("Download “%s”?", dialog.hit.title) else t("Download everything by %s?", dialog.hit.title),
                    "${t("Tracks already in your library are skipped.")} ${app.deemixQuotaText()}".trim(),
                    t("Download"), close,
                ) { app.deezerDownload(dialog.hit); close() }
                AppDialog.Equalizer -> EqualizerDialog(app, close)
                AppDialog.Update -> UpdateDialog(app, close)
                AppDialog.DeleteAllDownloads -> ConfirmDialog(
                    t("Delete all downloads?"), t("Every song saved on this computer is deleted from the disk. Your library on the server is not touched."), t("Delete"), close,
                ) { app.removeAllDownloads(); close() }
                AppDialog.AddPodcast -> NameDialog(t("Add a podcast"), t("Feed address (https://…)"), "", t("Add"), close) { app.addPodcast(it); close() }
                is AppDialog.DeletePodcast -> ConfirmDialog(
                    t("Delete podcast?"), t("“%s” and its downloaded episodes will be deleted from your server.", dialog.channel.title.orEmpty()), t("Delete"), close,
                ) { app.deletePodcast(dialog.channel); close() }
                is AppDialog.EditStation -> StationDialog(app, dialog.station, close)
                is AppDialog.DeleteStation -> ConfirmDialog(
                    t("Delete station?"), t("“%s” will be removed from your server's list of stations.", dialog.station.name.orEmpty()), t("Delete"), close,
                ) { app.deleteStation(dialog.station); close() }
            }
        }
    }
}

/** The card every dialog sits on. */
@Composable
private fun DialogCard(title: String, width: androidx.compose.ui.unit.Dp = 420.dp, content: @Composable () -> Unit) {
    Column(Modifier.width(width).overlayShadow().clip(PlatterShapes.Card).background(PlatterColors.Graphite).padding(24.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        content()
    }
}

@Composable
private fun Buttons(cancel: String = t("Cancel"), ok: String, enabled: Boolean = true, onCancel: () -> Unit, onOk: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        if (cancel.isNotEmpty()) {
            Text(
                cancel,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(PlatterShapes.Pill).clickable(onClick = onCancel).padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        PrimaryPill(ok, enabled = enabled, onClick = onOk)
    }
}

// --- the dialogs ----------------------------------------------------------------------

/** Offers the release the updater found, and follows its download; the program restarts itself when the installer has it. */
@Composable
private fun UpdateDialog(app: AppController, close: () -> Unit) {
    val offer = app.updateOffer
    if (offer == null) {
        close()
        return
    }
    val state = app.updateState
    DialogCard(t("Update available"), width = 480.dp) {
        Text(
            t("Platter %s is available. You have %s.", offer.version, app.version.orEmpty()),
            style = MaterialTheme.typography.bodyMedium,
            color = PlatterColors.Mist,
        )
        if (offer.notes.isNotEmpty()) {
            Text(t("What's new"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            Box(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                Text(offer.notes, style = MaterialTheme.typography.bodySmall, color = PlatterColors.Mist)
            }
        }
        when (state) {
            UpdateState.Idle -> Buttons(cancel = t("Later"), ok = t("Update"), onCancel = { app.postponeUpdate() }) { app.installUpdate() }
            is UpdateState.Downloading -> {
                Text(
                    t("Downloading… %d%%", (state.fraction * 100).toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                )
                LinearProgressIndicator(
                    progress = { state.fraction },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(PlatterShapes.Pill),
                    color = PlatterColors.Green,
                    trackColor = PlatterColors.Iron,
                    drawStopIndicator = {},
                )
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                    Text(
                        t("Hide"),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(PlatterShapes.Pill).clickable(onClick = close).padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            UpdateState.Installing -> Text(
                t("Installing. Platter closes and opens again by itself."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 20.dp),
            )
            is UpdateState.Failed -> {
                Text(
                    t("The update could not be installed: %s", t(state.message)),
                    style = MaterialTheme.typography.bodySmall,
                    color = PlatterColors.Mist,
                    modifier = Modifier.padding(top = 20.dp),
                )
                Buttons(cancel = t("Later"), ok = t("Try again"), onCancel = { app.postponeUpdate() }) { app.installUpdate() }
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, placeholder: String, initial: String, ok: String, onCancel: () -> Unit, onOk: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    DialogCard(title) {
        TextInput(name, { name = it }, placeholder, Modifier.fillMaxWidth(), autoFocus = true, onEnter = { if (name.isNotBlank()) onOk(name) })
        Buttons(ok = ok, enabled = name.isNotBlank(), onCancel = onCancel) { onOk(name) }
    }
}

@Composable
private fun ConfirmDialog(title: String, message: String, ok: String, onCancel: () -> Unit, onOk: () -> Unit) {
    DialogCard(title) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = PlatterColors.Mist)
        Buttons(ok = ok, onCancel = onCancel, onOk = onOk)
    }
}

@Composable
private fun AddToPlaylistDialog(app: AppController, songs: List<Song>, close: () -> Unit) {
    val what = if (songs.size == 1) "“${songs.single().title.orEmpty()}”" else tn(songs.size, "%d song", "%d songs")
    DialogCard(t("Add %s to", what)) {
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clip(PlatterShapes.Card).clickable { app.dialog = AppDialog.NewPlaylist(songs) }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(40.dp).clip(PlatterShapes.Card).background(PlatterColors.Iron), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(t("New playlist"), style = MaterialTheme.typography.titleSmall)
                }
            }
            // Only the listener's own: someone else's public playlist cannot be added to.
            items(app.ownPlaylists) { playlist ->
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clip(PlatterShapes.Card).clickable { app.addToPlaylist(playlist, songs); close() }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(app, playlist.coverArtId, 40.dp, PlatterShapes.Card)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(playlist.name.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(tn(playlist.songCount ?: 0, "%d song", "%d songs"), style = MaterialTheme.typography.bodySmall, color = PlatterColors.Mist)
                    }
                }
            }
        }
        Buttons(cancel = t("Cancel"), ok = t("Done"), onCancel = close, onOk = close)
    }
}

@Composable
private fun TrackInfoDialog(app: AppController, song: Song, close: () -> Unit) {
    val rows = listOf(
        t("Track") to song.track?.takeIf { it > 0 }?.let { n -> song.discNumber?.takeIf { it > 0 }?.let { "$it · $n" } ?: n.toString() },
        t("Year") to song.year?.takeIf { it > 0 }?.toString(),
        t("Genre") to song.genre,
        t("Duration") to song.duration?.takeIf { it > 0 }?.let(::formatSeconds),
        t("Format") to listOfNotNull(song.suffix?.uppercase(), song.bitrate?.takeIf { it > 0 }?.let { "$it kbps" }).joinToString(" · ").ifEmpty { null },
        t("Quality") to listOfNotNull(
            song.bitDepth?.takeIf { it > 0 }?.let { "$it-bit" },
            song.samplingRate?.takeIf { it > 0 }?.let { "%.1f kHz".format(it / 1000.0) },
            song.channelCount?.takeIf { it > 0 }?.let { if (it == 2) t("stereo") else if (it == 1) t("mono") else t("%d channels", it) },
        ).joinToString(" · ").ifEmpty { null },
        t("Size") to song.size?.takeIf { it > 0 }?.let { "%.1f MB".format(it / 1_048_576.0) },
        t("Plays") to song.playCount?.takeIf { it > 0 }?.toString(),
        t("Path") to song.path,
    ).filter { !it.second.isNullOrBlank() }

    DialogCard(t("Track info"), width = 520.dp) {
        // Who the song is, once, at the top; the rows below are what the file is.
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(app, song.coverArtId, 72.dp, PlatterShapes.Card)
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Text(song.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(song.artistLine().orEmpty(), style = MaterialTheme.typography.bodyMedium, color = PlatterColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                if (!song.album.isNullOrBlank()) {
                    Text(song.album.orEmpty(), style = MaterialTheme.typography.bodySmall, color = PlatterColors.Fog, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            rows.forEachIndexed { i, (label, value) ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(PlatterColors.Iron))
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = PlatterColors.Mist, maxLines = 1, softWrap = false, modifier = Modifier.width(132.dp).padding(top = 1.dp))
                    Text(value.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
        Buttons(cancel = "", ok = t("Close"), onCancel = close, onOk = close)
    }
}

/** A radio station: a name, where it is heard, and optionally its website. */
@Composable
private fun StationDialog(app: AppController, station: RadioStation?, close: () -> Unit) {
    var name by remember { mutableStateOf(station?.name.orEmpty()) }
    var stream by remember { mutableStateOf(station?.streamUrl.orEmpty()) }
    var home by remember { mutableStateOf(station?.homePageUrl.orEmpty()) }
    val ready = name.isNotBlank() && stream.isNotBlank()
    val save = { if (ready) { app.saveStation(station, name, stream, home); close() } }
    DialogCard(if (station == null) t("Add a station") else t("Edit station")) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextInput(name, { name = it }, t("Name"), Modifier.fillMaxWidth(), autoFocus = true, onEnter = save)
            TextInput(stream, { stream = it }, t("Stream address (https://…)"), Modifier.fillMaxWidth(), onEnter = save)
            TextInput(home, { home = it }, t("Website (optional)"), Modifier.fillMaxWidth(), onEnter = save)
        }
        Buttons(ok = t("Save"), enabled = ready, onCancel = close) { save() }
    }
}

/** A slider that stands up: [value] runs from the bottom of [range] to the top, with its middle marked, so zero reads as level. */
@Composable
private fun VerticalSlider(value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var height by remember { mutableStateOf(1f) }
    fun fromY(y: Float): Float = (range.endInclusive - (y / height).coerceIn(0f, 1f) * (range.endInclusive - range.start))
    Canvas(
        modifier
            .onSizeChanged { height = it.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(enabled) { if (enabled) detectTapGestures { onChange(fromY(it.y)) } }
            .pointerInput(enabled) { if (enabled) detectDragGestures { change, _ -> onChange(fromY(change.position.y)) } },
    ) {
        val x = size.width / 2
        val fraction = (range.endInclusive - value) / (range.endInclusive - range.start)
        val middle = size.height * (range.endInclusive / (range.endInclusive - range.start))
        val y = fraction.coerceIn(0f, 1f) * size.height
        drawLine(Color(0xFF535353), Offset(x, 0f), Offset(x, size.height), strokeWidth = 4f, cap = StrokeCap.Round)
        drawLine(Color(0xFF73777C), Offset(x - 8f, middle), Offset(x + 8f, middle), strokeWidth = 2f)
        drawLine(if (enabled) Color.White else Color(0xFF73777C), Offset(x, middle), Offset(x, y), strokeWidth = 4f, cap = StrokeCap.Round)
        drawCircle(if (enabled) Color.White else Color(0xFF73777C), radius = 8f, center = Offset(x, y))
    }
}

/** The equalizer: on or off, a preset to start from, and a slider for the preamp and each of the ten bands. */
@Composable
private fun EqualizerDialog(app: AppController, close: () -> Unit) {
    val eq = app.equalizer
    val gains = eq.gains()
    val chosen = EqPresets.nameOf(gains)
    DialogCard(t("Equalizer"), width = 640.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (eq.enabled) t("On: what you hear is shaped by the bands below.") else t("Off: the music is played as it is."),
                style = MaterialTheme.typography.bodyMedium, color = PlatterColors.Mist, modifier = Modifier.weight(1f),
            )
            Switch(
                checked = eq.enabled,
                onCheckedChange = { app.changeEqualizer(eq.copy(enabled = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = PlatterColors.Black, checkedTrackColor = PlatterColors.Green,
                    uncheckedThumbColor = PlatterColors.Mist, uncheckedTrackColor = PlatterColors.Iron, uncheckedBorderColor = PlatterColors.Steel,
                ),
            )
        }
        Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EqPresets.all.keys.forEach { name -> Chip(t(name), chosen == name && eq.enabled) { app.chooseEqPreset(name) } }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            EqColumn(t("Preamp"), eq.preamp, eq.enabled) { app.changeEqualizer(eq.copy(preamp = it)) }
            Spacer(Modifier.width(8.dp))
            gains.forEachIndexed { band, gain ->
                EqColumn(EqSettings.FREQUENCIES[band], gain, eq.enabled) { value ->
                    app.changeEqualizer(eq.copy(bands = gains.toMutableList().also { it[band] = value }))
                }
            }
        }
        Buttons(cancel = t("Reset"), ok = t("Done"), onCancel = { app.changeEqualizer(EqSettings(enabled = eq.enabled)) }, onOk = close)
    }
}

@Composable
private fun EqColumn(label: String, gain: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(48.dp)) {
        Text("%+.0f".format(gain), style = MaterialTheme.typography.labelSmall, color = if (enabled) PlatterColors.White else PlatterColors.Fog)
        Spacer(Modifier.height(4.dp))
        VerticalSlider(
            // Whole decibels: a finer step is not something an ear can tell, and it keeps the labels honest.
            gain, -EqSettings.RANGE_DB..EqSettings.RANGE_DB, { onChange(kotlin.math.round(it)) },
            Modifier.width(32.dp).height(150.dp), enabled,
        )
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = PlatterColors.Mist)
    }
}

private val HOME_ROW_HEIGHT = 40.dp

/** Order and visibility of the home sections, as the Android app's "rearrange home". */
@Composable
private fun ArrangeHomeDialog(app: AppController, close: () -> Unit) {
    var order by remember { mutableStateOf(app.homeOrder) }
    var hidden by remember { mutableStateOf(app.homeHidden) }

    // The row being dragged and how far the pointer has taken it from where it was picked up, in px. The list itself
    // does not move until the row is let go: the rows around it step aside as it passes them, and the row under the
    // pointer never changes place in the layout, so the drag is measured against something that stays still.
    var dragging by remember { mutableStateOf<HomeSection?>(null) }
    var dragged by remember { mutableStateOf(0f) }
    val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { HOME_ROW_HEIGHT.toPx() }
    val from = dragging?.let { order.indexOf(it) } ?: -1
    val to = if (from < 0) -1 else (from + Math.round(dragged / rowPx)).coerceIn(0, order.lastIndex)

    DialogCard(t("Arrange home")) {
        Column {
            order.forEachIndexed { index, section ->
              key(section) {
                val shown = section !in hidden
                val lifted = section == dragging
                // Rows between where the dragged one began and where it is now make room for it.
                val shift = when {
                    from < 0 || lifted -> 0f
                    to > from && index in from + 1..to -> -rowPx
                    to < from && index in to until from -> rowPx
                    else -> 0f
                }
                Row(
                    Modifier.fillMaxWidth().height(HOME_ROW_HEIGHT)
                        .zIndex(if (lifted) 1f else 0f)
                        .graphicsLayer { translationY = if (lifted) dragged else shift }
                        .clip(PlatterShapes.Card)
                        .background(if (lifted) PlatterColors.Smoke else Color.Transparent),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (shown) Icons.Filled.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                        if (shown) t("Shown") else t("Hidden"),
                        tint = if (shown) PlatterColors.Green else PlatterColors.Mist,
                        modifier = Modifier.size(24.dp).clip(CircleShape).clickable { hidden = if (shown) hidden + section else hidden - section },
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(t(section.title), style = MaterialTheme.typography.titleSmall, color = if (shown) PlatterColors.White else PlatterColors.Mist, modifier = Modifier.weight(1f))
                    // Taken by the handle, as on the phone: the row is under the pointer from the first movement.
                    Icon(
                        Icons.Filled.DragHandle, t("Drag to reorder"), tint = if (lifted) PlatterColors.White else PlatterColors.Mist,
                        modifier = Modifier.size(40.dp).padding(8.dp)
                            .pointerHoverIcon(PointerIcon(java.awt.Cursor(java.awt.Cursor.MOVE_CURSOR)))
                            .pointerInput(section) {
                                detectDragGestures(
                                    onDragStart = { dragging = section; dragged = 0f },
                                    onDragEnd = {
                                        val start = order.indexOf(section)
                                        val end = (start + Math.round(dragged / rowPx)).coerceIn(0, order.lastIndex)
                                        if (end != start) order = order.toMutableList().apply { add(end, removeAt(start)) }
                                        dragging = null
                                        dragged = 0f
                                    },
                                    onDragCancel = { dragging = null; dragged = 0f },
                                ) { change, amount ->
                                    change.consume()
                                    // Held inside the list: past its first or last row there is nowhere to put the row.
                                    val start = order.indexOf(section)
                                    dragged = (dragged + amount.y).coerceIn(-start * rowPx, (order.lastIndex - start) * rowPx)
                                }
                            },
                    )
                }
              }
            }
        }
        Buttons(ok = t("Save"), onCancel = close) { app.saveHomeLayout(order, hidden); close() }
    }
}

// --- toast ------------------------------------------------------------------------------

/** The offer to pick up a queue another device left on the server, for as long as the setting says. */
@Composable
fun ResumeBanner(app: AppController) {
    val offer = app.resumeOffer ?: return
    val song = offer.song ?: return
    Box(Modifier.fillMaxSize().padding(end = 24.dp, bottom = PLAYER_BAR_HEIGHT + 20.dp), contentAlignment = Alignment.BottomEnd) {
        Row(
            Modifier.width(420.dp).overlayShadow().clip(PlatterShapes.Card).background(PlatterColors.Graphite).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(app, song.coverArtId, 48.dp, PlatterShapes.Card)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t("Pick up where you left off"), style = MaterialTheme.typography.labelMedium, color = PlatterColors.Mist)
                Text(song.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(song.artistLine(), formatClock(offer.positionMs).takeIf { offer.positionMs > 0 }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = PlatterColors.Mist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            PrimaryPill(t("Resume")) { app.resume() }
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Outlined.Close, t("Dismiss"), tint = PlatterColors.Mist,
                modifier = Modifier.size(28.dp).clip(CircleShape).clickable { app.dismissResume() }.padding(4.dp),
            )
        }
    }
}

/** The line that confirms what just happened: a small card with the overlay shadow, above the player. */
@Composable
fun ToastHost(app: AppController) {
    val message = app.toast ?: return
    Box(Modifier.fillMaxSize().padding(bottom = PLAYER_BAR_HEIGHT + 20.dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            message,
            style = MaterialTheme.typography.labelLarge,
            color = PlatterColors.White,
            modifier = Modifier.overlayShadow().clip(PlatterShapes.Card).background(PlatterColors.Graphite).padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}
