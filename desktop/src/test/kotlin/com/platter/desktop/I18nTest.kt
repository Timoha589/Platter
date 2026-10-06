@file:OptIn(ExperimentalTestApi::class)

package com.platter.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.platter.desktop.api.Auth
import com.platter.desktop.api.Credentials
import com.platter.desktop.data.SettingsStore
import com.platter.desktop.i18n.AppLanguage
import com.platter.desktop.i18n.I18n
import com.platter.desktop.i18n.t
import com.platter.desktop.i18n.tn
import com.platter.desktop.ui.PlatterColors
import com.platter.desktop.ui.PlatterTheme
import com.platter.desktop.ui.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The interface in the phone's eight languages: that every word is there, that plurals agree, and that choosing one redraws the window. */
class I18nTest {
    @AfterTest
    fun backToSystemLanguage() = I18n.choose(null)

    /** Every `t("…")` and `tn(n, "one", "other")` in the sources, as the English text a dictionary has to know. */
    private fun keysInTheCode(): Set<String> {
        val keys = mutableSetOf<String>()
        val single = Regex("""(?<![\w.])t\("((?:[^"\\]|\\.)*)"""")
        val plural = Regex("""(?<![\w.])tn\([^"\n]*?,\s*"(?:[^"\\]|\\.)*",\s*"((?:[^"\\]|\\.)*)"""")
        fun unescape(s: String) = s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\$", "$")
        Files.walk(Paths.get("src/main/kotlin")).use { files ->
            files.filter { it.toString().endsWith(".kt") && !it.toString().contains("i18n") }.forEach { file ->
                val text = Files.readString(file)
                single.findAll(text).forEach { keys += unescape(it.groupValues[1]) }
                plural.findAll(text).forEach { keys += unescape(it.groupValues[1]) }
            }
        }
        return keys
    }

    private val translated = AppLanguage.entries.filter { it != AppLanguage.English }

    @Test
    fun `the code has something to translate`() {
        assertTrue(keysInTheCode().size > 300, "the scan found ${keysInTheCode().size} strings")
    }

    @Test
    fun `every string in the code is translated into every language`() {
        val keys = keysInTheCode()
        for (language in translated) {
            val missing = keys.filter { it !in language.words() }
            assertTrue(missing.isEmpty(), "${language.nativeName} lacks: ${missing.joinToString(" | ")}")
        }
    }

    @Test
    fun `no language has words the others do not`() {
        val reference = AppLanguage.Russian.words().keys
        for (language in translated) {
            assertEquals(reference, language.words().keys, "${language.nativeName} differs from Russian")
        }
    }

    @Test
    fun `a translation keeps the placeholders of the text it translates`() {
        val placeholder = Regex("""%[0-9$]*[sd]""")
        for (language in translated) {
            for ((english, words) in language.words()) {
                val want = placeholder.findAll(english).map { it.value }.sorted().toList()
                for (form in words.split('|')) {
                    assertEquals(want, placeholder.findAll(form).map { it.value }.sorted().toList(), "${language.nativeName}: “$english” -> “$form”")
                }
            }
        }
    }

    @Test
    fun `text is in the language chosen and falls back to English`() {
        assertEquals("Settings", t("Settings"))
        I18n.choose("de")
        assertEquals("Einstellungen", t("Settings"))
        assertEquals("Eine Zeile, die nie übersetzt wurde", t("Eine Zeile, die nie übersetzt wurde"))
        I18n.choose("ru")
        assertEquals("Настройки", t("Settings"))
        assertEquals("tim на http://x", t("%s on %s", "tim", "http://x"))
        I18n.choose("no-such-language")
        assertEquals("Settings", t("Settings"), "an unknown tag leaves the system language")
    }

    @Test
    fun `plurals take the form of the language`() {
        fun songs(n: Int) = tn(n, "%d song", "%d songs")
        assertEquals(listOf("0 songs", "1 song", "2 songs"), listOf(0, 1, 2).map(::songs))

        I18n.choose("ru")
        assertEquals(listOf("1 трек", "2 трека", "5 треков", "11 треков", "12 треков", "21 трек", "22 трека", "100 треков", "101 трек"), listOf(1, 2, 5, 11, 12, 21, 22, 100, 101).map(::songs))

        I18n.choose("de")
        assertEquals(listOf("1 Titel", "3 Titel"), listOf(1, 3).map(::songs))
        I18n.choose("fr")
        assertEquals(listOf("0 morceau", "1 morceau", "2 morceaux"), listOf(0, 1, 2).map(::songs))
        I18n.choose("it")
        assertEquals(listOf("1 brano", "2 brani"), listOf(1, 2).map(::songs))
        I18n.choose("pt")
        assertEquals(listOf("0 música", "2 músicas"), listOf(0, 2).map(::songs))
        I18n.choose("ko")
        assertEquals(listOf("1곡", "7곡"), listOf(1, 7).map(::songs))
        I18n.choose("zh")
        assertEquals(listOf("1 首歌曲", "7 首歌曲"), listOf(1, 7).map(::songs))
    }

    @Test
    fun `the language chosen survives a restart`() {
        val store = SettingsStore(Files.createTempDirectory("platter-lang").resolve("settings.json"))
        val first = AppController(store, CoroutineScope(SupervisorJob() + Dispatchers.Main), vlcArgs = listOf("--aout=dummy"))
        first.changeLanguage("ko")
        assertEquals("설정", t("Settings"))
        first.shutdown()

        I18n.choose(null)
        val second = AppController(store, CoroutineScope(SupervisorJob() + Dispatchers.Main), vlcArgs = listOf("--aout=dummy"))
        assertEquals("ko", second.language)
        assertEquals("설정", t("Settings"))
        second.changeLanguage(null)
        assertEquals("Settings", t("Settings"), "null follows the system again")
        second.shutdown()
    }

    // --- the window ------------------------------------------------------------------------------

    private fun app(fake: FakeSubsonic, language: String? = null): AppController {
        val store = SettingsStore(Files.createTempDirectory("platter-lang-ui").resolve("settings.json"))
        store.update {
            withCredentials(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt"))
            this.language = language
        }
        return AppController(store, CoroutineScope(SupervisorJob() + Dispatchers.Main), vlcArgs = listOf("--aout=dummy"))
    }

    private fun ComposeUiTest.step(frames: Int = 12) {
        repeat(frames) {
            Thread.sleep(60)
            mainClock.advanceTimeByFrame()
            waitForIdle()
        }
    }

    private fun ComposeUiTest.open(app: AppController) {
        mainClock.autoAdvance = false
        setContent { PlatterTheme { Box(Modifier.fillMaxSize().background(PlatterColors.Black)) { Shell(app) } } }
        step()
    }

    @Test
    fun `choosing a language in settings redraws the window and keeps the choice`() = FakeSubsonic().use { fake ->
        runDesktopComposeUiTest(1280, 1800) {
            val app = app(fake)
            open(app)
            app.navigate(Screen.Settings)
            step()

            onNodeWithText("Interface").assertExists()
            // The choice is a closed list that shows the language in use; System default is what it starts as.
            onNodeWithText("System default").performClick()
            step()
            onNodeWithText("Deutsch").performClick()
            step()

            assertEquals("de", app.language)
            onNodeWithText("Einstellungen").assertExists()
            onNodeWithText("Oberfläche").assertExists()
            onAllNodesWithText("Settings").assertCountEquals(0)
            // The sidebar and the language row itself follow too.
            onNodeWithText("Sammlung").assertExists()
            onNodeWithText("Deutsch").assertExists()

            app.shutdown()
        }
    }

    @Test
    fun `every language draws the home and settings screens`() = FakeSubsonic().use { fake ->
        val out = File("build/screenshots").apply { mkdirs() }
        for (language in AppLanguage.entries) {
            runDesktopComposeUiTest(1280, 1800) {
                val app = app(fake, language.tag)
                open(app)
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "lang-${language.tag}-home.png"))
                app.navigate(Screen.Settings)
                step()
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "lang-${language.tag}-settings.png"))
                onNodeWithText(t("Settings")).assertExists()
                app.shutdown()
            }
        }
    }
}
