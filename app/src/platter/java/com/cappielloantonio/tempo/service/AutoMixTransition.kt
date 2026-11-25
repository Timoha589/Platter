package com.cappielloantonio.tempo.service

import com.platter.automix.AutoMixPlanner
import com.platter.automix.BeatMixPlan
import com.platter.automix.FadeWindow
import com.platter.automix.TrackStructure
import com.platter.search.RecordingMetadata

internal data class AutoMixTransition(val window: FadeWindow, val beats: BeatMixPlan? = null, val detail: String = "") {
    val endMs: Long get() = window.startMs + window.fadeMs

    companion object {
        fun plan(
            from: RecordingMetadata,
            to: RecordingMetadata,
            durationMs: Long,
            standard: FadeWindow,
            fromAnalysis: TrackStructure?,
            toAnalysis: TrackStructure?,
            positionMs: Long,
        ): AutoMixTransition? {
            val selection = AutoMixPlanner.beatMix(
                from, to, durationMs, standard, fromAnalysis, toAnalysis, positionMs + AutoMixPlanner.PLAN_LEAD_MS,
            )
            val beats = selection.mix
            if (beats != null) {
                val start = beats.launchMs()
                val span = beats.fadeDurationMs(start, durationMs, standard.fadeMs)
                if (span >= AutoMixPlanner.MIN_FADE_MS) {
                    return AutoMixTransition(FadeWindow(start, span, beats.incomingStartMs, beats.incomingDurationMs), beats, selection.detail)
                }
            }
            return fallback(fromAnalysis, toAnalysis, durationMs, to.duration?.times(1_000L), standard, positionMs)
                ?.copy(detail = selection.detail)
        }

        fun fallback(
            from: TrackStructure?,
            to: TrackStructure?,
            durationMs: Long,
            incomingDurationMs: Long?,
            standard: FadeWindow,
            positionMs: Long,
        ): AutoMixTransition? {
            val window = AutoMixPlanner.shapedFade(
                from, to, durationMs, incomingDurationMs, standard, positionMs + AutoMixPlanner.PLAN_LEAD_MS,
            )
            return window.takeIf { it != standard && AutoMixPlanner.remainingFadeMs(it, positionMs) >= AutoMixPlanner.MIN_FADE_MS }
                ?.let(::AutoMixTransition)
        }
    }
}
