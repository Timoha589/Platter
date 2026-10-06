package com.platter.desktop

import com.platter.desktop.api.ServerAddress
import com.platter.desktop.data.PendingScrobbles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingScrobblesTest {
    private val file = Files.createTempDirectory("platter-pending").resolve("pending.json")

    @Test
    fun `plays are sent oldest first and crossed off as the server takes them`() = runBlocking {
        val pending = PendingScrobbles(file)
        pending.add("home", "a", 100)
        pending.add("home", "b", 200)
        pending.add("home", "c", 300)

        val sent = ArrayList<Pair<String, Long>>()
        pending.flush("home") { id, time -> sent += id to time; true }
        assertEquals(listOf("a" to 100L, "b" to 200L, "c" to 300L), sent)
        assertEquals(0, pending.count("home"))
    }

    @Test
    fun `sending stops at the first play that does not get through, and keeps the rest`() = runBlocking {
        val pending = PendingScrobbles(file)
        listOf("a", "b", "c").forEachIndexed { i, id -> pending.add("home", id, i * 100L) }

        var calls = 0
        pending.flush("home") { _, _ -> ++calls < 2 } // the first goes, the second does not
        assertEquals(2, calls, "nothing was tried after the failure")
        assertEquals(2, pending.count("home"))

        val sent = ArrayList<String>()
        pending.flush("home") { id, _ -> sent += id; true }
        assertEquals(listOf("b", "c"), sent, "the next try starts where it stopped")
    }

    @Test
    fun `a play waits for its own server`() = runBlocking {
        val pending = PendingScrobbles(file)
        pending.add("home", "a", 1)
        pending.add("work", "b", 2)

        val sent = ArrayList<String>()
        pending.flush("work") { id, _ -> sent += id; true }
        assertEquals(listOf("b"), sent)
        assertEquals(1, pending.count("home"))
        assertEquals(0, pending.count("work"))
    }

    @Test
    fun `plays are still there after a restart`() = runBlocking {
        PendingScrobbles(file).add("home", "a", 42)
        val reopened = PendingScrobbles(file)
        assertEquals(1, reopened.count("home"))
        val sent = ArrayList<Pair<String, Long>>()
        reopened.flush("home") { id, time -> sent += id to time; true }
        assertEquals(listOf("a" to 42L), sent)
    }

    @Test
    fun `only the latest thousand are kept`() {
        val pending = PendingScrobbles(file)
        repeat(1_050) { pending.add("home", "s$it", it.toLong()) }
        assertEquals(1_000, pending.count("home"))
        val first = ArrayList<String>()
        runBlocking { pending.flush("home") { id, _ -> if (first.isEmpty()) first += id; true } }
        assertEquals("s50", first.single(), "the oldest gave way")
    }

    @Test
    fun `a second flush while one is running does nothing`() = runBlocking {
        val pending = PendingScrobbles(file)
        pending.add("home", "a", 1)
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val first = async { pending.flush("home") { _, _ -> calls++; gate.await(); true } }
        delay(100)
        pending.flush("home") { _, _ -> calls++; true }
        gate.complete(Unit)
        first.await()
        assertEquals(1, calls, "the play was sent once, not twice")
    }

    @Test
    fun `a damaged file loses the waiting plays, not the app`() {
        Files.writeString(file, "{ this is not json")
        val pending = PendingScrobbles(file)
        assertEquals(0, pending.count("home"))
        pending.add("home", "a", 1)
        assertEquals(1, pending.count("home"))
    }
}

class ServerAddressTest {
    private fun subsonicServer() = MockWebServer().apply {
        // An unsigned ping is refused, in Subsonic's words: that is what makes an address count as a server.
        enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"failed","error":{"code":10,"message":"Required parameter is missing."}}}"""))
        start()
    }

    @Test
    fun `an address where a Subsonic server answers counts, even when it refuses the ping`() = runBlocking {
        val server = subsonicServer()
        try {
            assertTrue(ServerAddress.answers(server.url("/").toString()))
            assertEquals("/rest/ping.view", server.takeRequest().path!!.substringBefore('?'))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `anything else at an address does not - another service, or nothing`() = runBlocking {
        val router = MockWebServer().apply { enqueue(MockResponse().setBody("<html>router login</html>")); start() }
        try {
            assertFalse(ServerAddress.answers(router.url("/").toString()))
        } finally {
            router.shutdown()
        }
        assertFalse(ServerAddress.answers("http://127.0.0.1:1/"))
        assertFalse(ServerAddress.answers("http://[not an address"))
    }

    @Test
    fun `the local address is chosen while it answers, the main one otherwise, and no probe without one`() = runBlocking {
        val home = subsonicServer()
        try {
            val local = home.url("/").toString()
            assertEquals(local, ServerAddress.choose("https://music.example.com", local))
            assertEquals("https://music.example.com", ServerAddress.choose("https://music.example.com", "http://127.0.0.1:1/"))

            val before = home.requestCount
            assertEquals("https://music.example.com", ServerAddress.choose("https://music.example.com", null))
            assertEquals("https://music.example.com", ServerAddress.choose("https://music.example.com", "  "))
            assertEquals(before, home.requestCount)
        } finally {
            home.shutdown()
        }
    }

    @Test
    fun `two spellings of one address are the same`() {
        assertTrue(ServerAddress.same("music.example.com", "https://music.example.com/"))
        assertTrue(ServerAddress.same("HTTP://Host:4533", "http://host:4533/"))
        assertFalse(ServerAddress.same("http://host:4533", "http://host:4534"))
        assertFalse(ServerAddress.same(null, "http://host"))
    }
}
