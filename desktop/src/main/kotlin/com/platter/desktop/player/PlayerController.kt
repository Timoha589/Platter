package com.platter.desktop.player

import com.platter.automix.AutoMixPlanner
import com.platter.automix.MixTempo
import com.platter.desktop.i18n.t
import com.platter.desktop.log.AppLog
import com.platter.desktop.api.Song
import com.platter.desktop.api.TrackStructure
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
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** VLC's Windows output cubes volume. Keep the slider's level and apply the fade gain to the audio amplitude. */
internal fun vlcVolumeForGain(volume: Int, gain: Float): Int = (volume * cbrt(gain)).roundToInt()

// LibVLC can queue a volume request before the audio output has applied it.
internal fun confirmZeroVolume(setVolume: (Int) -> Boolean, volume: () -> Int): Boolean =
    setVolume(0) && volume() == 0

data class PlayerState(
    val queue: PlayQueue = PlayQueue(),
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Int = 100,
    /** 1.0 is normal; kept across tracks. */
    /** Set when there is no audio engine or a track would not play; shown instead of failing silently. */
    val error: String? = null,
    val fadeTransition: FadeTransition? = null,
    /** The planned overlap on the current track's timeline. */
    val upcomingOverlap: TimelineOverlap? = null,
    /** The incoming overlap on the current track's timeline, retained after the mix. */
    val incomingOverlap: TimelineOverlap? = null,
) {
    val current: Song? get() = queue.current
    val autoMixTransition: FadeTransition? get() = fadeTransition?.takeIf { it.mode == FadeMode.AutoMix }
}

enum class TransitionMode { Off, Crossfade, AutoMix }

enum class FadeMode { Crossfade, AutoMixFallback, AutoMix }

/** How often the audio engine updates the mix and publishes its progress. */
internal const val FADE_TICK_MS = 40L

data class TimelineOverlap(val rangeMs: LongRange, val mode: FadeMode)

/** The outgoing artwork and the fraction of the actual audio overlap already played. */
data class FadeTransition(val outgoingCoverArtId: String?, val mode: FadeMode, val progress: Float = 0f)

/**
 * What libvlc is given to open a file on this machine. Only ASCII, with the rest percent-encoded as UTF-8: JNA hands
 * strings to libvlc in the Windows code page, so a raw "Музыка" in the path reached it as bytes it could not open.
 */
fun mediaLocation(file: Path): String = file.toUri().toASCIIString()

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
    initialTransitionMode: TransitionMode = TransitionMode.Off,
    /** Base overlap for AutoMix and the default for ordinary crossfade. */
    private val crossfadeMs: Long = Crossfade.DEFAULT_MS,
    initialCrossfadeDurationMs: Long = crossfadeMs,
) {
    /** The equalizer asked for; applied when the engine is up, and again whenever it changes. */
    private var wantedEq = initialEqualizer
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
    @Volatile
    private var playbackVolumePending = false

    // --- crossfade (worker thread, except the flags the players' events set) ---

    @Volatile
    private var transitionMode = initialTransitionMode

    private var crossfadeDurationMs = initialCrossfadeDurationMs
    private val autoMixOn get() = transitionMode == TransitionMode.AutoMix
    @Volatile
    private var structures: Map<String, TrackStructure> = emptyMap()
    private data class MixInputs(val from: Song, val to: Song, val durationMs: Long,
        val fromStructure: TrackStructure?, val toStructure: TrackStructure?)
    private var plannedFor: MixInputs? = null
    private var plannedMix: BeatMixPlan? = null
    private var plannedCrossfade: CrossfadePlan? = null
    private var mixDecision = ""
    private var preparedMix: BeatMixPlan? = null
    private var preparedFade: CrossfadePlan? = null
    private var plainCueSet = false
    private var mixRateSet = false
    private var preparedBeatCue = 0L
    private var mixPrerollStarted = false
    private data class DeckClock(val positionMs: Long, val sampledAt: Long) {
        fun positionAt(now: Long, rate: Float) = positionMs + (now - sampledAt) / 1_000_000.0 * rate
    }
    @Volatile
    private var spareClock: DeckClock? = null
    private var mixLaunch: ScheduledFuture<*>? = null
    private var outgoingCueRate = 1f
    private var rateRecovery: ScheduledFuture<*>? = null

    private var activeMixRate = 1f
    internal val incomingPlaybackRate: Float get() = player?.status()?.rate() ?: 1f
    internal val outgoingPlaybackRate: Float get() = outgoing?.status()?.rate() ?: 1f
    internal val playbackVolume: Int get() = player?.audio()?.volume() ?: -1

    /** Holds the next track paused, ready to come in. Created for each prewarm. */
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
    private var tempoRatio = 1.0
    private var fadeElapsedNanos = 0L
    private var lastTickNanos = 0L
    private var bassGains: Pair<Float, Float>? = null

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
            // A recreated output must stay silent until it can accept the slider volume.
            val output = if (vlcArgs.none { it.startsWith("--aout") }) {
                listOf("--aout=directsound,any", "--directx-volume=0")
            } else emptyList()
            // libvlc filters sound as it is decoded, ahead of what is heard by its caching (a second for a stream by
            // default), so the equalizer's sliders were heard a second and more after they moved. Less is plenty for a
            // music file over HTTP, and the equalizer follows the hand.
            val caching = listOf("--network-caching=$NETWORK_CACHING_MS", "--file-caching=$FILE_CACHING_MS")
            // DirectSound would otherwise save a deck's temporary fade volume as the default for new audio outputs.
            val f = MediaPlayerFactory(listOf("--no-video", "--quiet", "--audio-time-stretch", "--no-volume-save") +
                output + caching + AppLog.vlcArguments() + vlcArgs)
            val p = f.mediaPlayers().newMediaPlayer()
            applyPlaybackVolume(p)
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
            if (mediaPlayer === spare) {
                command { parkSpare(mediaPlayer) }
                return
            }
            if (mediaPlayer !== player) return
            val generation = started
            command { restorePlaybackVolume(mediaPlayer, generation, retries = 10) }
            nowPlaying()
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
            _state.update { it.copy(isPlaying = false, error = t("This track could not be played."), upcomingOverlap = null) }
            command { abortFade() }
        }

        override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
            val sampledAt = System.nanoTime()
            if (mediaPlayer === spare) {
                spareClock = DeckClock(newTime, sampledAt)
                return
            }
            if (mediaPlayer !== player) return
            val generation = started
            _state.update { it.copy(positionMs = newTime) }
            if (transitionMode != TransitionMode.Off || playbackVolumePending) command {
                if (mediaPlayer === player && generation == started) {
                    if (playbackVolumePending && outgoing == null) applyPlaybackVolume(mediaPlayer)
                    crossfadeStep(newTime, sampledAt)
                }
            }
            val l = listen ?: return
            if (l.reachedMark(System.currentTimeMillis())) {
                val id = state.value.current?.id ?: return
                scope.launch { runCatching { scrobble(id, true, l.startedAt) } }
            }
        }

        override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
            if (mediaPlayer === player && newLength > 0) {
                _state.update { if (it.durationMs == newLength) it else it.copy(durationMs = newLength, upcomingOverlap = null) }
            }
        }
    }

    private fun parkSpare(deck: MediaPlayer) {
        if (deck !== spare || spareReady || spareFailed) return
        // VLC can report Playing before it creates the audio output. Keep it muted until there is an output to park.
        if (deck.audio().volume() < 0) {
            worker.schedule({ parkSpare(deck) }, AUDIO_OUTPUT_POLL_MS, TimeUnit.MILLISECONDS)
            return
        }
        deck.controls().setPause(true)
    }

    private fun nowPlaying() {
        _state.update { it.copy(isPlaying = true, error = null) }
        listen?.resume(System.currentTimeMillis()) ?: return
        val id = state.value.current?.id ?: return
        scope.launch { runCatching { scrobble(id, false, null) } }
    }

    // --- queue commands -------------------------------------------------------

    private fun updateQueue(transform: (PlayQueue) -> PlayQueue) {
        _state.update { state ->
            val before = state.queue
            val after = transform(before)
            val sameTrack = before.current === after.current
            val overlap = state.upcomingOverlap?.takeIf {
                val next = after.after(auto = true)?.takeUnless { it == after.index }
                sameTrack && next != null &&
                    after.songs[next] === before.after(auto = true)?.let(before.songs::get)
            }
            state.copy(
                queue = after,
                fadeTransition = state.fadeTransition.takeIf { sameTrack },
                upcomingOverlap = overlap,
                incomingOverlap = state.incomingOverlap.takeIf { sameTrack },
            )
        }
    }

    /** [positionMs] starts the song part-way in: picking a queue up where another device left it. */
    fun play(songs: List<Song>, start: Int = 0, positionMs: Long = 0) = command {
        updateQueue { it.replace(songs, start) }
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
        updateQueue { it.playNext(song) }
        if (wasEmpty) startCurrent()
    }

    fun playNext(songs: List<Song>) = command {
        if (songs.isEmpty()) return@command
        val wasEmpty = _state.value.queue.songs.isEmpty()
        updateQueue { it.playNext(songs) }
        if (wasEmpty) startCurrent()
    }

    fun enqueue(song: Song) = command {
        val wasEmpty = _state.value.queue.songs.isEmpty()
        updateQueue { it.enqueue(song) }
        if (wasEmpty) startCurrent()
    }

    /** Tops the queue up at the end without disturbing what is playing. */
    fun append(songs: List<Song>) = command {
        if (songs.isEmpty()) return@command
        val wasEmpty = _state.value.queue.songs.isEmpty()
        updateQueue { it.append(songs) }
        if (wasEmpty) startCurrent()
    }

    fun jumpTo(index: Int) = command {
        val from = _state.value.queue.index
        if (index != from) notifyLeft(if (index > from) Leave.Forward else Leave.Back)
        updateQueue { it.jumpTo(index) }
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
        updateQueue { it.removeAt(index) }
        if (removingCurrent) startCurrent()
    }

    fun toggleShuffle() {
        updateQueue { it.toggleShuffle() }
    }

    fun cycleRepeat() {
        updateQueue { it.cycleRepeat() }
    }

    // --- transport ------------------------------------------------------------

    fun toggle() = command {
        val p = player ?: return@command
        val leaving = outgoing
        when {
            p.status().isPlaying -> {
                if (mixPrerollStarted) dropPrewarm()
                p.controls().pause()
                if (leaving?.status()?.isPlaying == true) leaving.controls().pause()
            }
            !failed && state.value.current != null && p.status().length() > 0 -> {
                if (leaving == null) applyPlaybackVolume(p)
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
            updateQueue { it.jumpTo(it.index - 1) }
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
        plannedFor = null
        if (preparedMix != null || autoMixOn && preparedFade != null) dropPrewarm()
        if (restoredPosition != null) restoredPosition = ms
        player?.controls()?.setTime(ms)
        _state.update { s -> s.copy(positionMs = ms, upcomingOverlap = s.upcomingOverlap?.takeIf { ms <= it.rangeMs.first - 1_000 }) }
        // Going back to the start of a track that has counted is a new play.
        if (ms < ListenTracker.RESTART_WITHIN_MS) listen = listen?.let { if (it.counted) it.startOver(System.currentTimeMillis()) else it }
    }

    fun setVolume(volume: Int) {
        val v = volume.coerceIn(0, 100)
        // The state is told at once, so a wheel turned fast reads the volume it has just set and not one the worker has yet to reach.
        _state.update { it.copy(volume = v) }
        command { if (outgoing == null) player?.let(::applyPlaybackVolume) }
    }

    private fun applyPlaybackVolume(deck: MediaPlayer) {
        val audio = deck.audio()
        // Starting or resuming can report Playing before VLC has an audio output to change.
        playbackVolumePending = !audio.setVolume(state.value.volume)
        if (!playbackVolumePending && audio.isMute) audio.setMute(false)
    }

    private fun restorePlaybackVolume(deck: MediaPlayer, generation: Int, retries: Int) {
        if (deck !== player || generation != started || outgoing != null || failed || worker.isShutdown) return
        applyPlaybackVolume(deck)
        // Retry before the first playback clock tick so a newly created output need not stay silent until then.
        if (playbackVolumePending && retries > 0) {
            worker.schedule({
                if (playbackVolumePending) restorePlaybackVolume(deck, generation, retries - 1)
            }, AUDIO_OUTPUT_POLL_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** Prepare the next transition for the new mode. A fade already under way finishes. */
    fun setTransitionMode(mode: TransitionMode) = command {
        if (transitionMode == mode) return@command
        transitionMode = mode
        plannedFor = null
        _state.update { it.copy(upcomingOverlap = null) }
        dropPrewarm()
        applyEqualizer()
    }

    fun setCrossfadeDuration(durationMs: Long) = command {
        crossfadeDurationMs = durationMs
    }

    internal fun setStructures(ready: Map<String, TrackStructure>) {
        structures = ready
    }

    /** Starts the current song again where it stopped - after the address it was streaming from changed. */
    fun retryCurrent() = command {
        if (state.value.current != null) startCurrent(state.value.positionMs)
    }

    /** Apply edits on top of any bass handoff already in progress. */
    fun setEqualizer(settings: EqSettings) = command {
        wantedEq = settings
        applyEqualizer()
    }

    /** Give each deck its own equalizer. With AutoMix armed, keep the filter ready at unity when the user's EQ is off. */
    private fun applyEqualizer() {
        val f = factory ?: return
        val players = listOfNotNull(player, spare, outgoing)
        if (players.isEmpty()) return
        val incoming = if (outgoing == null) spare else player
        val leaving = outgoing ?: player
        val bass = bassGains
        val enabled = wantedEq.enabled || bass != null || autoMixOn
        try {
            val userPreamp = if (wantedEq.enabled) wantedEq.preamp else 0f
            val preamp = (userPreamp + LIBVLC_UNITY_PREAMP_DB).coerceAtMost(LIBVLC_MAX_PREAMP_DB)
            for (deck in players) {
                val audio = deck.audio()
                val attached = audio.equalizer()
                if (!enabled) {
                    if (attached != null) audio.setEqualizer(null)
                    continue
                }
                val eq = attached ?: f.equalizer().newEqualizer()
                val bassGain = when {
                    deck === incoming -> bass?.first ?: 1f
                    deck === leaving -> bass?.second ?: 1f
                    else -> 1f
                }
                if (eq.preamp() != preamp) eq.setPreamp(preamp)
                eq.setAmps(wantedEq.gainsWithBass(bassGain))
                if (attached == null) audio.setEqualizer(eq)
            }
            eqAttached = wantedEq.enabled
        } catch (e: Throwable) {
            // An engine without an equalizer plays on unchanged.
            eqAttached = false
        }
    }

    // --- internals (worker thread) -------------------------------------------

    private fun advance(auto: Boolean) {
        val before = state.value
        val queue = before.queue
        val to = queue.after(auto)
        if (to == null) {
            // The queue ran out: stay on the last track, stopped, so it can be played again.
            abortFade(restoreVolume = false)
            dropPrewarm()
            player?.controls()?.stop()
            started++
            playbackVolumePending = false
            _state.update { it.copy(isPlaying = false, positionMs = 0, upcomingOverlap = null, incomingOverlap = null) }
            return
        }
        if (auto && autoMixOn && Crossfade.plan(queue, before.durationMs, crossfadeMs) != null) {
            val next = queue.songs[to]
            val reason = when {
                spareFailed && spareFor === next -> "next stream could not be prepared"
                plannedFor?.to !== next -> "no transition was prepared"
                plannedMix != null -> "selected mix did not start before the end of the track"
                else -> "$mixDecision; crossfade did not start before the end of the track"
            }
            AppLog.info("AutoMix: ${transitionName(before.current, next)} continued without overlap ($reason)")
        }
        notifyLeft(if (auto) Leave.Auto else Leave.Forward)
        updateQueue { it.jumpTo(to) }
        startCurrent()
    }

    private fun startCurrent(startMs: Long = 0) {
        abortFade(restoreVolume = false)
        dropPrewarm()
        plannedFor = null
        _state.update { it.copy(upcomingOverlap = null, incomingOverlap = null) }
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
            applyPlaybackVolume(p)
            fail(t("Not signed in."))
            return
        }
        val durationMs = (song.duration ?: 0) * 1000L
        // Only music is scrobbled: a podcast episode or a live station is not a play the server counts.
        listen = if (song.isMusic) ListenTracker(song.id.orEmpty(), durationMs) else null
        _state.update { it.copy(positionMs = startMs, durationMs = durationMs, error = null) }
        val prepared = if (startMs > 0) p.media().prepare(url, ":start-time=${startMs / 1000.0}") else p.media().prepare(url)
        applyPlaybackVolume(p)
        if (prepared) p.controls().play()
    }

    // --- crossfade internals (worker thread) ---------------------------------

    /** Called as the track plays: opens the next one on the spare ahead of time, and starts the fade when its moment comes. */
    private fun crossfadeStep(positionMs: Long, sampledAt: Long) {
        if (transitionMode == TransitionMode.Off || outgoing != null || rateRecovery != null || failed) return
        val s = state.value
        val plan = Crossfade.plan(s.queue, s.durationMs, if (autoMixOn) crossfadeMs else crossfadeDurationMs)
        if (plan == null) {
            if (spareFor != null) dropPrewarm()
            _state.update { it.copy(upcomingOverlap = null) }
            return
        }
        val mix = if (autoMixOn) {
            val from = checkNotNull(s.current)
            val analysis = structures
            val inputs = MixInputs(from, plan.to, s.durationMs, from.id?.let(analysis::get), plan.to.id?.let(analysis::get))
            val previous = plannedFor
            val cue = preparedMix?.takeIf { it === plannedMix }?.launchMs()
            // A loudness profile can arrive after the grid. Keep an imminent cue that is already ready.
            val cueIsReady = cue != null && spareReady && !spareFailed &&
                (mixPrerollStarted || positionMs >= cue - AutoMixPlanner.PLAN_LEAD_MS || outgoingCueRate != 1f) &&
                previous != null && previous.from === from && previous.to === plan.to && previous.durationMs == s.durationMs
            if (plannedFor != inputs && !cueIsReady) {
                plannedFor = inputs
                plannedCrossfade = null
                val selection = AutoMixPlanner.beatMix(
                    from.mixMetadata(), plan.to.mixMetadata(), s.durationMs, plan.window(),
                    inputs.fromStructure, inputs.toStructure, positionMs + AutoMixPlanner.PLAN_LEAD_MS,
                )
                plannedMix = selection.mix
                mixDecision = selection.detail
            }
            plannedMix
        } else null
        val edges = if (autoMixOn && mix == null) {
            val shaped = plannedCrossfade ?: run {
                val from = plannedFor?.fromStructure
                val to = plannedFor?.toStructure
                val window = AutoMixPlanner.shapedFade(
                    from, to, s.durationMs, plan.to.duration?.times(1_000L), plan.window(), positionMs + AutoMixPlanner.PLAN_LEAD_MS,
                )
                plan.withWindow(window).also { plannedCrossfade = it }
            }
            shaped.takeIf { positionMs <= it.startMs + it.fadeMs - Crossfade.MIN_FADE_MS } ?: plan
        } else plan
        val previousFade = preparedFade
        val fade = if (autoMixOn && mix == null && preparedMix == null && spareFor === plan.to && previousFade != null &&
            (plainCueSet && previousFade == plan || positionMs >= minOf(previousFade.startMs, edges.startMs) - 1_000)) previousFade else edges
        val launch = mix?.launchMs() ?: fade.startMs
        val overlap = when {
            !autoMixOn || (spareFor === plan.to && spareFailed) -> null
            mix != null -> {
                val fadeMs = mix.fadeDurationMs(launch, s.durationMs, fade.fadeMs)
                if (launch >= 0 && positionMs <= launch + 2 * MIX_LATE_MS && fadeMs >= Crossfade.MIN_FADE_MS) {
                    TimelineOverlap(launch..(launch + fadeMs), FadeMode.AutoMix)
                } else null
            }
            positionMs <= launch + fade.fadeMs - Crossfade.MIN_FADE_MS ->
                TimelineOverlap(launch..(launch + fade.fadeMs), FadeMode.AutoMixFallback)
            else -> null
        }
        if (overlap != s.upcomingOverlap) {
            _state.update {
                if (it.queue === s.queue && it.durationMs == s.durationMs && !failed) it.copy(upcomingOverlap = overlap) else it
            }
        }
        if (spareFor !== plan.to || preparedMix !== mix || (autoMixOn && preparedFade != fade)) {
            if (spareFor != null) dropPrewarm()
            if (positionMs >= launch - Crossfade.PREWARM_MS) prewarm(fade, mix)
            return
        }
        if (mix != null) {
            if (spareFailed) fallbackToCrossfade("next stream could not be prepared")
            else if (positionMs > launch + 2 * MIX_LATE_MS) fallbackToCrossfade("selected cue at ${launch}ms was missed")
            else if (spareReady) scheduleBeatMix(plan, mix, positionMs, sampledAt)
            return
        }
        if (spareFailed) return
        if (spareReady) startCrossfade(plan, fade, positionMs)
        else if (positionMs >= s.durationMs - minimumOverlapMs(fade)) dropPrewarm()
    }

    private fun minimumOverlapMs(fade: CrossfadePlan): Long =
        if (autoMixOn) Crossfade.MIN_FADE_MS else AutoMixPlanner.minimumPlayableCrossfadeMs(fade.fadeMs)

    private fun startCrossfade(base: CrossfadePlan, fade: CrossfadePlan, positionMs: Long) {
        val incoming = spare ?: return
        val status = incoming.status()
        var cue = 0L
        val recordingDuration = fade.cueRecordingDurationMs
        if (recordingDuration != null) {
            when (AutoMixPlanner.cueStatus(recordingDuration, status.length(), status.isSeekable, fade.cueMs > 0)) {
                AutoMixPlanner.CueStatus.READY -> Unit
                AutoMixPlanner.CueStatus.UNKNOWN_DURATION -> {
                    if (positionMs >= fade.startMs - AutoMixPlanner.PLAN_LEAD_MS) useFullCrossfade(base, "incoming stream duration is unknown")
                    return
                }
                else -> {
                    useFullCrossfade(base, "incoming cue unavailable for this stream")
                    return
                }
            }
            if (fade.cueMs > 0) {
                if (!plainCueSet) {
                    incoming.controls().setTime(fade.cueMs)
                    plainCueSet = true
                    return
                }
                cue = status.time()
                if (!AutoMixPlanner.matchesCue(cue, fade.cueMs)) {
                    if (positionMs >= fade.startMs - AutoMixPlanner.PLAN_LEAD_MS) useFullCrossfade(base, "incoming seek was not confirmed before the fade")
                    return
                }
            }
        }
        if (positionMs >= fade.startMs) beginFade(fade, positionMs, cue, layered = fade != base)
    }

    private fun useFullCrossfade(plan: CrossfadePlan, reason: String) {
        if (plainCueSet) {
            // A seek may have moved the spare even if its confirmed position differs from the cue.
            dropPrewarm()
            prewarm(plan, null)
        }
        preparedFade = plan
        plainCueSet = true
        mixDecision = "$mixDecision; $reason"
    }

    private fun scheduleBeatMix(plan: CrossfadePlan, mix: BeatMixPlan, positionMs: Long, sampledAt: Long) {
        val incoming = spare ?: return
        val leaving = player ?: return
        val status = incoming.status()
        when (AutoMixPlanner.cueStatus(mix.incomingDurationMs, status.length(), status.isSeekable, requiresSeek = true)) {
            AutoMixPlanner.CueStatus.READY -> Unit
            AutoMixPlanner.CueStatus.UNKNOWN_DURATION -> return
            AutoMixPlanner.CueStatus.NOT_SEEKABLE -> {
                fallbackToCrossfade("next stream does not support seeking")
                return
            }
            AutoMixPlanner.CueStatus.DIFFERENT_RECORDING -> {
                fallbackToCrossfade("next stream duration differs from its analysis")
                return
            }
        }
        val incomingRate = BeatMix.incomingRate(mix.tempoRatio, 0f)
        if (!mixRateSet) {
            preparedBeatCue = AutoMixPlanner.prerollCue(mix, positionMs)
            incoming.controls().setTime(preparedBeatCue)
            if (incomingRate != status.rate() && !incoming.controls().setRate(incomingRate)) {
                fallbackToCrossfade("audio engine refused the selected playback rate")
                return
            }
            mixRateSet = true
            return
        }
        val cue = if (mixPrerollStarted) mix.incomingStartMs else status.time()
        if (!mixPrerollStarted && (!AutoMixPlanner.matchesCue(cue, preparedBeatCue) || cue > mix.incomingBeatMs)) return

        // shortcut: VLC reports its input clock every ~250 ms, so interpolate between events until the app mixes PCM on one output clock.
        val launchAt = mix.launchMs(cue)
        val cueRate = mix.initialOutgoingRate
        val toCueMs = launchAt - positionMs
        val rampRate = if (mixPrerollStarted) cueRate else MixTempo.outgoingCueRate(mix, toCueMs)
        if (rampRate != outgoingCueRate) {
            if (!leaving.controls().setRate(rampRate)) {
                fallbackToCrossfade("audio engine refused the outgoing playback rate")
                return
            }
            outgoingCueRate = rampRate
        }
        if (rampRate != cueRate) return

        val waitMs = (toCueMs / cueRate.toDouble() - elapsedMs(sampledAt)).roundToLong()
        if (waitMs < -MIX_LATE_MS) {
            fallbackToCrossfade("selected cue at ${launchAt}ms was missed")
            return
        }
        if (waitMs > MIX_LOOKAHEAD_MS) return
        mixLaunch?.cancel(false)
        val generation = started
        mixLaunch = worker.schedule({
            mixLaunch = null
            if (!autoMixOn || started != generation || player !== leaving || spare !== incoming || preparedMix !== mix ||
                !leaving.status().isPlaying) return@schedule
            val s = state.value
            val current = Crossfade.plan(s.queue, s.durationMs, crossfadeMs)
            if (current == null || current.to !== plan.to) return@schedule
            val estimatedPosition = positionMs + (elapsedMs(sampledAt) * cueRate.toDouble()).roundToLong()
            val failure = when {
                spareFailed -> "next stream could not be prepared"
                abs(status.rate() - incomingRate) > MixTempo.RATE_TOLERANCE || abs(leaving.status().rate() - cueRate) > MixTempo.RATE_TOLERANCE ->
                    "audio engine did not reach the selected playback rates"
                abs(estimatedPosition - launchAt) > MIX_LATE_MS -> "selected cue at ${launchAt}ms was missed"
                else -> null
            }
            if (failure != null) {
                fallbackToCrossfade(failure)
                return@schedule
            }
            if (!mixPrerollStarted && preparedBeatCue < mix.incomingStartMs) {
                // Give the decoder time to fill its output while the outgoing track is still alone.
                if (!silenceIncoming(incoming)) {
                    fallbackToCrossfade("incoming audio could not be silenced before preroll")
                    return@schedule
                }
                spareClock = null
                mixPrerollStarted = true
                incoming.controls().setPause(false)
            } else if (mixPrerollStarted) {
                val now = System.nanoTime()
                val incomingPosition = spareClock?.takeIf {
                    it.positionMs > preparedBeatCue && elapsedMs(it.sampledAt) <= MIX_CLOCK_MAX_AGE_MS
                }?.positionAt(now, incomingRate)
                val phase = incomingPosition?.let { mix.phaseErrorMs(estimatedPosition, it) }
                if (!status.isPlaying || incomingPosition == null || phase == null || abs(phase) > MIX_MAX_PHASE_MS) {
                    fallbackToCrossfade("incoming clock could not lock to the selected cue (phase ${phase?.let { "${it}ms" } ?: "unknown"})")
                    return@schedule
                }
                AppLog.info("AutoMix phase before overlap: ${phase}ms")
                beginFade(current, estimatedPosition, incomingPosition.roundToLong(), layered = true)
            } else {
                beginFade(current, estimatedPosition, cue, layered = true)
            }
        }, waitMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
    }

    private fun elapsedMs(sampledAt: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sampledAt)

    private fun fallbackToCrossfade(reason: String) {
        plannedMix = null
        mixDecision = reason
        _state.update { it.copy(upcomingOverlap = null) }
        dropPrewarm()
    }

    private fun transitionName(from: Song?, to: Song): String {
        fun label(song: Song?) = listOfNotNull(song?.artistLine(), song?.title).joinToString(" — ")
            .lineSequence().joinToString(" ")
        return "${label(from)} -> ${label(to)}"
    }

    private fun prewarm(fade: CrossfadePlan, mix: BeatMixPlan?) {
        val song = fade.to
        val f = factory ?: return
        val url = urlFor(song) ?: return
        // libvlc drops the audio output when a player is stopped. A fresh player can be silenced before decoding starts.
        val s = f.mediaPlayers().newMediaPlayer()
        s.events().addMediaPlayerEventListener(events)
        spare = s
        spareFor = song
        spareReady = false
        spareFailed = false
        preparedMix = mix
        preparedFade = fade
        plainCueSet = false
        mixRateSet = false
        mixPrerollStarted = false
        spareClock = null
        if (!silenceIncoming(s)) {
            AppLog.info("The next track could not be silenced before prewarming")
            spareFailed = true
            return
        }
        s.audio().setMute(true)
        bassGains = mix?.outgoingBeatPeriodMs?.let { 0f to 1f }
        applyEqualizer()
        if (!s.media().play(url)) spareFailed = true
    }

    private fun silenceIncoming(incoming: MediaPlayer): Boolean {
        val audio = incoming.audio()
        return confirmZeroVolume(audio::setVolume, audio::volume)
    }

    private fun dropPrewarm() {
        if (outgoing != null) return
        mixLaunch?.cancel(false)
        mixLaunch = null
        val preparing = spare
        spare = null
        if (outgoingCueRate != 1f) {
            player?.controls()?.setRate(1f)
            outgoingCueRate = 1f
        }
        preparedMix = null
        preparedFade = null
        plainCueSet = false
        mixRateSet = false
        mixPrerollStarted = false
        spareClock = null
        spareFor = null
        spareReady = false
        spareFailed = false
        preparing?.let(::stopAndRelease)
        clearBassHandoff()
    }

    /** The spare takes over as the player, silent, and the volumes start to cross. */
    private fun beginFade(plan: CrossfadePlan, positionMs: Long, cueMs: Long, layered: Boolean) {
        val incoming = spare ?: return
        val before = state.value
        val mix = preparedMix
        val incomingRunning = mixPrerollStarted
        val fadeMs = mix?.fadeDurationMs(positionMs, before.durationMs, plan.fadeMs)
            ?: AutoMixPlanner.remainingFadeMs(plan.window(), positionMs)
        if (fadeMs < minimumOverlapMs(plan)) {
            dropPrewarm()
            return
        }
        if (!silenceIncoming(incoming)) {
            val reason = "incoming audio could not be silenced before overlap"
            if (mix != null) {
                fallbackToCrossfade(reason)
            } else {
                AppLog.info("Crossfade skipped: $reason")
                spare = null
                spareFailed = true
                _state.update { it.copy(upcomingOverlap = null) }
                stopAndRelease(incoming)
            }
            return
        }
        incoming.audio().setMute(false)

        notifyLeft(Leave.Auto)
        listen?.pause(System.currentTimeMillis())

        outgoing = player
        player = incoming
        playbackVolumePending = false
        spare = null
        spareFor = null
        spareReady = false
        preparedMix = null
        preparedFade = null
        plainCueSet = false
        mixRateSet = false
        mixPrerollStarted = false
        spareClock = null
        tempoRatio = mix?.tempoRatio ?: 1.0
        activeMixRate = BeatMix.incomingRate(tempoRatio, 0f)
        val startOutgoingRate = outgoingCueRate
        outgoingCueRate = 1f
        started++
        failed = false
        restoredPosition = null

        val length = incoming.status().length()
        val durationMs = if (length > 0) length else (plan.to.duration ?: 0) * 1000L
        val fadeMode = when {
            mix != null -> FadeMode.AutoMix
            autoMixOn -> FadeMode.AutoMixFallback
            else -> FadeMode.Crossfade
        }
        val endMs = minOf(durationMs, cueMs + (fadeMs * tempoRatio).roundToLong())
        val incomingOverlap = if (fadeMode != FadeMode.Crossfade && endMs > cueMs) {
            TimelineOverlap(cueMs..endMs, fadeMode)
        } else null
        listen = ListenTracker(plan.to.id.orEmpty(), durationMs)
        _state.update {
            it.copy(
                queue = it.queue.jumpTo(plan.toIndex), positionMs = cueMs, durationMs = durationMs, error = null,
                fadeTransition = FadeTransition(before.current?.coverArtId, fadeMode),
                upcomingOverlap = null,
                incomingOverlap = incomingOverlap,
            )
        }

        fadeLengthMs = fadeMs
        fadeElapsedNanos = 0
        lastTickNanos = System.nanoTime()
        if (mix != null) {
            val bars = mix.bars?.let { ", $it bars" }.orEmpty()
            val bass = mix.outgoingBeatPeriodMs?.let { ", bass handoff over ${it.roundToLong()}ms" }.orEmpty()
            val pair = transitionName(before.current, plan.to)
            val remaining = (before.durationMs - positionMs - fadeMs).coerceAtLeast(0)
            AppLog.info("AutoMix at ${positionMs}ms, exit ${mix.exitSection ?: "beat grid"}, entry ${cueMs}ms, " +
                "incoming rate ${activeMixRate}x, outgoing rate ${startOutgoingRate}x, " +
                "section ${mix.section ?: "beat grid"}, overlap ${fadeMs}ms$bars$bass, " +
                "${remaining}ms left after fade; $pair ($mixDecision)")
        } else if (autoMixOn) {
            val vocal = plan.incomingVocalMs?.let { "; planned handoff at first vocal (${it}ms)" }.orEmpty()
            AppLog.info("Crossfade at ${positionMs}ms, entry ${cueMs}ms, overlap ${fadeMs}ms; " +
                "${transitionName(before.current, plan.to)} ($mixDecision$vocal)")
        }
        if (incomingRunning) nowPlaying() else incoming.controls().setPause(false)
        val autoMix = autoMixOn
        ticker = worker.scheduleAtFixedRate({ fadeTick(layered, autoMix, mix?.outgoingBeatPeriodMs) }, 0, FADE_TICK_MS, TimeUnit.MILLISECONDS)
    }

    /** One step of the fade. The clock runs only while the new track is actually sounding, so a pause holds it still. */
    private fun fadeTick(layered: Boolean, autoMix: Boolean, beatPeriodMs: Double?) {
        try {
            val leaving = outgoing ?: return
            val incoming = player ?: return
            val now = System.nanoTime()
            // Count the outgoing phrase in media time; its wall-clock duration changes as both decks glide.
            val halfPhraseNanos = fadeLengthMs * 500_000L
            val beforeMidpoint = fadeElapsedNanos < halfPhraseNanos
            val outgoingRate = BeatMix.outgoingRate(tempoRatio, activeMixRate).toDouble()
            if (incoming.status().isPlaying) {
                fadeElapsedNanos += ((now - lastTickNanos) * outgoingRate).toLong()
            }
            lastTickNanos = now
            val fadeElapsedMs = TimeUnit.NANOSECONDS.toMillis(fadeElapsedNanos)
            val progress = (fadeElapsedMs.toFloat() / fadeLengthMs).coerceAtMost(1f)
            if (tempoRatio != 1.0) {
                val nextRate = BeatMix.incomingRate(tempoRatio, progress)
                if (nextRate != activeMixRate && (abs(nextRate - activeMixRate) >= MixTempo.TEMPO_STEP || progress == 1f)) {
                    // Applying the same relative change to both decks keeps the matched beats together.
                    if (!leaving.controls().setRate(BeatMix.outgoingRate(tempoRatio, nextRate)) || !incoming.controls().setRate(nextRate)) {
                        AppLog.info("AutoMix tempo adjustment was refused; ending the overlap")
                        abortFade()
                        return
                    }
                    activeMixRate = nextRate
                }
                if (beforeMidpoint && fadeElapsedNanos >= halfPhraseNanos)
                    AppLog.info("AutoMix midpoint: outgoing ${leaving.status().rate()}x, incoming ${incoming.status().rate()}x")
            }

            val volume = state.value.volume
            if (beatPeriodMs != null) {
                val gains = Crossfade.bassGains(fadeElapsedMs + BASS_FILTER_LOOKAHEAD_MS * outgoingRate, fadeLengthMs, beatPeriodMs)
                if (bassGains != gains) {
                    bassGains = gains
                    applyEqualizer()
                }
            }
            val (inGain, outGain) = if (layered) Crossfade.mixGains(progress) else Crossfade.gains(progress)
            val inVolume = if (autoMix) vlcVolumeForGain(volume, inGain) else (volume * inGain).roundToInt()
            val outVolume = if (autoMix) vlcVolumeForGain(volume, outGain) else (volume * outGain).roundToInt()
            val incomingAudio = incoming.audio()
            incomingAudio.setVolume(inVolume)
            if (inVolume > 0 && incomingAudio.isMute) incomingAudio.setMute(false)
            leaving.audio().setVolume(outVolume)
            lastFadeVolumes = inVolume to outVolume

            val fade = state.value.fadeTransition
            if (fade != null && fade.progress != progress) {
                _state.update { it.copy(fadeTransition = it.fadeTransition?.copy(progress = progress)) }
            }
            if (progress == 1f) {
                if (tempoRatio != 1.0) AppLog.info("AutoMix handoff: incoming ${incoming.status().rate()}x")
                val handoffRate = activeMixRate
                abortFade(preserveIncomingRate = true)
                if (handoffRate != 1f) recoverRate(incoming, handoffRate)
            }
        } catch (e: Throwable) {
            AppLog.error("A crossfade step failed; the fade is ended", e)
            abortFade()
        }
    }

    /**
     * Ends the fade, finished or not: the old track stops at once - it has nothing left to play when the fade ran its
     * course - and the new one returns to the slider volume unless it is about to be replaced or stopped.
     */
    private fun abortFade(preserveIncomingRate: Boolean = false, restoreVolume: Boolean = true) {
        ticker?.cancel(false)
        ticker = null
        finishRateRecovery()
        val leaving = outgoing ?: return
        val elapsedMs = minOf(fadeElapsedNanos, fadeLengthMs * 1_000_000L) / 1_000_000.0
        val incomingElapsedMs = (elapsedMs * tempoRatio).roundToLong()
        runCatching { leaving.audio().setVolume(0) }
        if (tempoRatio != 1.0 && !preserveIncomingRate) {
            runCatching { player?.controls()?.setRate(1f) }
        }
        stopAndRelease(leaving)
        tempoRatio = 1.0
        activeMixRate = 1f
        outgoing = null
        if (restoreVolume) runCatching { player?.let(::applyPlaybackVolume) }
        clearBassHandoff()
        _state.update { state ->
            val heard = state.incomingOverlap?.let { overlap ->
                val range = overlap.rangeMs
                val endMs = minOf(range.last, range.first + incomingElapsedMs)
                if (endMs > range.first) overlap.copy(rangeMs = range.first..endMs) else null
            }
            state.copy(fadeTransition = null, incomingOverlap = heard)
        }
    }

    private fun stopAndRelease(deck: MediaPlayer) {
        runCatching { deck.controls().stop() }
        runCatching { deck.release() }
    }

    private fun clearBassHandoff() {
        if (bassGains == null) return
        bassGains = null
        applyEqualizer()
    }

    /** The incoming track can return to native speed as soon as its beats no longer have to follow the outgoing deck. */
    private fun recoverRate(incoming: MediaPlayer, handoffRate: Float) {
        var lastTick = System.nanoTime()
        var playedNanos = 0L
        rateRecovery = worker.scheduleAtFixedRate({
            try {
                val now = System.nanoTime()
                if (incoming.status().isPlaying) {
                    playedNanos += now - lastTick
                    val progress = playedNanos.toFloat() / TimeUnit.MILLISECONDS.toNanos(MixTempo.RECOVERY_MS)
                    if (progress >= 1f || !incoming.controls().setRate(BeatMix.glideRate(handoffRate, 1f, progress))) {
                        finishRateRecovery()
                    }
                }
                lastTick = now
            } catch (e: Throwable) {
                AppLog.error("AutoMix tempo recovery failed", e)
                finishRateRecovery()
            }
        }, FADE_TICK_MS, FADE_TICK_MS, TimeUnit.MILLISECONDS)
    }

    private fun finishRateRecovery() {
        val recovery = rateRecovery ?: return
        recovery.cancel(false)
        rateRecovery = null
        runCatching { player?.controls()?.setRate(1f) }
    }

    fun release() {
        // Closing twice - the window, then a shutdown hook - must not trip over the worker that is already gone.
        if (worker.isShutdown) return
        worker.execute {
            ticker?.cancel(false)
            mixLaunch?.cancel(false)
            rateRecovery?.cancel(false)
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
        const val AUDIO_OUTPUT_POLL_MS = 20L
        const val MIX_LATE_MS = AutoMixPlanner.CUE_TOLERANCE_MS
        const val MIX_LOOKAHEAD_MS = 285L
        const val MIX_CLOCK_MAX_AGE_MS = 450L
        const val MIX_MAX_PHASE_MS = AutoMixPlanner.PHASE_TOLERANCE_MS

        /** What libvlc's equalizer needs as preamp to leave the level as it was; measured, see EqualizerSoundTest. */
        const val LIBVLC_UNITY_PREAMP_DB = 12f

        /** The most libvlc takes. */
        const val LIBVLC_MAX_PREAMP_DB = 20f

        /** How far ahead of what is heard libvlc decodes, and so how late an equalizer change is heard. */
        const val NETWORK_CACHING_MS = 400
        const val FILE_CACHING_MS = 300
        /** The EQ acts on decoded samples ahead of the playhead. Allow for file and stream buffering. */
        const val BASS_FILTER_LOOKAHEAD_MS = (FILE_CACHING_MS + NETWORK_CACHING_MS) / 2
    }
}
