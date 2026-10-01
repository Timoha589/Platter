package com.cappielloantonio.tempo.service

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.repository.SongRepository
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.Preferences

/**
 * Decides when a track has been listened to, and tells the server.
 *
 * A play used to count only when a track ran out on its own. Skipping away
 * from a long track a few seconds before its end counted for nothing, while
 * seeking to its last seconds and letting them run counted in full; neither
 * the home screen's listening history nor the server's play counts told what
 * had actually been heard.
 *
 * Now a play counts once the track has been heard for half its length or four
 * minutes, whichever comes first - the rule Last.fm and ListenBrainz apply, so
 * what the server counts and what it passes on to them agree. Heard means time
 * spent playing, at the speed it was played: seeking ahead is not listening,
 * going back to hear a part again is, and a buffering stall is neither. The
 * play counts the moment the mark is passed rather than at the end, so it is
 * not lost to a skip, a new queue or the app being closed afterwards, and it
 * carries the time the track started, which is where the server files it
 * however late it arrives.
 *
 * "Now playing" goes to the server once when a track starts to be heard, and
 * again on resuming from a pause - not every time a stall clears, and not for
 * a queue lined up paused, which is nobody listening.
 */
@UnstableApi
class ScrobbleTracker(private val player: Player) : Player.Listener {
    private class Listen(val item: MediaItem) {
        /* Wall-clock time the track was first heard; 0 until it is. */
        var startedAt = 0L
        var heardMs = 0L
        var counted = false
        var announced = false
    }

    private val songRepository = SongRepository()
    private val handler = Handler(Looper.getMainLooper())
    private val check = Runnable { check() }

    private var listen: Listen? = null
    private var hearingSince = NOT_HEARING
    private var hearingSpeed = 1f
    private var pausedByUser = false

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        stopHearing()
        // Repeating a track comes through here too, and is heard anew.
        listen = mediaItem?.takeIf(::isLibraryTrack)?.let(::Listen)
        if (player.isPlaying) startHearing()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            startHearing()
        } else {
            stopHearing()
            // isPlaying drops on a stall too; only playWhenReady says it was paused.
            if (!player.playWhenReady) pausedByUser = true
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        // Playing the queue's last track again once it has ended is a new play.
        if (playbackState == Player.STATE_ENDED) startOver()
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        // So is going back to the start of one that has already counted.
        if (reason == Player.DISCONTINUITY_REASON_SEEK &&
                oldPosition.mediaItemIndex == newPosition.mediaItemIndex &&
                newPosition.positionMs < RESTART_WITHIN_MS
        ) {
            startOver()
        }
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        if (hearingSince != NOT_HEARING) {
            stopHearing()
            startHearing()
        }
    }

    fun release() {
        stopHearing()
    }

    /*
     * The time heard so far is added up first: the end of a track can be
     * reported before its playing stops, and the play may count only then.
     */
    private fun startOver() {
        val hearing = hearingSince != NOT_HEARING

        stopHearing()
        listen?.takeIf { it.counted }?.let { listen = Listen(it.item) }
        if (hearing) startHearing()
    }

    private fun startHearing() {
        val listen = listen ?: return
        if (hearingSince != NOT_HEARING) return

        hearingSince = SystemClock.elapsedRealtime()
        hearingSpeed = player.playbackParameters.speed
        if (listen.startedAt == 0L) listen.startedAt = System.currentTimeMillis()

        if (!listen.announced || pausedByUser) announce(listen)
        pausedByUser = false

        check()
    }

    private fun stopHearing() {
        if (hearingSince == NOT_HEARING) return

        listen?.let { it.heardMs += heardSinceStart() }
        hearingSince = NOT_HEARING
        handler.removeCallbacks(check)

        check()
    }

    private fun heardSinceStart() =
            ((SystemClock.elapsedRealtime() - hearingSince) * hearingSpeed).toLong()

    private fun check() {
        val listen = listen ?: return
        if (listen.counted) return

        val hearing = hearingSince != NOT_HEARING
        val heard = listen.heardMs + if (hearing) heardSinceStart() else 0
        val needed = neededMs(listen.item)

        if (heard >= needed) {
            listen.counted = true
            count(listen)
        } else if (hearing) {
            handler.removeCallbacks(check)
            handler.postDelayed(check, ((needed - heard) / hearingSpeed).toLong() + CHECK_SLACK_MS)
        }
    }

    /*
     * The length the server gave, or failing that the one the player worked
     * out - which is only this track's while it is the one playing.
     */
    private fun neededMs(item: MediaItem): Long {
        val duration = item.mediaMetadata.extras?.getInt("duration", 0)?.takeIf { it > 0 }?.let { it * 1000L }
                ?: player.duration.takeIf { it != C.TIME_UNSET && it > 0 && player.currentMediaItem == item }
                ?: return MAX_NEEDED_MS

        return minOf(duration / 2, MAX_NEEDED_MS)
    }

    private fun announce(listen: Listen) {
        listen.announced = true
        PendingScrobbles.flush()

        if (Preferences.isScrobblingEnabled()) {
            songRepository.scrobble(idOf(listen.item), false, null, null)
        }
    }

    private fun count(listen: Listen) {
        MediaManager.saveChronology(listen.item)

        if (Preferences.isScrobblingEnabled()) {
            PendingScrobbles.submit(idOf(listen.item), listen.startedAt)
        }
    }

    // Radio streams have no id, and podcast episodes are not scrobbled.
    private fun isLibraryTrack(item: MediaItem): Boolean {
        val extras = item.mediaMetadata.extras ?: return false
        return extras.getString("id") != null && extras.getString("type") == Constants.MEDIA_TYPE_MUSIC
    }

    private fun idOf(item: MediaItem) = item.mediaMetadata.extras!!.getString("id")!!

    private companion object {
        const val NOT_HEARING = -1L
        const val MAX_NEEDED_MS = 4 * 60 * 1000L

        /* Previous rewinds a track once past three seconds; this catches that. */
        const val RESTART_WITHIN_MS = 1000L

        /* The handler may run a touch early; better to check once than twice. */
        const val CHECK_SLACK_MS = 250L
    }
}
