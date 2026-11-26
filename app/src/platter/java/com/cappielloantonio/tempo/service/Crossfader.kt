package com.cappielloantonio.tempo.service

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.audio.EqualizerProcessor
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.Preferences
import com.cappielloantonio.tempo.util.Preferences.TransitionMode
import com.cappielloantonio.tempo.util.ReplayGainUtil
import com.platter.automix.AutoMixPlanner
import com.platter.automix.BeatMixPlan
import com.platter.automix.FadeEnvelope
import com.platter.automix.FadeWindow
import com.platter.automix.MixTempo
import com.platter.automix.TrackStructure
import com.platter.automix.shortFallback
import com.platter.search.RecordingMetadata
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Lets the end of one track and the start of the next play at the same time.
 *
 * The queue lives in one player, and a player plays one thing at a time, so
 * the overlap is built from two. The main player stays the truth about what
 * is playing - the notification, the scrobbler and the lyrics all read it -
 * and a second one, the helper, carries the tail of the track being left:
 *
 *  - Before the first cue the helper is made and loaded with the same track,
 *    paused.
 *  - Eight seconds before the cue it starts playing along with the main
 *    player, muted. A player is heard only some tens of milliseconds after it
 *    is told to start, and never after quite the same time, so the two are
 *    compared once they have played for a while and the helper's sound is
 *    shifted by the difference, sample by sample (see [SampleShifter]).
 *  - At the fade the two swap over: for a moment both play the same audio,
 *    the main one fading out, the helper in. Two copies that are off by more
 *    than a few milliseconds play a bit of the track twice, which is heard as
 *    a stutter.
 *  - The main player then moves on to the next track, silent, and fades it in
 *    while the helper fades out what is left of the old one. From here the
 *    main player is already on the new track, so the app shows it at once.
 *
 * Whatever goes wrong - the helper not ready in time, a skip, a seek, the
 * queue changing under it - ends the fade where it is, with the main player
 * back at full volume. The track then goes on or gives way to the next as it
 * would have without a crossfade.
 *
 * Tracks that are meant to run into each other (the next number on the same
 * album) are left to the player's own gapless playback.
 */
@UnstableApi
class Crossfader(
        private val player: ExoPlayer,
        private val playerEqualizer: EqualizerProcessor,
        private val newHelper: (SampleShifter, EqualizerProcessor) -> ExoPlayer,
        private val canFade: () -> Boolean
) : Player.Listener, SharedPreferences.OnSharedPreferenceChangeListener {
    private enum class Phase { IDLE, ALIGNING, HANDOFF, WAITING_FOR_FADE, FADING, RECOVERING }

    private data class Plan(
            val fromIndex: Int,
            val fromId: String,
            val toIndex: Int,
            val toId: String,
            val durationMs: Long,
            val toDurationMs: Long?,
            val standard: FadeWindow,
            val mix: AutoMixTransition? = null,
    ) {
        val window get() = mix?.window ?: standard
        val startMs get() = window.startMs
        val timelineMode get() = if (mix?.beats != null) AutoMixTimeline.Mode.BEAT_MIX else AutoMixTimeline.Mode.FALLBACK_CROSSFADE
    }

    private data class MixInputs(
            val base: Plan,
            val from: RecordingMetadata,
            val to: RecordingMetadata,
            val fromAnalysis: TrackStructure?,
            val toAnalysis: TrackStructure?,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val analysis = AutoMixAnalysis(::refresh)
    private var transitionMode = Preferences.getTransitionMode()
    private val autoMixOn get() = transitionMode == TransitionMode.AUTO_MIX
    private val playbackAllowed get() = player.playWhenReady &&
            player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE

    private var phase = Phase.IDLE
    private var plan: Plan? = null
    private var plannedFor: MixInputs? = null
    private var plannedMix: AutoMixTransition? = null
    private var helper: ExoPlayer? = null
    private var helperEqualizer: EqualizerProcessor? = null
    private var shifter: SampleShifter? = null
    private var helperFailed = false
    private var messages = emptyList<PlayerMessage>()

    // Our own seek to the next track must not be mistaken for the listener's.
    private var ownSeek = false

    // Lining the helper up: where its position started to count from, when a shift will have been heard,
    // the differences read since, and whether they are close enough. The first difference after the helper
    // is put somewhere says how far off the lead was.
    private var settleFrom = C.TIME_UNSET
    private var shiftHeardAt = 0L
    private var samples = 0
    private var helperAhead = Long.MIN_VALUE
    private var mainAhead = Long.MIN_VALUE
    private val readings = ArrayDeque<Long>(READINGS)
    private var aligned = false
    private var justMoved = false

    private var outVolume = 1f
    private var handoffFrom = C.TIME_UNSET
    private var handoffWaitedMs = 0L
    private var toStarted = false
    private var tailAtStartMs = 0L
    private lateinit var fadeEnvelope: FadeEnvelope
    private var fadeElapsedNanos = 0L
    private var lastFadeTickNanos = 0L
    private var coverToken: Long? = null
    private var incomingMixSegment: AutoMixTimeline.Segment? = null
    private var incomingPreview: AutoMixTimeline.Segment? = null
    private var heardThroughMs: Long? = null
    private var prerollCueMs = 0L
    private var incomingCueMs = 0L
    private var cueTimeMs = 0L
    private val cueSync = AutoMixCueSync()
    private var incomingChecked = false
    private var savedParameters: PlaybackParameters? = null
    private var ownParameters = false
    private var awaitingTempoRestore: PlaybackParameters? = null
    private var recoveryTickMs = 0L
    private var recoveryPlayedMs = 0L
    private var recoverySpeed = 1f

    private val tick = object : Runnable {
        override fun run() {
            when (phase) {
                Phase.ALIGNING -> align()
                Phase.HANDOFF -> handoff()
                Phase.WAITING_FOR_FADE -> waitForFade()
                Phase.FADING -> fade()
                Phase.RECOVERING -> recoverTempo()
                Phase.IDLE -> return
            }
            if (phase != Phase.IDLE) {
                handler.removeCallbacks(this)
                handler.postDelayed(this, TICK_MS)
            }
        }
    }

    init {
        player.addListener(this)
        App.getInstance().preferences.registerOnSharedPreferenceChangeListener(this)
        refresh()
    }

    fun release() {
        App.getInstance().preferences.unregisterOnSharedPreferenceChangeListener(this)
        player.removeListener(this)
        analysis.release()
        incomingMixSegment = null
        end(refreshAfter = false)
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        if (key == Preferences.CROSSFADE_DURATION) {
            if (transitionMode == TransitionMode.CROSSFADE) refresh()
            return
        }
        if (key != Preferences.CROSSFADE && key != Preferences.AUTO_MIX && key != Preferences.SERVER) return
        val mode = Preferences.getTransitionMode()
        if (mode == transitionMode) return
        transitionMode = mode
        plannedFor = null
        incomingMixSegment = null
        end(refreshAfter = false)
        refresh()
    }

    /* Works out again what to crossfade next, and keeps what is armed if it is still right. */
    fun refresh() {
        val enabled = transitionMode != TransitionMode.OFF && canFade()
        if (!enabled && (plan != null || incomingMixSegment != null)) {
            incomingMixSegment = null
            end(refreshAfter = false)
        }
        analysis.update(if (enabled && autoMixOn) analysisIds() else emptyList())
        when (phase) {
            Phase.IDLE -> {
                val wanted = planNext()
                if (wanted == plan) return
                disarm()
                if (wanted != null && canArm(wanted, player.currentPosition)) arm(wanted)
            }
            Phase.ALIGNING -> {
                val current = plan ?: return
                if (savedParameters != null || !stillOn(current)) return
                val wanted = planNext() ?: return
                if (wanted != current && canArm(wanted, player.currentPosition)) {
                    // Keep the outgoing helper aligned when the transition plan changes.
                    plan = wanted
                    preparePreroll(wanted)
                    publishTimeline()
                }
            }
            else -> Unit
        }
    }

    private fun rearm() {
        if (phase != Phase.IDLE) return
        plannedFor = null
        disarm()
        refresh()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) = refresh()

    override fun onRepeatModeChanged(repeatMode: Int) = onPlayOrderChanged()

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = onPlayOrderChanged()

    private fun onPlayOrderChanged() {
        if (phase == Phase.ALIGNING || phase == Phase.HANDOFF || phase == Phase.WAITING_FOR_FADE) end(refreshAfter = false)
        refresh()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_READY -> refresh()
            Player.STATE_IDLE, Player.STATE_ENDED -> {
                end()
                disarm()
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (ownSeek) return
        incomingMixSegment = null
        if (phase != Phase.IDLE) {
            Log.d(TAG, "interrupted by media transition ($reason)")
            end(refreshAfter = false)
        }
        rearm()
    }

    override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
    ) {
        if (ownSeek || reason != Player.DISCONTINUITY_REASON_SEEK) return
        if (phase != Phase.IDLE) {
            Log.d(TAG, "interrupted by a seek")
            end(refreshAfter = false)
        }
        rearm()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        /*
         * A pause before the fade lets the helper go rather than keep it and
         * its ticking for however long the pause lasts; playing again arms the
         * fade afresh, and the helper is made and lined up anew.
         */
        if (phase == Phase.ALIGNING && !playWhenReady) {
            end(refreshAfter = false)
            return
        }
        helper?.takeIf { phase != Phase.IDLE }?.playWhenReady = playbackAllowed
        if (phase == Phase.IDLE && playWhenReady) refresh()
    }

    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
        helper?.takeIf { phase != Phase.IDLE }?.playWhenReady = playbackAllowed
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        // The AudioTrack can report its applied rate after the command returns.
        if (ownParameters || savedParameters != null || playbackParameters != player.playbackParameters) return
        if (playbackParameters == awaitingTempoRestore) {
            awaitingTempoRestore = null
            if (phase == Phase.IDLE) refresh()
            return
        }
        onUserPlaybackParametersChanging()
        helper?.playbackParameters = playbackParameters
        if (phase == Phase.ALIGNING) resettle()
        if (phase == Phase.IDLE) refresh()
    }

    fun onUserPlaybackParametersChanging() {
        awaitingTempoRestore = null
        if (plan?.mix != null) {
            Log.d(TAG, "AutoMix interrupted by a change to playback speed")
            end(refreshAfter = false)
        }
    }

    override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {
        if (skipSilenceEnabled && plan?.mix != null) end(refreshAfter = false)
        refresh()
    }

    override fun onPlayerError(error: PlaybackException) {
        end()
        disarm()
    }

    /*
     * What would be crossfaded next, if anything: the track playing and the one
     * that follows it in play order, with the moment the fade would start.
     */
    private fun planNext(): Plan? {
        if (transitionMode == TransitionMode.OFF || !canFade()) return null
        if (player.repeatMode == Player.REPEAT_MODE_ONE) return null
        if (player.isCurrentMediaItemDynamic || player.isCurrentMediaItemLive) return null

        val fromIndex = player.currentMediaItemIndex
        val toIndex = player.nextMediaItemIndex
        if (fromIndex == C.INDEX_UNSET || toIndex == C.INDEX_UNSET || toIndex == fromIndex) return null

        val from = player.getMediaItemAt(fromIndex)
        val to = player.getMediaItemAt(toIndex)
        if (!isMusic(from) || !isMusic(to) || runsInto(from, to)) return null

        val length = player.duration
        if (length == C.TIME_UNSET || length <= 0) return null

        val toDuration = durationOf(to)
        val fadeMs = if (autoMixOn) AutoMixPlanner.DEFAULT_FADE_MS else Preferences.getCrossfadeDurationSeconds() * 1_000L
        val window = AutoMixPlanner.standardFade(length, toDuration, fadeMs) ?: return null
        val standard = Plan(fromIndex, from.mediaId, toIndex, to.mediaId, length, toDuration,
                if (autoMixOn) window.shortFallback() else window)
        if (!autoMixOn || !player.isCurrentMediaItemSeekable ||
                player.playbackParameters != PlaybackParameters.DEFAULT || player.skipSilenceEnabled) {
            plannedFor = null
            return standard
        }
        val position = player.currentPosition
        val inputs = MixInputs(standard, mixMetadata(from), mixMetadata(to), analysis[from.mediaId], analysis[to.mediaId])
        val previous = plan?.takeIf { it.copy(mix = null) == standard }
        val cueReady = helper?.playbackState == Player.STATE_READY && !helperFailed
        val imminentBeat = previous?.mix?.beats != null && cueReady && aligned &&
                position >= previous.startMs - AutoMixPlanner.PLAN_LEAD_MS
        if (imminentBeat) return previous
        if (plannedFor != inputs) {
            plannedFor = inputs
            plannedMix = AutoMixTransition.plan(inputs.from, inputs.to, length, window,
                    inputs.fromAnalysis, inputs.toAnalysis, position)
        }
        val mix = plannedMix?.takeIf { it.beats != null || AutoMixPlanner.canStartFallback(it.window, position) }
        val next = standard.copy(mix = mix)
        if (previous != null && previous.mix?.beats == null && mix?.beats == null && cueReady &&
                AutoMixPlanner.keepPreparedFallback(previous.window, next.window, position)) return previous
        return next
    }

    private fun canArm(wanted: Plan, position: Long): Boolean = when {
        wanted.mix?.beats != null -> position < wanted.startMs - MIN_LEAD_MS
        autoMixOn -> AutoMixPlanner.remainingFadeMs(wanted.window, position) >= MIN_FADE_MS + HANDOFF_MS
        else -> position < wanted.startMs - MIN_LEAD_MS
    }

    private fun mixMetadata(item: MediaItem): RecordingMetadata {
        val metadata = item.mediaMetadata
        val extras = metadata.extras
        val artist = extras?.getString("artist") ?: metadata.artist?.toString()
        val artistLine = extras?.getString("displayArtist")?.takeIf { it.isNotBlank() } ?: artist
        return RecordingMetadata(metadata.title?.toString() ?: extras?.getString("title"), artist,
                null, durationOf(item)?.div(1_000)?.toInt(), null, null, artistLine)
    }

    private fun fallback(armed: Plan, positionMs: Long) = AutoMixTransition.fallback(
            analysis[armed.fromId], analysis[armed.toId], armed.durationMs, armed.toDurationMs, armed.standard, positionMs,
    )

    private fun preparePreroll(armed: Plan) {
        prerollCueMs = armed.mix?.beats?.let { AutoMixCueSync.prerollCue(it, player.currentPosition) } ?: 0L
    }

    private fun launchAt(armed: Plan): Long =
            armed.mix?.beats?.let { AutoMixCueSync.openAt(it, prerollCueMs) } ?: armed.startMs

    private fun analysisIds(): List<String> {
        val from = player.currentMediaItem ?: return emptyList()
        val next = player.nextMediaItemIndex
        if (player.repeatMode == Player.REPEAT_MODE_ONE || !isMusic(from) ||
                next == C.INDEX_UNSET || next == player.currentMediaItemIndex) return emptyList()
        val following = player.currentTimeline.getNextWindowIndex(next, player.repeatMode, player.shuffleModeEnabled)
        return buildList {
            add(from.mediaId)
            for (index in listOf(next, following)) {
                if (index != C.INDEX_UNSET) {
                    val item = player.getMediaItemAt(index)
                    if (isMusic(item)) add(item.mediaId)
                }
            }
        }
    }

    private fun arm(wanted: Plan) {
        val style = when {
            wanted.mix?.beats != null -> "beat mix"
            wanted.mix != null -> "smart crossfade"
            else -> "crossfade"
        }
        val detail = wanted.mix?.detail?.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
        Log.d(TAG, "armed: $style at ${wanted.startMs} ms of ${wanted.durationMs}$detail")
        plan = wanted
        helperFailed = false
        preparePreroll(wanted)
        if (autoMixOn) player.preloadConfiguration = NEXT_TRACK_PRELOAD
        publishTimeline()

        val launch = launchAt(wanted)
        val prewarmAt = maxOf(0L, launch - if (autoMixOn) AutoMixPlanner.PREWARM_MS else PREWARM_MS)
        val alignAt = maxOf(0L, launch - ALIGNMENT_LEAD_MS)
        val now = player.currentPosition
        val pending = ArrayList<PlayerMessage>(2)
        if (now >= prewarmAt) prewarm() else pending += at(wanted.fromIndex, prewarmAt) { prewarm() }
        if (now < alignAt) pending += at(wanted.fromIndex, alignAt) { startAligning() }
        messages = pending
        if (now >= alignAt) startAligning()
    }

    private fun disarm() {
        messages.forEach { it.cancel() }
        messages = emptyList()
        plan = null
        player.preloadConfiguration = ExoPlayer.PreloadConfiguration.DEFAULT
        if (phase == Phase.IDLE) dropHelper()
        publishTimeline()
    }

    private fun at(index: Int, positionMs: Long, run: () -> Unit): PlayerMessage =
            player.createMessage { _, _ -> run() }
                    .setLooper(handler.looper)
                    .setPosition(index, positionMs)
                    .setDeleteAfterDelivery(true)
                    .also { it.send() }

    private fun prewarm() {
        val armed = plan ?: return
        if (helper != null || phase != Phase.IDLE) return
        if (player.currentMediaItemIndex != armed.fromIndex) return

        // Keep the incoming stream ready when there is only a second to overlap.
        if (!autoMixOn) player.preloadConfiguration = NEXT_TRACK_PRELOAD
        val shifts = SampleShifter()
        val equalizer = EqualizerProcessor()
        val made = newHelper(shifts, equalizer)
        made.volume = 0f
        made.playbackParameters = player.playbackParameters
        made.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (helper !== made) return
                helperFailed = true
                // Before the fade it is the track playing that failed, and would only fail again; during it,
                // the one being left, so the new track is armed for its own fade as usual.
                if (phase != Phase.IDLE) end(refreshAfter = phase == Phase.FADING)
                else publishTimeline()
            }
        })
        val start = maxOf(player.currentPosition, launchAt(armed) - ALIGNMENT_LEAD_MS)
        made.setMediaItem(player.getMediaItemAt(armed.fromIndex), start + lead)
        made.playWhenReady = false
        made.prepare()
        helper = made
        helperEqualizer = equalizer
        shifter = shifts
    }

    private fun dropHelper() {
        helper?.release()
        helper = null
        helperEqualizer = null
        shifter = null
    }

    /* A few seconds before the fade: the helper starts playing along, muted, to be lined up. */
    private fun startAligning() {
        val h = helper
        if (plan == null || h == null || helperFailed || phase != Phase.IDLE) return

        phase = Phase.ALIGNING
        aligned = false
        moveHelper(h)
        h.playWhenReady = playbackAllowed
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    /*
     * Puts the helper where the main player is plus the time it takes to be
     * heard, as last found. Only roughly: it is lined up by shifting from there.
     */
    private fun moveHelper(h: ExoPlayer) {
        h.seekTo(player.currentPosition + lead)
        resettle()
        justMoved = true
    }

    private fun resettle() {
        settleFrom = C.TIME_UNSET
        clearSamples()
        readings.clear()
        aligned = false
        justMoved = false
    }

    /*
     * Both players play the old track, the helper unheard, until the two are
     * lined up and the main one reaches the fade. If they cannot be lined up
     * in time the fade waits, and is shorter for it; with too little of the
     * track left for one, the track simply ends.
     */
    private fun align() {
        val armed = plan
        val h = helper
        val s = shifter
        if (armed == null || h == null || s == null || helperFailed || !stillOn(armed)) {
            end(refreshAfter = !helperFailed)
            return
        }

        val now = player.currentPosition
        val beats = armed.mix?.beats
        if (savedParameters == null) measure(h, s)

        val launch = launchAt(armed)
        val handoffAt = launch - HANDOFF_MS
        if (beats != null && now > handoffAt + MIX_LATE_MS) {
            useFallbackPlan(armed, h, now, "selected cue at ${launch}ms was missed")
            return
        }
        if (beats != null && aligned && h.isPlaying && player.isPlaying) {
            val rate = MixTempo.outgoingCueRate(beats, launch - now)
            setMixSpeed(rate)
            if (h.playbackParameters.speed != rate) h.playbackParameters = PlaybackParameters(rate)
        }
        if (now < handoffAt) return
        val endAt = armed.mix?.endMs ?: armed.durationMs
        if (endAt - now < minimumOverlapMs(armed) + HANDOFF_MS) {
            if (armed.mix != null) useFallbackPlan(armed, h, now, "too little audio remains at the selected cue")
            else {
                Log.d(TAG, "no crossfade: the helper could not be lined up in time")
                end(refreshAfter = false)
            }
            return
        }
        if (aligned && player.isPlaying && h.isPlaying) startHandoff()
    }

    private fun useFallbackPlan(armed: Plan, h: ExoPlayer, positionMs: Long, reason: String): Plan {
        Log.d(TAG, "AutoMix fallback: $reason")
        restoreTempo()
        h.playbackParameters = player.playbackParameters
        val nextMix = if (armed.mix?.beats != null) fallback(armed, positionMs)?.copy(detail = reason) else null
        if (plannedFor?.base == armed.copy(mix = null)) plannedMix = nextMix
        val next = armed.copy(mix = nextMix)
        plan = next
        if (phase != Phase.FADING) publishTimeline()
        return next
    }

    /* Compares where the two players are heard, once both have played for a while, and shifts the helper by that. */
    private fun measure(h: ExoPlayer, s: SampleShifter) {
        if (!h.isPlaying || !player.isPlaying) {
            settleFrom = C.TIME_UNSET
            clearSamples()
            readings.clear()
            return
        }

        // For a second or so after it is heard a player eases what it reports towards where it is.
        val position = h.currentPosition
        if (settleFrom == C.TIME_UNSET) settleFrom = position
        if (position < settleFrom + SETTLE_MS) return

        // A shift is heard only once the sound already handed to the speaker has played.
        val clock = SystemClock.elapsedRealtime()
        if (s.isShifting) shiftHeardAt = clock + SHIFT_HEARD_MS
        if (clock < shiftHeardAt) return

        /*
         * A player tells the app where it is only every ten milliseconds or so,
         * each on its own beat, so either number can be up to that much behind.
         * Against the clock the latest is the one furthest ahead, so over a run
         * of samples each player's furthest is taken as where it is.
         */
        helperAhead = maxOf(helperAhead, position + s.shiftedMs - clock)
        mainAhead = maxOf(mainAhead, player.currentPosition - clock)
        if (++samples < OFFSET_SAMPLES) return

        readings.addLast(helperAhead - mainAhead)
        clearSamples()
        if (readings.size > READINGS) readings.removeFirst()
        if (readings.size < READINGS) return

        // Even then each reading wanders by some milliseconds, so a second's worth of them is averaged.
        val offset = readings.average().roundToLong()
        val tolerance = if (aligned) ALIGN_LOOSE_MS else ALIGN_TOLERANCE_MS
        if (abs(offset) <= tolerance) {
            if (!aligned) Log.d(TAG, "aligned: helper is $offset ms ahead of the main player")
            aligned = true
            justMoved = false
            return
        }

        if (justMoved && abs(offset) <= MAX_LEAD_MS) lead = (lead - offset).coerceIn(0L, MAX_LEAD_MS)
        justMoved = false
        aligned = false
        readings.clear()
        if (abs(offset) > MAX_SHIFT_MS) {
            Log.d(TAG, "aligning: helper is $offset ms ahead of the main player, moving it")
            moveHelper(h)
        } else {
            Log.d(TAG, "aligning: helper is $offset ms ahead of the main player, shifting it")
            s.shift(-offset)
            shiftHeardAt = clock + SHIFT_HEARD_MS
        }
    }

    private fun clearSamples() {
        samples = 0
        helperAhead = Long.MIN_VALUE
        mainAhead = Long.MIN_VALUE
    }

    private fun stillOn(armed: Plan) = player.currentMediaItemIndex == armed.fromIndex &&
            player.nextMediaItemIndex == armed.toIndex &&
            player.currentMediaItem?.mediaId == armed.fromId &&
            player.getMediaItemAt(armed.toIndex).mediaId == armed.toId

    /* The main player is at the point where the fade begins, with the helper lined up. */
    private fun startHandoff() {
        outVolume = ReplayGainUtil.getVolume()
        handoffFrom = C.TIME_UNSET
        handoffWaitedMs = 0
        phase = Phase.HANDOFF
    }

    /*
     * Both players play the old track, one fading in as the other fades out.
     * The two copies are the same signal, so their gains add up to one rather
     * than following the equal-power curve of the crossfade proper.
     */
    private fun handoff() {
        val armed = plan
        val h = helper
        if (armed == null || h == null || !stillOn(armed)) {
            end()
            return
        }

        if (!h.isPlaying) {
            if (playbackAllowed) handoffWaitedMs += TICK_MS
            if (handoffWaitedMs > HANDOFF_WAIT_MS) {
                Log.d(TAG, "no crossfade: helper would not start")
                end()
            }
            return
        }

        if (handoffFrom == C.TIME_UNSET) {
            handoffFrom = h.currentPosition
            Log.d(TAG, "handoff: helper is ${h.currentPosition + (shifter?.shiftedMs ?: 0) - player.currentPosition} ms ahead of the main player")
        }

        val progress = ((h.currentPosition - handoffFrom) / HANDOFF_MS.toFloat()).coerceIn(0f, 1f)
        h.volume = outVolume * progress
        ReplayGainUtil.setFade(player, 1f - progress)

        if (progress >= 1f) {
            val position = outgoingPosition(h)
            val next = if (armed.mix?.beats != null && abs(position - launchAt(armed)) > MIX_LATE_MS) {
                useFallbackPlan(armed, h, position, "selected cue at ${launchAt(armed)}ms was missed during handoff")
            } else armed
            moveOn(next)
        }
    }

    /* The old track now plays from the helper alone; the main player takes the new one. */
    private fun moveOn(armed: Plan) {
        val h = helper ?: return
        val beats = armed.mix?.beats
        val position = outgoingPosition(h)
        ReplayGainUtil.setFade(player, 0f)
        // Reserve the cover change before the seek. Buffering and beat
        // alignment can still decide which transition actually plays.
        coverToken = CoverTransition.begin(armed.fromIndex, armed.fromId, armed.toIndex, armed.toId, autoMixOn)
        if (beats != null) {
            h.playbackParameters = PlaybackParameters(beats.initialOutgoingRate)
            setMixSpeed(MixTempo.incomingRate(beats.tempoRatio, 0f))
            if (beats.outgoingBeatPeriodMs != null) {
                playerEqualizer.mixBassGain = 0f
                helperEqualizer?.mixBassGain = 1f
            }
        }
        if (beats == null && position < armed.startMs) parkOnOutgoing(armed, position)
        else startIncoming(armed, beats?.cueAt(position + AutoMixCueSync.initialStartupMediaMs(beats)) ?: armed.window.cueMs)
    }

    /** A rejected early beat cue can leave a while before the replacement fade. */
    private fun parkOnOutgoing(armed: Plan, position: Long) {
        phase = Phase.WAITING_FOR_FADE
        incomingPreview = null
        publishTimeline()
        if (!stillOn(armed)) seekTrack(armed.fromIndex, position)
    }

    private fun waitForFade() {
        val armed = plan
        val h = helper
        if (armed == null || h == null || !stillOn(armed) || h.playbackState == Player.STATE_ENDED) {
            end()
            return
        }
        if (playbackAllowed && h.isPlaying && outgoingPosition(h) >= armed.startMs) {
            startIncoming(armed, armed.window.cueMs)
        }
    }

    private fun startIncoming(armed: Plan, cueMs: Long) {
        phase = Phase.FADING
        toStarted = false
        cueSync.reset(armed.startMs, armed.window.fadeMs)
        seekIncoming(armed, cueMs)
        refresh()
    }

    private fun seekIncoming(armed: Plan, cueMs: Long) {
        incomingCueMs = cueMs
        cueTimeMs = SystemClock.elapsedRealtime()
        incomingChecked = false
        val outgoingMs = helper?.let(::outgoingPosition) ?: armed.startMs
        if (armed.mix?.beats != null) cueSync.onSeek(outgoingMs, cueMs)
        publishIncomingPreview(armed, outgoingMs)
        seekTrack(armed.toIndex, cueMs)
    }

    private fun seekTrack(index: Int, cueMs: Long) {
        ownSeek = true
        try {
            if (cueMs > 0) player.seekTo(index, cueMs)
            else player.seekToDefaultPosition(index)
        } finally {
            ownSeek = false
        }
    }

    private fun outgoingPosition(h: ExoPlayer) = h.currentPosition + (shifter?.shiftedMs ?: 0)

    /* An analysed fade can finish at a phrase boundary before the recording ends. */
    private fun fade() {
        val armed = plan
        val h = helper
        if (armed == null || h == null || player.currentMediaItemIndex != armed.toIndex ||
                player.currentMediaItem?.mediaId != armed.toId) {
            Log.d(TAG, "fade: incoming track left the queue")
            end()
            return
        }

        val now = SystemClock.elapsedRealtimeNanos()
        if (!playbackAllowed) {
            lastFadeTickNanos = now
            return
        }
        if (!toStarted) {
            // The new track is still loading; the old one plays on at full volume meanwhile.
            if (h.playbackState == Player.STATE_ENDED) {
                Log.d(TAG, "fade: outgoing track ended while the incoming track was loading")
                end()
                return
            }
            val position = outgoingPosition(h)
            if (!readyForFade(armed, h, position)) return

            val naturalEnd = h.duration.takeIf { it != C.TIME_UNSET } ?: armed.durationMs
            val beats = armed.mix?.beats
            tailAtStartMs = beats?.fadeDurationMs(position, minOf(armed.durationMs, naturalEnd), armed.window.fadeMs)
                    ?: minOf(AutoMixPlanner.remainingFadeMs(armed.window, position), naturalEnd - position)
            if (beats != null && cueSync.startMs > armed.startMs) {
                tailAtStartMs = minOf(tailAtStartMs, armed.startMs + armed.window.fadeMs - position)
            }
            if (tailAtStartMs < minimumOverlapMs(armed)) {
                if (beats != null) {
                    fallbackAfterHandoff(armed, h, position, "too little audio remains for the beat mix")
                    return
                }
                end()
                return
            }
            fadeEnvelope = FadeEnvelope(tailAtStartMs, autoMixOn, beats, position,
                com.cappielloantonio.tempo.BuildConfig.AUTOMIX_BASS_MIDPOINT)
            if (beats?.outgoingBeatPeriodMs != null) {
                val center = if (fadeEnvelope.terminalBass) "final downbeat"
                    else fadeEnvelope.bassCenterMs?.let { "downbeat +${it.roundToLong()}ms" } ?: "midpoint"
                Log.d(TAG, "AutoMix bass handoff: $center")
            }
            Log.d(TAG, "fade: new track audible at ${position}ms, entry ${player.currentPosition}ms, overlap ${tailAtStartMs}ms")
            fadeElapsedNanos = 0L
            lastFadeTickNanos = now
            toStarted = true
            startIncomingMixSegment(armed)
            if (!autoMixOn) coverToken?.let { CoverTransition.advance(it, CoverTransition.Mode.FAST_SLIDE, 0f) }
        }

        // Count the outgoing phrase in media time only while the new track is playing, as on desktop.
        val outgoingRate = h.playbackParameters.speed.toDouble()
        if (player.isPlaying) fadeElapsedNanos += ((now - lastFadeTickNanos) * outgoingRate).toLong()
        lastFadeTickNanos = now
        val elapsedMs = fadeElapsedNanos / 1_000_000L
        val progress = fadeEnvelope.progressAt(elapsedMs)
        val mix = armed.mix
        mix?.beats?.let { updateMixTempo(h, it, progress) }
        val levels = fadeEnvelope.levels(progress, elapsedMs + BASS_FILTER_LOOKAHEAD_MS * outgoingRate)
        levels.bass?.let { (incoming, outgoing) ->
            playerEqualizer.mixBassGain = incoming
            helperEqualizer?.mixBassGain = outgoing
        }
        if (autoMixOn) helperEqualizer?.mixDamping = levels.highCut
        val (incoming, outgoing) = levels
        h.volume = outVolume * outgoing
        ReplayGainUtil.setFade(player, incoming)
        if (player.isPlaying) heardThroughMs = heardThroughMs?.let { maxOf(it, player.currentPosition) }
        if (autoMixOn) coverToken?.let {
            val mode = if (mix?.beats != null) CoverTransition.Mode.DISSOLVE else CoverTransition.Mode.SLIDE
            CoverTransition.advance(it, mode, progress)
        }

        if (progress >= 1f || h.playbackState == Player.STATE_ENDED) finishFade()
    }

    private fun minimumOverlapMs(armed: Plan): Long =
            if (autoMixOn) MIN_FADE_MS else AutoMixPlanner.minimumPlayableCrossfadeMs(armed.window.fadeMs)

    private fun readyForFade(armed: Plan, h: ExoPlayer, position: Long): Boolean {
        val mix = armed.mix ?: return player.isPlaying && position >= armed.startMs
        val beats = mix.beats
        if (beats != null && position > cueSync.startMs + MIX_LATE_MS) {
            if (!cueSync.deferUntilBeat(beats, position)) {
                val incoming = player.currentPosition
                Log.d(TAG, "AutoMix cue expired: outgoing ${position}ms, incoming ${incoming}ms, " +
                        "phase ${beats.phaseErrorMs(position, incoming.toDouble())}ms, playing ${player.isPlaying}, " +
                        "state ${player.playbackState}, corrections ${cueSync.retries}")
                fallbackAfterHandoff(armed, h, position, "incoming clock did not lock within the selected phrase")
                return false
            }
            Log.d(TAG, "AutoMix cue moved to the next beat at ${cueSync.startMs}ms")
            publishIncomingPreview(armed, position)
        }
        if (!player.isPlaying) {
            cueSync.resetClockSample()
            return false
        }

        // Verify what actually opened before either an analysed seek or a beat mix becomes audible.
        val recordingDuration = mix.window.cueRecordingDurationMs
        if (!incomingChecked) {
            val cueStatus = recordingDuration?.let {
                AutoMixPlanner.cueStatus(it, player.duration, player.isCurrentMediaItemSeekable, beats != null || incomingCueMs > 0)
            }
            val badSeek = beats == null && incomingCueMs > 0 && !AutoMixPlanner.matchesCue(player.currentPosition, incomingCueMs)
            if ((cueStatus != null && cueStatus != AutoMixPlanner.CueStatus.READY) || badSeek) {
                fallbackAfterHandoff(armed, h, position, "incoming cue unavailable for this stream ($cueStatus)")
                return false
            }
            incomingChecked = true
            if (beats != null) {
                cueSync.onReady(position)
                val incoming = player.currentPosition
                Log.d(TAG, "AutoMix cue playing: requested ${incomingCueMs}ms, actual ${incoming}ms, " +
                        "outgoing ${position}ms, phase ${beats.phaseErrorMs(position, incoming.toDouble())}ms, " +
                        "startup ${SystemClock.elapsedRealtime() - cueTimeMs}ms, corrections ${cueSync.retries}")
            }
        }

        if (beats == null) return position >= armed.startMs
        val incoming = player.currentPosition
        val offset = beats.phaseErrorMs(position, incoming.toDouble())
        if (abs(offset) <= AutoMixPlanner.PHASE_TOLERANCE_MS && position >= cueSync.startMs) {
            val expected = MixTempo.incomingRate(beats.tempoRatio, 0f)
            if (abs(player.playbackParameters.speed - expected) > MixTempo.RATE_TOLERANCE ||
                    abs(h.playbackParameters.speed - beats.initialOutgoingRate) > MixTempo.RATE_TOLERANCE) {
                fallbackAfterHandoff(armed, h, position, "audio output did not accept the selected playback rates")
                return false
            }
            Log.d(TAG, "AutoMix: beat overlap, phase offset $offset ms")
            return true
        }

        cueSync.retryCue(beats, position, incoming)?.let { cue ->
            Log.d(TAG, "AutoMix cue retry: outgoing ${position}ms, phase ${offset}ms, requesting ${cue}ms for ${cueSync.startMs}ms")
            seekIncoming(armed, cue)
        }
        return false
    }

    private fun fallbackAfterHandoff(armed: Plan, h: ExoPlayer, position: Long, reason: String) {
        val next = useFallbackPlan(armed, h, position, reason)
        clearTransitionEq()
        if (position < next.startMs) parkOnOutgoing(next, position)
        else seekIncoming(next, next.window.cueMs)
    }

    private fun updateMixTempo(h: ExoPlayer, beats: BeatMixPlan, progress: Float) {
        val rate = MixTempo.incomingRate(beats.tempoRatio, progress)
        if (abs(rate - player.playbackParameters.speed) >= MixTempo.TEMPO_STEP || progress == 1f) {
            val outRate = MixTempo.outgoingRate(beats.tempoRatio, rate)
            if (h.playbackParameters.speed != outRate) h.playbackParameters = PlaybackParameters(outRate)
            setMixSpeed(rate)
        }
    }

    private fun clearTransitionEq() {
        playerEqualizer.mixBassGain = null
        helperEqualizer?.mixBassGain = null
        helperEqualizer?.mixDamping = null
    }

    private fun finishFade() {
        finishIncomingMixSegment()
        finishCoverTransition()
        clearTransitionEq()
        if (savedParameters == null || player.playbackParameters == savedParameters) {
            end()
            return
        }
        dropHelper()
        ReplayGainUtil.setFade(player, 1f)
        recoverySpeed = player.playbackParameters.speed
        recoveryTickMs = SystemClock.elapsedRealtime()
        recoveryPlayedMs = 0L
        phase = Phase.RECOVERING
        publishTimeline()
    }

    private fun recoverTempo() {
        val original = savedParameters ?: return end()
        val now = SystemClock.elapsedRealtime()
        if (player.isPlaying) recoveryPlayedMs += now - recoveryTickMs
        recoveryTickMs = now
        val progress = recoveryPlayedMs / MixTempo.RECOVERY_MS.toFloat()
        if (progress >= 1f) {
            end()
        } else {
            val speed = MixTempo.glideRate(recoverySpeed, original.speed, progress)
            if (abs(player.playbackParameters.speed - speed) >= MixTempo.TEMPO_STEP) setMixSpeed(speed)
        }
    }

    private fun setMixSpeed(speed: Float) {
        if (player.playbackParameters.speed == speed) return
        if (savedParameters == null) savedParameters = player.playbackParameters
        setMixParameters(PlaybackParameters(speed))
    }

    private fun setMixParameters(parameters: PlaybackParameters) {
        ownParameters = true
        try {
            player.playbackParameters = parameters
        } finally {
            ownParameters = false
        }
    }

    private fun restoreTempo() {
        val original = savedParameters ?: return
        savedParameters = null
        awaitingTempoRestore = original.takeIf { it != player.playbackParameters }
        setMixParameters(original)
    }

    private fun finishCoverTransition() {
        coverToken?.let(CoverTransition::finish)
        coverToken = null
    }

    private fun startIncomingMixSegment(armed: Plan) {
        if (!autoMixOn) return
        val start = player.currentPosition
        incomingMixSegment = incomingSegment(armed, start, tailAtStartMs, player.duration.takeIf { it > 0 })
        incomingPreview = null
        heardThroughMs = incomingMixSegment?.startMs
        publishTimeline()
    }

    private fun publishIncomingPreview(armed: Plan, outgoingMs: Long) {
        if (!autoMixOn) return
        val beats = armed.mix?.beats
        val start = beats?.cueAt(cueSync.startMs) ?: incomingCueMs
        val overlapStart = if (beats != null) cueSync.startMs else maxOf(armed.startMs, outgoingMs)
        val overlapMs = AutoMixPlanner.remainingFadeMs(armed.window, overlapStart)
        // Moving the main player can update the controller before the overlap is audible.
        // Publish both tracks' ranges before the seek, then replace this estimate at audio start.
        incomingPreview = incomingSegment(armed, start, overlapMs, armed.toDurationMs)
        publishTimeline()
    }

    private fun incomingSegment(armed: Plan, start: Long, overlapMs: Long, durationMs: Long?) =
        AutoMixTimeline.Segment.incoming(
            armed.toIndex, armed.toId, start, overlapMs, armed.mix?.beats?.tempoRatio ?: 1.0, durationMs, armed.timelineMode,
        )

    private fun finishIncomingMixSegment() {
        val heard = heardThroughMs ?: return
        heardThroughMs = null
        incomingMixSegment = incomingMixSegment?.heardThrough(heard)
    }

    private fun publishTimeline() {
        val outgoing = plan?.takeIf { autoMixOn && phase != Phase.RECOVERING && !helperFailed }?.let { armed ->
            AutoMixTimeline.Segment(armed.fromIndex, armed.fromId, armed.startMs, armed.startMs + armed.window.fadeMs,
                armed.timelineMode, AutoMixTimeline.Direction.OUTGOING)
        }
        AutoMixTimeline.set(AutoMixTimeline.State(outgoing, incomingMixSegment, incomingPreview))
    }

    /* Leaves the fade, finished or not, with everything back to how a plain track plays. */
    private fun end(refreshAfter: Boolean = true) {
        handler.removeCallbacks(tick)
        val wasFading = phase != Phase.IDLE
        if (wasFading) Log.d(TAG, "end: was $phase")
        phase = Phase.IDLE
        incomingPreview = null
        finishIncomingMixSegment()
        finishCoverTransition()
        clearTransitionEq()
        ReplayGainUtil.setFade(player, 1f)
        restoreTempo()
        disarm()
        if (wasFading && refreshAfter) refresh()
    }

    private fun isMusic(item: MediaItem) =
            item.mediaMetadata.extras?.getString("type") == Constants.MEDIA_TYPE_MUSIC

    private fun durationOf(item: MediaItem) =
            item.mediaMetadata.extras?.getInt("duration", 0)?.takeIf { it > 0 }?.let { it * 1000L }

    /*
     * The next number on the same album is taken to be meant to follow without
     * a seam - a live record, a suite, a concept album - and is left to run on.
     */
    private fun runsInto(from: MediaItem, to: MediaItem): Boolean {
        val a = from.mediaMetadata
        val b = to.mediaMetadata
        val albumA = a.extras?.getString("albumId")
        val albumB = b.extras?.getString("albumId")
        if (albumA == null || albumA != albumB) return false

        val number = a.trackNumber ?: return false
        val next = b.trackNumber ?: return false
        return number > 0 && next == number + 1 && (a.discNumber ?: 0) == (b.discNumber ?: 0)
    }

    private companion object {
        const val TAG = "Crossfader"

        const val MIN_FADE_MS = AutoMixPlanner.MIN_FADE_MS
        const val MIX_LATE_MS = AutoMixPlanner.CUE_TOLERANCE_MS
        const val BASS_FILTER_LOOKAHEAD_MS = 250L
        val NEXT_TRACK_PRELOAD = ExoPlayer.PreloadConfiguration(5_000_000L)

        /* How far ahead of the fade the helper is made and loaded. */
        const val PREWARM_MS = 12_000L

        /* A fade that would begin within this of the present is not worth arming. */
        const val MIN_LEAD_MS = 300L

        /* How long before the fade the helper starts playing, unheard, to be lined up. */
        const val ALIGNMENT_LEAD_MS = 8_000L

        /* How long a started helper plays before what it reports is believed, and
         * how long a shift takes to be heard, at most, once it has gone through. */
        const val SETTLE_MS = 1_500L
        const val SHIFT_HEARD_MS = 1_000L

        /* Samples of the two positions in one reading, and readings averaged. */
        const val OFFSET_SAMPLES = 12
        const val READINGS = 8

        /* Two copies of a track this close together sound as one. Once lined up
         * the helper is left alone unless it is found further off than the
         * looser limit, so that a wandering reading does not undo it. */
        const val ALIGN_TOLERANCE_MS = 3L
        const val ALIGN_LOOSE_MS = 6L

        /* Further off than this the helper is put back by seeking, not shifted. */
        const val MAX_SHIFT_MS = 1_000L

        /*
         * How far ahead of the main player a helper is put when it starts:
         * about how late it is heard. Each device has its own; this is a first
         * guess, and what was found the last time is used from then on.
         */
        var lead = 60L
        const val MAX_LEAD_MS = 250L

        /* Short, so that two copies a few milliseconds apart are not heard together for long. */
        const val HANDOFF_MS = 60L
        const val HANDOFF_WAIT_MS = 1_500L
        const val TICK_MS = 10L
    }
}
