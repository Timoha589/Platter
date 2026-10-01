package com.cappielloantonio.tempo.deemix

import android.content.Context
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.cappielloantonio.tempo.R

/**
 * Plays the 30-second Deezer previews in search - one at a time, on a player of
 * its own so the listener's queue is left exactly as it was.
 *
 * It takes audio focus like any player, so the music that was playing pauses
 * rather than playing underneath it, and pressing play there again stops the
 * preview the same way.
 */
@OptIn(UnstableApi::class)
class DeezerPreviewPlayer(context: Context, private val onChange: (key: String?, state: State) -> Unit) {
    enum class State { LOADING, PLAYING, STOPPED }

    private val context = context.applicationContext
    private val http = DefaultHttpDataSource.Factory()
    private var player: ExoPlayer? = null
    private var request: DeemixRequest? = null

    /** The track being previewed, as [com.cappielloantonio.tempo.viewmodel.DeezerHit.key]. */
    var current: String? = null
        private set

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> if (current != null) onChange(current, State.PLAYING)
                Player.STATE_ENDED -> stop()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Something else took the speaker - the main player, a call.
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) stop()
        }

        override fun onPlayerError(error: PlaybackException) {
            stop()
            Toast.makeText(context, R.string.deezer_preview_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Starts [key]'s preview, or stops it if it is the one playing. */
    fun toggle(key: String, trackId: Long) {
        if (key == current) {
            stop()
            return
        }

        stop()
        current = key
        onChange(key, State.LOADING)

        request = DeemixClient.preview(trackId) { result ->
            val preview = result.value
            if (preview == null || key != current) {
                if (key == current) stop()
                return@preview
            }

            http.setDefaultRequestProperties(mapOf("Authorization" to preview.authorization))
            val source = ProgressiveMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(preview.url))

            player().apply {
                setMediaSource(source)
                prepare()
                play()
            }
        }
    }

    fun stop() {
        request?.cancel()
        request = null
        player?.stop()
        player?.clearMediaItems()

        if (current != null) {
            current = null
            onChange(null, State.STOPPED)
        }
    }

    fun release() {
        stop()
        player?.release()
        player = null
    }

    private fun player(): ExoPlayer = player ?: ExoPlayer.Builder(context)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()
            .also {
                it.addListener(listener)
                player = it
            }
}
