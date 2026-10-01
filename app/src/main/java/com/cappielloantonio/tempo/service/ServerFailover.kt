package com.cappielloantonio.tempo.service

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.util.Preferences
import com.cappielloantonio.tempo.util.ServerAddress

/**
 * A track that failed to load for want of a network, on a server with a local
 * address, is tried again on whichever address answers now.
 *
 * Most moves between home and away are caught by the network callback before
 * a track notices (ServerAddress.watchNetwork). This is for the rest: the
 * home Wi-Fi still connected but out of reach, a network change the system
 * did not report. The player stops on the error; once the address has
 * changed, preparing it again resumes the same track at the same position.
 * If the address did not change there is nothing to retry, and the error
 * stands as before. Without a local address this never acts.
 */
@UnstableApi
class ServerFailover(private val player: Player) : Player.Listener {
    override fun onPlayerError(error: PlaybackException) {
        if (Preferences.getLocalAddress().isNullOrBlank()) return
        if (error.errorCode != PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED &&
                error.errorCode != PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        ) return

        ServerAddress.check { changed ->
            if (changed && player.playerError != null) player.prepare()
        }
    }
}
