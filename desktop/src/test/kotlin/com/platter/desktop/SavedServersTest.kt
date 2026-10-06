package com.platter.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.platter.desktop.api.Auth
import com.platter.desktop.api.Credentials
import com.platter.desktop.data.Secrets
import com.platter.desktop.data.SettingsStore
import com.platter.desktop.ui.PlatterColors
import com.platter.desktop.ui.PlatterTheme
import com.platter.desktop.ui.screens.LoginScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Servers signed in to are kept, passwords sealed, and are there to pick again after signing out or restarting. */
@OptIn(ExperimentalTestApi::class)
class SavedServersTest {
    private val apps = ArrayList<AppController>()
    private val dir = Files.createTempDirectory("platter-servers-test")

    @AfterTest
    fun tearDown() = apps.forEach { it.shutdown() }

    private fun store() = SettingsStore(dir.resolve("settings.json"))

    private fun open(store: SettingsStore = store()): AppController =
        AppController(store, CoroutineScope(SupervisorJob() + Dispatchers.Main), vlcArgs = listOf("--aout=dummy")).also { apps += it }

    private fun needsDpapi() = assumeTrue(System.getProperty("os.name").startsWith("Windows"), "DPAPI is Windows-only")

    @Test
    fun `signing out keeps the server, and a press signs in again without the password`() = FakeSubsonic().use { fake ->
        needsDpapi()
        val app = open()
        runBlocking { app.signIn(fake.url, "tim", "pw") }
        assertEquals(1, app.servers.size)
        assertEquals("tim", app.servers[0].username)

        app.signOut()
        assertNull(app.client)
        assertEquals(1, app.servers.size, "the account stays after signing out")

        assertTrue(runBlocking { app.signInTo(app.servers[0]) })
        assertEquals("tim", app.client?.credentials?.username)
        assertEquals(1, app.servers.size, "signing in to a kept server does not add another")
    }

    @Test
    fun `the list is there after a restart, even signed out`() = FakeSubsonic().use { fake ->
        needsDpapi()
        val store = store()
        val first = open(store)
        runBlocking { first.signIn(fake.url, "tim", "pw", name = "Home") }
        first.signOut()
        first.shutdown()

        val second = open(store)
        assertNull(second.client)
        assertEquals(listOf("Home"), second.servers.map { it.name })
        assertTrue(runBlocking { second.signInTo(second.servers[0]) })
    }

    @Test
    fun `several servers and accounts are kept apart, and one can be forgotten`() = FakeSubsonic().use { fake ->
        needsDpapi()
        val app = open()
        runBlocking { app.signIn(fake.url, "tim", "pw") }
        app.signOut()
        runBlocking { app.signIn(fake.url, "anna", "other") }
        app.signOut()
        assertEquals(listOf("tim", "anna"), app.servers.map { it.username })

        // The same account again is the same entry, with its name kept unless a new one is given.
        runBlocking { app.signIn(fake.url, "TIM", "pw", name = "Tim at home") }
        assertEquals(2, app.servers.size)
        assertEquals("Tim at home", app.servers.single { it.username.equals("tim", true) }.name)

        app.signOut()
        app.forgetServer(app.servers.first { it.username == "anna" }.id)
        assertEquals(1, app.servers.size)
        assertTrue(app.servers.single().username.equals("tim", ignoreCase = true))
    }

    @Test
    fun `a listener already signed in finds that account in the list`() = FakeSubsonic().use { fake ->
        val store = store()
        store.update {
            withCredentials(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt"))
            sealedPassword = Secrets.seal("pw")
            localAddress = "http://192.168.1.5:4533"
        }
        val app = open(store)
        assertNotNull(app.client, "still signed in")
        val kept = app.servers.single()
        assertEquals("tim", kept.username)
        assertEquals("http://192.168.1.5:4533", kept.localAddress)
        assertEquals(kept.id, store.load().activeServer)

        // Migrating once is enough: the next start reads the list as it is.
        val again = open(store)
        assertEquals(listOf(kept.id), again.servers.map { it.id })
    }

    @Test
    fun `the home address belongs to its server`() = FakeSubsonic().use { fake ->
        needsDpapi()
        val app = open()
        runBlocking { app.signIn(fake.url, "tim", "pw") }
        app.changeLocalAddress("http://192.168.1.5:4533")
        app.signOut()
        assertNull(app.localAddress)

        runBlocking { app.signIn(fake.url, "anna", "other") }
        assertNull(app.localAddress, "another account does not inherit it")
        app.signOut()

        assertTrue(runBlocking { app.signInTo(app.servers.first { it.username == "tim" }) })
        assertEquals("http://192.168.1.5:4533", app.localAddress)
    }

    @Test
    fun `a password that cannot be read is asked for instead of failing`() = FakeSubsonic().use { fake ->
        val store = store()
        store.update {
            servers = listOf(com.platter.desktop.data.SavedServer("one", null, fake.url, "tim", null, null))
        }
        val app = open(store)
        assertEquals(false, runBlocking { app.signInTo(app.servers[0]) })
        assertNull(app.client)
    }

    @Test
    fun `the sign-in screen lists kept servers, and pressing one signs in`() = FakeSubsonic().use { fake ->
        needsDpapi()
        val store = store()
        val first = open(store)
        runBlocking { first.signIn(fake.url, "tim", "pw", name = "Home server") }
        first.signOut()
        first.shutdown()

        runDesktopComposeUiTest(1280, 800) {
            val app = open(store)
            mainClock.autoAdvance = false
            setContent { PlatterTheme { Box(Modifier.fillMaxSize().background(PlatterColors.Black)) { LoginScreen(app) } } }
            step()
            onNodeWithText("Choose a server").assertIsDisplayed()
            onNodeWithText("Home server").assertIsDisplayed()
            onNodeWithText("Add a server").assertIsDisplayed()

            onNodeWithText("Home server").performClick()
            repeat(40) {
                step(1)
                if (app.client != null) return@repeat
            }
            waitUntil("the sign-in") { app.client != null }
            assertEquals("tim", app.client?.credentials?.username)
        }
    }

    @Test
    fun `a first start offers Timoha Premium, which asks for a name and a password and nothing else`() {
        val app = open()
        runDesktopComposeUiTest(1280, 800) {
            mainClock.autoAdvance = false
            setContent { PlatterTheme { Box(Modifier.fillMaxSize().background(PlatterColors.Black)) { LoginScreen(app) } } }
            step()
            onNodeWithTag("premium-offer").assertIsDisplayed()
            onNodeWithText("Timoha Premium").assertIsDisplayed()
            onNodeWithText("Server address").assertDoesNotExist()

            onNodeWithTag("premium-offer").performClick()
            step()
            onNodeWithText("Sign in with your music.timoha.top account").assertIsDisplayed()
            onNodeWithText("Username").assertIsDisplayed()
            onNodeWithText("Password").assertIsDisplayed()
            onNodeWithText("Server address").assertDoesNotExist()

            onNodeWithText("Back").performClick()
            step()
            onNodeWithTag("premium-offer").assertIsDisplayed()
        }
    }

    @Test
    fun `once Timoha Premium is among the servers its offer is gone`() {
        val store = store()
        store.update { servers = listOf(com.platter.desktop.data.SavedServer("p", "Timoha Premium", "https://music.timoha.top", "tim", null, null)) }
        val app = open(store)
        runDesktopComposeUiTest(1280, 800) {
            mainClock.autoAdvance = false
            setContent { PlatterTheme { Box(Modifier.fillMaxSize().background(PlatterColors.Black)) { LoginScreen(app) } } }
            step()
            onNodeWithTag("premium-offer").assertDoesNotExist()
            onNodeWithText("tim · music.timoha.top").assertIsDisplayed()
        }
    }

    @Test
    fun `the cross forgets a server from the sign-in screen`() = FakeSubsonic().use { fake ->
        needsDpapi()
        val store = store()
        val first = open(store)
        runBlocking { first.signIn(fake.url, "tim", "pw") }
        runBlocking { first.signOut(); first.signIn(fake.url, "anna", "other") }
        first.signOut()
        first.shutdown()

        runDesktopComposeUiTest(1280, 800) {
            val app = open(store)
            mainClock.autoAdvance = false
            setContent { PlatterTheme { Box(Modifier.fillMaxSize().background(PlatterColors.Black)) { LoginScreen(app) } } }
            step()
            assertEquals(2, app.servers.size)
            onAllNodesWithContentDescription("Remove server")[0].performClick()
            step()
            assertEquals(1, app.servers.size)
        }
    }

    private fun ComposeUiTest.step(frames: Int = 6) {
        repeat(frames) {
            Thread.sleep(60)
            mainClock.advanceTimeByFrame()
            waitForIdle()
        }
    }

    private fun waitUntil(what: String, condition: () -> Boolean) = com.platter.desktop.waitUntil(what, condition = condition)
}
