package com.platter.desktop.player

import com.platter.desktop.api.Song
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A crossfade out of the track playing: the song that follows it, and how long before the end the two start to overlap. */
data class CrossfadePlan(val toIndex: Int, val to: Song, val startMs: Long, val fadeMs: Long)

/**
 * What a crossfade is made of, apart from the audio: when it begins, whether a pair of tracks gets one at all, and how
 * loud each side is as it goes. No libvlc here, so it can be tested alone.
 */
object Crossfade {
    /** How long the two tracks overlap, for a track long enough to allow it. */
    const val DEFAULT_MS = 6_000L

    /** Less than this is a cut, not a fade. */
    const val MIN_FADE_MS = 1_000L

    /** How far ahead of the fade the next track is opened and held ready. */
    const val PREWARM_MS = 10_000L

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

        var fade = minOf(wantedMs, durationMs / 3)
        to.duration?.takeIf { it > 0 }?.let { fade = minOf(fade, it * 1000L / 3) }
        if (fade < MIN_FADE_MS) return null
        return CrossfadePlan(toIndex, to, durationMs - fade, fade)
    }

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
    fun gains(progress: Float): Pair<Float, Float> {
        val x = progress.coerceIn(0f, 1f) * (PI.toFloat() / 2)
        return sin(x) to cos(x)
    }
}
