package com.platter.desktop

import com.platter.desktop.player.ListenTracker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListenTrackerTest {
    private val threeMinutes = 180_000L

    @Test
    fun `counts at half the length of a short track`() {
        val t = ListenTracker("s", threeMinutes)
        t.resume(1_000)
        assertFalse(t.reachedMark(1_000 + 89_999))
        assertTrue(t.reachedMark(1_000 + 90_000))
        assertEquals(1_000, t.startedAt)
    }

    @Test
    fun `counts at four minutes for a long track`() {
        val t = ListenTracker("s", 20 * 60_000L)
        t.resume(0)
        assertFalse(t.reachedMark(239_999))
        assertTrue(t.reachedMark(240_000))
    }

    @Test
    fun `counts only once`() {
        val t = ListenTracker("s", threeMinutes)
        t.resume(0)
        assertTrue(t.reachedMark(100_000))
        assertFalse(t.reachedMark(150_000))
    }

    @Test
    fun `time paused is not listening`() {
        val t = ListenTracker("s", threeMinutes)
        t.resume(0)
        t.pause(60_000)
        assertFalse(t.reachedMark(10 * 60_000))
        t.resume(10 * 60_000)
        assertTrue(t.reachedMark(10 * 60_000 + 30_000))
    }

    @Test
    fun `a track of unknown length never counts`() {
        val t = ListenTracker("s", 0)
        t.resume(0)
        assertFalse(t.reachedMark(10 * 60_000))
    }

    @Test
    fun `start over begins a new play but keeps hearing`() {
        val t = ListenTracker("s", threeMinutes)
        t.resume(0)
        assertTrue(t.reachedMark(100_000))

        val again = t.startOver(120_000)

        assertFalse(again.counted)
        assertEquals(120_000, again.startedAt)
        assertTrue(again.reachedMark(120_000 + 90_000))
    }
}
