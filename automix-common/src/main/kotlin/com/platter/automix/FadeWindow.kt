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

internal fun equalPowerGains(progress: Float): Pair<Float, Float> {
    val angle = progress.coerceIn(0f, 1f) * (PI.toFloat() / 2)
    return sin(angle) to cos(angle).coerceAtLeast(0f)
}

/** Cross the bass over a single beat centered in the overlap. */
internal fun bassGains(elapsedMs: Double, overlapMs: Long, beatPeriodMs: Double): Pair<Float, Float> {
    val widthMs = minOf(beatPeriodMs, overlapMs.toDouble())
    return equalPowerGains(((elapsedMs - (overlapMs - widthMs) / 2) / widthMs).toFloat())
}

internal fun bandGainWithBass(baseDb: Float, band: Int, bassGain: Float): Float {
    val gain = when (band) {
        0 -> bassGain.coerceAtLeast(0.1f)
        1 -> 0.2f + 0.8f * bassGain
        else -> 1f
    }
    return (baseDb + 20f * log10(gain)).coerceAtLeast(-20f)
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
internal fun mixGains(progress: Float): Pair<Float, Float> {
    val x = progress.coerceIn(0f, 1f)
    val (incoming, outgoing) = equalPowerGains(x)
    val incomingPower = incoming * incoming
    val outgoingPower = outgoing * outgoing
    // The extra power peaks at 0.25 (+0.97 dB for uncorrelated tracks) and vanishes at both ends.
    val overlapPower = incomingPower * outgoingPower
    return sqrt(incomingPower + overlapPower * (1f - x)) to sqrt(outgoingPower + overlapPower * x)
}
