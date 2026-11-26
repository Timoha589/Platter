package com.platter.automix

import kotlin.math.abs
import kotlin.math.roundToLong

internal data class BeatMixPlan(
    val outgoingBeatMs: Long,
    val incomingBeatMs: Long,
    val incomingStartMs: Long,
    val incomingDurationMs: Long,
    val tempoRatio: Double,
    val section: String? = null,
    val exitSection: String? = null,
    val overlapMs: Long? = null,
    val bars: Int? = null,
    val phaseAdvanceMs: Long = 0,
    val outgoingBeatPeriodMs: Double? = null,
    val bassHandoffMs: Long? = null,
) {
    val initialOutgoingRate: Float get() = MixTempo.initialOutgoingRate(tempoRatio)

    fun launchMs(cue: Long = incomingStartMs) =
        outgoingBeatMs - ((incomingBeatMs - cue) / tempoRatio).roundToLong()

    fun fadeDurationMs(startMs: Long, durationMs: Long, crossfadeMs: Long): Long {
        val end = if (overlapMs == null) durationMs else minOf(outgoingBeatMs, durationMs)
        return minOf(overlapMs ?: crossfadeMs, end - startMs)
    }

    fun phaseErrorMs(outgoingMs: Long, incomingMs: Double): Long =
        ((incomingMs - incomingBeatMs) / tempoRatio - (outgoingMs - outgoingBeatMs)).roundToLong()

    fun cueAt(outgoingMs: Long): Long =
        (incomingStartMs + ((outgoingMs - launchMs()) * tempoRatio).roundToLong()).coerceAtLeast(0)

    /** Convert the selected downbeat to the overlap clock, accounting for late launch. */
    fun bassCenterMs(startMs: Long, fadeMs: Long, forceMidpoint: Boolean): Double? {
        val period = outgoingBeatPeriodMs ?: return null
        val center = bassHandoffMs?.minus(startMs)?.toDouble() ?: return null
        return center.takeIf { !forceMidpoint && it >= period && it <= fadeMs - period }
    }

    fun bassEndsOverlap(startMs: Long, fadeMs: Long, forceMidpoint: Boolean): Boolean =
        !forceMidpoint && overlapMs != null && bassHandoffMs == outgoingBeatMs && startMs + fadeMs == outgoingBeatMs
}

internal object MixTempo {
    const val RECOVERY_MS = 1_600L
    const val TEMPO_STEP = 0.0005f
    const val RATE_TOLERANCE = 0.005f
    const val RAMP_LEAD_MS = 2_000L
    private const val SETTLE_LEAD_MS = 400L
    private const val MIN_RATE = 0.90
    private const val MAX_RATE = 1.10
    private val trackRates = MIN_RATE..MAX_RATE
    val supportedTempoRatios = (MIN_RATE / MAX_RATE)..(MAX_RATE / MIN_RATE)

    fun initialOutgoingRate(tempoRatio: Double): Float =
        (tempoRatio.coerceIn(trackRates) / tempoRatio).coerceIn(trackRates).toFloat()

    fun outgoingRate(tempoRatio: Double, incomingRate: Float): Float =
        (incomingRate / tempoRatio).coerceIn(trackRates).toFloat()

    fun outgoingCueRate(mix: BeatMixPlan, timeToCueMs: Long): Float {
        val progress = (RAMP_LEAD_MS - timeToCueMs).toFloat() / (RAMP_LEAD_MS - SETTLE_LEAD_MS)
        return glideRate(1f, mix.initialOutgoingRate, progress)
    }

    /** Get as close as possible to each track's original tempo while both decks must stay in sync. */
    fun incomingRate(tempoRatio: Double, progress: Float): Float {
        val start = tempoRatio.coerceIn(trackRates).toFloat()
        val end = 1.0.coerceIn(tempoRatio * MIN_RATE, tempoRatio * MAX_RATE).coerceIn(trackRates).toFloat()
        return glideRate(start, end, progress)
    }

    fun glideRate(from: Float, to: Float, progress: Float): Float {
        val x = progress.coerceIn(0f, 1f)
        val eased = x * x * (3f - 2f * x)
        return from + (to - from) * eased
    }

    fun sameDuration(expectedMs: Long, actualMs: Long): Boolean = abs(expectedMs - actualMs) <= 1_500
}
