package com.platter.desktop.player

import com.platter.desktop.i18n.t
import com.platter.desktop.log.AppLog
import com.platter.desktop.api.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.State
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class PlayerState(
    val queue: PlayQueue = PlayQueue(),
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Int = 100,
    /** 1.0 is normal; kept across tracks. */
    /** Set when there is no audio engine or a track would not play; shown instead of failing silently. */
    val error: String? = null,
) {
    val current: Song? get() = queue.current
}

/** How a track was left behind: it ran out, the listener moved on, or went back to an earlier one. */
enum class Leave { Auto, Forward, Back }

/** Told when playback moves off a track, for whoever wants to know how it was listened to (the wave's feedback). Called on the player's worker thread. */
fun interface TrackListener {
    fun onLeft(song: Song, positionMs: Long, durationMs: Long, how: Leave)
}

/**
 * Plays the queue through libvlc (via vlcj), which - unlike the JDK's own
 * players - handles FLAC, Opus and the rest of what a Subsonic library holds.
 *
 * vlcj must not be called from inside its own event callbacks, or it
 * deadlocks, so every command goes through one worker thread and the callbacks
 * only update state or queue a command.
 *
 * [urlFor] turns a track into a stream URL; [scrobble] reports plays to the
 * server (id, submission, start time in epoch ms).
 *
 * A crossfade needs two tracks sounding at once, so there are two players. A
 * few seconds before the end of the track playing, the next one is opened on the
 * spare and held paused. When the fade begins the spare becomes the player - the
 * queue, the position, the scrobbler and every command follow the new track from
 * that moment - while the old one, the outgoing player, carries on with the end
 * of its track, fading out as the new one fades in. Anything the listener does
 * meanwhile (a skip, a seek) ends the fade where it is.
 */
class PlayerController(
    private val scope: CoroutineScope,
    private val urlFor: (Song) -> String?,
    private val scrobble: suspend (String, Boolean, Long?) -> Unit,
    initialVolume: Int = 100,
    /** Extra libvlc options; tests pass `--aout=dummy` so nothing sounds. */
    private val vlcArgs: List<String> = emptyList(),
    initialEqualizer: EqSettings = EqSettings(),
    initialCrossfade: Boolean = false,
    /** How long two tracks overlap at most; tests make it short. */
    private val crossfadeMs: Long = Crossfade.DEFAULT_MS,
) {
    /** The equalizer asked for; applied when the engine is up, and again whenever it changes. */
    private var wantedEq = initialEqualizer
    private var activeEq: uk.co.caprica.vlcj.player.base.Equalizer? = null
    @Volatile
    private var eqAttached = false

    /** Whether libvlc has the equalizer in the signal right now - false where it refused one, so a failure is not silent. */
    val equalizerActive: Boolean get() = eqAttached

    private val _state = MutableStateFlow(PlayerState(volume = initialVolume))
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "platter-player").apply { isDaemon = true } }
    private var factory: MediaPlayerFactory? = null

    /** The player of the track the queue is on. Replaced by the spare when a crossfade begins. */
    @Volatile
    private var player: MediaPlayer? = null
    private var listen: ListenTracker? = null

    // --- crossfade (worker thread, except the flags the players' events set) ---

    @Volatile
    private var crossfadeOn = initialCrossfade

    /** The second player: holds the next track paused, ready to come in; null until one is needed. Idle between fades. */
    @Volatile
    private var spare: MediaPlayer? = null

    /** The song [spare] has been opened on, and whether it is up and paused. */
    @Volatile
    private var spareFor: Song? = null
    @Volatile
    private var spareReady = false
    @Volatile
    private var spareFailed = false

    /** While a fade runs: the player that is leaving, and the clock of the fade. */
    @Volatile
    private var outgoing: MediaPlayer? = null
    private var ticker: ScheduledFuture<*>? = null
    private var fadeLengthMs = 0L
    private var fadeElapsedMs = 0L
    private var lastTickNanos = 0L

    /** True while two tracks overlap. */
    val crossfading: Boolean get() = outgoing != null

    /** The volumes the last tick of a fade set, as (incoming, outgoing); for tests. */
    @Volatile
    internal var lastFadeVolumes: Pair<Int, Int>? = null

    @Volatile
    var trackListener: TrackListener? = null

    /**
     * Counts the tracks started. An end-of-track event queued for one track must not move the queue on from the next one
     * the listener has picked in the meantime.
     */
    @Volatile
    private var started = 0

    /** The current track would not play; pressing play must start it again, not wake a dead stream. */
    @Volatile
    private var failed = false

    /**
     * A queue was put back by [restore] and nothing has been started from it yet: the engine holds no media, and this is
     * where the first press of play begins. A seek made meanwhile moves it.
     */
    @Volatile
    private var restoredPosition: Long? = null

    init {
        worker.execute(::startEngine)
    }

    /**
     * Runs a command on the worker. One that throws is reported and dropped: left to the executor it would
     * stop halfway - the queue not moved, nothing started - and the player would look stuck for good.
     */
    private fun command(block: () -> Unit) = worker.execute {
        try {
            block()
        } catch (e: Throwable) {
            AppLog.error("A player command failed and was skipped", e)
        }
    }

    private fun startEngine() {
        try {
            if (!VlcDiscovery.discover()) {
                fail("The audio engine (libvlc) was not found. Reinstall Platter, or install VLC (64-bit) from videolan.org.")
                return
            }
            // A crossfade sets two players' volumes apart. On Windows libvlc's default output (WASAPI) has one volume for the
            // whole program - every player in it moves together, and it is the system mixer's slider for Platter - so the two
            // would fight and the fade be heard as stutter. DirectSound gives each player its own volume.
            val output = if (vlcArgs.none { it.startsWith("--aout") }) listOf("--aout=directsound,any") else emptyList()
            val f = MediaPlayerFactory(listOf("--no-video", "--quiet") + output + AppLog.vlcArguments() + vlcArgs)
            val p = f.mediaPlayers().newMediaPlayer()
            p.audio().setVolume(_state.value.volume)
            p.events().addMediaPlayerEventListener(events)
            factory = f
            player = p
            applyEqualizer()
        } catch (e: Throwable) {
            AppLog.error("The audio engine could not start", e)
            fail(t("Could not start the audio engine: %s", e.message))
        }
    }

    private fun fail(message: String) = _state.update { it.copy(error = message, isPlaying = false) }

    private val events = object : MediaPlayerEventAdapter() {
        override fun playing(mediaPlayer: MediaPlayer) {
            // The spare was opened to be held: stop it as soon as it is up, so it waits at the start of its track.
            if (mediaPlayer === spare) {
                command { if (spare === mediaPlayer && !spareReady) mediaPlayer.controls().setPause(true) }
                return
            }
            if (mediaPlayer !== player) return
            _state.update { it.copy(isPlaying = true, error = null) }
            listen?.resume(System.currentTimeMillis()) ?: return
            // "Now playing" goes out when a track starts to be heard, and again on resuming from a pause.
            val id = state.value.current?.id ?: return
            scope.launch { runCatching { scrobble(id, false, null) } }
        }

        override fun paused(mediaPlayer: MediaPlayer) {
            if (mediaPlayer === spare) {
                spareReady = true
                return
            }
            if (mediaPlayer !== player) return
            _state.update { it.copy(isPlaying = false) }
            listen?.pause(System.currentTimeMillis())
        }

        override fun stopped(mediaPlayer: MediaPlayer) {
            if (mediaPlayer !== player) return
            _state.update { it.copy(isPlaying = false) }
            listen?.pause(System.currentTimeMillis())
        }

        override fun finished(mediaPlayer: MediaPlayer) {
            if (mediaPlayer !== player) return
            listen?.pause(System.currentTimeMillis())
            val track = started
            command { if (track == started) advance(auto = true) }
        }

        override fun error(mediaPlayer: MediaPlayer) {
            if (mediaPlayer === spare) {
                spareFailed = true
                return
            }
            if (mediaPlayer !== player) return
            failed = true
            val song = state.value.current
            AppLog.error("A track could not be played: ${song?.title} (id ${song?.id}), from ${song?.let(urlFor)?.substringBefore('?')}")
            _state.update { it.copy(isPlaying = false, error = t("This track could not be played.")) }
            command { abortFade() }
        }

        override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
            if (mediaPlayer !== player) return
            _state.update { it.copy(positionMs = newTime) }
            if (crossfadeOn) command { crossfadeStep(newTime) }
            val l = listen ?: return
            if (l.reachedMark(System.currentTimeMillis())) {
                val id = state.value.current?.id ?: return
                scope.launch { runCatching { scrobble(id, true, l.startedAt) } }
            }
        }

        override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
            if (mediaPlayer === player && newLength > 0) _state.update { it.copy(durationMs = newLength) }
        }
    }

    // --- queue commands -------------------------------------------------------

    /** [positionMs] starts the song part-way in: picking a queue up where another device left it. */
    fun play(songs: List<Song>, start: Int = 0, positionMs: Long = 0) = command {
        _state.update { it.copy(queue = it.queue.replace(songs, start)) }
        startCurrent(positionMs)
    }

    /**
     * Puts a queue back as it was, stopped at [positionMs] of the current song: nothing sounds until play is pressed,
     * which then begins there. For the start of the app, before anything has been played.
     */
    fun restore(queue: PlayQueue, positionMs: Long) {
        val song = queue.current ?: return
        restoredPosition = positionMs
        _state.update { it.copy(queue = queue, positionMs = positionMs, durationMs = (song.duration ?: 0) * 1000L) }
    }

    fun playNext(song: Song) = command {
        val wasEmpty = _state.value.queue.songs.isEmpty()
        _state.update { it.copy(queue = it.queue.playNext(song)) }
        if (wasEmpty) startCurrent()
    }

    fun playNext(songs: List<Song>) = command {
        if (songs.isEmpty()) return@command
        val wasEmpty = _state.value.queue.songs.isEmpty()
        _state.update { it.copy(queue = it.queue.playNext(songs)) }
        if (wasEmpty) startCurrent()
    }

    fun enqueue(song: Song) = command {
        val wasEmpty = _state.value.queue.songs.isEmpty()
        _state.update { it.copy(queue = it.queue.enqueue(song)) }
        if (wasEmpty) startCurrent()
    }

    /** Tops the queue up at the end without disturbing what is playing. */
    fun append(songs: List<Song>) = command {
        if (songs.isEmpty()) return@command
        val wasEmpty = _state.value.queue.songs.isEmpty()
        _state.update { it.copy(queue = it.queue.append(songs)) }
        if (wasEmpty) startCurrent()
    }

    fun jumpTo(index: Int) = command {
        val from = _state.value.queue.index
        if (index != from) notifyLeft(if (index > from) Leave.Forward else Leave.Back)
        _state.update { it.copy(queue = it.queue.jumpTo(index)) }
        startCurrent()
    }

    private fun notifyLeft(how: Leave) {
        val s = state.value
        val song = s.current ?: return
        // The wave and the like hear about music; a podcast or a station is not a track to rate.
        if (!song.isMusic) return
        // A track that ran out has been heard to its end, whatever the last tick said.
        val position = if (how == Leave.Auto && s.durationMs > 0) s.durationMs else s.positionMs
        // Whoever listens must not be able to stop the queue from moving on.
        runCatching { trackListener?.onLeft(song, position, s.durationMs, how) }
    }

    fun removeAt(index: Int) = command {
        val removingCurrent = index == _state.value.queue.index
        _state.update { it.copy(queue = it.queue.removeAt(index)) }
        if (removingCurrent) startCurrent()
    }

    fun toggleShuffle() {
        _state.update { it.copy(queue = it.queue.toggleShuffle()) }
    }

    fun cycleRepeat() {
        _state.update { it.copy(queue = it.queue.cycleRepeat()) }
    }

    // --- transport ------------------------------------------------------------

    fun toggle() = command {
        val p = player ?: return@command
        val leaving = outgoing
        when {
            p.status().isPlaying -> {
                p.controls().pause()
                if (leaving?.status()?.isPlaying == true) leaving.controls().pause()
            }
            !failed && state.value.current != null && p.status().length() > 0 -> {
                p.controls().play()
                if (leaving?.status()?.state() == State.PAUSED) leaving.controls().play()
            }
            else -> startCurrent(restoredPosition ?: 0)
        }
    }

    fun next() = command { advance(auto = false) }

    fun previous() = command {
        val s = state.value
        if (s.positionMs > ListenTracker.RESTART_WITHIN_MS || s.queue.index <= 0) {
            seekToMs(0)
        } else {
            _state.update { it.copy(queue = it.queue.jumpTo(it.queue.index - 1)) }
            startCurrent()
        }
    }

    /** [fraction] is 0..1 of the track. */
    fun seek(fraction: Float) = command {
        val duration = state.value.durationMs
        if (duration > 0) seekToMs((duration * fraction.coerceIn(0f, 1f)).toLong())
    }

    /** Seeks to an absolute position - a tapped lyric line. */
    fun seekMs(ms: Long) = command { seekToMs(ms.coerceAtLeast(0)) }

    private fun seekToMs(ms: Long) {
        abortFade()
        if (restoredPosition != null) restoredPosition = ms
        player?.controls()?.setTime(ms)
        _state.update { it.copy(positionMs = ms) }
        // Going back to the start of a track that has counted is a new play.
        if (ms < ListenTracker.RESTART_WITHIN_MS) listen = listen?.let { if (it.counted) it.startOver(System.currentTimeMillis()) else it }
    }

    fun setVolume(volume: Int) {
        val v = volume.coerceIn(0, 100)
        // The state is told at once, so a wheel turned fast reads the volume it has just set and not one the worker has yet to reach.
        _state.update { it.copy(volume = v) }
        command { player?.audio()?.setVolume(v) }
    }

    /** Switched off, nothing more is prepared; a fade already under way finishes. */
    fun setCrossfade(on: Boolean) {
        crossfadeOn = on
        if (!on) command { dropPrewarm() }
    }

    /** Starts the current song again where it stopped - after the address it was streaming from changed. */
    fun retryCurrent() = command {
        if (state.value.current != null) startCurrent(state.value.positionMs)
    }

    /** Sets the equalizer, live: what is playing changes at once. Off, it is taken out of the signal altogether. */
    fun setEqualizer(settings: EqSettings) = command {
        wantedEq = settings
        applyEqualizer()
    }

    /**
     * Worker thread. One equalizer lives as long as the engine and is bent in place; handing it to the player again
     * after each change makes libvlc take the new gains at once, mid-song. Off, it is taken out of the signal.
     */
    private fun applyEqualizer() {
        val f = factory ?: return
        // Every player: the spare and the outgoing one come into play later, and must not bring an old setting with them.
        val players = listOfNotNull(player, spare, outgoing)
        if (players.isEmpty()) return
        try {
            if (wantedEq.enabled) {
                val eq = activeEq ?: f.equalizer().newEqualizer().also { activeEq = it }
                eq.setPreamp(wantedEq.preamp)
                eq.setAmps(wantedEq.gains().toFloatArray())
                players.forEach { it.audio().setEqualizer(eq) }
                eqAttached = true
            } else if (eqAttached) {
                players.forEach { it.audio().setEqualizer(null) }
                eqAttached = false
            }
        } catch (e: Throwable) {
            // An engine without an equalizer plays on unchanged.
        }
    }

    // --- internals (worker thread) -------------------------------------------

    private fun advance(auto: Boolean) {
        val queue = state.value.queue
        val to = queue.after(auto)
        if (to == null) {
            // The queue ran out: stay on the last track, stopped, so it can be played again.
            player?.controls()?.stop()
            _state.update { it.copy(isPlaying = false, positionMs = 0) }
            return
        }
        notifyLeft(if (auto) Leave.Auto else Leave.Forward)
        _state.update { it.copy(queue = it.queue.jumpTo(to)) }
        startCurrent()
    }

    private fun startCurrent(startMs: Long = 0) {
        abortFade()
        dropPrewarm()
        restoredPosition = null
        started++
        failed = false
        val p = player
        val song = state.value.current
        if (song == null) {
            p?.controls()?.stop()
            listen = null
            _state.update { it.copy(isPlaying = false, positionMs = 0, durationMs = 0) }
            return
        }
        if (p == null) return // the engine's own message is already in state.error
        val url = urlFor(song)
        if (url == null) {
            fail(t("Not signed in."))
            return
        }
        val durationMs = (song.duration ?: 0) * 1000L
        // Only music is scrobbled: a podcast episode or a live station is not a play the server counts.
        listen = if (song.isMusic) ListenTracker(song.id.orEmpty(), durationMs) else null
        _state.update { it.copy(positionMs = startMs, durationMs = durationMs, error = null) }
        if (startMs > 0) p.media().play(url, ":start-time=${startMs / 1000.0}") else p.media().play(url)
    }

    // --- crossfade internals (worker thread) ---------------------------------

    /** Called as the track plays: opens the next one on the spare ahead of time, and starts the fade when its moment comes. */
    private fun crossfadeStep(positionMs: Long) {
        if (!crossfadeOn || outgoing != null || failed) return
        val s = state.value
        val plan = Crossfade.plan(s.queue, s.durationMs, crossfadeMs)
        if (plan == null) {
            if (spareFor != null) dropPrewarm()
            return
        }
        val held = spareFor
        if (held != null && held !== plan.to) {
            // What comes next has changed since the spare was opened.
            dropPrewarm()
            return
        }
        if (held == null) {
            if (positionMs >= plan.startMs - Crossfade.PREWARM_MS) prewarm(plan.to)
            return
        }
        if (spareFailed) return
        if (positionMs >= plan.startMs && spareReady) beginFade(plan, positionMs)
        else if (positionMs >= s.durationMs - Crossfade.MIN_FADE_MS) dropPrewarm() // too late for a fade; the track ends as it would
    }

    private fun prewarm(song: Song) {
        val f = factory ?: return
        val url = urlFor(song) ?: return
        val s = spare ?: f.mediaPlayers().newMediaPlayer().also {
            it.events().addMediaPlayerEventListener(events)
            spare = it
            applyEqualizer()
        }
        spareFor = song
        spareReady = false
        spareFailed = false
        s.audio().setVolume(0)
        s.media().play(url)
    }

    private fun dropPrewarm() {
        if (outgoing != null) return
        spareFor = null
        spareReady = false
        spareFailed = false
        spare?.let { runCatching { it.controls().stop() } }
    }

    /** The spare takes over as the player, silent, and the volumes start to cross. */
    private fun beginFade(plan: CrossfadePlan, positionMs: Long) {
        val incoming = spare ?: return
        val before = state.value
        val fadeMs = minOf(plan.fadeMs, before.durationMs - positionMs)
        if (fadeMs < Crossfade.MIN_FADE_MS) {
            dropPrewarm()
            return
        }

        // The track left behind is heard to its end, as when one runs out on its own.
        notifyLeft(Leave.Auto)
        listen?.pause(System.currentTimeMillis())

        outgoing = player
        player = incoming
        spare = null
        spareFor = null
        spareReady = false
        started++
        failed = false
        restoredPosition = null

        val length = incoming.status().length()
        val durationMs = if (length > 0) length else (plan.to.duration ?: 0) * 1000L
        listen = ListenTracker(plan.to.id.orEmpty(), durationMs)
        _state.update { it.copy(queue = it.queue.jumpTo(plan.toIndex), positionMs = 0, durationMs = durationMs, error = null) }

        fadeLengthMs = fadeMs
        fadeElapsedMs = 0
        lastTickNanos = System.nanoTime()
        incoming.audio().setVolume(0)
        incoming.controls().setPause(false)
        ticker = worker.scheduleAtFixedRate(::fadeTick, 0, FADE_TICK_MS, TimeUnit.MILLISECONDS)
    }

    /** One step of the fade. The clock runs only while the new track is actually sounding, so a pause holds it still. */
    private fun fadeTick() {
        try {
            val leaving = outgoing ?: return
            val incoming = player ?: return
            val now = System.nanoTime()
            if (incoming.status().isPlaying) fadeElapsedMs += (now - lastTickNanos) / 1_000_000
            lastTickNanos = now

            val volume = state.value.volume
            val (inGain, outGain) = Crossfade.gains(fadeElapsedMs.toFloat() / fadeLengthMs)
            val inVolume = (volume * inGain).roundToInt()
            val outVolume = (volume * outGain).roundToInt()
            incoming.audio().setVolume(inVolume)
            leaving.audio().setVolume(outVolume)
            lastFadeVolumes = inVolume to outVolume

            if (fadeElapsedMs >= fadeLengthMs) abortFade()
        } catch (e: Throwable) {
            AppLog.error("A crossfade step failed; the fade is ended", e)
            abortFade()
        }
    }

    /**
     * Ends the fade, finished or not: the old track stops at once - it has nothing left to play when the fade ran its
     * course - and the new one is at the full volume. The old player is kept as the spare for the next fade.
     */
    private fun abortFade() {
        ticker?.cancel(false)
        ticker = null
        val leaving = outgoing ?: return
        val volume = state.value.volume
        runCatching { leaving.controls().stop() }
        runCatching { leaving.audio().setVolume(volume) }
        runCatching { player?.audio()?.setVolume(volume) }
        spare = leaving
        spareFor = null
        spareReady = false
        outgoing = null
    }

    fun release() {
        // Closing twice - the window, then a shutdown hook - must not trip over the worker that is already gone.
        if (worker.isShutdown) return
        worker.execute {
            ticker?.cancel(false)
            runCatching { player?.controls()?.stop() }
            runCatching { outgoing?.controls()?.stop() }
            runCatching { spare?.controls()?.stop() }
            runCatching { player?.release() }
            runCatching { outgoing?.release() }
            runCatching { spare?.release() }
            runCatching { factory?.release() }
        }
        worker.shutdown()
    }

    /** [release], then waits for the engine to be let go of; false if it took longer than [timeoutMs]. For tests: libvlc instances must not overlap. */
    fun releaseAndWait(timeoutMs: Long = 10_000): Boolean {
        release()
        return worker.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private companion object {
        /** How often the volumes of a fade are set: fine enough that the steps cannot be heard. */
        const val FADE_TICK_MS = 40L
    }
}
