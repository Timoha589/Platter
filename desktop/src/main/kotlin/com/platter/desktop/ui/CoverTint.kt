package com.platter.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import coil3.toBitmap
import com.platter.desktop.AppController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Color as AwtColor

/**
 * The colour a page takes from a cover: the cover's own dominant colour, darkened until white text reads on it.
 * Null until the cover has loaded, or when it has none - the page draws its plain background then.
 */
@Composable
fun rememberCoverTint(app: AppController, coverArtId: String?): Color? = rememberFromCover(app, coverArtId, ::dominantTint)

/** Two colours a cover is made of, for a background that is a slope between them: both dark enough for white text. */
data class CoverPalette(val primary: Color, val secondary: Color)

/** [rememberCoverTint]'s sibling for the full-screen player: the cover's two strongest colours. Null until the cover has loaded. */
@Composable
fun rememberCoverPalette(app: AppController, coverArtId: String?): CoverPalette? = rememberFromCover(app, coverArtId, ::coverPalette)

/** Loads the cover small, and gives what [read] makes of it - null until it has loaded, or when it has none. */
@Composable
private fun <T : Any> rememberFromCover(app: AppController, coverArtId: String?, read: (org.jetbrains.skia.Bitmap) -> T): T? {
    val context = LocalPlatformContext.current
    val local = remember(coverArtId, app.downloadsVersion) { app.localCover(coverArtId) }
    val model: Any? = local ?: coverArtId?.let { app.client?.coverArtUrl(it, 64) }
    var result by remember(model) { mutableStateOf<T?>(null) }
    LaunchedEffect(model) {
        if (model == null) return@LaunchedEffect
        val loaded = runCatching {
            SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(model).size(Size(64, 64)).build())
        }.getOrNull()
        if (loaded is SuccessResult) {
            result = withContext(Dispatchers.Default) { runCatching { read(loaded.image.toBitmap()) }.getOrNull() }
        }
    }
    return result
}

private fun dominantTint(bitmap: org.jetbrains.skia.Bitmap): Color {
    val pixels = bitmap.asComposeImageBitmap().toPixelMap()
    var r = 0.0
    var g = 0.0
    var b = 0.0
    var total = 0.0
    for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
        val c = pixels[x, y]
        if (c.alpha < 0.5f) continue
        val high = maxOf(c.red, c.green, c.blue)
        val low = minOf(c.red, c.green, c.blue)
        val saturation = if (high == 0f) 0f else (high - low) / high
        // Vivid, bright pixels say more about a cover than the grey and the black around them.
        val weight = saturation * high + 0.02
        r += c.red * weight
        g += c.green * weight
        b += c.blue * weight
        total += weight
    }
    if (total == 0.0) return PlatterColors.Steel
    val hsb = AwtColor.RGBtoHSB((r / total * 255).toInt(), (g / total * 255).toInt(), (b / total * 255).toInt(), null)
    // Dark enough for white text, light enough to be a colour and not a shadow.
    return Color.hsv(hsb[0] * 360f, hsb[1].coerceAtMost(0.85f), hsb[2].coerceIn(0.35f, 0.6f))
}

/**
 * The pixels are sorted into twelve hues, each counted by how vivid and bright it is. The strongest hue is the first colour;
 * the second is the strongest one at least 60 degrees away from it, or - on a cover of a single hue - the first, turned a little.
 *
 * Black and grey are not colours: a mostly black sleeve has its black in every hue by rounding, and counted, it outvotes the
 * few real colours - which, brought up to a dark-but-visible lightness, turned into a loud olive. Those pixels are left out,
 * and a cover with next to no colour in it gets a neutral grey slope of its own brightness.
 */
internal fun coverPalette(bitmap: org.jetbrains.skia.Bitmap): CoverPalette {
    val pixels = bitmap.asComposeImageBitmap().toPixelMap()
    val buckets = 12
    val weight = DoubleArray(buckets)
    val red = DoubleArray(buckets)
    val green = DoubleArray(buckets)
    val blue = DoubleArray(buckets)
    var greyValue = 0.0
    var greyCount = 0
    var colourCount = 0
    var opaqueCount = 0
    for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
        val c = pixels[x, y]
        if (c.alpha < 0.5f) continue
        val hsb = AwtColor.RGBtoHSB((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt(), null)
        opaqueCount++
        if (hsb[1] < 0.15f || hsb[2] < 0.2f) {
            greyValue += hsb[2]
            greyCount++
            continue
        }
        colourCount++
        val w = (hsb[1] * hsb[2]).toDouble()
        val i = (hsb[0] * buckets).toInt() % buckets
        weight[i] += w
        red[i] += c.red * w
        green[i] += c.green * w
        blue[i] += c.blue * w
    }
    fun mean(i: Int) = AwtColor.RGBtoHSB((red[i] / weight[i] * 255).toInt(), (green[i] / weight[i] * 255).toInt(), (blue[i] / weight[i] * 255).toInt(), null)
    if (opaqueCount == 0) return CoverPalette(PlatterColors.Steel, PlatterColors.Iron)
    if (colourCount < opaqueCount * 0.03) {
        val value = if (greyCount > 0) (greyValue / greyCount).toFloat() else 0.5f
        return CoverPalette(Color.hsv(0f, 0f, value.coerceIn(0.45f, 0.65f)), Color.hsv(0f, 0f, (value * 0.6f).coerceIn(0.25f, 0.4f)))
    }
    val order = (0 until buckets).filter { weight[it] > 0 }.sortedByDescending { weight[it] }
    val first = order.firstOrNull() ?: return CoverPalette(PlatterColors.Steel, PlatterColors.Iron)
    val a = mean(first)
    val apart = order.firstOrNull { other ->
        val gap = Math.abs(other - first).let { minOf(it, buckets - it) }
        gap >= 2 && weight[other] >= weight[first] * 0.12
    }
    val b = apart?.let(::mean) ?: floatArrayOf((a[0] + 0.07f) % 1f, a[1], a[2])
    // The first is the livelier, the second sits darker, so the slope reads as light falling off, not as two stripes.
    return CoverPalette(
        Color.hsv(a[0] * 360f, a[1].coerceAtMost(0.85f), a[2].coerceIn(0.45f, 0.65f)),
        Color.hsv(b[0] * 360f, b[1].coerceAtMost(0.8f), b[2].coerceIn(0.25f, 0.4f)),
    )
}
