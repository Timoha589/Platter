@file:OptIn(ExperimentalTestApi::class)

package com.platter.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.platter.desktop.api.Song
import com.platter.desktop.data.SettingsStore
import com.platter.desktop.player.FadeMode
import com.platter.desktop.player.FadeTransition
import com.platter.desktop.ui.CoverStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CoverStageTest {
    @Test
    fun `a scaled transition keeps both ends of the fade clear of stray artwork`() = runDesktopComposeUiTest(860, 450) {
        val store = SettingsStore(Files.createTempDirectory("platter-cover").resolve("settings.json"))
        val app = AppController(store, CoroutineScope(SupervisorJob() + Dispatchers.Main), vlcArgs = listOf("--aout=dummy"))
        val progress = mutableStateOf(0f)
        val song = Song().apply { id = "incoming" }
        val stageWidth = 480
        val scale = 0.83f // The full player also scales the cover when it makes room for lyrics.
        val shift = 0.35f
        val padding = 80
        val background = Color.White
        try {
            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.fillMaxSize().background(background), contentAlignment = Alignment.Center) {
                        CoverStage(
                            app, song, FadeTransition(null, FadeMode.AutoMixFallback, progress.value),
                            forward = true, side = 320.dp, shadow = 32.dp, stageWidth = stageWidth.dp,
                            modifier = Modifier.size(stageWidth.dp, 368.dp).graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = shift
                            },
                        )
                    }
                }
            }
            for (fraction in listOf(0.06f, 0.11f, 0.16f, 0.2f, 0.4f, 0.72f, 0.8f, 0.84f, 0.89f, 0.94f)) {
                runOnIdle { progress.value = fraction }
                if (fraction == 0.2f) {
                    val frames = mutableSetOf<List<Int>>()
                    repeat(5) {
                        mainClock.advanceTimeByFrame()
                        val frame = onRoot().captureToImage().toAwtImage()
                        frames += List(frame.width) { x -> frame.getRGB(x, frame.height / 2) }
                    }
                    assertTrue(frames.size >= 3, "one audio tick should produce intermediate motion frames")
                }
                mainClock.advanceTimeBy(96)
                val frame = onRoot().captureToImage().toAwtImage()
                val center = frame.width / 2f + shift
                val amount = minOf(1f, fraction * 5, (1f - fraction) * 5)
                val visibleHalfWidth = (stageWidth / 2f + padding * (1f - amount)) * scale
                val left = floor(center - visibleHalfWidth).toInt()
                val right = ceil(center + visibleHalfWidth).toInt()
                val row = frame.height / 2
                assertNotEquals(background.toArgb(), frame.getRGB(frame.width / 2, row), "artwork should be visible at $fraction")
                for (x in (0 until left) + (right until frame.width)) {
                    assertEquals(background.toArgb(), frame.getRGB(x, row), "stray cover pixel at x=$x, progress=$fraction")
                }
            }
        } finally {
            app.shutdown()
        }
    }
}
