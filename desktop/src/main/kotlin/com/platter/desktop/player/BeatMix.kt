package com.platter.desktop.player

import com.platter.automix.LocalBeatGrid
import com.platter.automix.MixTempo
import com.platter.desktop.api.Song
import com.platter.search.RecordingMetadata

internal typealias BeatMixPlan = com.platter.automix.BeatMixPlan

internal fun Song.mixMetadata() = RecordingMetadata(title, artist, null, duration, null, null, artistLine())

internal object BeatMix {
    fun incomingRate(tempoRatio: Double, progress: Float) = MixTempo.incomingRate(tempoRatio, progress)

    fun outgoingRate(tempoRatio: Double, incomingRate: Float) = MixTempo.outgoingRate(tempoRatio, incomingRate)

    fun glideRate(from: Float, to: Float, progress: Float) = MixTempo.glideRate(from, to, progress)

    fun sameDuration(expectedMs: Long, actualMs: Long) = MixTempo.sameDuration(expectedMs, actualMs)

    fun plan(from: Song, durationMs: Long, fade: CrossfadePlan): BeatMixPlan? =
        LocalBeatGrid.plan(from.mixMetadata(), fade.to.mixMetadata(), durationMs, fade.window())
}
