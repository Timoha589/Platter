package com.cappielloantonio.tempo.service

import com.cappielloantonio.tempo.service.AutoMixTimeline.Direction
import com.cappielloantonio.tempo.service.AutoMixTimeline.Mode
import com.cappielloantonio.tempo.service.AutoMixTimeline.Segment
import com.cappielloantonio.tempo.service.AutoMixTimeline.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoMixTimelineTest {
    @Test
    fun handoffPublishesBothTracksBeforeSwitching() {
        val previous = Segment(1, "repeat", 0, 1_500, Mode.FALLBACK_CROSSFADE, Direction.INCOMING)
        val outgoing = Segment(1, "repeat", 80_000, 92_000, Mode.BEAT_MIX, Direction.OUTGOING)
        val preview = Segment.incoming(2, "repeat", 2_000, 12_000, 0.95, 100_000, Mode.BEAT_MIX)!!
        val handoff = State(outgoing, previous, preview)

        assertEquals(listOf(previous, outgoing), handoff.segmentsFor(1, "repeat"))
        assertEquals(listOf(preview), handoff.segmentsFor(2, "repeat"))
        assertEquals(emptyList<Segment>(), handoff.segmentsFor(2, "another-track"))

        val actual = preview.copy(startMs = 2_090, endMs = 13_350)
        assertEquals(listOf(actual), State(outgoing, actual).segmentsFor(2, "repeat"))
    }

    @Test
    fun fallbackReplacesTheBeatPreviewAndKeepsTheHeardPortion() {
        val fallback = Segment.incoming(2, "next", 1_000, 7_000, 1.0, 6_500, Mode.FALLBACK_CROSSFADE)!!
        assertEquals(6_500L, fallback.endMs)
        assertEquals(4_200L, fallback.heardThrough(4_200)?.endMs)
        assertEquals(fallback, fallback.heardThrough(7_000))
        assertNull(fallback.heardThrough(1_000))
        assertNull(Segment.incoming(2, "next", 6_500, 7_000, 1.0, 6_500, Mode.FALLBACK_CROSSFADE))
    }
}
