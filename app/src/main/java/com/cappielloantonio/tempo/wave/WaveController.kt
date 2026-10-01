package com.cappielloantonio.tempo.wave

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.service.MediaManager

/**
 * Keeps a wave going from inside the player service, and tells the server how
 * it is being listened to.
 *
 * The home screen only starts a wave: one batch, handed to the player like any
 * other queue. From then on this listener, which lives as long as the player
 * does, fetches the next batch while a few tracks of the current one are still
 * to come - so the next batch is decided with the skips and finishes of this
 * one already known - and appends it to the queue. Nothing waits on the app
 * being open.
 *
 * Feedback is read off the player's own transitions: a track that ran out, or
 * was left after half its length (four minutes at most - the rule the scrobble
 * counts by), was finished; one left earlier was skipped, with the position it
 * was left at, which the server uses to tell "not this" from "enough of this".
 * Going back to an earlier track is neither.
 */
@UnstableApi
class WaveController(private val player: Player) : Player.Listener {
    private var fetching = false
    private var failedAt = 0L

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refillIfLow()

    // A queue being set - the wave starting, or restored after a restart.
    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) refillIfLow()
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        val item = oldPosition.mediaItem ?: return
        val extras = item.mediaMetadata.extras ?: return
        val id = extras.getString("id") ?: return
        if (!WaveState.isWaveTrack(id)) return

        val durationMs = extras.getInt("duration", 0) * 1000L
        val type = when (reason) {
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> WaveEvent.COMPLETE
            Player.DISCONTINUITY_REASON_SEEK, Player.DISCONTINUITY_REASON_SKIP -> {
                if (newPosition.mediaItemIndex <= oldPosition.mediaItemIndex) return
                if (heardEnough(oldPosition.positionMs, durationMs)) WaveEvent.COMPLETE else WaveEvent.SKIP
            }
            else -> return
        }
        WaveState.addEvent(WaveEvent(id, type, oldPosition.positionMs, durationMs))
    }

    private fun heardEnough(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0) return positionMs >= MAX_NEEDED_MS
        return positionMs >= minOf(durationMs / 2, MAX_NEEDED_MS)
    }

    private fun refillIfLow() {
        val current = player.currentMediaItem ?: return
        if (!WaveState.isWaveTrack(current.mediaMetadata.extras?.getString("id"))) return
        if (fetching) return

        val left = player.mediaItemCount - player.currentMediaItemIndex - 1
        if (left > REFILL_WHEN_LEFT) return
        // After a failure, wait before asking again - unless the queue is about to run dry.
        if (left > 0 && SystemClock.elapsedRealtime() - failedAt < RETRY_AFTER_MS) return

        val session = WaveState.session() ?: return
        val events = WaveState.drainEvents()
        fetching = true
        WaveClient.next(session, events) { result ->
            fetching = false
            when (result) {
                is WaveResult.Ok -> {
                    WaveState.adopt(result.batch)
                    val songs = WaveState.songs(result.batch)
                    if (songs.isNotEmpty()) MediaManager.appendToQueue(player, songs)
                }
                is WaveResult.Failed -> {
                    WaveState.restoreEvents(events)
                    failedAt = SystemClock.elapsedRealtime()
                    // The wave cannot go on: rather than stop, carry on the way
                    // any queue does when it runs out.
                    if (player.mediaItemCount - player.currentMediaItemIndex - 1 == 0) {
                        MediaManager.continuousPlayAfterWave(player.currentMediaItem)
                    }
                }
            }
        }
    }

    private companion object {
        /* Ask for more with this many tracks still to come: ~10 minutes of slack. */
        const val REFILL_WHEN_LEFT = 3
        const val RETRY_AFTER_MS = 60_000L
        const val MAX_NEEDED_MS = 4 * 60 * 1000L
    }
}
