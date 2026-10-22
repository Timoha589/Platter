@file:OptIn(ExperimentalTestApi::class)

package com.platter.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.platter.desktop.ui.PlatterTheme
import com.platter.desktop.ui.RefreshButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The refresh arrows turn when pressed, however quickly the answer comes back, and come to rest where they began. */
class RefreshSpinTest {
    /**
     * The arrows' shape: which pixels the glyph covers. Colour is left out - a press leaves the pointer over them, which
     * whitens the glyph and lays the button's dark circle behind it - so only the angle tells two pictures apart.
     */
    private fun ComposeUiTest.picture(): List<Boolean> {
        waitForIdle()
        val image = onNodeWithTag("refresh").captureToImage().toAwtImage()
        return (0 until image.height).flatMap { y ->
            (0 until image.width).map { x ->
                val rgb = image.getRGB(x, y)
                (rgb shr 16 and 0xff) + (rgb shr 8 and 0xff) + (rgb and 0xff) > 3 * 0x80
            }
        }
    }

    /**
     * How much of the glyph is somewhere else: the share of the pixels either picture covers that only one does. Grey on
     * black and white on the dark circle smooth their edges differently, so the same angle still differs by its rim.
     */
    private fun moved(a: List<Boolean>, b: List<Boolean>): Double {
        val either = a.indices.count { a[it] || b[it] }
        return a.indices.count { a[it] != b[it] }.toDouble() / either.coerceAtLeast(1)
    }

    private fun turned(a: List<Boolean>, b: List<Boolean>) = moved(a, b) > 0.3

    private fun atRest(a: List<Boolean>, b: List<Boolean>) = moved(a, b) < 0.15

    private fun ComposeUiTest.advance(ms: Long) {
        mainClock.advanceTimeBy(ms)
        waitForIdle()
    }

    @Test
    fun `a press turns the arrows a whole turn even when the answer is instant`() = runDesktopComposeUiTest(200, 200) {
        var presses = 0
        mainClock.autoAdvance = false
        setContent { PlatterTheme { RefreshButton(refreshing = false) { presses++ } } }
        val rest = picture()

        onNodeWithTag("refresh").performClick()
        assertEquals(1, presses)
        advance(300)
        picture().let { assertTrue(turned(rest, it), "mid-turn the arrows are drawn turned (moved ${moved(rest, it)})") }

        // A second press while turning is not a second refresh.
        onNodeWithTag("refresh").performClick()
        assertEquals(1, presses)

        advance(1_000)
        picture().let { assertTrue(atRest(rest, it), "after the turn they are back where they started (moved ${moved(rest, it)})") }
    }

    @Test
    fun `the arrows keep turning while the answer is on its way`() = runDesktopComposeUiTest(200, 200) {
        var refreshing by mutableStateOf(false)
        mainClock.autoAdvance = false
        setContent { PlatterTheme { RefreshButton(refreshing) { refreshing = true } } }
        val rest = picture()

        onNodeWithTag("refresh").performClick()
        advance(800 * 2 + 300)
        picture().let { assertTrue(turned(rest, it), "still turning after two turns, since the answer has not come (moved ${moved(rest, it)})") }

        refreshing = false
        advance(1_000)
        picture().let { assertTrue(atRest(rest, it), "the answer in, the turn ends where it began (moved ${moved(rest, it)})") }
    }
}
