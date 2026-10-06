package com.platter.desktop

import com.platter.desktop.api.Auth
import com.platter.desktop.api.Credentials
import com.platter.desktop.api.SubsonicClient
import com.platter.desktop.data.Secrets
import com.platter.desktop.data.SettingsStore
import com.platter.desktop.player.PlayerController
import com.platter.desktop.wave.WaveClient
import com.platter.desktop.wave.WaveController
import com.platter.desktop.wave.WaveEvent
import com.platter.desktop.wave.WaveResult
import com.platter.desktop.wave.WaveState
import com.platter.desktop.wave.NetworkAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

fun waitUntil(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (condition()) return
        Thread.sleep(50)
    }
    fail("Timed out waiting for $what")
}

class WaveTest {
    private val auth = mapOf("u" to "tim", "t" to "tok", "s" to "salt", "v" to "1.16.1", "c" to "Platter", "f" to "json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun wave(fake: FakeSubsonic, password: String? = "sesame") = WaveClient(fake.waveUrl, auth, password = { password })

    // --- addresses ------------------------------------------------------------

    @Test
    fun `a password is only sent where it cannot be read on the way`() {
        assertTrue(NetworkAddress.isPrivateOrEncrypted("https://music.example.com/".toHttpUrl()))
        assertTrue(NetworkAddress.isPrivateOrEncrypted("http://192.168.1.5:4533/".toHttpUrl()))
        assertTrue(NetworkAddress.isPrivateOrEncrypted("http://10.0.0.2/".toHttpUrl()))
        assertTrue(NetworkAddress.isPrivateOrEncrypted("http://172.20.0.1/".toHttpUrl()))
        assertTrue(NetworkAddress.isPrivateOrEncrypted("http://localhost:8765/".toHttpUrl()))
        assertTrue(NetworkAddress.isPrivateOrEncrypted("http://nas.local/".toHttpUrl()))
        assertFalse(NetworkAddress.isPrivateOrEncrypted("http://music.example.com/".toHttpUrl()))
        assertFalse(NetworkAddress.isPrivateOrEncrypted("http://172.32.0.1/".toHttpUrl()))
        assertFalse(NetworkAddress.isPrivateOrEncrypted("http://8.8.8.8/".toHttpUrl()))
    }

    @Test
    fun `the wave lives at the servers own address with wave, unless set by hand`() {
        assertEquals("https://music.timoha.top/wave/", WaveClient.baseUrlFor("music.timoha.top", null))
        assertEquals("http://host:4533/wave/", WaveClient.baseUrlFor("http://host:4533/", ""))
        assertEquals("http://nas:8765/wave/", WaveClient.baseUrlFor("https://elsewhere", "http://nas:8765/wave"))
    }

    @Test
    fun `the wave is offered on timoha premium or where an address was set by hand`() {
        assertTrue(WaveClient.isAvailable("https://music.timoha.top", null))
        assertTrue(WaveClient.isAvailable("music.timoha.top/", ""))
        assertFalse(WaveClient.isAvailable("https://navidrome.example.com", null))
        assertTrue(WaveClient.isAvailable("https://navidrome.example.com", "http://nas:8765/wave/"))
        assertFalse(WaveClient.isAvailable(null, null))
    }

    // --- the client -----------------------------------------------------------

    @Test
    fun `start gets a batch, signed, with the password hex-encoded in a header`() = FakeSubsonic().use { fake ->
        val result = runBlocking { wave(fake, "pässword").start() }

        val batch = (result as WaveResult.Ok).batch
        assertEquals("sess1", batch.session)
        assertEquals("electronic", batch.vibe?.label)
        assertEquals(5, batch.songs().size)
        assertEquals(128.0, batch.tracks!!.first().tempo)

        val request = fake.requests.first { it.pathSegments == listOf("wave", "start") }
        assertEquals("tim", request.queryParameter("u"))
        assertEquals("tok", request.queryParameter("t"))
        assertEquals("salt", request.queryParameter("s"))
        // UTF-8 bytes of the password, hex, behind "enc:" - any password fits in a header.
        assertEquals("enc:70c3a47373776f7264", fake.wavePasswords.single())
    }

    @Test
    fun `no password header without a password`() = FakeSubsonic().use { fake ->
        runBlocking { wave(fake, password = null).start() }
        assertNull(fake.wavePasswords.single())
    }

    @Test
    fun `failures are told apart`() {
        val client = WaveClient("http://localhost:1/wave/", auth, { null })
        assertEquals(WaveResult.Reason.NOT_CONFIGURED, client.failure(403, """{"detail":{"error":"user_not_configured"}}""").reason)
        assertEquals(WaveResult.Reason.NOT_CONFIGURED, client.failure(400, """{"detail":"user_not_configured"}""").reason)
        assertEquals(WaveResult.Reason.UNAVAILABLE, client.failure(503, """{"detail":"audiomuse_down"}""").reason)
        assertEquals("audiomuse_down", client.failure(503, """{"detail":"audiomuse_down"}""").detail)
        assertEquals(WaveResult.Reason.UNAVAILABLE, client.failure(200, """{"tracks":[]}""").reason)
        assertEquals(WaveResult.Reason.UNREACHABLE, client.failure(404, "<html>nginx</html>").reason)
        assertEquals("HTTP 404", client.failure(404, "").detail)
    }

    @Test
    fun `an address nobody answers at is unreachable, not a crash`() {
        val result = runBlocking { WaveClient("http://127.0.0.1:1/wave/", auth, { null }).start() }
        assertEquals(WaveResult.Reason.UNREACHABLE, (result as WaveResult.Failed).reason)
    }

    @Test
    fun `lyrics search finds songs by their words`() = FakeSubsonic().use { fake ->
        val found = runBlocking { wave(fake).searchLyrics("come together", 10) }!!
        assertEquals(false, found.indexing)
        assertEquals("come together / right now", found.results!!.single().snippet)
        assertEquals("s2-1", found.results!!.single().song?.id)
        assertEquals("come together", fake.requests.last { it.pathSegments.last() == "search" }.queryParameter("q"))
    }

    @Test
    fun `lyrics search says null when the service is not there`() = FakeSubsonic().use { fake ->
        assertNull(runBlocking { WaveClient(fake.url + "nowhere/", auth, { null }).searchLyrics("x", 10) })
    }

    // --- feedback -------------------------------------------------------------


    private fun stateWith(vararg ids: String) = WaveState().apply {
        val batch = com.google.gson.Gson().fromJson(
            """{"session":"s","tracks":[${ids.joinToString(",") { """{"id":"$it"}""" }}]}""",
            com.platter.desktop.wave.WaveBatch::class.java,
        )
        begin(batch)
    }

    @Test
    fun `a track left early is a skip, after half or four minutes a completion, going back is nothing`() {
        val state = stateWith("t1")
        val player = PlayerController(scope, { null }, { _, _, _ -> }, vlcArgs = listOf("--aout=dummy"))
        val c = WaveController(scope, player, state, { null })
        val min = 60_000L

        c.onLeft("t1", 20_000, 200_000, com.platter.desktop.player.Leave.Forward)
        c.onLeft("t1", 100_000, 200_000, com.platter.desktop.player.Leave.Forward)
        c.onLeft("t1", 4 * min, 30 * min, com.platter.desktop.player.Leave.Forward)
        c.onLeft("t1", 3 * min, 30 * min, com.platter.desktop.player.Leave.Forward)
        c.onLeft("t1", 200_000, 200_000, com.platter.desktop.player.Leave.Auto)
        c.onLeft("t1", 10_000, 200_000, com.platter.desktop.player.Leave.Back)
        c.onLeft("not-a-wave-track", 1, 2, com.platter.desktop.player.Leave.Forward)

        player.release()
        val events = state.drainEvents()
        assertEquals(listOf("skip", "complete", "complete", "skip", "complete"), events.map { it.type })
        assertEquals(20_000, events.first().positionMs)
        assertEquals(200_000, events.first().durationMs)
        assertTrue(state.drainEvents().isEmpty())
    }

    @Test
    fun `feedback that could not be sent is put back in front of newer feedback`() {
        val state = stateWith("a", "b")
        state.addEvent(WaveEvent("a", WaveEvent.SKIP))
        val taken = state.drainEvents()
        state.addEvent(WaveEvent("b", WaveEvent.COMPLETE))
        state.restoreEvents(taken)
        assertEquals(listOf("a", "b"), state.drainEvents().map { it.id })
    }

    // --- the wave playing, end to end -------------------------------------------

    @Test
    fun `the wave tops itself up with the feedback so far and retries after a failure`() = FakeSubsonic().use { fake ->
        var clock = 0L
        val creds = Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt")
        val client = SubsonicClient(creds)
        val waveClient = WaveClient(fake.waveUrl, client.authParams, { "sesame" })
        val state = WaveState()
        val player = PlayerController(scope, { client.streamUrl(it.id!!) }, { _, _, _ -> }, vlcArgs = listOf("--aout=dummy"))
        WaveController(scope, player, state, { waveClient }, now = { clock })
        try {
            val batch = (runBlocking { waveClient.start() } as WaveResult.Ok).batch
            state.begin(batch)
            fake.waveNextFails = true
            player.play(batch.songs())

            // The first track runs out; three are left, so the wave asks for more - and the server cannot.
            waitUntil("the first top-up attempt") { fake.waveNextBodies.isNotEmpty() }
            val first = fake.waveNextBodies.first()
            assertTrue("\"session\":\"sess1\"" in first, first)
            assertTrue("\"id\":\"w0-1\"" in first && "\"type\":\"complete\"" in first, first)
            assertTrue("\"duration_ms\":4000" in first || "duration_ms" in first, first)
            assertEquals(5, player.state.value.queue.songs.size)

            // Time passes and the server recovers: the same feedback is sent again, and the queue grows.
            fake.waveNextFails = false
            clock += 120_000
            waitUntil("the retry to bring a second batch") { player.state.value.queue.songs.size == 10 }
            assertTrue(fake.waveNextBodies.size >= 2)
            assertTrue("\"id\":\"w0-1\"" in fake.waveNextBodies[1], "the feedback that failed to send went again: ${fake.waveNextBodies[1]}")
            assertTrue(player.state.value.queue.songs.last().id!!.startsWith("w1-"))
            assertTrue(state.isWaveTrack("w1-3"))
            assertEquals("jazz", state.vibe.value)
        } finally {
            player.release()
        }
    }

    @Test
    fun `tapping the card starts the wave and likes and dislikes reach it`() = FakeSubsonic().use { fake ->
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "DPAPI is Windows-only")
        val store = SettingsStore(Files.createTempDirectory("platter-wave").resolve("settings.json"))
        store.update {
            withCredentials(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt"))
            sealedPassword = Secrets.seal("sesame")
        }
        val app = AppController(store, scope, vlcArgs = listOf("--aout=dummy"), waveUrl = fake.waveUrl)
        try {
            assertTrue(app.waveAvailable)

            app.onWaveTapped()
            waitUntil("the wave to play") { app.player.state.value.isPlaying }
            assertTrue(app.waveState.isWaveTrack(app.player.state.value.current?.id))
            assertEquals("electronic", app.waveState.vibe.value)
            // The password the app signed in with went along, from its sealed copy.
            assertEquals("enc:736573616d65", fake.wavePasswords.first())

            val song = app.player.state.value.current!!
            app.toggleLike(song)
            app.toggleDislike(song)
            waitUntil("the rating") { fake.requestsTo("setRating").isNotEmpty() }
            assertEquals("1", fake.requestsTo("setRating").single().queryParameter("rating"))
            assertTrue(app.isDisliked(song))
            assertFalse(app.isLiked(song.id, song.starred), "disliking drops the like")
            val kinds = app.waveState.drainEvents().map { it.type }
            assertEquals(listOf("like", "dislike", "unlike"), kinds)

            // While the wave is what plays, the card is its play/pause.
            app.onWaveTapped()
            waitUntil("pause") { !app.player.state.value.isPlaying }
        } finally {
            app.shutdown()
        }
    }

    @Test
    fun `the password survives a sealed round trip, and an unsealed value is not one`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "DPAPI is Windows-only")
        val sealed = assertNotNull(Secrets.seal("пароль 123"))
        assertFalse("пароль" in sealed)
        assertEquals("пароль 123", Secrets.open(sealed))
        assertNull(Secrets.open("not a sealed value"))
        assertNull(Secrets.open(null))
    }
}
