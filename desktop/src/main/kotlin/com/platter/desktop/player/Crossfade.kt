package com.platter.desktop.player

import com.platter.automix.AutoMixPlanner
import com.platter.automix.FadeWindow
import com.platter.automix.audibleEdges
import com.platter.automix.equalPowerGains
import com.platter.desktop.api.Song
import com.platter.desktop.api.TrackStructure

/** The next song and where the two tracks overlap at their original speeds. */
data class CrossfadePlan(
    val toIndex: Int,
    val to: Song,
    val startMs: Long,
    val fadeMs: Long,
    val cueMs: Long = 0,
    val cueRecordingDurationMs: Long? = null,
    val incomingVocalMs: Long? = null,
)

/**
 * What a crossfade is made of, apart from the audio: when it begins, whether a pair of tracks gets one at all, and how
 * loud each side is as it goes. No libvlc here, so it can be tested alone.
 */
object Crossfade {
    /** How long the two tracks overlap, for a track long enough to allow it. */
    const val DEFAULT_MS = AutoMixPlanner.DEFAULT_FADE_MS

    /** Less than this is a cut, not a fade. */
    const val MIN_FADE_MS = AutoMixPlanner.MIN_FADE_MS

    /** How far ahead of the fade the next track is opened and held ready. */
    const val PREWARM_MS = AutoMixPlanner.PREWARM_MS

    /**
     * The crossfade into the track that follows the current one, or null for none: nothing follows, repeat-one, a podcast
     * or a station on either side, or two tracks meant to run on from each other. A short track gives way faster - a third
     * of it at most, on either side. [durationMs] is the length of the current track.
     */
    fun plan(queue: PlayQueue, durationMs: Long, wantedMs: Long = DEFAULT_MS): CrossfadePlan? {
        val from = queue.current ?: return null
        val toIndex = queue.after(auto = true) ?: return null
        if (toIndex == queue.index) return null
        val to = queue.songs[toIndex]
        if (!from.isMusic || !to.isMusic || runsInto(from, to)) return null
        if (durationMs <= 0) return null

        val window = AutoMixPlanner.standardFade(durationMs, to.duration?.times(1_000L), wantedMs) ?: return null
        return CrossfadePlan(toIndex, to, window.startMs, window.fadeMs)
    }

    /** Measured quiet edges can guide a crossfade even when the two beat grids are incompatible. */
    internal fun audibleEdges(plan: CrossfadePlan, durationMs: Long, from: TrackStructure?, to: TrackStructure?): CrossfadePlan =
        plan.withWindow(audibleEdges(plan.window(), durationMs, plan.to.duration?.times(1000L) ?: 0, from, to, MIN_FADE_MS))

    /**
     * The next number on the same album is taken to be meant to follow without a seam - a live record, a suite, a concept
     * album - and is left to run on by itself.
     */
    fun runsInto(from: Song, to: Song): Boolean {
        val album = from.albumId ?: return false
        if (album != to.albumId) return false
        val number = from.track ?: return false
        return number > 0 && to.track == number + 1 && (from.discNumber ?: 0) == (to.discNumber ?: 0)
    }

    /**
     * How loud the track coming in and the one going out are, as a share of the volume, [progress] of the way through
     * (0..1). Equal power: the two add up to the same loudness all the way instead of dipping in the middle.
     */
    fun gains(progress: Float): Pair<Float, Float> = equalPowerGains(progress)

    internal fun mixGains(progress: Float): Pair<Float, Float> = com.platter.automix.mixGains(progress)

    /** Cross the bass over a single beat centered in the overlap, independently of the overall volumes. */
    internal fun bassGains(elapsedMs: Double, overlapMs: Long, beatPeriodMs: Double): Pair<Float, Float> =
        com.platter.automix.bassGains(elapsedMs, overlapMs, beatPeriodMs)
}

internal fun CrossfadePlan.window() = FadeWindow(startMs, fadeMs, cueMs, cueRecordingDurationMs, incomingVocalMs)

internal fun CrossfadePlan.withWindow(window: FadeWindow) = copy(
    startMs = window.startMs, fadeMs = window.fadeMs, cueMs = window.cueMs,
    cueRecordingDurationMs = window.cueRecordingDurationMs, incomingVocalMs = window.incomingVocalMs,
)
