package com.platter.desktop

import com.platter.desktop.api.Auth
import com.platter.desktop.api.Credentials
import com.platter.desktop.api.Song
import com.platter.desktop.api.SubsonicClient
import com.platter.desktop.player.Crossfade
import com.platter.desktop.player.PlayQueue
import com.platter.desktop.player.PlayerController
import com.platter.desktop.player.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** When a crossfade happens and how loud each side is: no audio needed. */
class CrossfadePlanTest {
    private fun song(id: String, album: String? = null, track: Int? = null, seconds: Int = 200, disc: Int? = null) =
        Song().apply { this.id = id; albumId = album; this.track = track; duration = seconds; discNumber = disc }

    private fun queue(vararg songs: Song) = PlayQueue().replace(songs.toList())

    @Test
    fun `fades into the next track a few seconds before the end`() {
        val plan = assertNotNull(Crossfade.plan(queue(song("a"), song("b")), 200_000))
        assertEquals(1, plan.toIndex)
        assertEquals(6_000, plan.fadeMs)
        assertEquals(194_000, plan.startMs)
    }

    @Test
    fun `a short track gives way faster, a third of it at most, on either side`() {
        assertEquals(3_000, Crossfade.plan(queue(song("a"), song("b")), 9_000)?.fadeMs)
        assertEquals(4_000, Crossfade.plan(queue(song("a"), song("b", seconds = 12)), 200_000)?.fadeMs)
    }

    @Test
    fun `too short to be a fade is a cut`() {
        assertNull(Crossfade.plan(queue(song("a"), song("b")), 2_500))
        assertNull(Crossfade.plan(queue(song("a"), song("b", seconds = 2)), 200_000))
    }

    @Test
    fun `nothing follows, or the same track repeats - no fade`() {
        assertNull(Crossfade.plan(queue(song("a")), 200_000))
        assertNull(Crossfade.plan(queue(song("a"), song("b")).jumpTo(1), 200_000))
        assertNull(Crossfade.plan(queue(song("a"), song("b")).copy(repeat = RepeatMode.One), 200_000))
        // Round the queue's end, with repeat-all, there is a next one.
        assertEquals(0, Crossfade.plan(queue(song("a"), song("b")).jumpTo(1).copy(repeat = RepeatMode.All), 200_000)?.toIndex)
        assertNull(Crossfade.plan(queue(song("a")).copy(repeat = RepeatMode.All), 200_000))
    }

    @Test
    fun `a podcast or a station is not crossfaded`() {
        val episode = song("p").apply { kind = Song.KIND_PODCAST }
        assertNull(Crossfade.plan(queue(song("a"), episode), 200_000))
        assertNull(Crossfade.plan(queue(episode, song("a")), 200_000))
    }

    @Test
    fun `the next number on the same album runs on by itself`() {
        assertTrue(Crossfade.runsInto(song("a", "al", 3), song("b", "al", 4)))
        assertNull(Crossfade.plan(queue(song("a", "al", 3), song("b", "al", 4)), 200_000))
        // Not the next number, another album, another disc, or numbers unknown: those are crossfaded.
        assertFalse(Crossfade.runsInto(song("a", "al", 3), song("b", "al", 5)))
        assertFalse(Crossfade.runsInto(song("a", "al", 3), song("b", "other", 4)))
        assertFalse(Crossfade.runsInto(song("a", "al", 3, disc = 1), song("b", "al", 4, disc = 2)))
        assertFalse(Crossfade.runsInto(song("a", "al"), song("b", "al")))
        assertNotNull(Crossfade.plan(queue(song("a", "al", 3), song("b", "al", 5)), 200_000))
    }

    @Test
    fun `the two sides add up to the same loudness all the way through`() {
        val (start, end) = Crossfade.gains(0f) to Crossfade.gains(1f)
        assertEquals(0f, start.first, 1e-6f)
        assertEquals(1f, start.second, 1e-6f)
        assertEquals(1f, end.first, 1e-6f)
        assertEquals(0f, end.second, 1e-6f)
        for (i in 0..10) {
            val (incoming, outgoing) = Crossfade.gains(i / 10f)
            assertEquals(1f, incoming * incoming + outgoing * outgoing, 1e-5f)
        }
        assertEquals(Crossfade.gains(1f), Crossfade.gains(7f))
    }
}

/** Two real libvlc players overlapping, silent (`--aout=dummy`), against the fake server. */
class CrossfadePlayerTest {
    private fun waitFor(timeoutMs: Long = 15_000, what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail("Timed out waiting for $what")
    }

    /** Runs [body] with a player that crossfades 1.5 s at most, the fake server's four second tracks being short. */
    private lateinit var client: SubsonicClient

    private fun withPlayer(crossfade: Boolean = true, body: (PlayerController, List<Song>) -> Unit) = FakeSubsonic().use { fake ->
        client = SubsonicClient(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val player = PlayerController(
            scope, { song -> client.streamUrl(song.id!!) }, { _, _, _ -> },
            vlcArgs = listOf("--aout=dummy"), initialCrossfade = crossfade, crossfadeMs = 1_500,
        )
        try {
            // One track of each of three albums, so none of them runs on into the next.
            val songs = listOf("a0", "a1", "a2").map { runBlocking { client.album(it) }.songs!!.first() }
            body(player, songs)
        } finally {
            assertTrue(player.releaseAndWait(), "the engine was let go of")
        }
    }

    @Test
    fun `the next track comes in before the current one has run out, and the two cross`() = withPlayer { player, songs ->
        player.play(songs)
        waitFor(what = "the first track to play") { player.state.value.isPlaying }
        waitFor(what = "the crossfade to begin") { player.crossfading }

        // The four second track is not over, and the queue is already on the second.
        assertTrue(player.state.value.positionMs < 1_400, "the new track has only just begun")
        assertEquals(songs[1].id, player.state.value.current?.id)

        // While it runs, both sides are heard: neither is at the full volume or at nothing.
        var crossed = false
        waitFor(what = "the fade to finish") {
            player.lastFadeVolumes?.let { (incoming, outgoing) -> if (incoming in 10..90 && outgoing in 10..90) crossed = true }
            !player.crossfading
        }
        assertTrue(crossed, "both tracks sounded at once, saw ${player.lastFadeVolumes}")

        // Afterwards the new track is alone, at the volume the listener set.
        assertEquals(songs[1].id, player.state.value.current?.id)
        assertEquals(null, player.state.value.error)
        waitFor(what = "the second track to play on") { player.state.value.isPlaying }
        assertEquals(1, player.state.value.queue.index)
    }

    @Test
    fun `a skip in the middle of a fade ends it and plays what was asked for`() = withPlayer { player, songs ->
        player.play(songs)
        waitFor(what = "the crossfade to begin") { player.crossfading }
        player.next()
        waitFor(what = "the fade to end") { !player.crossfading }
        waitFor(what = "the third track") { player.state.value.current?.id == songs[2].id && player.state.value.isPlaying }
    }

    @Test
    fun `a pause holds the fade still and a resume lets it finish`() = withPlayer { player, songs ->
        player.play(songs)
        waitFor(what = "the crossfade to begin") { player.crossfading }
        waitFor(what = "the new track to sound") { player.state.value.isPlaying }
        player.toggle()
        waitFor(what = "the pause") { !player.state.value.isPlaying }
        Thread.sleep(1_500)
        assertTrue(player.crossfading, "a paused fade does not run out")
        player.toggle()
        waitFor(what = "the resume") { player.state.value.isPlaying }
        waitFor(what = "the fade to finish") { !player.crossfading }
        assertEquals(songs[1].id, player.state.value.current?.id)
    }

    @Test
    fun `tracks that run on from each other are left to follow without a fade`() = withPlayer { player, _ ->
        val album = runBlocking { client.album("a0") }.songs!!
        player.play(album.take(2))
        var faded = false
        waitFor(what = "the queue to move on by itself") {
            if (player.crossfading) faded = true
            player.state.value.current?.id == album[1].id
        }
        assertFalse(faded, "consecutive tracks of an album are not crossfaded")
    }

    @Test
    fun `switched off, there is no fade`() = withPlayer(crossfade = false) { player, songs ->
        player.play(songs)
        var faded = false
        waitFor(what = "the queue to move on by itself") {
            if (player.crossfading) faded = true
            player.state.value.current?.id == songs[1].id
        }
        assertFalse(faded)
    }
}
