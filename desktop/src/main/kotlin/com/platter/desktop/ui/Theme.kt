package com.platter.desktop.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The Spotify design system as the Android app has it: spotify_tokens.xml and
 * DESIGN.md. Dark only, fixed palette, no dynamic colour.
 */
object PlatterColors {
    val Green = Color(0xFF1ED760)
    val Black = Color(0xFF000000)
    val Carbon = Color(0xFF121212)
    val Graphite = Color(0xFF1F1F1F)
    val Smoke = Color(0xFF292929)
    val Iron = Color(0xFF333333)
    val Steel = Color(0xFF535353)
    val Fog = Color(0xFF73777C)
    val Mist = Color(0xFFB3B3B3)
    val Bone = Color(0xFFC5C5C5)
    val White = Color(0xFFFFFFFF)
    val Liked = Color(0xFFE5484D)
    val Error = Color(0xFFB85850)
}

object PlatterShapes {
    /** Every cover and card. */
    val Card = RoundedCornerShape(6.dp)
    val Small = RoundedCornerShape(2.dp)

    /** Buttons and chips are fully round. */
    val Pill = RoundedCornerShape(percent = 50)
}

/** What dialogs, menus and toasts lift off the page with: DESIGN.md's shadow-lg, rgba(0, 0, 0, 0.5) 0 8px 24px. */
fun Modifier.overlayShadow(shape: Shape = PlatterShapes.Card): Modifier =
    shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f))

object PlatterSpacing {
    /** Where a page's content starts, counted from the edge of the panel it is in. */
    val Gutter = 24.dp

    /** How far a hover surface (a card, a row) reaches out beyond the content it holds, so the content sits on the gutter. */
    val Bleed = 12.dp

    /** The narrowest a card's cell in a grid goes: the card, and the bleed it is pushed in by. */
    val CardCell = 180.dp
    val Gap = 12.dp

    /** A song in a list: its row, its cover, and the sidebar's playlists beside them. Big enough to read at a glance. */
    val TrackRowHeight = 64.dp
    val TrackCover = 48.dp
    val SidebarRowHeight = 56.dp
    val SidebarCover = 44.dp
    val Section = 32.dp
}

val Inter = FontFamily(
    Font(resource = "fonts/inter_regular.ttf", weight = FontWeight.Normal),
    Font(resource = "fonts/inter_medium.ttf", weight = FontWeight.Medium),
    Font(resource = "fonts/inter_semi_bold.ttf", weight = FontWeight.SemiBold),
    Font(resource = "fonts/inter_bold.ttf", weight = FontWeight.Bold),
)

private val scheme: ColorScheme = darkColorScheme(
    primary = PlatterColors.White,
    onPrimary = PlatterColors.Black,
    secondary = PlatterColors.Green,
    onSecondary = PlatterColors.Black,
    background = PlatterColors.Black,
    onBackground = PlatterColors.White,
    surface = PlatterColors.Carbon,
    onSurface = PlatterColors.White,
    surfaceVariant = PlatterColors.Graphite,
    onSurfaceVariant = PlatterColors.Mist,
    surfaceContainer = PlatterColors.Graphite,
    surfaceContainerHigh = PlatterColors.Smoke,
    outline = PlatterColors.Iron,
    outlineVariant = PlatterColors.Iron,
    error = PlatterColors.Error,
)

/* Headings never go above 24; titles are 14/600, metadata 12/400 - the same scale the Android app uses. */
private fun style(size: Int, weight: FontWeight, line: Float = 1.33f) =
    TextStyle(fontFamily = Inter, fontSize = size.sp, fontWeight = weight, lineHeight = (size * line).sp)

private val typography = Typography(
    headlineMedium = style(24, FontWeight.Bold, 1.2f),
    titleMedium = style(16, FontWeight.Bold),
    titleSmall = style(14, FontWeight.SemiBold),
    bodyLarge = style(16, FontWeight.Normal),
    bodyMedium = style(14, FontWeight.Normal),
    bodySmall = style(12, FontWeight.Normal),
    labelLarge = style(14, FontWeight.Bold),
    labelMedium = style(12, FontWeight.SemiBold),
    labelSmall = style(11, FontWeight.Normal),
)

@Composable
fun PlatterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = typography,
        shapes = Shapes(small = PlatterShapes.Small, medium = PlatterShapes.Card, large = PlatterShapes.Card),
    ) {
        // Text without a colour of its own takes LocalContentColor, which is black until a Surface sets it.
        Surface(Modifier.fillMaxSize(), color = PlatterColors.Black, contentColor = PlatterColors.White, content = content)
    }
}
