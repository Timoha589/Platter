package com.platter.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.platter.desktop.data.SettingsStore
import com.platter.desktop.ui.PlatterColors
import com.platter.desktop.ui.PlatterTheme
import com.platter.desktop.ui.windowIcons
import com.platter.desktop.ui.tintTitleBar
import com.platter.desktop.ui.Shell
import com.platter.desktop.ui.screens.LoginScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import java.awt.GraphicsEnvironment
import java.awt.Rectangle

/** Smaller than this the layout (sidebar, page, player) has no room, so a remembered size below it is not used. */
private const val MIN_WINDOW_WIDTH = 800
private const val MIN_WINDOW_HEIGHT = 500

/** Whether a window with its top left at [x], [y] can still be grabbed: some of its title bar is on a screen that exists now. */
private fun reachable(x: Int, y: Int, width: Int): Boolean {
    val grip = Rectangle(x, y, width.coerceAtLeast(1), 48)
    return GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { it.defaultConfiguration.bounds.intersects(grip) }
}

@OptIn(FlowPreview::class)
fun main() = application {
    val app = remember { AppController(SettingsStore(), CoroutineScope(SupervisorJob() + Dispatchers.Main)) }
    val window = remember {
        // The window opens where and as big as it was left; a place that is no longer on any screen (a monitor unplugged) is dropped.
        val saved = app.savedWindow
        val size = saved?.let { DpSize(it.width.coerceAtLeast(MIN_WINDOW_WIDTH).dp, it.height.coerceAtLeast(MIN_WINDOW_HEIGHT).dp) } ?: DpSize(1280.dp, 800.dp)
        val position = if (saved?.x != null && saved.y != null && reachable(saved.x, saved.y, saved.width)) {
            WindowPosition.Absolute(saved.x.dp, saved.y.dp)
        } else {
            WindowPosition.PlatformDefault
        }
        androidx.compose.ui.window.WindowState(
            placement = if (saved?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = position,
            size = size,
        )
    }

    // An update ends the program the same way closing the window does, once the installer has been started.
    SideEffect {
        app.onQuit = {
            rememberWindow(app, window.size, window.position, window.placement)
            app.shutdown()
            exitApplication()
        }
    }

    // What the window is, written down a moment after it stops changing. Maximised, only the fact is news: the normal bounds stay.
    LaunchedEffect(window) {
        snapshotFlow { Triple(window.size, window.position, window.placement) }
            .debounce(400)
            .collect { (size, position, placement) -> rememberWindow(app, size, position, placement) }
    }

    Window(
        onCloseRequest = {
            rememberWindow(app, window.size, window.position, window.placement)
            app.shutdown()
            exitApplication()
        },
        title = "Platter",
        state = window,
        // Space plays and pauses, unless a text field has taken the key first; Escape closes a dialog.
        onKeyEvent = { event ->
            when {
                event.type == KeyEventType.KeyDown && event.key == Key.Escape && app.dialog != null -> {
                    app.dialog = null
                    true
                }
                event.type == KeyEventType.KeyDown && event.key == Key.Spacebar && app.client != null -> {
                    app.player.toggle()
                    true
                }
                else -> false
            }
        },
    ) {
        // The title bar takes the colour of the app, once the window is there to be painted.
        LaunchedEffect(Unit) {
            tintTitleBar(this@Window.window)
            this@Window.window.iconImages = windowIcons()
        }
        PlatterTheme {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(PlatterColors.Black)) {
                if (app.client == null) LoginScreen(app) else Shell(app)
            }
        }
    }
}

private fun rememberWindow(app: AppController, size: DpSize, position: WindowPosition, placement: WindowPlacement) {
    if (placement == WindowPlacement.Floating && size.isSpecified && size.width.value >= MIN_WINDOW_WIDTH) {
        val placed = position.isSpecified
        app.saveWindow(
            size.width.value.toInt(), size.height.value.toInt(),
            if (placed) position.x.value.toInt() else null, if (placed) position.y.value.toInt() else null,
            maximized = false,
        )
    } else {
        app.saveWindow(null, null, null, null, maximized = placement == WindowPlacement.Maximized)
    }
}
