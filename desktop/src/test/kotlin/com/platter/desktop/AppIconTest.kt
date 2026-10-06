package com.platter.desktop

import com.platter.desktop.ui.windowIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppIconTest {
    @Test
    fun `the window has an icon drawn for each size, and each is square, round and green at its heart`() {
        val icons = windowIcons()
        assertEquals(listOf(16, 20, 24, 32, 40, 48, 64, 96, 128, 256), icons.map { it.getWidth(null) })
        icons.forEach { assertEquals(it.getWidth(null), it.getHeight(null)) }
        val image = icons.last() as java.awt.image.BufferedImage
        // The corner is empty, and a point between the grooves and the rim is the green of the disc.
        assertEquals(0, image.getRGB(2, 2) ushr 24, "transparent corner")
        val green = image.getRGB(128, (256 * 0.5 - 256 * 0.44).toInt()) and 0xFFFFFF
        assertTrue(green == 0x1ED760, "disc green: ${green.toString(16)}")
    }
}
