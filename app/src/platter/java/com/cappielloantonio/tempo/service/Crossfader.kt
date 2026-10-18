package com.cappielloantonio.tempo.service

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.Preferences
import com.cappielloantonio.tempo.util.ReplayGainUtil
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Lets the end of one track and the start of the next play at the same time.
 *
 * The queue lives in one player, and a player plays one thing at a time, so
 * the overlap is built from two. The main player stays the truth about what
 * is playing - the notification, the scrobbler and the lyrics all read it -
 * and a second one, the helper, carries the tail of the track being left:
 *
 *  - Twelve seconds before the fade the helper is made and loaded with the
 *    same track, paused.
 *  - Eight seconds before the fade it starts playing along with the main
 *    player, muted. A player is heard only some tens of milliseconds after it
 *    is told to start, and never after quite the same time, so the two are
 *    compared once they have played for a while and the helper's sound is
 *    shifted by the difference, sample by sample (see [SampleShifter]).
 *  - At the fade the two swap over: for a moment both play the same audio,
 *    the main one fading out, the helper in. Two copies that are off by more
 *    than a few milliseconds play a bit of the track twice, which is heard as
 *    a stutter.
 *  - The main player then moves on to the next track, silent, and fades it in
 *    while the helper fades out what is left of the old one. From here the
 *    main player is already on the new track, so the app shows it at once.
 *
 * Whatever goes wrong - the helper not ready in time, a skip, a seek, the
 * queue changing under it - ends the fade where it is, with the main player
 * back at full volume. The track then goes on or gives way to the next as it
 * would have without a crossfade.
 *
 * Tracks that are meant to run into each other (the next number on the same
 * album) are left to the player's own gapless playback.
 */
@UnstableApi
class Crossfader(
        private val player: ExoPlayer,
        private val newHelper: (SampleShifter) -> ExoPlayer,
        private val canFade: () -> Boolean
) : Player.Listener, SharedPreferences.OnSharedPreferenceChangeListener {
    private enum class Phase { IDLE, ALIGNING, HANDOFF, FADING }

    private class Plan(
            val fromIndex: Int,
            val fromId: String,
            val toIndex: Int,
            val toId: String,
            val startMs: Long,
            val durationMs: Long
    ) {
        fun sameAs(other: Plan) = fromIndex == other.fromIndex && fromId == other.fromId &&
                toIndex == other.toIndex && toId == other.toId &&
                startMs == other.startMs && durationMs == other.durationMs
    }

    private val handler = Handler(Looper.getMainLooper())

    private var phase = Phase.IDLE
    private var plan: Plan? = null
    private var helper: ExoPlayer? = null
    private var shifter: SampleShifter? = null
    private var helperFailed = false
    private var messages = emptyList<PlayerMessage>()

    // Our own seek to the next track must not be mistaken for the listener's.
    private var ownSeek = false

    // Lining the helper up: where its position started to count from, when a shift will have been heard,
    // the differences read since, and whether they are close enough. The first difference after the helper
    // is put somewhere says how far off the lead was.
    private var settleFrom = C.TIME_UNSET
    private var shiftHeardAt = 0L
    private var samples = 0
    private var helperAhead = Long.MIN_VALUE
    private var mainAhead = Long.MIN_VALUE
    private val readings = ArrayDeque<Long>(READINGS)
    private var aligned = false
    private var justMoved = false

    private var outVolume = 1f
    private var handoffFrom = C.TIME_UNSET
    private var handoffWaitedMs = 0L
    private var toStarted = false
    private var tailAtStartMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            when (phase) {
                Phase.ALIGNING -> align()
                Phase.HANDOFF -> handoff()
                Phase.FADING -> fade()
                Phase.IDLE -> return
            }
            if (phase != Phase.IDLE) handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        player.addListener(this)
        App.getInstance().preferences.registerOnSharedPreferenceChangeListener(this)
        refresh()
    }

    fun release() {
        App.getInstance().preferences.unregisterOnSharedPreferenceChangeListener(this)
        player.removeListener(this)
        end()
        disarm()
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        if (key != Preferences.CROSSFADE) return
        if (!Preferences.isCrossfadeEnabled()) end(refreshAfter = false)
        rearm()
    }

    /* Works out again what to crossfade next, and keeps what is armed if it is still right. */
    fun refresh() {
        if (phase != Phase.IDLE) return

        val wanted = planNext()
        val armed = plan
        if (wanted != null && armed != null && wanted.sameAs(armed)) return

        disarm()
        if (wanted != null && player.currentPosition < wanted.startMs - MIN_LEAD_MS) arm(wanted)
    }

    private fun rearm() {
        if (phase != Phase.IDLE) return
        disarm()
        refresh()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) = refresh()

    override fun onRepeatModeChanged(repeatMode: Int) = rearm()

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = rearm()

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_READY -> refresh()
            Player.STATE_IDLE, Player.STATE_ENDED -> {
                end()
                disarm()
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (ownSeek) return
        if (phase != Phase.IDLE) end(refreshAfter = false)
        rearm()
    }

    override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
    ) {
        if (ownSeek || reason != Player.DISCONTINUITY_REASON_SEEK) return
        if (phase != Phase.IDLE) end(refreshAfter = false)
        rearm()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        /*
         * A pause before the fade lets the helper go rather than keep it and
         * its ticking for however long the pause lasts; playing again arms the
         * fade afresh, and the helper is made and lined up anew.
         */
        if (phase == Phase.ALIGNING && !playWhenReady) {
            end(refreshAfter = false)
            return
        }
        helper?.takeIf { phase != Phase.IDLE }?.playWhenReady = playWhenReady
        if (phase == Phase.IDLE && playWhenReady) refresh()
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        helper?.playbackParameters = playbackParameters
        if (phase == Phase.ALIGNING) resettle()
    }

    override fun onPlayerError(error: PlaybackException) {
        end()
        disarm()
    }

    /*
     * What would be crossfaded next, if anything: the track playing and the one
     * that follows it in play order, with the moment the fade would start.
     */
    private fun planNext(): Plan? {
        if (!Preferences.isCrossfadeEnabled() || !canFade()) return null
        if (player.repeatMode == Player.REPEAT_MODE_ONE) return null
        if (player.isCurrentMediaItemDynamic || player.isCurrentMediaItemLive) return null

        val fromIndex = player.currentMediaItemIndex
        val toIndex = player.nextMediaItemIndex
        if (fromIndex == C.INDEX_UNSET || toIndex == C.INDEX_UNSET || toIndex == fromIndex) return null

        val from = player.getMediaItemAt(fromIndex)
        val to = player.getMediaItemAt(toIndex)
        if (!isMusic(from) || !isMusic(to) || runsInto(from, to)) return null

        val length = player.duration
        if (length == C.TIME_UNSET || length <= 0) return null

        // A short track gives way faster: a third of it at most, on either side.
        var fade = FADE_MS
        fade = minOf(fade, length / 3)
        durationOf(to)?.let { fade = minOf(fade, it / 3) }
        if (fade < MIN_FADE_MS) return null

        return Plan(fromIndex, from.mediaId, toIndex, to.mediaId, length - fade, length)
    }

    private fun arm(wanted: Plan) {
        Log.d(TAG, "armed: crossfade at ${wanted.startMs} ms of ${wanted.durationMs}")
        plan = wanted
        helperFailed = false

        val prewarmAt = maxOf(0L, wanted.startMs - PREWARM_MS)
        val alignAt = maxOf(0L, wanted.startMs - PREROLL_MS)
        val now = player.currentPosition
        val pending = ArrayList<PlayerMessage>(2)
        if (now >= prewarmAt) prewarm() else pending += at(wanted.fromIndex, prewarmAt) { prewarm() }
        if (now < alignAt) pending += at(wanted.fromIndex, alignAt) { startAligning() }
        messages = pending
        if (now >= alignAt) startAligning()
    }

    private fun disarm() {
        messages.forEach { it.cancel() }
        messages = emptyList()
        plan = null
        if (phase == Phase.IDLE) dropHelper()
    }

    private fun at(index: Int, positionMs: Long, run: () -> Unit): PlayerMessage =
            player.createMessage { _, _ -> run() }
                    .setLooper(handler.looper)
                    .setPosition(index, positionMs)
                    .setDeleteAfterDelivery(true)
                    .also { it.send() }

    private fun prewarm() {
        val armed = plan ?: return
        if (helper != null || phase != Phase.IDLE) return
        if (player.currentMediaItemIndex != armed.fromIndex) return

        val shifts = SampleShifter()
        val made = newHelper(shifts)
        made.volume = 0f
        made.playbackParameters = player.playbackParameters
        made.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (helper !== made) return
                helperFailed = true
                // Before the fade it is the track playing that failed, and would only fail again; during it,
                // the one being left, so the new track is armed for its own fade as usual.
                if (phase != Phase.IDLE) end(refreshAfter = phase == Phase.FADING)
            }
        })
        made.setMediaItem(player.getMediaItemAt(armed.fromIndex), maxOf(0L, armed.startMs - PREROLL_MS) + lead)
        made.playWhenReady = false
        made.prepare()
        helper = made
        shifter = shifts
    }

    private fun dropHelper() {
        helper?.release()
        helper = null
        shifter = null
    }

    /* A few seconds before the fade: the helper starts playing along, muted, to be lined up. */
    private fun startAligning() {
        val h = helper
        if (plan == null || h == null || helperFailed || phase != Phase.IDLE) return

        phase = Phase.ALIGNING
        aligned = false
        moveHelper(h)
        h.playWhenReady = player.playWhenReady
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    /*
     * Puts the helper where the main player is plus the time it takes to be
     * heard, as last found. Only roughly: it is lined up by shifting from there.
     */
    private fun moveHelper(h: ExoPlayer) {
        h.seekTo(player.currentPosition + lead)
        resettle()
        justMoved = true
    }

    private fun resettle() {
        settleFrom = C.TIME_UNSET
        clearSamples()
        readings.clear()
        aligned = false
        justMoved = false
    }

    /*
     * Both players play the old track, the helper unheard, until the two are
     * lined up and the main one reaches the fade. If they cannot be lined up
     * in time the fade waits, and is shorter for it; with too little of the
     * track left for one, the track simply ends.
     */
    private fun align() {
        val armed = plan
        val h = helper
        val s = shifter
        if (armed == null || h == null || s == null || helperFailed || !stillOn(armed)) {
            end(refreshAfter = !helperFailed)
            return
        }

        val now = player.currentPosition
        measure(h, s)

        if (now < armed.startMs) return
        if (aligned && player.isPlaying) {
            startHandoff()
        } else if (armed.durationMs - now < MIN_FADE_MS + HANDOFF_MS) {
            Log.d(TAG, "no crossfade: the helper could not be lined up in time")
            end(refreshAfter = false)
        }
    }

    /* Compares where the two players are heard, once both have played for a while, and shifts the helper by that. */
    private fun measure(h: ExoPlayer, s: SampleShifter) {
        if (!h.isPlaying || !player.isPlaying) {
            settleFrom = C.TIME_UNSET
            clearSamples()
            readings.clear()
            return
        }

        // For a second or so after it is heard a player eases what it reports towards where it is.
        val position = h.currentPosition
        if (settleFrom == C.TIME_UNSET) settleFrom = position
        if (position < settleFrom + SETTLE_MS) return

        // A shift is heard only once the sound already handed to the speaker has played.
        val clock = SystemClock.elapsedRealtime()
        if (s.isShifting) shiftHeardAt = clock + SHIFT_HEARD_MS
        if (clock < shiftHeardAt) return

        /*
         * A player tells the app where it is only every ten milliseconds or so,
         * each on its own beat, so either number can be up to that much behind.
         * Against the clock the latest is the one furthest ahead, so over a run
         * of samples each player's furthest is taken as where it is.
         */
        helperAhead = maxOf(helperAhead, position + s.shiftedMs - clock)
        mainAhead = maxOf(mainAhead, player.currentPosition - clock)
        if (++samples < OFFSET_SAMPLES) return

        readings.addLast(helperAhead - mainAhead)
        clearSamples()
        if (readings.size > READINGS) readings.removeFirst()
        if (readings.size < READINGS) return

        // Even then each reading wanders by some milliseconds, so a second's worth of them is averaged.
        val offset = readings.average().roundToLong()
        val tolerance = if (aligned) ALIGN_LOOSE_MS else ALIGN_TOLERANCE_MS
        if (abs(offset) <= tolerance) {
            if (!aligned) Log.d(TAG, "aligned: helper is $offset ms ahead of the main player")
            aligned = true
            justMoved = false
            return
        }

        if (justMoved && abs(offset) <= MAX_LEAD_MS) lead = (lead - offset).coerceIn(0L, MAX_LEAD_MS)
        justMoved = false
        aligned = false
        readings.clear()
        if (abs(offset) > MAX_SHIFT_MS) {
            Log.d(TAG, "aligning: helper is $offset ms ahead of the main player, moving it")
            moveHelper(h)
        } else {
            Log.d(TAG, "aligning: helper is $offset ms ahead of the main player, shifting it")
            s.shift(-offset)
            shiftHeardAt = clock + SHIFT_HEARD_MS
        }
    }

    private fun clearSamples() {
        samples = 0
        helperAhead = Long.MIN_VALUE
        mainAhead = Long.MIN_VALUE
    }

    private fun stillOn(armed: Plan) = player.currentMediaItemIndex == armed.fromIndex &&
            player.nextMediaItemIndex == armed.toIndex &&
            player.currentMediaItem?.mediaId == armed.fromId

    /* The main player is at the point where the fade begins, with the helper lined up. */
    private fun startHandoff() {
        outVolume = ReplayGainUtil.getVolume()
        handoffFrom = C.TIME_UNSET
        handoffWaitedMs = 0
        phase = Phase.HANDOFF
    }

    /*
     * Both players play the old track, one fading in as the other fades out.
     * The two copies are the same signal, so their gains add up to one rather
     * than following the equal-power curve of the crossfade proper.
     */
    private fun handoff() {
        val armed = plan
        val h = helper
        if (armed == null || h == null || player.currentMediaItem?.mediaId != armed.fromId ||
                player.nextMediaItemIndex != armed.toIndex) {
            end()
            return
        }

        if (!h.isPlaying) {
            if (player.playWhenReady) handoffWaitedMs += TICK_MS
            if (handoffWaitedMs > HANDOFF_WAIT_MS) {
                Log.d(TAG, "no crossfade: helper would not start")
                end()
            }
            return
        }

        if (handoffFrom == C.TIME_UNSET) {
            handoffFrom = h.currentPosition
            Log.d(TAG, "handoff: helper is ${h.currentPosition + (shifter?.shiftedMs ?: 0) - player.currentPosition} ms ahead of the main player")
        }

        val progress = ((h.currentPosition - handoffFrom) / HANDOFF_MS.toFloat()).coerceIn(0f, 1f)
        h.volume = outVolume * progress
        ReplayGainUtil.setFade(player, 1f - progress)

        if (progress >= 1f) moveOn(armed)
    }

    /* The old track now plays from the helper alone; the main player takes the new one. */
    private fun moveOn(armed: Plan) {
        ReplayGainUtil.setFade(player, 0f)
        ownSeek = true
        try {
            player.seekToDefaultPosition(armed.toIndex)
        } finally {
            ownSeek = false
        }
        toStarted = false
        phase = Phase.FADING
    }

    /*
     * The new track fades in as the end of the old one fades out. Both follow
     * how much of the old track is left, so that it reaches silence exactly as
     * it ends and the new one is at full volume by then.
     */
    private fun fade() {
        val armed = plan
        val h = helper
        if (armed == null || h == null || player.currentMediaItem?.mediaId != armed.toId) {
            end()
            return
        }

        val lengthMs = h.duration.takeIf { it != C.TIME_UNSET } ?: armed.durationMs
        val left = (lengthMs - h.currentPosition).coerceAtLeast(0)

        if (!toStarted) {
            // The new track is still loading; the old one plays on at full volume meanwhile.
            if (h.playbackState == Player.STATE_ENDED) {
                end()
                return
            }
            if (!player.isPlaying) return

            toStarted = true
            tailAtStartMs = left
            Log.d(TAG, "fade: new track audible with ${left} ms of the old one left")
            if (left < MIN_FADE_MS) {
                end()
                return
            }
        }

        val progress = (1f - left / tailAtStartMs.toFloat()).coerceIn(0f, 1f)
        h.volume = outVolume * cos(progress * PI.toFloat() / 2)
        ReplayGainUtil.setFade(player, sin(progress * PI.toFloat() / 2))

        if (progress >= 1f || h.playbackState == Player.STATE_ENDED) end()
    }

    /* Leaves the fade, finished or not, with everything back to how a plain track plays. */
    private fun end(refreshAfter: Boolean = true) {
        handler.removeCallbacks(tick)
        val wasFading = phase != Phase.IDLE
        if (wasFading) Log.d(TAG, "end: was $phase")
        phase = Phase.IDLE
        ReplayGainUtil.setFade(player, 1f)
        dropHelper()
        plan = null
        messages.forEach { it.cancel() }
        messages = emptyList()
        if (wasFading && refreshAfter) refresh()
    }

    private fun isMusic(item: MediaItem) =
            item.mediaMetadata.extras?.getString("type") == Constants.MEDIA_TYPE_MUSIC

    private fun durationOf(item: MediaItem) =
            item.mediaMetadata.extras?.getInt("duration", 0)?.takeIf { it > 0 }?.let { it * 1000L }

    /*
     * The next number on the same album is taken to be meant to follow without
     * a seam - a live record, a suite, a concept album - and is left to run on.
     */
    private fun runsInto(from: MediaItem, to: MediaItem): Boolean {
        val a = from.mediaMetadata
        val b = to.mediaMetadata
        val albumA = a.extras?.getString("albumId")
        val albumB = b.extras?.getString("albumId")
        if (albumA == null || albumA != albumB) return false

        val number = a.trackNumber ?: return false
        val next = b.trackNumber ?: return false
        return number > 0 && next == number + 1 && (a.discNumber ?: 0) == (b.discNumber ?: 0)
    }

    private companion object {
        const val TAG = "Crossfader"

        /* How long the two tracks overlap, for a track long enough to allow it. */
        const val FADE_MS = 6_000L
        const val MIN_FADE_MS = 1_500L

        /* How far ahead of the fade the helper is made and loaded. */
        const val PREWARM_MS = 12_000L

        /* A fade that would begin within this of the present is not worth arming. */
        const val MIN_LEAD_MS = 300L

        /* How long before the fade the helper starts playing, unheard, to be lined up. */
        const val PREROLL_MS = 8_000L

        /* How long a started helper plays before what it reports is believed, and
         * how long a shift takes to be heard, at most, once it has gone through. */
        const val SETTLE_MS = 1_500L
        const val SHIFT_HEARD_MS = 1_000L

        /* Samples of the two positions in one reading, and readings averaged. */
        const val OFFSET_SAMPLES = 12
        const val READINGS = 8

        /* Two copies of a track this close together sound as one. Once lined up
         * the helper is left alone unless it is found further off than the
         * looser limit, so that a wandering reading does not undo it. */
        const val ALIGN_TOLERANCE_MS = 3L
        const val ALIGN_LOOSE_MS = 6L

        /* Further off than this the helper is put back by seeking, not shifted. */
        const val MAX_SHIFT_MS = 1_000L

        /*
         * How far ahead of the main player a helper is put when it starts:
         * about how late it is heard. Each device has its own; this is a first
         * guess, and what was found the last time is used from then on.
         */
        var lead = 60L
        const val MAX_LEAD_MS = 250L

        /* Short, so that two copies a few milliseconds apart are not heard together for long. */
        const val HANDOFF_MS = 60L
        const val HANDOFF_WAIT_MS = 1_500L
        const val TICK_MS = 10L
    }
}
