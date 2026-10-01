package com.cappielloantonio.tempo.service

import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Intent
import android.content.SharedPreferences
import androidx.lifecycle.Observer
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession.ControllerInfo
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.repository.AutomotiveRepository
import com.cappielloantonio.tempo.repository.SongRepository
import com.cappielloantonio.tempo.subsonic.api.mediaannotation.MediaAnnotationClient.PlaybackState
import com.cappielloantonio.tempo.ui.activity.MainActivity
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.DownloadUtil
import com.cappielloantonio.tempo.util.FavoriteState
import com.cappielloantonio.tempo.util.NetworkUtil
import com.cappielloantonio.tempo.util.Preferences
import com.cappielloantonio.tempo.util.ReplayGainUtil
import com.cappielloantonio.tempo.wave.WaveController
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

@UnstableApi
class MediaService : MediaLibraryService(), SessionAvailabilityListener {
    private lateinit var automotiveRepository: AutomotiveRepository
    private val songRepository = SongRepository()
    private lateinit var player: ExoPlayer
    private lateinit var castPlayer: CastPlayer
    private lateinit var mediaLibrarySession: MediaLibrarySession
    private lateinit var librarySessionCallback: MediaLibrarySessionCallback
    private lateinit var scrobbleTracker: ScrobbleTracker

    /*
     * The gain is only worked out when the tracks change, so without this a
     * new replay gain mode would wait for the next track to be heard.
     */
    private val replayGainModeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "replay_gain_mode") ReplayGainUtil.setReplayGain(player, player.currentTracks)
    }

    /*
     * The notification's heart is drawn from the queued MediaItem and redrawn
     * on each track change. A like made in the player, or the server's newer
     * word on a track liked elsewhere, only reached it with the next track;
     * now it is redrawn as soon as the state of the track playing changes.
     */
    private val favoriteObserver = Observer<String> { mediaId ->
        if (this::mediaLibrarySession.isInitialized &&
                mediaId == mediaLibrarySession.player.currentMediaItem?.mediaMetadata?.extras?.getString("id")
        ) {
            librarySessionCallback.refreshCustomLayout(mediaLibrarySession)
        }
    }

    override fun onCreate() {
        super.onCreate()

        initializeRepository()
        initializePlayer()
        initializeCastPlayer()
        initializeMediaLibrarySession()
        initializePlayerListener()
        App.getInstance().preferences.registerOnSharedPreferenceChangeListener(replayGainModeListener)
        FavoriteState.changes().observeForever(favoriteObserver)
        PendingScrobbles.flush()

        setPlayer(
                null,
                if (this::castPlayer.isInitialized && castPlayer.isCastSessionAvailable) castPlayer else player
        )
    }

    override fun onGetSession(controllerInfo: ControllerInfo): MediaLibrarySession {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaLibrarySession.player

        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    private fun initializeRepository() {
        automotiveRepository = AutomotiveRepository()
    }

    private fun initializePlayer() {
        player = ExoPlayer.Builder(this)
                .setRenderersFactory(getRenderersFactory())
                .setMediaSourceFactory(getMediaSourceFactory())
                .setAudioAttributes(AudioAttributes.DEFAULT, true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setLoadControl(initializeLoadControl())
                .build()
    }

    private fun initializeCastPlayer() {
        if (GoogleApiAvailability.getInstance()
                        .isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
        ) {
            castPlayer = CastPlayer(CastContext.getSharedInstance(this))
            castPlayer.setSessionAvailabilityListener(this)
        }
    }

    /*
     * Where tapping the notification itself goes.
     *
     * This used to be a TaskStackBuilder, which stamps the intent with
     * FLAG_ACTIVITY_CLEAR_TASK: tapping the notification tore down whatever the
     * app had open and built MainActivity again from nothing, so it always
     * arrived at the start destination - the home screen - with no sign of the
     * track the notification was about. NEW_TASK brings the task that is
     * already there forward instead, SINGLE_TOP delivers to the running
     * activity through onNewIntent rather than stacking a second copy, and the
     * action tells MainActivity to open the player over whatever it finds.
     */
    private fun initializeMediaLibrarySession() {
        val sessionActivityPendingIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java)
                        .setAction(Constants.ACTION_SHOW_PLAYER)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT
        )

        mediaLibrarySession =
                MediaLibrarySession.Builder(this, resumingOnSkip(player), createLibrarySessionCallback())
                        .setSessionActivity(sessionActivityPendingIntent)
                        // Artwork from the copy kept with a download, so the notification has it offline too.
                        .setBitmapLoader(CacheBitmapLoader(CoverBitmapLoader(this)))
                        .build()
    }

    private fun createLibrarySessionCallback(): MediaLibrarySession.Callback {
        librarySessionCallback = MediaLibrarySessionCallback(this, automotiveRepository)
        return librarySessionCallback
    }

    private fun initializePlayerListener() {
        // Keeps a wave topped up and reports how it is listened to.
        player.addListener(WaveController(player))
        // A track that cannot reach the home address is retried on the main one.
        player.addListener(ServerFailover(player))

        scrobbleTracker = ScrobbleTracker(player)
        player.addListener(scrobbleTracker)

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem == null) return

                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    MediaManager.setLastPlayedTimestamp(mediaItem)
                }

                MediaManager.reportPlayback(mediaItem, PlaybackState.STARTING, 0)

                // A track that comes up playing is being listened to; one lined
                // up in a paused queue is not yet, and waits for play.
                if (player.playWhenReady) SmartDownloads.onListening(this@MediaService, mediaItem)

                // The notification's favourite button belongs to the track, not
                // to the session, so it is redrawn for each new one...
                librarySessionCallback.refreshCustomLayout(mediaLibrarySession)

                // ...first from what the queue knows, then from the server,
                // whether or not the app is open - favoriteObserver redraws it
                // if the answer differs. Only for library tracks, and not
                // offline, where there is no one to ask.
                val extras = mediaItem.mediaMetadata.extras
                val id = extras?.getString("id")
                if (id != null && extras.getString("type") == Constants.MEDIA_TYPE_MUSIC && !NetworkUtil.isOffline()) {
                    songRepository.refreshFavorite(id)
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                ReplayGainUtil.setReplayGain(player, tracks)

                if (player.currentMediaItemIndex + 1 == player.mediaItemCount)
                    MediaManager.continuousPlay(player.currentMediaItem)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    MediaManager.setPlayingPausedTimestamp(
                            player.currentMediaItem,
                            player.currentPosition
                    )
                } else {
                    SmartDownloads.onListening(this@MediaService, player.currentMediaItem)
                }

                /*
                 * isPlaying also drops on a buffering stall, which is not a
                 * pause - reporting one would make the server show the track as
                 * paused every time the network hiccups. Only playWhenReady
                 * says the user actually stopped it.
                 */
                if (isPlaying || !player.playWhenReady) {
                    MediaManager.reportPlayback(
                            player.currentMediaItem,
                            if (isPlaying) PlaybackState.PLAYING else PlaybackState.PAUSED,
                            player.currentPosition
                    )
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)

                if (playbackState == Player.STATE_ENDED) {
                    MediaManager.reportPlayback(
                            player.currentMediaItem,
                            PlaybackState.STOPPED,
                            player.currentPosition
                    )
                }
            }

            override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
            ) {
                super.onPositionDiscontinuity(oldPosition, newPosition, reason)

                /*
                 * A seek moves the position without changing the play state, so
                 * nothing else in this listener fires. Without a report here the
                 * server keeps extrapolating from wherever it last heard, and
                 * "now playing" drifts for the rest of the track.
                 */
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    MediaManager.reportPlayback(
                            newPosition.mediaItem,
                            if (player.isPlaying) PlaybackState.PLAYING else PlaybackState.PAUSED,
                            newPosition.positionMs
                    )
                }

                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION &&
                        newPosition.mediaItem?.mediaMetadata?.extras?.getString("type") == Constants.MEDIA_TYPE_MUSIC
                ) {
                    MediaManager.setLastPlayedTimestamp(newPosition.mediaItem)
                }
            }
        })
    }

    private fun initializeLoadControl(): DefaultLoadControl {
        return DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        (DefaultLoadControl.DEFAULT_MIN_BUFFER_MS * Preferences.getBufferingStrategy()).toInt(),
                        (DefaultLoadControl.DEFAULT_MAX_BUFFER_MS * Preferences.getBufferingStrategy()).toInt(),
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                )
                .build()
    }

    private fun setPlayer(oldPlayer: Player?, newPlayer: Player) {
        if (oldPlayer === newPlayer) return
        oldPlayer?.stop()
        mediaLibrarySession.player = resumingOnSkip(newPlayer)
    }

    /*
     * Skipping to another track plays it, even from pause.
     *
     * A skip used to carry the pause over, so pressing next on a paused track
     * lined up the next one and left it sitting there silent - the press read
     * as having done nothing. Every skip reaches the player through the session,
     * whoever makes it: the player's buttons, the notification, headphones, and
     * a swipe on the artwork or the mini player, which lands on
     * seekToDefaultPosition. Queues being started or restored seek with an
     * explicit position instead, and are left to decide for themselves.
     *
     * Only a skip that actually changes the track resumes: next on the last
     * track, or previous rewinding the current one, stays paused.
     */
    private fun resumingOnSkip(player: Player): Player = object : ForwardingPlayer(player) {
        override fun seekToNext() = resumeIfMoved { super.seekToNext() }

        override fun seekToPrevious() = resumeIfMoved { super.seekToPrevious() }

        override fun seekToNextMediaItem() = resumeIfMoved { super.seekToNextMediaItem() }

        override fun seekToPreviousMediaItem() = resumeIfMoved { super.seekToPreviousMediaItem() }

        override fun seekToDefaultPosition(mediaItemIndex: Int) = resumeIfMoved { super.seekToDefaultPosition(mediaItemIndex) }

        private inline fun resumeIfMoved(skip: () -> Unit) {
            val from = currentMediaItemIndex
            skip()
            if (currentMediaItemIndex != from) play()
        }
    }

    private fun releasePlayer() {
        // Let the server drop this player from "now playing" instead of leaving
        // a stale entry to time out on its own.
        MediaManager.reportPlayback(player.currentMediaItem, PlaybackState.STOPPED, player.currentPosition)
        scrobbleTracker.release()
        App.getInstance().preferences.unregisterOnSharedPreferenceChangeListener(replayGainModeListener)
        FavoriteState.changes().removeObserver(favoriteObserver)

        if (this::castPlayer.isInitialized) castPlayer.setSessionAvailabilityListener(null)
        if (this::castPlayer.isInitialized) castPlayer.release()
        player.release()
        mediaLibrarySession.release()
        automotiveRepository.deleteMetadata()
        clearListener()
    }

    private fun getRenderersFactory() = DownloadUtil.buildRenderersFactory(this, false)

    private fun getMediaSourceFactory() =
            DefaultMediaSourceFactory(this).setDataSourceFactory(DownloadUtil.getDataSourceFactory(this))

    override fun onCastSessionAvailable() {
        setPlayer(player, castPlayer)
    }

    override fun onCastSessionUnavailable() {
        setPlayer(castPlayer, player)
    }
}