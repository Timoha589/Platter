@file:OptIn(ExperimentalTestApi::class)

package com.platter.desktop

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.platter.desktop.api.Song
import com.platter.desktop.player.FadeMode
import com.platter.desktop.player.FadeTransition
import com.platter.desktop.ui.CoverPalette
import com.platter.desktop.ui.rememberPlayerBackground
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerBackgroundTest {
    private val outgoing = CoverPalette(Color.Red, Color.Blue)
    private val incoming = CoverPalette(Color.Green, Color.Magenta)

    @Test
    fun `both AutoMix modes follow the fade clock instead of wall time`() = runDesktopComposeUiTest(100, 100) {
        val fade = mutableStateOf<FadeTransition?>(FadeTransition("outgoing", FadeMode.AutoMix))
        val song = Song().apply { id = "incoming" }
        var shown: CoverPalette? = null
        mainClock.autoAdvance = false
        setContent {
            val background = rememberPlayerBackground(song, 1, incoming, outgoing, fade.value)
            SideEffect { shown = background }
        }
        for (mode in listOf(FadeMode.AutoMix, FadeMode.AutoMixFallback)) {
            runOnIdle { fade.value = FadeTransition("outgoing", mode, 0.25f) }
            mainClock.advanceTimeBy(120)
            runOnIdle { assertEquals(blended(0.25f), shown) }
            mainClock.advanceTimeBy(2_000)
            runOnIdle { assertEquals(blended(0.25f), shown) }

            runOnIdle { fade.value = FadeTransition("outgoing", mode, 0.75f) }
            mainClock.advanceTimeBy(120)
            runOnIdle { assertEquals(blended(0.75f), shown) }
        }
        runOnIdle { fade.value = null }
        mainClock.advanceTimeBy(120)
        runOnIdle { assertEquals(incoming, shown) }
    }

    @Test
    fun `a palette loaded during the fade blends over the remaining progress`() = runDesktopComposeUiTest(100, 100) {
        val fade = mutableStateOf(FadeTransition("outgoing", FadeMode.AutoMixFallback, 0.5f))
        val palette = mutableStateOf<CoverPalette?>(null)
        val song = Song().apply { id = "incoming" }
        var shown: CoverPalette? = null
        mainClock.autoAdvance = false
        setContent {
            val background = rememberPlayerBackground(song, 1, palette.value, outgoing, fade.value)
            SideEffect { shown = background }
        }
        runOnIdle { palette.value = incoming }
        mainClock.advanceTimeBy(120)
        runOnIdle { assertEquals(outgoing, shown) }

        runOnIdle { fade.value = fade.value.copy(progress = 0.75f) }
        mainClock.advanceTimeBy(120)
        runOnIdle { assertEquals(blended(0.5f), shown) }
    }

    private fun blended(fraction: Float) = CoverPalette(
        lerp(outgoing.primary, incoming.primary, fraction), lerp(outgoing.secondary, incoming.secondary, fraction),
    )
}
