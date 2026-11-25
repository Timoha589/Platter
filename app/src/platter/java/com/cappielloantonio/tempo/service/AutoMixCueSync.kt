package com.cappielloantonio.tempo.service

import com.platter.automix.AutoMixPlanner
import com.platter.automix.BeatMixPlan
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Aligns Media3's muted incoming deck once its audio clock has settled after seeking. */
internal class AutoMixCueSync {
    private var seekOutgoingMs = 0L
    private var soughtCueMs = 0L
    private var readyOutgoingMs: Long? = null
    private var stableSinceMs: Long? = null
    private var stablePhaseMs = 0L
    private var latestStartMs = 0L
    var startMs = 0L
        private set
    var retries = 0
        private set

    fun reset(startMs: Long, overlapMs: Long) {
        this.startMs = startMs
        latestStartMs = startMs + minOf(MAX_START_DELAY_MS, overlapMs / 3, (overlapMs - AutoMixPlanner.MIN_FADE_MS).coerceAtLeast(0))
        retries = 0
        resetClockSample()
    }

    fun onSeek(outgoingMs: Long, cueMs: Long) {
        seekOutgoingMs = outgoingMs
        soughtCueMs = cueMs
        readyOutgoingMs = null
        resetClockSample()
    }

    fun onReady(outgoingMs: Long) {
        readyOutgoingMs = outgoingMs
    }

    fun resetClockSample() {
        stableSinceMs = null
    }

    fun deferUntilBeat(mix: BeatMixPlan, earliestMs: Long): Boolean {
        if (earliestMs <= startMs) return true
        val periodMs = mix.outgoingBeatPeriodMs ?: return false
        if (periodMs <= 0 || !periodMs.isFinite()) return false
        val firstBeatMs = mix.launchMs()
        val beats = ceil((earliestMs - firstBeatMs) / periodMs)
        val nextMs = firstBeatMs + (beats * periodMs).roundToLong()
        if (nextMs > latestStartMs) return false
        startMs = nextMs
        return true
    }

    fun retryCue(mix: BeatMixPlan, outgoingMs: Long, incomingMs: Long): Long? {
        val phaseMs = mix.phaseErrorMs(outgoingMs, incomingMs.toDouble())
        if (abs(phaseMs) <= AutoMixPlanner.PHASE_TOLERANCE_MS) {
            resetClockSample()
            return null
        }
        if (retries >= MAX_RETRIES || outgoingMs < seekOutgoingMs + MIN_SAMPLE_LEAD_MS) return null
        val stableSince = stableSinceMs
        if (stableSince == null || abs(phaseMs - stablePhaseMs) > CLOCK_JITTER_MS) {
            stableSinceMs = outgoingMs
            stablePhaseMs = phaseMs
            return null
        }
        if (outgoingMs - stableSince < STABLE_CLOCK_MS) return null

        // Measure media time lost during startup. A correction must lead by that time even if the observed phase is ahead.
        val phaseAtSeekMs = mix.phaseErrorMs(seekOutgoingMs, soughtCueMs.toDouble())
        val startupMs = phaseAtSeekMs - phaseMs
        // The first opening also creates the stream. A seek within that open stream only needs the audio output to settle again.
        val openOnlyMs = if (retries == 0) ((readyOutgoingMs ?: seekOutgoingMs) - seekOutgoingMs - IN_STREAM_READY_MS)
            .coerceAtLeast(0) else 0L
        val projectedMs = outgoingMs + (startupMs - openOnlyMs).coerceIn(0L, MAX_STARTUP_MS)
        // Let the decoder keep running if it is still too early to restart at sample zero.
        if (projectedMs < mix.launchMs(0) || !deferUntilBeat(mix, projectedMs + STABLE_CLOCK_MS)) return null
        retries++
        return mix.cueAt(projectedMs)
    }

    companion object {
        private const val MAX_PREROLL_MS = 2_800L
        private const val INITIAL_STARTUP_MS = 250L
        private const val MIN_OPEN_LEAD_MS = 1_200L
        private const val IN_STREAM_READY_MS = 30L
        private const val MIN_SAMPLE_LEAD_MS = 300L
        private const val CLOCK_JITTER_MS = 25L
        private const val STABLE_CLOCK_MS = 120L
        private const val MAX_STARTUP_MS = 750L
        private const val MAX_RETRIES = 2
        private const val MAX_START_DELAY_MS = 2_000L

        fun prerollCue(mix: BeatMixPlan, positionMs: Long): Long {
            val availableMs = (mix.launchMs() - positionMs - AutoMixPlanner.PLAN_LEAD_MS).coerceAtLeast(0)
            val lead = minOf(MAX_PREROLL_MS, mix.incomingStartMs, (availableMs * mix.tempoRatio).roundToLong())
            return mix.incomingStartMs - lead
        }

        fun initialStartupMediaMs(mix: BeatMixPlan): Long = (INITIAL_STARTUP_MS * mix.initialOutgoingRate).roundToLong()

        fun openAt(mix: BeatMixPlan, prerollCueMs: Long): Long = minOf(
            mix.launchMs(prerollCueMs) - initialStartupMediaMs(mix),
            mix.launchMs() - (MIN_OPEN_LEAD_MS * mix.initialOutgoingRate).roundToLong(),
        ).coerceAtLeast(0)
    }
}
