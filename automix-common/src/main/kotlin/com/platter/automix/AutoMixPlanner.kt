package com.platter.automix

import com.platter.search.RecordingMetadata
import kotlin.math.abs
import kotlin.math.roundToLong

internal object AutoMixPlanner {
    const val MIN_CROSSFADE_SECONDS = 1
    const val MAX_CROSSFADE_SECONDS = 10
    const val DEFAULT_CROSSFADE_SECONDS = 6
    const val DEFAULT_FADE_MS = DEFAULT_CROSSFADE_SECONDS * 1_000L
    const val MIN_FADE_MS = MIN_CROSSFADE_SECONDS * 1_000L
    const val PLAN_LEAD_MS = 1_000L
    const val PREWARM_MS = 10_000L
    const val CUE_TOLERANCE_MS = 85L
    const val PHASE_TOLERANCE_MS = 55L
    const val PREROLL_MS = 1_200L
    const val MIN_PREROLL_MS = 700L

    enum class CueStatus { READY, UNKNOWN_DURATION, NOT_SEEKABLE, DIFFERENT_RECORDING }

    fun standardFade(durationMs: Long, incomingDurationMs: Long?, wantedMs: Long = DEFAULT_FADE_MS): FadeWindow? {
        val fadeMs = minOf(wantedMs, durationMs / 3, incomingDurationMs?.takeIf { it > 0 }?.div(3) ?: Long.MAX_VALUE)
        return FadeWindow(durationMs - fadeMs, fadeMs).takeIf { fadeMs >= MIN_FADE_MS }
    }

    /** Allow for playback clock and startup delays on the shortest ordinary fades. */
    fun minimumPlayableCrossfadeMs(plannedMs: Long): Long = minOf(MIN_FADE_MS, plannedMs * 2 / 3)

    fun remainingFadeMs(window: FadeWindow, positionMs: Long): Long =
        minOf(window.fadeMs, window.startMs + window.fadeMs - positionMs)

    fun cueStatus(expectedDurationMs: Long, actualDurationMs: Long, seekable: Boolean, requiresSeek: Boolean): CueStatus = when {
        actualDurationMs <= 0 -> CueStatus.UNKNOWN_DURATION
        requiresSeek && !seekable -> CueStatus.NOT_SEEKABLE
        !MixTempo.sameDuration(expectedDurationMs, actualDurationMs) -> CueStatus.DIFFERENT_RECORDING
        else -> CueStatus.READY
    }

    fun matchesCue(actualMs: Long, cueMs: Long): Boolean = actualMs >= 0 && abs(actualMs - cueMs) <= CUE_TOLERANCE_MS

    fun beatMix(
        from: RecordingMetadata,
        to: RecordingMetadata,
        durationMs: Long,
        standard: FadeWindow,
        fromAnalysis: TrackStructure?,
        toAnalysis: TrackStructure?,
        earliestMs: Long,
    ): StructureMix.Selection {
        val structural = if (fromAnalysis != null && toAnalysis != null) {
            StructureMix.plan(fromAnalysis, toAnalysis, durationMs, to.duration?.times(1_000L), earliestMs)
        } else null
        if (structural?.mix != null) return structural
        val detail = structural?.detail ?: "server analysis unavailable for one or both tracks"
        val local = LocalBeatGrid.plan(from, to, durationMs, standard) ?: return StructureMix.Selection(null, detail)
        val launch = local.launchMs()
        if (launch < earliestMs) return StructureMix.Selection(null, "$detail; local beat cue already passed")
        val span = local.fadeDurationMs(launch, durationMs, standard.fadeMs)
        val outgoing = fromAnalysis?.takeIf { MixTempo.sameDuration(it.durationMs, durationMs) }?.sections.orEmpty()
        val incoming = toAnalysis?.takeIf { MixTempo.sameDuration(it.durationMs, local.incomingDurationMs) }?.sections.orEmpty()
        val cue = local.incomingStartMs
        val end = cue + (span * local.tempoRatio).roundToLong()
        if (StructureMix.hasVocalOverlap(outgoing, incoming, launch, launch + span, cue, end)) {
            return StructureMix.Selection(null, "$detail; local beat grid overlaps vocals")
        }
        return StructureMix.Selection(local, "$detail; using the local beat grid")
    }

    fun shapedFade(
        from: TrackStructure?,
        to: TrackStructure?,
        durationMs: Long,
        incomingDurationMs: Long?,
        standard: FadeWindow,
        earliestMs: Long,
    ): FadeWindow {
        val advertised = incomingDurationMs ?: 0L
        val edges = audibleEdges(standard, durationMs, advertised, from, to, MIN_FADE_MS)
        return StructureMix.crossfade(from, to, durationMs, advertised, edges, earliestMs, MIN_FADE_MS)
    }

    fun prerollCue(mix: BeatMixPlan, positionMs: Long): Long {
        val lead = minOf(mix.incomingStartMs, PREROLL_MS, (mix.launchMs() - positionMs - PLAN_LEAD_MS).coerceAtLeast(0))
        return if (lead >= MIN_PREROLL_MS) mix.incomingStartMs - lead else mix.incomingStartMs
    }
}
