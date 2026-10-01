package com.cappielloantonio.tempo.service

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.interfaces.StarCallback
import com.cappielloantonio.tempo.model.Download
import com.cappielloantonio.tempo.repository.AutomotiveRepository
import com.cappielloantonio.tempo.repository.FavoriteRepository
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.FavoriteState
import com.cappielloantonio.tempo.util.NetworkUtil
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

open class MediaLibrarySessionCallback(
    private val context: Context,
    automotiveRepository: AutomotiveRepository
) :
    MediaLibraryService.MediaLibrarySession.Callback {

    init {
        MediaBrowserTree.initialize(automotiveRepository)
    }

    private val favoriteRepository = FavoriteRepository()

    /*
     * The one button the notification carries besides the transport controls.
     *
     * Index 0 is the "not a favourite yet" state and index 1 the "already a
     * favourite" one, so the pair can be indexed by the boolean directly.
     */
    private val favoriteCommandButtons: List<CommandButton> = listOf(
        /*
         * Both an icon and its meaning: the drawable is what the notification
         * draws, and ICON_HEART_* is what Android Auto and Automotive read,
         * since they render their own artwork and ignore an app's drawable.
         */
        CommandButton.Builder(CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(context.getString(R.string.notification_favorite_add))
            .setSessionCommand(
                SessionCommand(
                    CUSTOM_COMMAND_FAVORITE_ON, Bundle.EMPTY
                )
            ).setCustomIconResId(R.drawable.ic_favorites_outlined).build(),

        CommandButton.Builder(CommandButton.ICON_HEART_FILLED)
            .setDisplayName(context.getString(R.string.notification_favorite_remove))
            .setSessionCommand(
                SessionCommand(
                    CUSTOM_COMMAND_FAVORITE_OFF, Bundle.EMPTY
                )
            ).setCustomIconResId(R.drawable.ic_favorite).build()
    )

    @OptIn(UnstableApi::class)
    val mediaNotificationSessionCommands =
        MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            .also { builder ->
                favoriteCommandButtons.forEach { commandButton ->
                    commandButton.sessionCommand?.let { builder.add(it) }
                }
            }.build()

    @OptIn(UnstableApi::class)
    override fun onConnect(
        session: MediaSession, controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        if (session.isMediaNotificationController(controller) || session.isAutomotiveController(
                controller
            ) || session.isAutoCompanionController(controller)
        ) {
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(mediaNotificationSessionCommands)
                .setCustomLayout(customLayoutFor(session)).build()
        }

        return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
    }

    @OptIn(UnstableApi::class)
    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        if (CUSTOM_COMMAND_FAVORITE_ON == customCommand.customAction) {
            setFavorite(session, true)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        } else if (CUSTOM_COMMAND_FAVORITE_OFF == customCommand.customAction) {
            setFavorite(session, false)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
    }

    /**
     * Redraws the notification's button for whatever is playing now. Called on
     * every track change: without it the button would go on showing the state
     * of the track it was last pressed on.
     */
    @OptIn(UnstableApi::class)
    fun refreshCustomLayout(session: MediaSession) {
        val controller = session.mediaNotificationControllerInfo ?: return
        session.setCustomLayout(controller, customLayoutFor(session))
    }

    /**
     * Nothing, or the one button in the state the current track is in.
     *
     * Radio streams and podcast episodes are not library tracks and cannot be
     * starred, so they get no button rather than one that would fail.
     */
    @OptIn(UnstableApi::class)
    private fun customLayoutFor(session: MediaSession): ImmutableList<CommandButton> {
        val extras = session.player.currentMediaItem?.mediaMetadata?.extras
            ?: return ImmutableList.of()

        if (extras.getString("type") != Constants.MEDIA_TYPE_MUSIC) return ImmutableList.of()

        val isFavorite = FavoriteState.isFavorite(
            extras.getString("id"),
            extras.getLong("starred") != 0L
        )

        return ImmutableList.of(favoriteCommandButtons[if (isFavorite) 1 else 0])
    }

    /**
     * Stars or unstars what is playing, by the same rules the player sheet
     * follows: offline the change is queued for the next connection, online it
     * goes straight out and falls back to the queue if the server refuses it.
     *
     * With "cache liked tracks" on, a like also downloads the track and an
     * unlike takes back a copy that was only there for the like, as from the
     * player sheet. What the notification's MediaItem carries is enough to
     * name the download.
     */
    @OptIn(UnstableApi::class)
    private fun setFavorite(session: MediaSession, favorite: Boolean) {
        val mediaItem = session.player.currentMediaItem ?: return
        val extras = mediaItem.mediaMetadata.extras ?: return
        val mediaId = extras.getString("id") ?: return

        if (NetworkUtil.isOffline()) {
            favoriteRepository.starLater(mediaId, null, null, favorite)
        } else if (favorite) {
            favoriteRepository.star(mediaId, null, null, object : StarCallback {
                override fun onError() {
                    favoriteRepository.starLater(mediaId, null, null, true)
                }
            })
        } else {
            favoriteRepository.unstar(mediaId, null, null, object : StarCallback {
                override fun onError() {
                    favoriteRepository.starLater(mediaId, null, null, false)
                }
            })
        }

        FavoriteState.set(mediaId, favorite)

        if (!favorite) {
            LikedTracksCache.onUnliked(context, mediaId)
        } else if (!NetworkUtil.isOffline()) {
            LikedTracksCache.onLiked(context, Download(mediaItem))
        }
        refreshCustomLayout(session)
    }

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        return Futures.immediateFuture(LibraryResult.ofItem(MediaBrowserTree.getRootItem(), params))
    }

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return MediaBrowserTree.getChildren(parentId)
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
        return super.onAddMediaItems(
            mediaSession,
            controller,
            MediaBrowserTree.getItems(mediaItems)
        )
    }

    override fun onSearch(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        session.notifySearchResultChanged(browser, query, 60, params)
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return MediaBrowserTree.search(query)
    }

    companion object {
        private const val CUSTOM_COMMAND_FAVORITE_ON = "com.platter.music.FAVORITE_ON"
        private const val CUSTOM_COMMAND_FAVORITE_OFF = "com.platter.music.FAVORITE_OFF"
    }
}