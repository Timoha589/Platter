package com.platter.automix

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.platter.search.RecordingMatcher
import com.platter.search.RecordingMetadata
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.roundToInt

/** Uses the bundled beat grids when the server cannot supply a compatible pair. */
internal object LocalBeatGrid {
    private data class Track(
        @SerializedName("id") val id: String,
        @SerializedName("artist") val artist: String,
        @SerializedName("title") val title: String,
        @SerializedName("durationMs") val durationMs: Long,
        @SerializedName("intro") val intro: List<Long>,
        @SerializedName("outro") val outro: List<Long>,
    )

    private val creditSeparators = Regex("\\s*/\\s*|\\s*;\\s*|\\s+(?:feat\\.?|ft\\.?|featuring)\\s+|,\\s+", RegexOption.IGNORE_CASE)
    private val catalog by lazy {
        val tracks = runCatching {
            LocalBeatGrid::class.java.getResourceAsStream("/automix-trial.json")
                ?.bufferedReader(Charsets.UTF_8)?.use { Gson().fromJson(it, Array<Track>::class.java).toList() }
        }.getOrNull().orEmpty()
        RecordingMatcher(tracks, {
            RecordingMetadata(it.title, it.artist, null, (it.durationMs / 1_000.0).roundToInt(), null, null, id = it.id)
        }, ::normalize, { it?.split(creditSeparators).orEmpty() })
    }

    private fun normalize(text: String?): String {
        val folded = Normalizer.normalize(text.orEmpty().lowercase().trim(), Normalizer.Form.NFD)
        return folded.filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
    }

    private fun track(metadata: RecordingMetadata): Track? {
        val duration = metadata.duration?.takeIf { it > 0 } ?: return null
        return listOfNotNull(metadata.artistLine, metadata.artist).distinct().mapNotNull { artist ->
            catalog.match(RecordingMetadata(metadata.title, artist, null, duration, null, null))
        }.distinct().singleOrNull()?.takeIf { MixTempo.sameDuration(it.durationMs, duration * 1_000L) }
    }

    fun plan(from: RecordingMetadata, to: RecordingMetadata, durationMs: Long, fade: FadeWindow): BeatMixPlan? {
        val outgoing = track(from)?.takeIf { MixTempo.sameDuration(it.durationMs, durationMs) } ?: return null
        val incoming = track(to) ?: return null
        val end = minOf(durationMs, outgoing.durationMs)
        val tail = outgoing.outro.filter { it < end }

        for ((i, outgoingBeat) in tail.withIndex()) {
            if (outgoingBeat !in fade.startMs..(fade.startMs + MAX_OUTRO_SHIFT_MS)) continue
            val beats = tail.subList(i, tail.size)
            if (beats.size < MIN_BEATS) break
            val period = period(beats)
            if (!isSteady(beats, period) || end - beats.last() > period + 200) continue

            for ((j, incomingBeat) in incoming.intro.withIndex()) {
                if (incomingBeat > MAX_INTRO_SKIP_MS || j + beats.size > incoming.intro.size) break
                val intro = incoming.intro.subList(j, j + beats.size)
                val incomingPeriod = period(intro)
                val rate = incomingPeriod / period
                if (rate !in MixTempo.supportedTempoRatios || !isSteady(intro, incomingPeriod)) continue
                if (beats.indices.any { abs(beats[it] - outgoingBeat - (intro[it] - incomingBeat) / rate) > MAX_PHASE_ERROR_MS }) continue

                return BeatMixPlan(
                    outgoingBeat, incomingBeat, (incomingBeat - CUE_LEAD_MS).coerceAtLeast(0), incoming.durationMs, rate,
                    outgoingBeatPeriodMs = period,
                )
            }
        }
        return null
    }

    private fun period(beats: List<Long>): Double = (beats.last() - beats.first()).toDouble() / beats.lastIndex

    private fun isSteady(beats: List<Long>, period: Double): Boolean = period in 240.0..850.0 && beats.indices.all {
        abs(beats[it] - beats.first() - it * period) <= MAX_PHASE_ERROR_MS
    }

    private const val MIN_BEATS = 5
    private const val MAX_OUTRO_SHIFT_MS = 1_200L
    private const val MAX_INTRO_SKIP_MS = 4_000L
    private const val MAX_PHASE_ERROR_MS = 65
    private const val CUE_LEAD_MS = 160L
}
