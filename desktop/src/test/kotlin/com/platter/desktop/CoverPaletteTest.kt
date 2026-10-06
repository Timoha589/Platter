package com.platter.desktop

import androidx.compose.ui.graphics.Color
import com.platter.desktop.ui.coverPalette
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.IRect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** The two colours the full-screen player's background is a slope between. */
class CoverPaletteTest {
    private fun bitmap(vararg halves: Int): Bitmap {
        val b = Bitmap()
        b.allocN32Pixels(16, 16)
        val width = 16 / halves.size
        halves.forEachIndexed { i, colour -> b.erase(colour, IRect.makeXYWH(i * width, 0, width, 16)) }
        return b
    }

    private fun hue(c: Color): Float {
        val hsb = java.awt.Color.RGBtoHSB((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt(), null)
        return hsb[0] * 360f
    }

    private fun brightness(c: Color) = maxOf(c.red, c.green, c.blue)

    private fun apart(a: Float, b: Float) = abs(a - b).let { minOf(it, 360f - it) }

    @Test
    fun `a cover of two colours gives both of them`() {
        val palette = coverPalette(bitmap(0xFFD02020.toInt(), 0xFF2040D0.toInt()))
        assertTrue(apart(hue(palette.primary), hue(palette.secondary)) > 90f, "red and blue: ${hue(palette.primary)} / ${hue(palette.secondary)}")
    }

    @Test
    fun `a cover of one colour gives a second one close to it`() {
        val palette = coverPalette(bitmap(0xFF20A040.toInt()))
        val gap = apart(hue(palette.primary), hue(palette.secondary))
        assertTrue(gap in 10f..60f, "the same green, turned a little: $gap")
    }

    @Test
    fun `both stay dark enough for white text, and the first is the livelier`() {
        for (colours in listOf(intArrayOf(0xFFFFFF00.toInt()), intArrayOf(0xFFFFFFFF.toInt()), intArrayOf(0xFF00FFFF.toInt(), 0xFFFF00FF.toInt()))) {
            val palette = coverPalette(bitmap(*colours))
            assertTrue(brightness(palette.primary) <= 0.66f, "primary ${palette.primary}")
            assertTrue(brightness(palette.secondary) <= 0.41f, "secondary ${palette.secondary}")
            assertTrue(brightness(palette.primary) >= brightness(palette.secondary), "primary is the brighter")
        }
    }
}
