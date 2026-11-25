package com.platter.desktop

import com.platter.desktop.api.Auth
import com.platter.desktop.api.AudioMuseFeatures
import com.platter.desktop.api.Credentials
import com.platter.desktop.api.Song
import com.platter.desktop.api.SubsonicClient
import com.platter.desktop.api.TrackAudioLevels
import com.platter.desktop.api.TrackSection
import com.platter.desktop.api.TrackStructure
import com.platter.desktop.player.BeatMix
import com.platter.desktop.player.Crossfade
import com.platter.desktop.player.EqSettings
import com.platter.desktop.player.FadeMode
import com.platter.desktop.player.PlayQueue
import com.platter.desktop.player.PlayerController
import com.platter.desktop.player.RepeatMode
import com.platter.desktop.player.StructureMix
import com.platter.desktop.player.TimelineOverlap
import com.platter.desktop.player.TransitionMode
import com.platter.desktop.player.confirmZeroVolume
import com.platter.desktop.player.vlcVolumeForGain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** When a crossfade happens and how loud each side is: no audio needed. */
class CrossfadePlanTest {
    @Test
    fun `silence requires both an accepted request and a zero readback`() {
        val acceptZero: (Int) -> Boolean = { it == 0 }
        assertFalse(confirmZeroVolume(acceptZero) { 71 })
        assertFalse(confirmZeroVolume(acceptZero) { -1 })
        assertFalse(confirmZeroVolume({ false }) { 0 })
        assertTrue(confirmZeroVolume(acceptZero) { 0 })
    }

    @Test
    fun `tempo handoff starts and ends gently in either direction`() {
        for (match in listOf(0.945, 1.0, 1.055)) {
            fun rate(at: Float) = BeatMix.incomingRate(match, at)
            assertEquals(match.toFloat(), rate(0f))
            assertEquals(1f, rate(1f))
            assertEquals(((1.0 + match) / 2).toFloat(), rate(0.5f), 0.00001f)
            assertEquals(match.toFloat(), rate(0.01f), 0.00003f)
            assertEquals(1f, rate(0.99f), 0.00003f)
        }

        for (ratio in listOf(90.0 / 110, 0.86, 0.9, 1.1, 1.17, 110.0 / 90)) {
            for (step in 0..10) {
                val incoming = BeatMix.incomingRate(ratio, step / 10f)
                val outgoing = BeatMix.outgoingRate(ratio, incoming)
                assertTrue(incoming in 0.90f..1.10f)
                assertTrue(outgoing in 0.90f..1.10f)
                assertEquals(ratio, (incoming / outgoing).toDouble(), 0.000001)
            }
        }
        for (progress in listOf(0f, 0.5f, 1f)) {
            assertEquals(0.90f, BeatMix.incomingRate(90.0 / 110, progress))
            assertEquals(1.10f, BeatMix.incomingRate(110.0 / 90, progress))
        }
    }

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
    fun `crossfade can use measured edges without changing the overlap duration`() {
        val plan = assertNotNull(Crossfade.plan(queue(song("a"), song("b")), 200_000))
        val from = TrackStructure(200_000, audioLevels = TrackAudioLevels(lastSoundMs = 195_000))
        val to = TrackStructure(200_400, audioLevels = TrackAudioLevels(firstSoundMs = 2_300))
        val fade = Crossfade.audibleEdges(plan, 200_000, from, to)
        assertEquals(189_000L, fade.startMs)
        assertEquals(6_000L, fade.fadeMs)
        assertEquals(2_300L, fade.cueMs)
        assertEquals(200_400L, fade.cueRecordingDurationMs)
        assertEquals(plan, Crossfade.audibleEdges(plan, 200_000, from.copy(durationMs = 205_000),
            to.copy(durationMs = 198_000)))
        assertEquals(plan, Crossfade.audibleEdges(plan, 200_000, null,
            to.copy(audioLevels = TrackAudioLevels(firstSoundMs = 199_000))))
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

    @Test
    fun `AutoMix brings in the next track early and keeps the previous one audible without a volume jump`() {
        assertEquals(0f to 1f, Crossfade.mixGains(0f))
        val (incomingEnd, outgoingEnd) = Crossfade.mixGains(1f)
        assertEquals(1f, incomingEnd, 1e-6f)
        assertEquals(0f, outgoingEnd, 1e-6f)

        val (incomingEarly, outgoingEarly) = Crossfade.mixGains(0.25f)
        val (incomingLate, outgoingLate) = Crossfade.mixGains(0.75f)
        assertTrue(incomingEarly >= 0.48f && outgoingEarly >= 0.94f)
        assertTrue(incomingLate >= 0.94f && outgoingLate >= 0.48f)

        var previous = Crossfade.mixGains(0f)
        for (step in 1..100) {
            val (incoming, outgoing) = Crossfade.mixGains(step / 100f)
            assertTrue(incoming >= previous.first && outgoing <= previous.second)
            assertTrue(incoming * incoming + outgoing * outgoing in 0.99999f..1.25001f)
            previous = incoming to outgoing
        }
    }

    @Test
    fun `AutoMix applies linear gains on the cubic Windows volume scale`() {
        assertEquals(0, vlcVolumeForGain(70, 0f))
        assertEquals(70, vlcVolumeForGain(70, 1f))
        assertEquals(35, vlcVolumeForGain(70, 0.125f))
        assertEquals(89, vlcVolumeForGain(100, Crossfade.gains(0.5f).first))
    }

    @Test
    fun `AutoMix hands bass over in one beat and retains the user's other bands`() {
        fun bass(at: Double) = Crossfade.bassGains(at, overlapMs = 8_000, beatPeriodMs = 500.0)
        assertEquals(0f to 1f, bass(3_750.0))
        assertEquals(1f to 0f, bass(4_250.0))
        assertEquals(Crossfade.gains(0.5f), bass(4_000.0))

        val settings = EqSettings(enabled = true, preamp = -3f, bands = listOf(4f, 3f, 2f, 1f))
        val cut = settings.gainsWithBass(bass(3_750.0).first)
        assertTrue(cut[0] < 0f && cut[1] < 3f)
        assertEquals(settings.gains().drop(2), cut.toList().drop(2))
        assertEquals(settings.gains(), settings.gainsWithBass(bass(4_250.0).first).toList())
        assertTrue(settings.copy(enabled = false).gainsWithBass(1f).all { it == 0f })
    }

    @Test
    fun `AutoMix selects matching beats from the trial and rejects an untrusted track or different recording`() {
        val (from, to) = trialPair()
        val duration = 244_689L
        val fade = assertNotNull(Crossfade.plan(queue(from, to), duration))
        val mix = assertNotNull(BeatMix.plan(from, duration, fade))
        assertEquals(238_771L, mix.outgoingBeatMs)
        assertEquals(975L, mix.incomingBeatMs)
        assertTrue(mix.tempoRatio in 1.03..1.04)

        assertNull(BeatMix.plan(from, duration - 4_000, fade))
        val ghost = song("02", seconds = 205).apply { title = "Ghost"; artist = "Hunter Hunted" }
        assertNull(BeatMix.plan(from, duration, fade.copy(to = ghost)))
    }

    @Test
    fun `AutoMix prefers the first vocal even if it has to skip the start of the intro or shorten the fade`() {
        val fade = assertNotNull(Crossfade.plan(queue(song("a"), song("b")), 200_000))
        val from = TrackStructure(200_000, (0L..200_000 step 500).toList(), (0L..200_000 step 2_000).toList(),
            listOf(TrackSection(100_000, 134_000, "chorus"), TrackSection(134_000, 158_000, "inst"),
                TrackSection(158_000, 178_000, "chorus"), TrackSection(178_000, 200_000, "outro")))
        val to = TrackStructure(200_000, (0L..200_000 step 510).toList(), (0L..200_000 step 2_040).toList(),
            listOf(TrackSection(0, 20_400, "intro"), TrackSection(20_400, 50_000, "verse")))
        fun plan(a: TrackStructure = from, b: TrackStructure = to, earliest: Long = 0) =
            StructureMix.plan(a, b, 200_000, fade, earliest).mix
        val mix = assertNotNull(plan())
        assertEquals(178_000, mix.launchMs())
        assertEquals(16_000, mix.overlapMs)
        assertEquals(4_080, mix.incomingStartMs)
        assertEquals(20_400, mix.incomingBeatMs)
        assertEquals("verse", mix.section)
        assertTrue("handoff at first vocal (20400ms)" in StructureMix.plan(from, to, 200_000, fade, 0).detail)
        assertEquals(1.02, mix.tempoRatio)
        val drift = from.beatsMs!!.map { if (it == 188_000L) it + 140 else it }
        val alternate = assertNotNull(plan(from.copy(beatsMs = drift)))
        assertEquals(178_000, alternate.launchMs())
        assertEquals(8_000, alternate.overlapMs)
        assertEquals(12_240, alternate.incomingStartMs)
        assertEquals(20_400, alternate.incomingBeatMs)
        val introOnly = assertNotNull(plan(b = to.copy(sections = to.sections?.take(1))))
        assertEquals(0, introOnly.incomingStartMs)
        assertEquals("intro", introOnly.section)
        assertNull(plan(earliest = 198_000))
        assertNull(plan(from.copy(durationMs = 195_000)))
        assertNull(plan(b = to.copy(downbeatsMs = emptyList())))
    }

    @Test
    fun `AutoMix allows up to ten percent per track in both directions`() {
        val fade = assertNotNull(Crossfade.plan(queue(song("a"), song("b")), 200_000))
        val from = TrackStructure(200_000, (160_000L..200_000 step 500).toList(),
            (160_000L..200_000 step 2_000).toList(), listOf(TrackSection(160_000, 200_000, "outro")))
        fun plan(beatMs: Long) = StructureMix.plan(from, TrackStructure(
            200_000, (0L..40 * beatMs step beatMs).toList(), (0L..40 * beatMs step 4 * beatMs).toList(),
            listOf(TrackSection(0, 40 * beatMs, "intro")),
        ), 200_000, fade, 0)

        assertEquals(0.82, assertNotNull(plan(410).mix).tempoRatio)
        assertEquals(1.222, assertNotNull(plan(611).mix).tempoRatio)
        assertNull(plan(409).mix)
        assertNull(plan(612).mix)
        assertTrue("outside the tempo limit" in plan(409).detail)
    }

    @Test
    fun `AutoMix prefers eight bars and the latest clean exit on long instrumental passages`() {
        val fade = assertNotNull(Crossfade.plan(queue(song("a"), song("b")), 200_000))
        val grid = TrackStructure(200_000, (0L..200_000 step 500).toList(), (0L..200_000 step 2_000).toList())
        val to = grid.copy(sections = listOf(TrackSection(0, 20_000, "intro"), TrackSection(20_000, 40_000, "inst"),
            TrackSection(40_000, 80_000, "verse")))
        fun plan(tailEnd: Long, incoming: TrackStructure = to) = StructureMix.plan(
            grid.copy(sections = listOf(TrackSection(142_000, 160_000, "chorus"), TrackSection(160_000, tailEnd, "outro"))),
            incoming, 200_000, fade, 0).mix
        val long = assertNotNull(plan(200_000))
        assertEquals(184_000L, long.launchMs())
        assertEquals(16_000, long.overlapMs)
        assertEquals(24_000, long.incomingStartMs)
        assertEquals(40_000, long.incomingBeatMs)
        assertEquals(168_000L, plan(188_000)?.launchMs())
        assertNull(plan(200_000, to.copy(beatsMs = (0L..200_000 step 650).toList())))
    }

    @Test
    fun `AutoMix tests a longer tail when an early intro is too short for the usual search`() {
        val fade = assertNotNull(Crossfade.plan(queue(song("a"), song("b")), 200_000))
        val beats = (0L..200_000 step 500).toList()
        val bars = (0L..200_000 step 2_000).toList()
        val from = TrackStructure(200_000, beats, bars, listOf(
            TrackSection(176_000, 190_000, "chorus"), TrackSection(190_000, 200_000, "outro"),
        ))
        val to = TrackStructure(200_000, beats, bars, listOf(
            TrackSection(0, 6_300, "intro"), TrackSection(6_300, 16_000, "verse"), TrackSection(16_000, 66_000, "chorus"),
            TrackSection(68_000, 76_000, "inst"),
        ))

        val selection = StructureMix.plan(from, to, 200_000, fade, 0)
        val mix = assertNotNull(selection.mix)
        assertEquals(8, mix.bars)
        assertEquals(200_000L, mix.outgoingBeatMs)
        assertTrue(mix.incomingBeatMs < 40_000)
        assertTrue("early search:" in selection.detail)

        val wrongGrid = to.copy(beatsMs = (0L..200_000 step 650).toList())
        val rejected = StructureMix.plan(from, wrongGrid, 200_000, fade, 0)
        assertNull(rejected.mix)
        assertTrue("first rejection: Grid at" in rejected.detail)
    }

    @Test
    fun `AutoMix rejects matching phrases with simultaneous vocals and limits the fallback fade`() {
        val fade = assertNotNull(Crossfade.plan(queue(song("a"), song("b")), 200_000))
        val grid = TrackStructure(200_000, (0L..200_000 step 500).toList(), (0L..200_000 step 2_000).toList())
        val from = grid.copy(sections = listOf(TrackSection(150_000, 200_000, "chorus")))
        val to = grid.copy(sections = listOf(TrackSection(0, 40_000, "verse"), TrackSection(40_000, 70_000, "chorus")))

        val selection = StructureMix.plan(from, to, 200_000, fade, 0)
        assertNull(selection.mix)
        assertTrue("first rejection: Vocals at" in selection.detail)
        val fallback = StructureMix.crossfade(from, to, 200_000, fade, 0)
        assertEquals(1_500, fallback.fadeMs)
        assertEquals(198_500, fallback.startMs)

        val outro = from.copy(sections = listOf(
            TrackSection(150_000, 190_000, "chorus"), TrackSection(190_000, 200_000, "outro"),
        ))
        val clean = assertNotNull(StructureMix.plan(outro, to, 200_000, fade, 0).mix)
        assertEquals("outro", clean.exitSection)
        assertEquals(192_000, clean.launchMs())
    }

    @Test
    fun `AutoMix advances the incoming cue for consistent audio attacks and ignores unreliable offsets`() {
        val fade = assertNotNull(Crossfade.plan(queue(song("a"), song("b")), 200_000))
        val beats = (0L..200_000 step 500).toList()
        val bars = (0L..200_000 step 2_000).toList()
        val from = TrackStructure(200_000, beats, bars, listOf(TrackSection(160_000, 200_000, "outro")),
            beatOffsetsMs = beats.map { 10L })
        val to = TrackStructure(200_000, beats, bars, listOf(TrackSection(0, 40_000, "intro")))
        val nominal = assertNotNull(StructureMix.plan(from, to, 200_000, fade, 0).mix)
        val corrected = assertNotNull(StructureMix.plan(from, to.copy(beatOffsetsMs = beats.map { 37L }), 200_000, fade, 0).mix)
        assertEquals(27L, corrected.phaseAdvanceMs)
        assertEquals(nominal.launchMs(), corrected.launchMs())
        assertEquals(nominal.incomingStartMs + 27, corrected.incomingStartMs)
        assertEquals(0L, corrected.phaseErrorMs(corrected.launchMs(), corrected.incomingStartMs.toDouble()))
        assertEquals(-100L, corrected.phaseErrorMs(corrected.launchMs(), corrected.incomingStartMs - 100.0))

        val unstable = to.copy(beatOffsetsMs = beats.mapIndexed { i, _ -> if (i % 2 == 0) -45L else 75L })
        assertEquals(nominal, StructureMix.plan(from, unstable, 200_000, fade, 0).mix)
        val slightlySlower = to.copy(beatsMs = beats.map { it + it / 1_600 }, downbeatsMs = bars.map { it + it / 1_600 })
        assertEquals(1.0, assertNotNull(StructureMix.plan(from, slightlySlower, 200_000, fade, 0).mix).tempoRatio)
    }

    @Test
    fun `AudioMuse compatibility guides overlap without replacing the local beat grid`() {
        val fade = assertNotNull(Crossfade.plan(queue(song("a"), song("b")), 200_000))
        val grid = TrackStructure(200_000, (0L..200_000 step 500).toList(), (0L..200_000 step 2_000).toList())
        val from = grid.copy(sections = listOf(TrackSection(160_000, 200_000, "outro")),
            audioMuse = AudioMuseFeatures(60.0, "C", "major", 0.5))
        val to = grid.copy(sections = listOf(TrackSection(0, 40_000, "intro")))
        fun plan(key: String, scale: String) = StructureMix.plan(from,
            to.copy(audioMuse = AudioMuseFeatures(240.0, key, scale, 0.5)), 200_000, fade, 0)

        val compatible = plan("A", "minor")
        assertEquals(16_000L, assertNotNull(compatible.mix).overlapMs)
        assertEquals(1.0, compatible.mix.tempoRatio)
        assertTrue("AudioMuse features available" in compatible.detail)
        assertEquals(8_000L, assertNotNull(plan("Gb", "major").mix).overlapMs)
    }

    @Test
    fun `measured silence moves the overlap ahead of the quiet tail`() {
        val fade = assertNotNull(Crossfade.plan(queue(song("a"), song("b")), 200_000))
        val grid = TrackStructure(200_000, (0L..200_000 step 500).toList(), (0L..200_000 step 2_000).toList())
        val from = grid.copy(sections = listOf(TrackSection(160_000, 200_000, "outro")))
        val to = grid.copy(sections = listOf(TrackSection(0, 40_000, "intro")))
        val original = assertNotNull(StructureMix.plan(from, to, 200_000, fade, 0).mix)
        assertEquals(200_000L, original.outgoingBeatMs)

        val recorded = from.copy(audioLevels = TrackAudioLevels(lastSoundMs = 192_000))
        val selected = StructureMix.plan(recorded, to, 200_000, fade, 0)
        val mix = assertNotNull(selected.mix)
        assertEquals(192_000L, mix.outgoingBeatMs)
        assertEquals(16_000L, mix.overlapMs)
        assertTrue("local loudness available" in selected.detail)
    }

    @Test
    fun `crossfade uses the outgoing section and ends at the first vocal without a beat grid`() {
        val fade = assertNotNull(Crossfade.plan(queue(song("a"), song("b")), 200_000))
        val from = TrackStructure(200_000, sections = listOf(TrackSection(170_000, 198_000, "outro")))
        val to = TrackStructure(200_000, sections = listOf(TrackSection(0, 23_500, "intro"),
            TrackSection(23_500, 40_000, "verse"), TrackSection(40_000, 60_000, "chorus")))
        val plan = StructureMix.crossfade(from, to, 200_000, fade, 0)
        assertEquals(180_000, plan.startMs)
        assertEquals(18_000, plan.fadeMs)
        assertEquals(5_500, plan.cueMs)
        assertEquals(23_500, plan.incomingVocalMs)

        val late = StructureMix.crossfade(from, to, 200_000, fade, 191_000)
        assertTrue(late.startMs >= 191_000)
        assertEquals(23_500, late.cueMs + late.fadeMs)
        assertEquals(fade, StructureMix.crossfade(null, to.copy(durationMs = 196_000), 200_000, fade, 0))
    }

    @Test
    fun `local loudness avoids an overlap where both parts go quiet together`() {
        val fade = assertNotNull(Crossfade.plan(queue(song("a"), song("b")), 200_000))
        val grid = TrackStructure(200_000, (0L..200_000 step 500).toList(), (0L..200_000 step 2_000).toList())
        val from = grid.copy(sections = listOf(TrackSection(160_000, 200_000, "outro")))
        val to = grid.copy(sections = listOf(TrackSection(0, 8_000, "verse"), TrackSection(8_000, 26_000, "inst"),
            TrackSection(26_000, 56_000, "verse")))
        fun levels(dip: Long) = TrackAudioLevels(stepMs = 500,
            momentaryLufs = (0L..200_000 step 500).map { if (it == dip) -30.0 else -15.0 })

        assertEquals(8_000L, StructureMix.plan(from, to, 200_000, fade, 0).mix?.incomingStartMs)
        val selected = StructureMix.plan(from.copy(audioLevels = levels(192_000)),
            to.copy(audioLevels = levels(16_000)), 200_000, fade, 0)
        assertEquals(10_000L, selected.mix?.incomingStartMs)
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

    private fun withPlayer(mode: TransitionMode = TransitionMode.AutoMix, body: (PlayerController, List<Song>) -> Unit) = FakeSubsonic().use { fake ->
        client = SubsonicClient(Credentials(fake.url, "tim", Auth.token("pw", "salt"), "salt"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val player = PlayerController(
            scope, { song -> client.streamUrl(song.id!!) }, { _, _, _ -> },
            vlcArgs = listOf("--aout=dummy"), initialTransitionMode = mode, crossfadeMs = 1_500,
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
        waitFor(what = "the fallback overlap on the first track") { player.state.value.upcomingOverlap != null }
        val upcoming = player.state.value
        val plan = assertNotNull(Crossfade.plan(upcoming.queue, upcoming.durationMs, 1_500))
        assertEquals(TimelineOverlap(plan.startMs..upcoming.durationMs, FadeMode.AutoMixFallback), upcoming.upcomingOverlap)
        waitFor(what = "the crossfade to begin") { player.state.value.fadeTransition != null }

        // The four second track is not over, and the queue is already on the second.
        assertTrue(player.state.value.positionMs < 1_400, "the new track has only just begun")
        assertEquals(songs[1].id, player.state.value.current?.id)
        assertNull(player.state.value.autoMixTransition)
        val fade = assertNotNull(player.state.value.fadeTransition)
        assertEquals(songs[0].coverArtId, fade.outgoingCoverArtId)
        assertEquals(FadeMode.AutoMixFallback, fade.mode)
        assertNull(player.state.value.upcomingOverlap)
        val incoming = assertNotNull(player.state.value.incomingOverlap)
        assertEquals(FadeMode.AutoMixFallback, incoming.mode)
        assertEquals(0L, incoming.rangeMs.first)
        assertTrue(incoming.rangeMs.last in Crossfade.MIN_FADE_MS..plan.fadeMs)

        // While it runs, both sides are heard: neither is at the full volume or at nothing.
        var crossed = false
        var coverFading = false
        waitFor(what = "the fade to finish") {
            player.lastFadeVolumes?.let { (incoming, outgoing) -> if (incoming in 10..90 && outgoing in 10..90) crossed = true }
            if (player.state.value.fadeTransition?.progress?.let { it in 0.15f..0.85f } == true) coverFading = true
            player.state.value.fadeTransition == null
        }
        assertTrue(crossed, "both tracks sounded at once, saw ${player.lastFadeVolumes}")
        assertTrue(coverFading, "the cover used the progress of the audio overlap")

        // Afterwards the new track is alone, at the volume the listener set.
        assertEquals(songs[1].id, player.state.value.current?.id)
        assertEquals(null, player.state.value.error)
        assertEquals(incoming, player.state.value.incomingOverlap)
        waitFor(what = "the second track to play on") { player.state.value.isPlaying }
        assertEquals(1, player.state.value.queue.index)
    }

    @Test
    fun `a crossfade with AutoMix disabled is classified as an ordinary crossfade`() = withPlayer(TransitionMode.Crossfade) { player, songs ->
        player.play(songs)
        waitFor(what = "the ordinary crossfade") { player.state.value.fadeTransition != null }
        assertEquals(FadeMode.Crossfade, assertNotNull(player.state.value.fadeTransition).mode)
        assertNull(player.state.value.incomingOverlap)
    }

    @Test
    fun `a one second crossfade still starts after the clock passes its cue`() = withPlayer(TransitionMode.Crossfade) { player, songs ->
        player.play(songs)
        waitFor(what = "the first track to play") { player.state.value.isPlaying }
        player.setCrossfadeDuration(1_000)
        waitFor(what = "the one second crossfade") { player.state.value.fadeTransition?.mode == FadeMode.Crossfade }
        assertEquals(songs[1].id, player.state.value.current?.id)
    }

    private fun withTrialStreams(
        seekable: Boolean = true,
        vlcArgs: List<String> = listOf("--aout=dummy"),
        body: (PlayerController, SubsonicClient) -> Unit,
    ) = MockWebServer().use { server ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val streams = mapOf("03" to timedWav(244_689), "27" to timedWav(247_171))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.requestUrl?.encodedPath == "/rest/platterAutoMix") {
                    val beats = (0..247_000 step 500).joinToString()
                    val bars = (0..246_000 step 2_000).joinToString()
                    return MockResponse().setBody("""{"subsonic-response":{"status":"ok","platterAutoMix":{
                        "pending":[],"tracks":{"03":{"durationMs":244689,"beatsMs":[$beats],"downbeatsMs":[$bars],"sections":[
                        {"startMs":150000,"endMs":180000,"label":"chorus"},{"startMs":180000,"endMs":208000,"label":"inst"}]},
                        "27":{"durationMs":247171,"beatsMs":[$beats],"downbeatsMs":[14000,16000,18000,20000,22000,24000,26000,28000,30000],
                        "sections":[{"startMs":0,"endMs":30000,"label":"intro"},
                        {"startMs":30000,"endMs":56000,"label":"chorus"}]}}}}}""")
                }
                val id = request.requestUrl?.queryParameter("id")
                val wav = streams[id] ?: return MockResponse().setResponseCode(404)
                if (!seekable && id == "27") {
                    return MockResponse().setHeader("Content-Type", "audio/wav").setChunkedBody(Buffer().write(wav), 8_192)
                }
                val range = request.getHeader("Range")
                val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                if (start !in wav.indices) return MockResponse().setResponseCode(416)
                val response = MockResponse().setHeader("Content-Type", "audio/wav").setHeader("Accept-Ranges", "bytes")
                    .setBody(Buffer().write(wav, start, wav.size - start))
                if (range != null) response.setResponseCode(206).setHeader("Content-Range", "bytes $start-${wav.lastIndex}/${wav.size}")
                return response
            }
        }
        val client = SubsonicClient(Credentials("http://127.0.0.1:${server.port}/", "tim", Auth.token("pw", "salt"), "salt"))
        val player = PlayerController(
            scope, { song -> client.streamUrl(song.id!!) }, { _, _, _ -> },
            vlcArgs = vlcArgs, initialTransitionMode = TransitionMode.AutoMix,
        )
        try {
            body(player, client)
        } finally {
            player.releaseAndWait()
            scope.cancel()
        }
    }

    @Test
    fun `crossfade skips measured silence when the stream supports seeking`() = withTrialStreams { player, _ ->
        player.setStructures(quietEdges())
        player.play(trialPair().onEach { it.title = "untagged fixture" }, positionMs = 228_000)
        waitFor(what = "the trimmed crossfade") { player.crossfading && player.state.value.current?.id == "27" }
        assertTrue(player.state.value.positionMs in 1_900L..2_450L, "cue was ${player.state.value.positionMs} ms")
        assertEquals(1f, player.incomingPlaybackRate)
    }

    @Test
    fun `a stream without seeking still crossfades from the start`() = withTrialStreams(seekable = false) { player, _ ->
        player.setStructures(quietEdges())
        player.play(trialPair().onEach { it.title = "untagged fixture" }, positionMs = 228_000)
        waitFor(what = "the ordinary crossfade") { player.crossfading && player.state.value.current?.id == "27" }
        assertTrue(player.state.value.positionMs < 1_000, "cue was ${player.state.value.positionMs} ms")
        assertEquals(1f, player.incomingPlaybackRate)
        assertEquals(0L, assertNotNull(player.state.value.incomingOverlap).rangeMs.first)
    }

    private fun quietEdges() = mapOf(
        "03" to TrackStructure(244_689, audioLevels = TrackAudioLevels(lastSoundMs = 240_000)),
        "27" to TrackStructure(247_171, audioLevels = TrackAudioLevels(firstSoundMs = 2_000)),
    )

    @Test
    fun `incompatible grids use a crossfade that hands off at the first vocal at native speed`() = withTrialStreams { player, _ ->
        player.setStructures(mapOf(
            "03" to TrackStructure(244_689, (220_000L..242_000 step 500).toList(), (220_000L..242_000 step 2_000).toList(),
                listOf(TrackSection(220_000, 242_000, "outro"))),
            "27" to TrackStructure(247_171, (0L..22_400 step 350).toList(), (0L..22_400 step 1_400).toList(),
                listOf(TrackSection(0, 22_400, "intro"), TrackSection(22_400, 50_000, "verse"))),
        ))
        player.play(trialPair().onEach { it.title = "untagged fixture" }, positionMs = 214_000)
        waitFor(what = "the outgoing track to play before seeking toward the overlap") {
            player.state.value.isPlaying && player.state.value.positionMs >= 214_000
        }
        waitFor(what = "the fallback timeline to show the shaped fade") {
            player.state.value.upcomingOverlap == TimelineOverlap(224_000L..242_000L, FadeMode.AutoMixFallback)
        }
        player.seekMs(221_000)
        waitFor(what = "the incoming track to start during its intro") {
            player.crossfading && player.state.value.current?.id == "27"
        }
        assertTrue(player.state.value.positionMs in 4_200..4_700)
        assertNull(player.state.value.autoMixTransition)
        assertEquals(1f, player.outgoingPlaybackRate)
        assertEquals(1f, player.incomingPlaybackRate)
        waitFor(what = "the incoming track to play for several seconds") { player.state.value.positionMs >= 8_000 }
        val fade = assertNotNull(player.state.value.fadeTransition)
        assertEquals(FadeMode.AutoMixFallback, fade.mode)
        assertTrue(fade.progress in 0.15f..0.30f)
        val incoming = assertNotNull(player.state.value.incomingOverlap)
        assertEquals(FadeMode.AutoMixFallback, incoming.mode)
        assertTrue(incoming.rangeMs.first in 4_200..4_700)
        assertTrue(incoming.rangeMs.last in 21_800..22_700)
        waitFor(timeoutMs = 22_000, what = "the fade to finish at the first vocal") { player.state.value.fadeTransition == null }
        assertTrue(player.state.value.positionMs in 21_800..22_700, "handoff was at ${player.state.value.positionMs} ms")
        assertEquals(incoming, player.state.value.incomingOverlap)
    }

    @Test
    fun `libvlc starts the analysed stream at the cue and returns to normal speed`() = withTrialStreams { player, _ ->
        val songs = trialPair().onEach { it.coverArtId = "cover-${it.id}" }
        player.play(songs, positionMs = 234_000)
        waitFor(what = "the planned overlap on the outgoing track") { player.state.value.upcomingOverlap?.mode == FadeMode.AutoMix }
        val upcoming = player.state.value
        val range = assertNotNull(upcoming.upcomingOverlap).rangeMs
        val fade = assertNotNull(Crossfade.plan(upcoming.queue, upcoming.durationMs))
        val plan = assertNotNull(BeatMix.plan(songs.first(), upcoming.durationMs, fade))
        assertEquals("03", upcoming.current?.id)
        assertTrue(upcoming.positionMs < range.first)
        assertEquals(plan.launchMs()..minOf(upcoming.durationMs, plan.launchMs() + fade.fadeMs), range)

        waitFor(what = "AutoMix to begin at the cue") { player.state.value.autoMixTransition != null }
        val started = player.state.value
        val transition = assertNotNull(started.autoMixTransition)
        assertEquals("27", started.current?.id)
        assertNull(started.upcomingOverlap)
        val incomingOverlap = assertNotNull(started.incomingOverlap)
        assertEquals(FadeMode.AutoMix, incomingOverlap.mode)
        val incomingRange = incomingOverlap.rangeMs
        assertTrue(incomingRange.first in 700L..1_300L)
        val expectedIncomingMs = (range.last - range.first) * plan.tempoRatio
        assertTrue(kotlin.math.abs(incomingRange.last - incomingRange.first - expectedIncomingMs) < 200)
        assertEquals(songs[0].coverArtId, transition.outgoingCoverArtId)
        assertTrue(started.positionMs in 700L..1_300L, "cue was ${started.positionMs} ms")
        val match = player.incomingPlaybackRate
        assertTrue(match in 1.03f..1.04f)
        waitFor(what = "both deck tempos to move during the overlap") {
            player.crossfading && player.incomingPlaybackRate in 1.013f..1.023f && player.outgoingPlaybackRate < 0.995f
        }
        player.toggle()
        waitFor(what = "the tempo glide to pause") { !player.state.value.isPlaying }
        val pausedRate = player.incomingPlaybackRate
        val pausedProgress = assertNotNull(player.state.value.autoMixTransition).progress
        assertTrue(pausedProgress in 0.1f..0.9f && pausedProgress > transition.progress)
        Thread.sleep(300)
        assertEquals(pausedRate, player.incomingPlaybackRate, 0.001f)
        assertEquals(pausedProgress, assertNotNull(player.state.value.autoMixTransition).progress)
        assertEquals(match, player.incomingPlaybackRate / player.outgoingPlaybackRate, 0.002f)
        player.toggle()
        waitFor(what = "the new track to finish the handoff at native speed") {
            !player.crossfading && player.incomingPlaybackRate == 1f && player.state.value.autoMixTransition == null
        }
        assertEquals("27", player.state.value.current?.id)
        assertEquals(incomingOverlap, player.state.value.incomingOverlap)
    }

    @Test
    fun `libvlc applies opposite tempo changes and returns to native speed after the vocal handoff`() = withTrialStreams { player, _ ->
        player.setStructures(mapOf(
            "03" to TrackStructure(244_689, (220_000L..232_000 step 500).toList(), (220_000L..232_000 step 2_000).toList(),
                listOf(TrackSection(220_000, 231_000, "outro"))),
            "27" to TrackStructure(247_171, (0L..6_560 step 410).toList(), (0L..6_560 step 1_640).toList(),
                listOf(TrackSection(0, 6_560, "intro"), TrackSection(6_560, 45_000, "verse"))),
        ))
        player.play(trialPair(), positionMs = 216_000)
        waitFor(what = "the outgoing track to speed up before the cue") {
            player.state.value.current?.id == "03" && player.incomingPlaybackRate > 1.03f
        }
        waitFor(what = "the decks to sound together near their opposite limits") {
            player.state.value.autoMixTransition != null && player.incomingPlaybackRate in 0.90f..0.903f &&
                player.outgoingPlaybackRate in 1.096f..1.10f
        }
        waitFor(what = "the incoming track to play alone at the first vocal") {
            player.state.value.current?.id == "27" && !player.crossfading
        }
        assertTrue(player.state.value.positionMs in 6_100L..7_000L)
        assertTrue(player.incomingPlaybackRate in 0.90f..0.94f)
        waitFor(what = "the incoming track to regain its original tempo") { player.incomingPlaybackRate == 1f }
    }

    @Test
    fun `outgoing analysis arriving during playback starts a sixteen second mix a minute before EOF`() = withTrialStreams { player, client ->
        val result = assertNotNull(runBlocking { client.autoMix(listOf("03", "27")) })
        player.setStructures(result.tracks.orEmpty().filterKeys { it == "27" })
        player.play(trialPair(), positionMs = 174_000)
        waitFor(what = "the first track to play without an exit cue") { player.state.value.positionMs >= 174_800 }
        player.setStructures(result.tracks.orEmpty())
        waitFor(what = "the timeline to show the early sixteen-second mix") {
            player.state.value.upcomingOverlap == TimelineOverlap(188_000L..204_000L, FadeMode.AutoMix)
        }
        waitFor(what = "the last second before the prepared transition") { player.state.value.positionMs >= 187_100 }
        val tracks = result.tracks.orEmpty()
        val from = assertNotNull(tracks["03"])
        player.setStructures(tracks + ("03" to from.copy(audioLevels = TrackAudioLevels(lastSoundMs = 244_000))))
        waitFor(timeoutMs = 20_000, what = "the transition to the chorus") {
            player.crossfading && player.state.value.current?.id == "27"
        }
        val incoming = player.state.value
        assertTrue(incoming.positionMs in 13_900..14_400, "intro cue was ${incoming.positionMs} ms")
        val range = assertNotNull(incoming.incomingOverlap).rangeMs
        assertTrue(range.first in 13_900L..14_400L)
        assertTrue(range.last in 29_900L..30_400L)
        waitFor(what = "the incoming rhythm to come up while the outgoing track is still near full volume") {
            player.lastFadeVolumes?.let { (incoming, outgoing) -> incoming >= 78 && outgoing >= 98 } == true
        }
        waitFor(what = "both inputs still sounding past the old six second limit") { player.state.value.positionMs > 21_000 }
        assertTrue(player.crossfading)
        waitFor(what = "the outgoing player to stop at the handoff") { !player.crossfading }
        assertTrue(player.state.value.positionMs in 29_000..31_500)
        assertEquals(1f, player.incomingPlaybackRate)
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
    @EnabledOnOs(OS.WINDOWS)
    fun `replaying the last track after a skip during paused AutoMix restores the slider volume`() =
        withTrialStreams(vlcArgs = emptyList()) { player, _ ->
            player.setVolume(57)
            player.play(trialPair(), positionMs = 234_000)
            waitFor(what = "AutoMix to start") { (player.state.value.autoMixTransition?.progress ?: 0f) > 0.05f }
            player.toggle()
            waitFor(what = "the overlap to pause") { !player.state.value.isPlaying }
            player.next()
            waitFor(what = "the last track's audio output to close") {
                player.state.value.positionMs == 0L && !player.state.value.isPlaying && player.playbackVolume == -1
            }

            player.toggle()
            waitFor(what = "the last track to play from the start") {
                val volume = player.playbackVolume
                assertTrue(volume <= 57, "The restarted output briefly reached $volume% with the slider at 57%")
                player.state.value.isPlaying && player.state.value.positionMs in 1..1_000 && volume == 57
            }
            assertEquals("27", player.state.value.current?.id)
            assertEquals(57, player.state.value.volume)
            assertEquals(57, player.playbackVolume)
            assertFalse(player.crossfading)
        }

    @Test
    fun `a pause holds the fade still and a resume lets it finish`() = withPlayer { player, songs ->
        player.play(songs)
        waitFor(what = "the crossfade to begin") { player.crossfading }
        waitFor(what = "the new track to sound") { player.state.value.isPlaying }
        player.toggle()
        waitFor(what = "the pause") { !player.state.value.isPlaying }
        Thread.sleep(80)
        val pausedFade = assertNotNull(player.state.value.fadeTransition)
        Thread.sleep(1_500)
        assertTrue(player.crossfading, "a paused fade does not run out")
        assertEquals(pausedFade, player.state.value.fadeTransition)
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
    fun `switched off, there is no fade`() = withPlayer { player, songs ->
        player.play(songs)
        waitFor(what = "the first track to play") { player.state.value.isPlaying }
        player.setTransitionMode(TransitionMode.Off)
        var faded = false
        waitFor(what = "the queue to move on by itself") {
            if (player.crossfading) faded = true
            player.state.value.current?.id == songs[1].id
        }
        assertFalse(faded)
    }
}

private fun trialPair(): List<Song> = listOf(
    Song().apply { id = "03"; title = "El Alem Allah"; artist = "Amr Diab"; duration = 245 },
    Song().apply { id = "27"; title = "Hemisphere"; artist = "FUCK ART, LET'S DANCE!"; duration = 247 },
)

/** PCM silence with the same timeline as the analysed song, for testing the native player without using music files. */
private fun timedWav(durationMs: Int): ByteArray {
    val rate = 8_000
    val dataBytes = durationMs * (rate * 2 / 1_000)
    val header = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVEfmt ".toByteArray())
    header.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
    header.put("data".toByteArray()).putInt(dataBytes)
    return header.array()
}
