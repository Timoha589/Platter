package com.cappielloantonio.tempo.service

import com.platter.automix.AutoMixPlanner
import com.platter.automix.AutoMixPlanner.CueStatus
import com.platter.automix.FadeWindow
import com.platter.automix.TrackAudioLevels
import com.platter.automix.TrackSection
import com.platter.automix.TrackStructure
import com.platter.search.RecordingMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoMixTransitionTest {
    private val durationMs = 48_000L
    private val standard = FadeWindow(42_000, 6_000)
    private val from = structure(500, TrackSection(32_000, 48_000, "outro"))
    private val to = structure(510, TrackSection(0, 10_200, "intro"), TrackSection(10_200, 48_000, "verse"))

    @Test
    fun `an aligned phrase ends at the vocal and a delayed start advances the cue by the tempo ratio`() {
        val mix = checkNotNull(plan(from, to))
        val beats = checkNotNull(mix.beats)
        assertEquals(40_000L, mix.window.startMs)
        assertEquals(48_000L, mix.endMs)
        assertEquals(2_040L, beats.cueAt(mix.window.startMs))
        assertEquals(2_550L, beats.cueAt(mix.window.startMs + 500))
        assertEquals(10_200L, beats.cueAt(mix.endMs))
        val preroll = AutoMixPlanner.prerollCue(beats, 0)
        assertEquals(840L, preroll)
        assertEquals(preroll, beats.cueAt(beats.launchMs(preroll)))
        assertEquals(0L, AutoMixPlanner.prerollCue(beats.copy(incomingStartMs = 300), 0))
        assertEquals(1_530L, AutoMixPlanner.prerollCue(beats, mix.window.startMs - 1_500))
    }

    @Test
    fun `measured silence can guide a fade without a beat grid`() {
        val outgoing = TrackStructure(durationMs, audioLevels = TrackAudioLevels(lastSoundMs = 46_000))
        val incoming = TrackStructure(durationMs, audioLevels = TrackAudioLevels(firstSoundMs = 1_000))
        val mix = checkNotNull(plan(outgoing, incoming))
        assertNull(mix.beats)
        assertEquals(44_500L, mix.window.startMs)
        assertEquals(46_000L, mix.endMs)
        assertEquals(1_000L, mix.window.cueMs)
    }

    @Test
    fun `a quiet intro can extend incompatible rhythms to the same ten second maximum as desktop`() {
        val incoming = structure(350, TrackSection(0, durationMs, "intro")).copy(
            audioLevels = TrackAudioLevels(0, 100, List(100) { -55.0 } + List(380) { -16.0 }),
        )
        val mix = checkNotNull(plan(from, incoming))
        assertNull(mix.beats)
        assertEquals(10_000L, mix.window.fadeMs)
        assertEquals(durationMs, mix.endMs)
    }

    @Test
    fun `missing late or mismatched analysis leaves the normal fade available`() {
        assertNull(plan(null, null))
        assertNull(plan(from, to, positionMs = 46_500))
        assertNull(plan(from.copy(durationMs = 40_000), to.copy(durationMs = 40_000)))
    }

    @Test
    fun `Android can use the same bundled grid as desktop when server analysis is missing`() {
        val duration = 244_689L
        val outgoing = track("El Alem Allah", "Amr Diab", 245)
        val incoming = track("Hemisphere", "FUCK ART, LET'S DANCE!", 247)
        val base = checkNotNull(AutoMixPlanner.standardFade(duration, 247_000L))
        val analysed = checkNotNull(AutoMixTransition.plan(outgoing, incoming, duration, base, null, null, 0))
        val beats = checkNotNull(analysed.beats)
        assertEquals(238_771L, beats.outgoingBeatMs)
        assertEquals(975L, beats.incomingBeatMs)
        assertEquals(minOf(duration, analysed.window.startMs + base.fadeMs), analysed.endMs)
        assertNull(AutoMixTransition.plan(outgoing, incoming, duration, base, null, null, 238_000))
    }

    @Test
    fun `an expired phrase falls back to the same base fade limit as desktop`() {
        assertEquals(FadeWindow(2_000L, 1_000L), AutoMixPlanner.standardFade(3_000L, 3_000L))
        assertNull(AutoMixPlanner.standardFade(2_999L, 3_000L))
        assertNotNull(AutoMixTransition.fallback(from, to, durationMs, durationMs, standard, 45_000))
        assertNull(AutoMixTransition.fallback(from, to, durationMs, durationMs, standard, 47_001))
    }

    @Test
    fun `both players reject the same recording and seek mismatches`() {
        assertEquals(CueStatus.UNKNOWN_DURATION, AutoMixPlanner.cueStatus(48_000, -1, true, true))
        assertEquals(CueStatus.NOT_SEEKABLE, AutoMixPlanner.cueStatus(48_000, 48_000, false, true))
        assertEquals(CueStatus.READY, AutoMixPlanner.cueStatus(48_000, 48_000, false, false))
        assertEquals(CueStatus.READY, AutoMixPlanner.cueStatus(48_000, 49_500, true, true))
        assertEquals(CueStatus.DIFFERENT_RECORDING, AutoMixPlanner.cueStatus(48_000, 49_501, true, true))
        assertTrue(AutoMixPlanner.matchesCue(1_085, 1_000))
        assertFalse(AutoMixPlanner.matchesCue(1_086, 1_000))
    }

    private fun plan(from: TrackStructure?, to: TrackStructure?, positionMs: Long = 0) =
        AutoMixTransition.plan(track("Old", "Artist", 48), track("New", "Artist", 48), durationMs, standard, from, to, positionMs)

    private fun track(title: String, artist: String, seconds: Int) =
        RecordingMetadata(title, artist, null, seconds, null, null)

    private fun structure(step: Long, vararg sections: TrackSection) = TrackStructure(
        durationMs,
        beatsMs = (0L..durationMs step step).toList(),
        downbeatsMs = (0L..durationMs step step * 4).toList(),
        sections = sections.toList(),
    )
}
