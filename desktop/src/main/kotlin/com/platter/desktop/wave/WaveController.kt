package com.platter.desktop.wave

import com.platter.desktop.player.Leave
import com.platter.desktop.player.PlayerController
import com.platter.desktop.player.PlayerState
import com.platter.desktop.player.TrackListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps a wave going, and tells the server how it is being listened to.
 *
 * The home screen only starts a wave: one batch, handed to the player like any
 * other queue. From then on this controller fetches the next batch while a few
 * tracks of the current one are still to come - so the next batch is decided
 * with the skips and finishes of this one already known - and appends it to
 * the queue.
 *
 * Feedback is read off the player's own transitions: a track that ran out, or
 * was left after half its length (four minutes at most - the rule the scrobble
 * counts by), was finished; one left earlier was skipped, with the position it
 * was left at, which the server uses to tell "not this" from "enough of this".
 * Going back to an earlier track is neither.
 */
class WaveController(
    private val scope: CoroutineScope,
    private val player: PlayerController,
    private val state: WaveState,
    private val client: () -> WaveClient?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var fetching = false
    /* "Never": a clock that starts near zero must not read the first fetch as a retry too soon. */
    private var failedAt = Long.MIN_VALUE / 2

    init {
        player.trackListener = TrackListener { song, positionMs, durationMs, how -> onLeft(song.id, positionMs, durationMs, how) }
        scope.launch { player.state.collect { refillIfLow(it) } }
    }

    internal fun onLeft(id: String?, positionMs: Long, durationMs: Long, how: Leave) {
        if (id == null || !state.isWaveTrack(id)) return
        val type = when (how) {
            Leave.Auto -> WaveEvent.COMPLETE
            Leave.Forward -> if (heardEnough(positionMs, durationMs)) WaveEvent.COMPLETE else WaveEvent.SKIP
            Leave.Back -> return
        }
        state.addEvent(WaveEvent(id, type, positionMs, durationMs))
    }

    private fun heardEnough(positionMs: Long, durationMs: Long): Boolean =
        if (durationMs <= 0) positionMs >= MAX_NEEDED_MS else positionMs >= minOf(durationMs / 2, MAX_NEEDED_MS)

    private fun refillIfLow(s: PlayerState) {
        val current = s.current ?: return
        if (!state.isWaveTrack(current.id) || fetching) return

        val left = s.queue.songs.size - s.queue.index - 1
        if (left > REFILL_WHEN_LEFT) return
        // After a failure, wait before asking again - not so long when the queue is about to run dry.
        val wait = if (left > 0) RETRY_AFTER_MS else RETRY_WHEN_DRY_MS
        if (now() - failedAt < wait) return

        val wave = client() ?: return
        val session = state.session() ?: return
        val events = state.drainEvents()
        fetching = true
        scope.launch {
            val result = try {
                wave.next(session, events)
            } finally {
                fetching = false
            }
            when (result) {
                is WaveResult.Ok -> {
                    state.adopt(result.batch)
                    // Only if the listener is still on the wave: they may have started an album meanwhile.
                    if (state.isWaveTrack(player.state.value.current?.id)) player.append(result.batch.songs())
                }
                is WaveResult.Failed -> {
                    state.restoreEvents(events)
                    failedAt = now()
                }
            }
        }
    }

    private companion object {
        /* Ask for more with this many tracks still to come: ~10 minutes of slack. */
        const val REFILL_WHEN_LEFT = 3
        const val RETRY_AFTER_MS = 60_000L
        const val RETRY_WHEN_DRY_MS = 5_000L
        const val MAX_NEEDED_MS = 4 * 60 * 1000L
    }
}
