package com.platter.desktop.ui.screens

import com.platter.desktop.i18n.t
import com.platter.desktop.i18n.tn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import com.platter.desktop.ui.PlatterMenu
import com.platter.desktop.ui.PlatterMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.platter.desktop.AppController
import com.platter.desktop.AppDialog
import com.platter.desktop.DeemixStatus
import com.platter.desktop.i18n.AppLanguage
import com.platter.desktop.ui.formatBytes
import com.platter.desktop.ui.OutlinedPill
import com.platter.desktop.ui.PlatterColors
import com.platter.desktop.ui.PlatterShapes
import com.platter.desktop.ui.PlatterSpacing
import com.platter.desktop.ui.PrimaryPill
import com.platter.desktop.ui.TextInput

/** The bitrate caps offered, in kbps; 0 is the file as stored. */
private val BITRATES = listOf(0, 320, 256, 192, 128, 96)

/** The formats the server can transcode to; null is the file as stored. */
private val FORMATS = listOf<String?>(null, "mp3", "opus", "aac")

private val SYNC_SECONDS = listOf(5, 15, 30, 60)

private val SMART_COUNTS = listOf(25, 50, 100, 250, 500)

/** How wide a group of settings grows: past this a row's words and its control drift too far apart to read together. */
private val GROUP_WIDTH = 880.dp

/** The most room a row's control takes. */
private val CONTROL_WIDTH = 420.dp

/** A folder picker, on the window's own thread: it is modal, and what is chosen is where new downloads go. */
private fun chooseFolder(app: AppController) {
    val chooser = javax.swing.JFileChooser(app.downloadRoot().toFile().takeIf { it.isDirectory })
    chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
    chooser.dialogTitle = t("Where should downloads be kept?")
    if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) app.changeDownloadFolder(chooser.selectedFile.absolutePath)
}

/** Where Platter lives; the phone's updater reads its releases from the same place. */
private const val REPOSITORY = "https://github.com/Timoha589/Platter"

private val REPLAY_GAIN = listOf("off" to "Off", "track" to "Track", "album" to "Album")

@Composable
fun SettingsScreen(app: AppController) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = PlatterSpacing.Gutter, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(t("Settings"), style = MaterialTheme.typography.headlineMedium)

        Group(t("Interface")) {
            Setting(t("Language"), t("The language of Platter's menus and messages. System follows Windows.")) {
                Choice(listOf<AppLanguage?>(null) + AppLanguage.entries, AppLanguage.fromTag(app.language), { it?.nativeName ?: t("System default") }) { app.changeLanguage(it?.tag) }
            }
        }

        Group(t("Account")) {
            Setting(t("Signed in"), t("%s on %s", app.client?.credentials?.username.orEmpty(), app.client?.credentials?.serverUrl.orEmpty())) {
                OutlinedPill(t("Sign out")) { app.signOut() }
            }
            Setting(
                t("Library"), t("Ask the server to look for new music."),
                note = if (app.scanning) app.scanCount?.let { tn(it.toInt(), "%d file so far", "%d files so far") } ?: t("Asking the server") else null,
            ) {
                OutlinedPill(if (app.scanning) t("Scanning…") else t("Scan library")) { app.scanLibrary() }
            }
        }

        Group(t("Playback")) {
            Setting(t("Stream quality"), t("Caps the bitrate your server sends, to save data. Applies from the next track.")) {
                Choice(BITRATES, app.maxBitrate, { if (it == 0) t("Original") else "$it kbps" }) { app.changeMaxBitrate(it) }
            }
            Setting(t("Transcode to"), t("The format the server converts to when a cap is set. Original keeps the stored format.")) {
                Choice(FORMATS, app.streamFormat, { it?.uppercase() ?: t("Original") }) { app.changeStreamFormat(it) }
            }
            Setting(t("Volume levelling"), t("ReplayGain evens out loud and quiet records from the tags in the files. Applies the next time Platter starts.")) {
                Choice(REPLAY_GAIN.map { it.first }, app.replayGain, { key -> t(REPLAY_GAIN.first { it.first == key }.second) }) { app.changeReplayGain(it) }
            }
            Setting(t("Equalizer"), t("Shape the sound with ten bands.")) {
                OutlinedPill(if (app.equalizer.enabled) t("Equalizer: on…") else t("Equalizer…")) { app.dialog = AppDialog.Equalizer }
            }
            Setting(t("Equalizer button in the player"), t("Shows a button for the equalizer in the player bar, next to the lyrics and the queue.")) {
                Toggle(app.equalizerButton) { app.changeEqualizerButton(it) }
            }
            Setting(
                t("Scrobbling"), t("Tell your server what you listen to, for its history and for Last.fm."),
                note = if (app.pendingPlays > 0) tn(app.pendingPlays, "%d play is waiting to be sent to the server. It goes as soon as it answers.", "%d plays are waiting to be sent to the server. They go as soon as it answers.") else null,
            ) {
                Toggle(app.scrobbling) { app.changeScrobbling(it) }
            }
            Setting(
                t("Sync play queue"),
                t("Keep your queue on the server, so Platter on your phone or here can pick it up where you left off. Off, nothing is saved."),
            ) {
                Toggle(app.syncQueue) { app.changeSyncQueue(it) }
            }
            if (app.syncQueue) {
                Setting(t("Offer to resume for"), t("How long the offer to pick up a saved queue stays on screen after Platter starts.")) {
                    Choice(SYNC_SECONDS, app.syncSeconds, { t("%d s", it) }) { app.changeSyncSeconds(it) }
                }
            }
        }

        Group(t("Connection")) {
            var local by remember { mutableStateOf(app.localAddress.orEmpty()) }
            Setting(
                t("Home address"),
                t("If your server has an address on your home network, Platter uses it while it answers and the main one everywhere else."),
                note = if (app.localAddress.isNullOrBlank()) null
                else if (app.usingLocal) t("Using the home address.") else t("Using the main address; the home address does not answer."),
            ) {
                AddressField(local, { local = it }, "http://192.168.1.10:4533") {
                    app.changeLocalAddress(local)
                    app.showToast(if (local.isBlank()) t("Home address cleared") else t("Home address saved"))
                }
            }
        }

        Group(t("Downloads")) {
            Setting(
                t("Saved songs"),
                t("%s saved, %s. Saved songs play from the disk, with or without the server.", tn(app.downloadedSongs().size, "%d song", "%d songs"), formatBytes(app.downloadedBytes)),
                note = app.downloadRoot().toString(),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedPill(t("Choose folder…")) { chooseFolder(app) }
                    OutlinedPill(t("Open folder")) { runCatching { java.awt.Desktop.getDesktop().open(app.downloadRoot().toFile().also { it.mkdirs() }) } }
                }
            }
            Setting(t("Download quality"), t("Caps the bitrate of what is saved, to save space. Original keeps the file as it is on the server.")) {
                Choice(BITRATES, app.downloadBitrate, { if (it == 0) t("Original") else "$it kbps" }) { app.changeDownloadBitrate(it) }
            }
            Setting(
                t("Save songs as you listen"),
                t("Songs are saved in the background while you play them. Once the limit is reached, the ones played longest ago make room. Songs you download yourself, or keep by liking, are never counted."),
            ) {
                Toggle(app.smartDownload) { app.changeSmartDownload(it) }
            }
            if (app.smartDownload) {
                Setting(t("Keep up to"), t("How many songs saved this way to keep.")) {
                    Choice(SMART_COUNTS, app.smartCount, { tn(it, "%d song", "%d songs") }) { app.changeSmartCount(it) }
                }
            }
            Setting(
                t("Keep liked songs downloaded"),
                t("Every song you like is saved, and a copy kept only for a like goes when you unlike the song."),
            ) {
                Toggle(app.keepLiked) { app.changeKeepLiked(it) }
            }
            Setting(t("Delete all downloads"), t("Every song saved on this computer is deleted from the disk. Your library on the server is not touched.")) {
                OutlinedPill(t("Delete all downloads…")) { app.dialog = AppDialog.DeleteAllDownloads }
            }
        }

        Group(t("Deemix plus")) {
            var address by remember { mutableStateOf(app.deemixUrl.orEmpty()) }
            var typed by remember { mutableStateOf("") }
            LaunchedEffect(Unit) { app.refreshDeemixAccount() }
            Setting(
                t("Address"),
                t("Leave the address empty to have Platter find it: your server's host on port 8088 at home, deemix.<your domain> away."),
                note = deemixLine(app.deemixStatus, app).ifEmpty { null },
            ) {
                AddressField(address, { address = it }, "https://deemix.example.com") {
                    app.changeDeemixUrl(address)
                    app.showToast(if (address.isBlank()) t("Deemix plus address cleared") else t("Deemix plus address saved"))
                }
            }
            Setting(
                t("Password"),
                if (app.hasDeemixPassword) t("A password for the site is saved. Type another to replace it, or save an empty one to forget it.")
                else t("Only needed if your Deemix plus password differs from your server password."),
            ) {
                AddressField(typed, { typed = it }, t("Deemix plus password"), password = true) { app.changeDeemixPassword(typed); typed = "" }
            }
        }

        Group(t("About")) {
            Setting(
                t("Platter for Windows"), t("Audio is played by VLC."),
                note = app.version?.let { t("Version %s", it) },
            )
            // A build run from the sources has no version to compare, so there is nothing to look for.
            if (app.version != null) {
                Setting(t("Updates"), t("Platter looks for a newer version when it starts, and installs it when you say so.")) {
                    OutlinedPill(if (app.checkingUpdate) t("Checking…") else t("Check for updates")) { app.checkForUpdate(manual = true) }
                }
            }
            Setting(t("Source code"), REPOSITORY) {
                OutlinedPill(t("Open on GitHub")) { openInBrowser(REPOSITORY) }
            }
        }
    }
}

/** How the account reads under the Deemix plus heading. */
private fun deemixLine(status: DeemixStatus, app: AppController): String = when (status) {
    DeemixStatus.NotChecked -> ""
    DeemixStatus.Checking -> t("Signing in…")
    is DeemixStatus.Ready -> "${status.user} · ${status.role}" + app.deemixQuotaText().takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
    is DeemixStatus.Unreachable -> t("Deemix plus did not answer at: %s", status.address ?: t("no address to try"))
    is DeemixStatus.Rejected -> t("Could not sign in as %s. If your Deemix plus password is not your server password, enter it below.", status.user.orEmpty())
    is DeemixStatus.Failed -> t("Could not sign in: %s", status.message ?: "-")
}

/** A heading and a card of rows under it: the graphite internal card of DESIGN.md, with a rule above each row. */
@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().widthIn(max = GROUP_WIDTH).clip(PlatterShapes.Card).background(PlatterColors.Graphite).padding(horizontal = 24.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 12.dp))
        content()
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * One setting: what it is and what it does on the left, the way to change it on the right. [note] is the live
 * part of the answer (what is happening now); without a [control] the row is only information.
 */
@Composable
private fun Setting(title: String, description: String? = null, note: String? = null, control: (@Composable () -> Unit)? = null) {
    HorizontalDivider(color = PlatterColors.Iron)
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = PlatterColors.Mist, modifier = Modifier.padding(top = 4.dp)) }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        }
        if (control != null) {
            Spacer(Modifier.width(24.dp))
            Box(Modifier.widthIn(max = CONTROL_WIDTH), contentAlignment = Alignment.CenterEnd) { control() }
        }
    }
}

/** A closed pill showing the value in use; pressed, it lists the others and ticks the one chosen. */
@Composable
private fun <T> Choice(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.defaultMinSize(minWidth = 160.dp).height(36.dp).clip(PlatterShapes.Pill).background(PlatterColors.Smoke)
                .clickable { open = true }.padding(start = 16.dp, end = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label(selected), style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Filled.ArrowDropDown, t("Change"), tint = PlatterColors.White, modifier = Modifier.size(24.dp))
        }
        PlatterMenu(open, { open = false }) {
            options.forEach { option ->
                PlatterMenuItem(
                    label(option),
                    onClick = { open = false; onPick(option) },
                    trailingIcon = if (option == selected) {
                        { Icon(Icons.Filled.Check, null, tint = PlatterColors.White, modifier = Modifier.size(18.dp)) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = on,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = PlatterColors.Black,
            checkedTrackColor = PlatterColors.Green,
            uncheckedThumbColor = PlatterColors.Mist,
            uncheckedTrackColor = PlatterColors.Iron,
            uncheckedBorderColor = PlatterColors.Steel,
        ),
    )
}

/** A line of text to type and the pill that keeps it. */
@Composable
private fun AddressField(value: String, onChange: (String) -> Unit, placeholder: String, password: Boolean = false, onSave: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextInput(value, onChange, placeholder, Modifier.width(260.dp), password = password, onEnter = onSave)
        PrimaryPill(t("Save"), onClick = onSave)
    }
}
