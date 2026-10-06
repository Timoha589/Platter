package com.platter.desktop

import com.platter.desktop.data.SettingsStore
import com.platter.desktop.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Finding a newer release, fetching its installer whole, and handing it over - with no GitHub and no installer run. */
class UpdaterTest {
    private val server = MockWebServer()
    private val dir: Path = Files.createTempDirectory("platter-update-test")
    private val installer = "pretend this is an msi".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(installer).joinToString("") { "%02x".format(it) }

    @Volatile
    private var releases = ""

    @Volatile
    private var served = 0
    private val apps = ArrayList<AppController>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/releases") -> MockResponse().setBody(releases)
                request.path == "/Platter-1.1.0.msi" -> {
                    served++
                    MockResponse().setBody(okio.Buffer().write(installer))
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        apps.forEach { it.shutdown() }
        server.shutdown()
    }

    private fun release(
        tag: String,
        vararg assets: String,
        draft: Boolean = false,
        pre: Boolean = false,
        size: Long = installer.size.toLong(),
        digest: String = "sha256:$sha",
    ): String {
        val list = assets.joinToString(",") {
            """{"name":"$it","browser_download_url":"${server.url("/$it")}","size":$size,"digest":"$digest"}"""
        }
        return """{"tag_name":"$tag","body":"Notes for $tag","draft":$draft,"prerelease":$pre,"assets":[$list]}"""
    }

    private fun updater(current: String? = "1.0.0", launch: (Path, Path) -> Unit = { _, _ -> }) =
        Updater(dir, current, server.url("/releases").toString(), launch = launch)

    private fun open(updater: Updater, store: SettingsStore = SettingsStore(dir.resolve("settings.json"))) =
        AppController(store, CoroutineScope(SupervisorJob() + Dispatchers.Default), vlcArgs = listOf("--aout=dummy"), updater = updater).also { apps += it }

    @Test
    fun `the newest release that carries an msi and is newer than this build is offered`() = runBlocking {
        releases = "[" + listOf(
            release("v1.3.0", "Platter-1.3.0.apk"), // the phone's only: no installer
            release("v1.2.0", "Platter-1.2.0.msi", draft = true),
            release("v1.2.5", "Platter-1.2.5.msi", pre = true),
            release("desktop-v1.1.0", "Platter-1.1.0.msi", "app.apk"),
            release("v0.9.0", "Platter-0.9.0.msi"),
        ).joinToString(",") + "]"

        val found = assertNotNull(updater().newer())
        assertEquals("1.1.0", found.version)
        assertEquals("Notes for desktop-v1.1.0", found.notes)
        assertTrue(found.url.endsWith("/Platter-1.1.0.msi"))
        assertEquals(sha, found.sha256)
    }

    @Test
    fun `nothing is offered when this build is current, when there is no release, or when it runs from the sources`() = runBlocking {
        releases = "[" + release("v1.0.0", "Platter-1.0.0.msi") + "]"
        assertNull(updater("1.0.0").newer())
        assertNull(updater("1.0").newer(), "1.0 is 1.0.0")
        assertNull(updater(current = null).newer())

        releases = "[]"
        assertNull(updater().newer())
    }

    @Test
    fun `versions compare by number, not by text`() {
        assertTrue(Updater.compare("1.10.0", "1.9.0") > 0)
        assertEquals(0, Updater.compare("v1.2", "1.2.0"))
        assertTrue(Updater.compare("2.0.0-beta", "1.9.9") > 0)
        assertEquals("1.2.3", Updater.versionOf("desktop-v1.2.3"))
        assertNull(Updater.versionOf("nightly"))
    }

    @Test
    fun `a failing answer from GitHub is an error, not a silent nothing`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(403)
        }
        assertFailsWith<java.io.IOException> { runBlocking { updater().newer() } }
    }

    @Test
    fun `the installer is downloaded whole, with progress, and reused on the next try`() = runBlocking {
        releases = "[" + release("v1.1.0", "Platter-1.1.0.msi") + "]"
        val u = updater()
        val info = assertNotNull(u.newer())

        val seen = ArrayList<Float>()
        val file = u.download(info) { seen += it }
        assertEquals(installer.toList(), Files.readAllBytes(file).toList())
        assertEquals(1f, seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(1, served)

        u.download(info)
        assertEquals(1, served, "a whole copy is not fetched again")
    }

    @Test
    fun `a damaged download is thrown away`() = runBlocking {
        releases = "[" + release("v1.1.0", "Platter-1.1.0.msi", digest = "sha256:" + "0".repeat(64)) + "]"
        val u = updater()
        val info = assertNotNull(u.newer())
        val error = assertFailsWith<java.io.IOException> { u.download(info) }
        assertEquals(Updater.CHECKSUM, error.message)
        assertTrue(Files.list(dir).use { it.toList() }.isEmpty(), "nothing is left behind")

        releases = "[" + release("v1.1.0", "Platter-1.1.0.msi", size = 5) + "]"
        val short = assertNotNull(u.newer())
        assertEquals(Updater.INCOMPLETE, assertFailsWith<java.io.IOException> { u.download(short) }.message)
    }

    @Test
    fun `pressing Update downloads, starts the installer and quits`() {
        releases = "[" + release("v1.1.0", "Platter-1.1.0.msi") + "]"
        var started: Path? = null // written on the update thread, read after quit is seen
        val quit = java.util.concurrent.atomic.AtomicBoolean(false)
        val app = open(updater { msi, _ -> started = msi })
        app.onQuit = { quit.set(true) }

        // The look at start has found it and put the offer up.
        waitFor { app.updateOffer != null }
        assertEquals(AppDialog.Update, app.dialog)
        assertEquals("1.1.0", app.updateOffer?.version)

        app.installUpdate()
        waitFor { quit.get() }
        assertEquals("Platter-1.1.0.msi", started?.fileName.toString())
        assertEquals(UpdateState.Installing, app.updateState)
    }

    @Test
    fun `Later keeps the offer away for a day, and a manual look still finds it`() {
        releases = "[" + release("v1.1.0", "Platter-1.1.0.msi") + "]"
        val store = SettingsStore(dir.resolve("settings.json"))

        val first = open(updater(), store)
        waitFor { first.dialog == AppDialog.Update }
        first.postponeUpdate()
        assertNull(first.dialog)
        assertTrue(store.load().nextUpdateCheck > System.currentTimeMillis())

        val second = open(updater(), store)
        Thread.sleep(400)
        assertNull(second.dialog, "quiet until tomorrow")

        second.checkForUpdate(manual = true)
        waitFor { second.dialog == AppDialog.Update }
    }

    @Test
    fun `a download that fails leaves the dialog able to try again`() {
        releases = "[" + release("v1.1.0", "Platter-1.1.0.msi", digest = "sha256:" + "0".repeat(64)) + "]"
        val app = open(updater())
        waitFor { app.updateOffer != null }
        app.installUpdate()
        waitFor { app.updateState is UpdateState.Failed }
        assertEquals(Updater.CHECKSUM, (app.updateState as UpdateState.Failed).message)
    }

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(25)
        assertTrue(what(), "timed out")
    }
}
