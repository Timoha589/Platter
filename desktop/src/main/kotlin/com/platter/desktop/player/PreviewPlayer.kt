package com.platter.desktop.player

import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * Plays the 30-second Deezer previews - one at a time, on a player of its own so the listener's queue is left
 * exactly as it was. Created on first use: most sessions never preview anything.
 *
 * Like [PlayerController], every command goes through one worker thread, since vlcj deadlocks when called from its
 * own event callbacks; the callbacks only report. [onEnd] is told whether the preview ran to its end (true) or
 * could not be played (false).
 */
class PreviewPlayer(private val vlcArgs: List<String>, private val onEnd: (finished: Boolean) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "platter-preview").apply { isDaemon = true } }
    private var factory: MediaPlayerFactory? = null
    private var player: MediaPlayer? = null

    private val events = object : MediaPlayerEventAdapter() {
        override fun finished(mediaPlayer: MediaPlayer) = onEnd(true)
        override fun error(mediaPlayer: MediaPlayer) = onEnd(false)
    }

    /** Starts [file]; whatever was playing here stops. */
    fun play(file: Path) = worker.execute {
        val p = engine()
        if (p == null) {
            onEnd(false)
            return@execute
        }
        p.media().play(file.toString())
    }

    fun stop() {
        if (worker.isShutdown) return
        worker.execute { player?.controls()?.stop() }
    }

    fun release() {
        if (worker.isShutdown) return
        worker.execute {
            runCatching { player?.controls()?.stop() }
            runCatching { player?.release() }
            runCatching { factory?.release() }
        }
        worker.shutdown()
    }

    private fun engine(): MediaPlayer? {
        player?.let { return it }
        return try {
            if (!NativeDiscovery().discover()) return null
            val f = MediaPlayerFactory(listOf("--no-video", "--quiet") + vlcArgs)
            val p = f.mediaPlayers().newMediaPlayer()
            p.events().addMediaPlayerEventListener(events)
            factory = f
            player = p
            p
        } catch (e: Throwable) {
            null
        }
    }
}
