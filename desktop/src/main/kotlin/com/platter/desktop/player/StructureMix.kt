package com.platter.desktop.player

import com.platter.automix.StructureMix as SharedStructureMix
import com.platter.desktop.api.TrackStructure

internal object StructureMix {
    fun plan(from: TrackStructure, to: TrackStructure, durationMs: Long, fade: CrossfadePlan, earliestMs: Long) =
        SharedStructureMix.plan(from, to, durationMs, fade.to.duration?.times(1000L), earliestMs)

    fun crossfade(from: TrackStructure?, to: TrackStructure?, durationMs: Long, edges: CrossfadePlan, earliestMs: Long): CrossfadePlan =
        edges.withWindow(SharedStructureMix.crossfade(
            from, to, durationMs, edges.to.duration?.times(1000L) ?: 0, edges.window(), earliestMs, Crossfade.MIN_FADE_MS,
        ))
}
