package com.cappielloantonio.tempo.service

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
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
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lets the end of one track and the start of the next play at the same time.
 *
 * The queue lives in one player, and a player plays one thing at a time, so
 * the overlap is built from two. The main player stays the truth about what
 * is playing - the notification, the scrobbler and the lyrics all read it -
 * and a second one, the helper, carries the tail of the track being left:
 *
 *  - Ten seconds before the fade the helper is made, pointed at the same
 *    track a little past where the fade will begin, and left paused and muted.
 *  - When the main player reaches that point the helper starts. For a moment
 *    both play the same audio - the main one fading out, the helper in - which
 *    hides that two players never line up to the millisecond.
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
        private val newHelper: () -> ExoPlayer,
        private val canFade: () -> Boolean
) : Player.Listener, SharedPreferences.OnSharedPreferenceChangeListener {
    private enum class Phase { IDLE, HANDOFF, FADING }

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
    private var helperReady = false
    private var helperFailed = false
    private var messages = emptyList<PlayerMessage>()

    // Our own seek to the next track must not be mistaken for the listener's.
    private var ownSeek = false

    private var outVolume = 1f
    private var handoffFrom = C.TIME_UNSET
    private var handoffWaitedMs = 0L
    private var toStarted = false
    private var tailAtStartMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            when (phase) {
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
        helper?.takeIf { phase != Phase.IDLE }?.playWhenReady = playWhenReady
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        helper?.playbackParameters = playbackParameters
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
        helperReady = false
        helperFailed = false

        val prewarmAt = maxOf(0L, wanted.startMs - PREWARM_MS)
        val pending = ArrayList<PlayerMessage>(2)
        if (player.currentPosition >= prewarmAt) prewarm() else pending += at(wanted.fromIndex, prewarmAt) { prewarm() }
        pending += at(wanted.fromIndex, wanted.startMs) { begin() }
        messages = pending
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

        val made = newHelper()
        made.volume = 0f
        made.playbackParameters = player.playbackParameters
        made.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (helper !== made) return
                if (playbackState == Player.STATE_READY) helperReady = true
            }

            override fun onPlayerError(error: PlaybackException) {
                if (helper !== made) return
                helperFailed = true
                if (phase != Phase.IDLE) end()
            }
        })
        made.setMediaItem(player.getMediaItemAt(armed.fromIndex), armed.startMs + ALIGN_MS)
        made.playWhenReady = false
        made.prepare()
        helper = made
    }

    private fun dropHelper() {
        helper?.release()
        helper = null
        helperReady = false
    }

    /* The main player is at the point where the fade begins. */
    private fun begin() {
        val armed = plan ?: return
        messages = emptyList()

        val ready = helper?.takeIf { helperReady && !helperFailed }
        val stillRight = player.currentMediaItemIndex == armed.fromIndex &&
                player.nextMediaItemIndex == armed.toIndex &&
                player.currentMediaItem?.mediaId == armed.fromId
        if (ready == null || !stillRight || !player.isPlaying) {
            Log.d(TAG, "no crossfade: helper ready=${ready != null}, queue unchanged=$stillRight")
            disarm()
            return
        }

        outVolume = ReplayGainUtil.getVolume()
        handoffFrom = C.TIME_UNSET
        handoffWaitedMs = 0
        phase = Phase.HANDOFF
        ready.playWhenReady = true
        handler.removeCallbacks(tick)
        handler.post(tick)
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
            Log.d(TAG, "handoff: helper is ${h.currentPosition - player.currentPosition} ms ahead of the main player")
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

        /* The helper is pointed this far past the fade's start, which is about
         * how late the main player has got by the time the helper is heard. */
        const val ALIGN_MS = 8L

        const val HANDOFF_MS = 150L
        const val HANDOFF_WAIT_MS = 1_500L
        const val TICK_MS = 10L
    }
}
