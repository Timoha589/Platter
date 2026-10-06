package com.platter.desktop

import com.platter.desktop.api.Auth
import com.platter.desktop.api.Credentials
import com.platter.desktop.api.SubsonicClient
import com.platter.desktop.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Real libvlc, silent output (`--aout=dummy`), against the fake server: the
 * stream is fetched, playback starts, the queue advances when a track ends,
 * and "now playing" and the play itself reach the server.
 */
class PlayerControllerTest {
    private fun waitFor(timeoutMs: Long = 15_000, what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(50)
        }
        fail("Timed out waiting for $what")
    }

    @Test
    fun `survives being clicked around - the last thing asked for is what plays`() = FakeSubsonic().use { fake ->
        val client = SubsonicClient(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val player = PlayerController(scope, { song -> client.streamUrl(song.id!!) }, { id, sub, time -> client.scrobble(id, sub, time) }, vlcArgs = listOf("--aout=dummy"))
        try {
            val songs = runBlocking { client.album("a0") }.songs!! + runBlocking { client.album("a1") }.songs!!
            val random = java.util.Random(7)
            repeat(6) { round ->
                player.play(songs, random.nextInt(songs.size))
                repeat(25) {
                    when (random.nextInt(7)) {
                        0 -> player.play(songs, random.nextInt(songs.size))
                        1 -> player.next()
                        2 -> player.previous()
                        3 -> player.jumpTo(random.nextInt(songs.size))
                        4 -> player.toggle()
                        5 -> player.seek(random.nextFloat())
                        else -> player.playNext(songs[random.nextInt(songs.size)])
                    }
                    Thread.sleep(random.nextInt(40).toLong())
                }
                // Whatever came before, one more pick must start exactly that song.
                val pick = random.nextInt(songs.size)
                Thread.sleep(300)
                player.play(songs, pick)
                waitFor(what = "round $round: ${songs[pick].id} to play after the clicking (state: ${player.state.value.let { "${it.current?.id} playing=${it.isPlaying} err=${it.error} q=${it.queue.index}/${it.queue.songs.size}" }})") {
                    player.state.value.let { it.current?.id == songs[pick].id && it.isPlaying }
                }
                assertEquals(songs, player.state.value.queue.songs, "the queue is the one that was asked for")
            }
        } finally {
            player.release()
        }
    }

    @Test
    fun `a track that will not play does not stall the queue`() = FakeSubsonic().use { fake ->
        val client = SubsonicClient(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val broken = java.nio.file.Files.createTempFile("broken", ".mp3").also { java.nio.file.Files.write(it, ByteArray(2048) { 7 }) }
        val player = PlayerController(
            scope,
            { song -> if (song.id == "bad") broken.toUri().toString() else client.streamUrl(song.id!!) },
            { _, _, _ -> },
            vlcArgs = listOf("--aout=dummy"),
        )
        try {
            val good = runBlocking { client.album("a0") }.songs!!
            val bad = com.platter.desktop.api.Song().apply { id = "bad"; title = "Broken"; artist = "x"; album = "y"; duration = 100 }
            val queue = listOf(good[0], bad, good[1], good[2])

            // It ran into the broken one by itself: the queue must go on to the next song.
            player.play(queue, 1)
            waitFor(what = "the queue to move past a broken track on its own (state: ${player.state.value.current?.id} ${player.state.value.error})") {
                player.state.value.let { it.current?.id == "s0-2" && it.isPlaying }
            }

            // Asked for by hand, then pressing play on it again must try again rather than do nothing.
            player.jumpTo(1)
            Thread.sleep(1500)
            val log = StringBuilder()
            fun dump(tag: String) = player.state.value.let { log.append("$tag: ${it.current?.id} playing=${it.isPlaying} err=${it.error} q=${it.queue.index}\n") }
            dump("after jumpTo(bad)")
            player.toggle(); Thread.sleep(1500); dump("after toggle")
            player.next()
            val end = System.currentTimeMillis() + 2500
            while (!player.state.value.isPlaying && System.currentTimeMillis() < end) Thread.sleep(25)
            dump("after next")
            assertTrue(player.state.value.isPlaying, log.toString())
        } finally {
            player.release()
        }
    }

    @Test
    fun `a listener that throws cannot stop the queue from moving on`() = FakeSubsonic().use { fake ->
        val client = SubsonicClient(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val player = PlayerController(scope, { song -> client.streamUrl(song.id!!) }, { _, _, _ -> }, vlcArgs = listOf("--aout=dummy"))
        player.trackListener = com.platter.desktop.player.TrackListener { _, _, _, _ -> throw NoClassDefFoundError("gone") }
        try {
            val songs = runBlocking { client.album("a0") }.songs!!
            player.play(songs)
            waitFor(what = "playback to start") { player.state.value.isPlaying }
            player.next()
            waitFor(what = "the second track, though the listener failed") { player.state.value.current?.id == "s0-2" && player.state.value.isPlaying }
            player.jumpTo(3)
            waitFor(what = "a jump, though the listener failed") { player.state.value.current?.id == "s0-4" }
        } finally {
            player.release()
        }
    }

    @Test
    fun `plays a queue, advances on its own and scrobbles`() = FakeSubsonic().use { fake ->
        val client = SubsonicClient(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val player = PlayerController(
            scope = scope,
            urlFor = { song -> client.streamUrl(song.id!!) },
            scrobble = { id, submission, time -> client.scrobble(id, submission, time) },
            vlcArgs = listOf("--aout=dummy"),
        )
        try {
            val songs = runBlocking { client.album("a0") }.songs!!
            player.play(songs)

            waitFor(what = "playback to start") { player.state.value.isPlaying }
            assertEquals("s0-1", player.state.value.current?.id)
            assertTrue(fake.requestsTo("stream").any { it.queryParameter("id") == "s0-1" }, "the first track was streamed")
            waitFor(what = "now playing to be announced") {
                fake.requestsTo("scrobble").any { it.queryParameter("id") == "s0-1" && it.queryParameter("submission") == "false" }
            }

            // Half of a four second track: the play counts, filed under the time it started.
            waitFor(what = "the play to be scrobbled") {
                fake.requestsTo("scrobble").any { it.queryParameter("id") == "s0-1" && it.queryParameter("submission") == "true" }
            }
            val counted = fake.requestsTo("scrobble").first { it.queryParameter("submission") == "true" }
            assertTrue(counted.queryParameter("time")!!.toLong() > 0)

            // The track runs out and the queue moves on by itself.
            waitFor(what = "the second track") { player.state.value.current?.id == "s0-2" && player.state.value.isPlaying }
            // "Playing" can still be left over from the track before while the next one is being asked for.
            waitFor(what = "the second track to be streamed") { fake.requestsTo("stream").any { it.queryParameter("id") == "s0-2" } }

            // Pause, resume, and a manual skip.
            player.toggle()
            waitFor(what = "pause") { !player.state.value.isPlaying }
            player.toggle()
            waitFor(what = "resume") { player.state.value.isPlaying }
            player.next()
            waitFor(what = "the third track") { player.state.value.current?.id == "s0-3" }
        } finally {
            player.release()
        }
    }
}
