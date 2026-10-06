package com.platter.desktop.player

/**
 * Decides when a track has been listened to - the rule from the Android app's
 * ScrobbleTracker, without the Media3 listener around it.
 *
 * A play counts once the track has been heard for half its length or four
 * minutes, whichever comes first (the rule Last.fm and ListenBrainz apply, so
 * what the server counts and what it passes on agree). Heard means time spent
 * playing: seeking ahead is not listening, going back to hear a part again is,
 * and time paused is neither. The play counts the moment the mark is passed
 * rather than at the end, so a skip or closing the app afterwards cannot lose
 * it, and it carries the time the track started, which is where the server
 * files it however late it arrives.
 *
 * Time is passed in, so the rule is testable without waiting.
 */
class ListenTracker(private val songId: String, private val durationMs: Long) {
    /** Wall-clock time the track was first heard; 0 until it is. */
    var startedAt = 0L
        private set
    var counted = false
        private set

    private var heardMs = 0L
    private var hearingSince = NOT_HEARING

    val isHearing get() = hearingSince != NOT_HEARING

    fun resume(now: Long) {
        if (isHearing) return
        hearingSince = now
        if (startedAt == 0L) startedAt = now
    }

    fun pause(now: Long) {
        if (!isHearing) return
        heardMs += now - hearingSince
        hearingSince = NOT_HEARING
    }

    fun heard(now: Long): Long = heardMs + if (isHearing) now - hearingSince else 0L

    /** True exactly once: the first time the mark is passed. */
    fun reachedMark(now: Long): Boolean {
        if (counted || durationMs <= 0) return false
        val mark = minOf(durationMs / 2, FOUR_MINUTES_MS)
        if (heard(now) < mark) return false
        counted = true
        return true
    }

    /** The same track heard again from the start is a new play. */
    fun startOver(now: Long): ListenTracker {
        val again = ListenTracker(songId, durationMs)
        if (isHearing) again.resume(now)
        return again
    }

    companion object {
        private const val NOT_HEARING = -1L
        const val FOUR_MINUTES_MS = 4 * 60 * 1000L
        /** Seeking back to within this of the start counts as playing it again. */
        const val RESTART_WITHIN_MS = 3_000L
    }
}
