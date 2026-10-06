package com.platter.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/** The sizes the icon is drawn for, each on its own: a large picture shrunk by Windows to 24 px is muddy, a drawn one is not. */
private val ICON_SIZES = listOf(16, 20, 24, 32, 40, 48, 64, 96, 128, 256)

/** All of them, for the window: Windows takes the one nearest each place it shows the icon (taskbar, title bar, Alt+Tab). */
fun windowIcons(): List<java.awt.Image> = ICON_SIZES.mapNotNull { n ->
    object {}.javaClass.getResourceAsStream("/icons/platter-$n.png")?.use { javax.imageio.ImageIO.read(it) }
}

/** The picture drawn for the size nearest above [pixels], so what is shown is shrunk as little as it can be. */
fun appIcon(pixels: Int = 128): Painter {
    val n = ICON_SIZES.firstOrNull { it >= pixels } ?: ICON_SIZES.last()
    return BitmapPainter(useResource("icons/platter-$n.png", ::loadImageBitmap))
}

/** The app's icon - the phone's record platter, drawn again for each size - as the sign-in and the top bar show it. */
@Composable
fun PlatterMark(size: Dp, modifier: Modifier = Modifier) {
    val pixels = with(LocalDensity.current) { size.roundToPx() }
    val painter = remember(pixels) { appIcon(pixels) }
    Image(painter, contentDescription = null, modifier = modifier.size(size))
}
