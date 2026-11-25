package com.cappielloantonio.tempo.service

import com.platter.automix.BeatMixPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AutoMixCueSyncTest {
    private val mix = BeatMixPlan(28_000, 12_000, 4_000, 40_000, 1.0)

    @Test
    fun `an unstable clock is ignored and the next seek accounts for startup`() {
        val sync = AutoMixCueSync()
        sync.reset(startMs = 20_000, overlapMs = 8_000)
        sync.onSeek(outgoingMs = 17_150, cueMs = 1_400)
        assertNull(sync.retryCue(mix, outgoingMs = 17_550, incomingMs = 1_450))
        assertNull(sync.retryCue(mix, outgoingMs = 17_610, incomingMs = 1_470))
        assertNull(sync.retryCue(mix, outgoingMs = 17_720, incomingMs = 1_580))
        assertEquals(2_120L, sync.retryCue(mix, outgoingMs = 17_730, incomingMs = 1_590))

        sync.onSeek(outgoingMs = 17_730, cueMs = 2_120)
        assertNull(sync.retryCue(mix, outgoingMs = 18_080, incomingMs = 2_120))
        assertEquals(1, sync.retries)
    }

    @Test
    fun `a seek is skipped when the clock could not settle before the overlap`() {
        val sync = AutoMixCueSync()
        sync.reset(startMs = 20_000, overlapMs = 8_000)
        sync.onSeek(outgoingMs = 19_000, cueMs = 3_250)
        assertNull(sync.retryCue(mix, outgoingMs = 19_380, incomingMs = 3_300))
        assertNull(sync.retryCue(mix, outgoingMs = 19_570, incomingMs = 3_440))
        assertNull(sync.retryCue(mix, outgoingMs = 19_690, incomingMs = 3_560))
        assertEquals(0, sync.retries)
    }

    @Test
    fun `short intros still open early and a late correction stays on the selected beat grid`() {
        val short = mix.copy(incomingBeatMs = 8_560, incomingStartMs = 560, outgoingBeatPeriodMs = 500.0)
        val sync = AutoMixCueSync()
        sync.reset(startMs = short.launchMs(), overlapMs = 8_000)
        assertEquals(18_800L, AutoMixCueSync.openAt(short, prerollCueMs = 0))

        sync.onSeek(outgoingMs = 18_810, cueMs = 0)
        sync.onReady(outgoingMs = 18_950)
        assertNull(sync.retryCue(short, outgoingMs = 19_440, incomingMs = 140))
        assertEquals(500L, sync.retryCue(short, outgoingMs = 19_560, incomingMs = 260))
        assertEquals(20_500L, sync.startMs)

        assertFalse(sync.deferUntilBeat(short, earliestMs = 22_001))
        assertEquals(20_500L, sync.startMs)
    }
}
