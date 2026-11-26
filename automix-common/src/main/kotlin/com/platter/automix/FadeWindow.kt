package com.platter.automix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

internal data class FadeWindow(
    val startMs: Long,
    val fadeMs: Long,
    val cueMs: Long = 0,
    val cueRecordingDurationMs: Long? = null,
    val incomingVocalMs: Long? = null,
)

/** At native speeds the two rhythms may drift. Keep their overlap brief and retain the outgoing end. */
internal fun FadeWindow.shortFallback(): FadeWindow {
    val overlap = minOf(fadeMs, 1_500L)
    return copy(startMs = startMs + fadeMs - overlap, fadeMs = overlap)
}

internal fun equalPowerGains(progress: Float): Pair<Float, Float> {
    val angle = progress.coerceIn(0f, 1f) * (PI.toFloat() / 2)
    return sin(angle) to cos(angle).coerceAtLeast(0f)
}

/** Cross the bass over one beat at the selected center, defaulting to the overlap midpoint. */
internal fun bassGains(elapsedMs: Double, overlapMs: Long, beatPeriodMs: Double, centerMs: Double? = null,
                       terminal: Boolean = false): Pair<Float, Float> {
    val widthMs = minOf(beatPeriodMs, overlapMs.toDouble())
    if (terminal) {
        val release = ((elapsedMs - (overlapMs - widthMs)) / widthMs).toFloat()
        return equalPowerGains(release).first to 1f
    }
    val startMs = (centerMs ?: overlapMs / 2.0) - widthMs / 2
    return equalPowerGains(((elapsedMs - startMs) / widthMs).toFloat())
}

/** Ease the outgoing highs down while the incoming track takes over. */
internal fun outgoingDamping(progress: Float): Float = MixTempo.glideRate(0f, 1f, (progress - 0.45f) / 0.40f)

internal fun transitionBandGain(baseDb: Float, band: Int, bassGain: Float, damping: Float): Float {
    val lowGain = when (band) {
        0 -> bassGain.coerceAtLeast(0.1f)
        1 -> 0.2f + 0.8f * bassGain
        else -> 1f
    }
    val highCutDb = when (band) {
        5 -> 4f
        6 -> 7f
        in 7..9 -> 9f
        else -> 0f
    }
    return (baseDb + 20f * log10(lowGain) - damping * highCutDb).coerceAtLeast(-20f)
}

/** The audible envelope, sampled on the outgoing deck's media clock by either player. */
internal class FadeEnvelope(
    private val durationMs: Long,
    private val autoMix: Boolean,
    private val beats: BeatMixPlan?,
    startMs: Long,
    forceBassMidpoint: Boolean,
) {
    val bassCenterMs = beats?.bassCenterMs(startMs, durationMs, forceBassMidpoint)
    val terminalBass = beats?.bassEndsOverlap(startMs, durationMs, forceBassMidpoint) == true

    data class Levels(val incoming: Float, val outgoing: Float, val bass: Pair<Float, Float>?, val highCut: Float)

    fun progressAt(elapsedMs: Long): Float = (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)

    fun levels(progress: Float, bassPositionMs: Double): Levels {
        val period = beats?.outgoingBeatPeriodMs
        val bass = period?.let { bassGains(bassPositionMs, durationMs, it, bassCenterMs, terminalBass) }
        val (incoming, outgoing) = if (beats == null) equalPowerGains(progress)
            else mixGains(progress, durationMs, period.takeIf { terminalBass })
        return Levels(incoming, outgoing, bass, if (autoMix) outgoingDamping(progress) else 0f)
    }
}

/** Measured quiet edges can guide a crossfade even when the two beat grids are incompatible. */
internal fun audibleEdges(
    plan: FadeWindow,
    durationMs: Long,
    incomingDurationMs: Long,
    from: TrackStructure?,
    to: TrackStructure?,
    minFadeMs: Long,
): FadeWindow {
    val tail = from?.takeIf { MixTempo.sameDuration(it.durationMs, durationMs) }?.let {
        it.audioLevels?.lastSoundMs?.takeIf { end -> end in 1 until durationMs && it.durationMs - end >= 250 }
    } ?: durationMs
    val intro = to?.takeIf { MixTempo.sameDuration(it.durationMs, incomingDurationMs) }?.audioLevels
        ?.firstSoundMs?.takeIf { it in 250 until incomingDurationMs } ?: 0
    val incomingAudibleMs = if (intro > 0) incomingDurationMs - intro else Long.MAX_VALUE
    val fadeMs = minOf(plan.fadeMs, tail / 3, incomingAudibleMs / 3)
    if (fadeMs < minFadeMs) return plan
    return plan.copy(startMs = tail - fadeMs, fadeMs = fadeMs, cueMs = intro,
        cueRecordingDurationMs = to?.durationMs?.takeIf { intro > 0 })
}

/** Bring up the incoming rhythm early and keep the outgoing rhythm present until the handoff. */
internal fun mixGains(progress: Float, overlapMs: Long = 0, terminalBassBeatMs: Double? = null): Pair<Float, Float> {
    val position = progress.coerceIn(0f, 1f)
    val x = if (terminalBassBeatMs == null) position else {
        // Keep the outgoing bed audible until just before the incoming bass appears. The
        // last half-beat completes the handoff at the original boundary at equal power.
        val handoff = 1f - (terminalBassBeatMs / 2 / overlapMs).toFloat().coerceAtMost(0.25f)
        val held = 0.70f
        if (position < handoff) minOf(position, held)
        else MixTempo.glideRate(held, 1f, (position - handoff) / (1f - handoff))
    }
    val (incoming, outgoing) = equalPowerGains(x)
    val incomingPower = incoming * incoming
    val outgoingPower = outgoing * outgoing
    // The extra power peaks at 0.25 (+0.97 dB for uncorrelated tracks) and vanishes at both ends.
    val overlapPower = incomingPower * outgoingPower
    return sqrt(incomingPower + overlapPower * (1f - x)) to sqrt(outgoingPower + overlapPower * x)
}
