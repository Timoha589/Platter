@file:OptIn(ExperimentalTestApi::class)

package com.platter.desktop

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.platter.desktop.api.Song
import com.platter.desktop.player.FadeMode
import com.platter.desktop.player.PlayQueue
import com.platter.desktop.player.PlayerState
import com.platter.desktop.player.TimelineOverlap
import com.platter.desktop.ui.PlaybackProgressBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlaybackProgressBarTest {
    private fun ComposeUiTest.timelinePixels() = onNodeWithTag("timeline").captureToImage().toAwtImage().let { image ->
        List(image.width) { x -> image.getRGB(x, image.height / 2) }
    }

    @Test
    fun `new beat and fallback markers light up from their starting points`() = runDesktopComposeUiTest(360, 48) {
        mainClock.autoAdvance = false
        val songs = listOf(Song().apply { id = "first" }, Song().apply { id = "second" })
        val state = mutableStateOf(PlayerState(queue = PlayQueue(songs, 0), durationMs = 100_000, positionMs = 10_000))
        setContent { PlaybackProgressBar(state.value, {}, Modifier.fillMaxWidth().testTag("timeline")) }
        val before = timelinePixels()

        val crossfade = TimelineOverlap(20_000L..32_000L, FadeMode.AutoMixFallback)
        val autoMix = TimelineOverlap(60_000L..72_000L, FadeMode.AutoMix)
        runOnIdle { state.value = state.value.copy(incomingOverlap = crossfade, upcomingOverlap = autoMix) }
        mainClock.advanceTimeBy(96)
        val during = timelinePixels()
        mainClock.advanceTimeBy(320)
        val after = timelinePixels()

        for ((left, right) in listOf(0.215f to 0.305f, 0.62f to 0.70f)) {
            val start = (left * after.size).toInt()
            val end = (right * after.size).toInt()
            assertEquals(after[start], during[start])
            assertNotEquals(before[end], during[end], "the full range is visible while the color comes in")
            assertNotEquals(after[end], during[end])
        }

        runOnIdle { state.value = state.value.copy(positionMs = 11_000) }
        mainClock.advanceTimeBy(32)
        val afterTick = timelinePixels()
        assertEquals(after.drop(after.size / 5), afterTick.drop(afterTick.size / 5))

        runOnIdle { state.value = state.value.copy(durationMs = 200_000, upcomingOverlap = null) }
        mainClock.advanceTimeBy(64)
        timelinePixels()
        runOnIdle { state.value = state.value.copy(upcomingOverlap = autoMix.copy(rangeMs = 120_000L..144_000L)) }
        mainClock.advanceTimeBy(64)
        val sample = (after.size * 0.7f).toInt()
        assertEquals(after[sample], timelinePixels()[sample], "a corrected plan keeps its completed reveal")

        runOnIdle { state.value = state.value.copy(queue = state.value.queue.jumpTo(1)) }
        mainClock.advanceTimeBy(64)
        assertNotEquals(after[sample], timelinePixels()[sample], "a new track gets its own reveal")
    }

    @Test
    fun `the AutoMix glint repeats while the fallback crossfade stays blue`() = runDesktopComposeUiTest(360, 48) {
        mainClock.autoAdvance = false
        val playing = mutableStateOf(true)
        val state = PlayerState(
            durationMs = 100_000,
            positionMs = 20_000,
            incomingOverlap = TimelineOverlap(10_000L..30_000L, FadeMode.AutoMixFallback),
            upcomingOverlap = TimelineOverlap(60_000L..69_000L, FadeMode.AutoMix),
        )
        setContent {
            PlaybackProgressBar(state.copy(isPlaying = playing.value), {}, Modifier.fillMaxWidth().testTag("timeline"))
        }
        mainClock.advanceTimeBy(760)
        val early = timelinePixels()
        mainClock.advanceTimeBy(700)
        val late = timelinePixels()
        mainClock.advanceTimeBy(970)
        val resting = timelinePixels()
        mainClock.advanceTimeBy(400)
        assertEquals(resting, timelinePixels())
        mainClock.advanceTimeBy(1_400)
        val nextPass = timelinePixels()
        assertNotEquals(resting, nextPass, "the glint returns after its quiet interval")
        fun sample(frame: List<Int>, fraction: Float) = frame[(frame.size * fraction).toInt()]

        for (frame in listOf(early, late, resting, nextPass)) {
            assertEquals(0xFF579DFF.toInt(), sample(frame, 0.15f))
            assertEquals(0xFF6B82A6.toInt(), sample(frame, 0.25f))
            assertEquals(sample(resting, 0.5f), sample(frame, 0.5f))
        }
        val left = (0.625f * resting.size).toInt()
        val right = (0.664f * resting.size).toInt()
        assertTrue(early[left] != resting[left] && late[left] == resting[left])
        assertTrue(late[right] != resting[right] && early[right] == resting[right])

        runOnIdle { playing.value = false }
        mainClock.advanceTimeBy(1_400)
        assertEquals(resting, timelinePixels())
        runOnIdle { playing.value = true }
        mainClock.advanceTimeBy(900)
        assertNotEquals(resting, timelinePixels(), "resuming playback brings back the glint")
    }
}
