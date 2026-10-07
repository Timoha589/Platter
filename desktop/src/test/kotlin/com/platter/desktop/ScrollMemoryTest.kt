@file:OptIn(ExperimentalTestApi::class)

package com.platter.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.platter.desktop.api.Auth
import com.platter.desktop.api.Credentials
import com.platter.desktop.data.SettingsStore
import com.platter.desktop.ui.PlatterColors
import com.platter.desktop.ui.PlatterTheme
import com.platter.desktop.ui.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Back and Forward put a page back where it was scrolled to, instead of at the top. */
class ScrollMemoryTest {
    private fun app(fake: FakeSubsonic): AppController {
        val store = SettingsStore(Files.createTempDirectory("platter-scroll-test").resolve("settings.json"))
        store.update { withCredentials(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt")) }
        return AppController(store, CoroutineScope(SupervisorJob() + Dispatchers.Main), vlcArgs = listOf("--aout=dummy"))
    }

    private fun ComposeUiTest.step(frames: Int = 10) {
        repeat(frames) {
            Thread.sleep(60)
            mainClock.advanceTimeByFrame()
            waitForIdle()
        }
    }

    @Test
    fun `a scrolled page is where it was left after going forward and back`() = FakeSubsonic().use { fake ->
        // Short, so the end of the settings page is below the fold.
        runDesktopComposeUiTest(1280, 560) {
            val app = app(fake)
            mainClock.autoAdvance = false
            setContent { PlatterTheme { Box(Modifier.fillMaxSize().background(PlatterColors.Black)) { Shell(app) } } }
            step()

            app.navigate(Screen.Settings)
            step()
            val end = onNodeWithText("Open on GitHub")
            runCatching { end.assertIsDisplayed() }.let { assertTrue(it.isFailure, "the test window must hide the end of the page") }

            onRoot().performMouseInput { moveTo(center); scroll(300f) }
            step()
            end.assertIsDisplayed()
            val left = app.scrollMemory["0|"]
            assertNotEquals(null, left)
            assertTrue(left!!.first > 0, "the position is written down")

            // Away to a page on top of it, and back: the end of the settings is still the part in view.
            app.navigate(Screen.Album("a1"))
            step()
            app.back()
            step(20)
            assertEquals(Screen.Settings, app.screen)
            end.assertIsDisplayed()
            assertEquals(left, app.scrollMemory["0|"])

            // A page reached anew starts at the top, and forgets what was left behind it.
            app.navigate(Screen.Library)
            step()
            assertNull(app.scrollMemory["0|"])
            app.shutdown()
        }
    }
}
