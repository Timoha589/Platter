package com.platter.automix

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal object StructureMix {
    private val backing = setOf("intro", "inst", "solo", "outro", "break")
    private val vocals = setOf("verse", "chorus", "bridge")
    private val phraseBars = listOf(16, 12, 8, 4)
    private val preferredOverlapMs = 12_000L..18_000L
    private const val EARLY_HANDOFF_MS = 45_000L
    private const val MAX_VOCAL_OVERLAP_MS = 400L

    data class Selection(val mix: BeatMixPlan?, val detail: String)

    private data class Phrase(val first: Int, val label: String?, val vocal: TrackSection? = null)
    private data class Alignment(val beats: Int, val errorMs: Double, val outgoingIndex: Int, val incomingIndex: Int)
    private enum class PhraseResult { Compatible, Tempo, Grid, Vocals }
    private val pitchClasses = mapOf(
        "C" to 0, "C#" to 1, "Db" to 1, "D" to 2, "D#" to 3, "Eb" to 3, "E" to 4,
        "F" to 5, "F#" to 6, "Gb" to 6, "G" to 7, "G#" to 8, "Ab" to 8, "A" to 9,
        "A#" to 10, "Bb" to 10, "B" to 11,
    )

    fun plan(from: TrackStructure, to: TrackStructure, durationMs: Long, incomingDurationMs: Long?, earliestMs: Long): Selection {
        val advertised = incomingDurationMs ?: return Selection(null, "missing duration for the incoming track")
        if (!MixTempo.sameDuration(from.durationMs, durationMs) || !MixTempo.sameDuration(to.durationMs, advertised))
            return Selection(null, "analysis duration differs from the recording")
        val incoming = passages(to)
        val firstVocal = incoming.firstOrNull { it.label in vocals }
        val outgoing = passages(from)
        val inBars = to.downbeatsMs.orEmpty()
        val outBars = from.downbeatsMs.orEmpty()
        val inBeats = to.beatsMs.orEmpty()
        val outBeats = from.beatsMs.orEmpty()
        val bass = to.bassEntries
        val bassMarkers = bass?.takeIf { it.version in 1..2 }?.downbeatsMs.orEmpty().sorted()
        val bassStatus = when {
            bass == null -> "bass entries missing"
            bass.version !in 1..2 -> "bass entries version ${bass.version} unsupported"
            bassMarkers.isEmpty() -> "bass entries v${bass.version}: no sustained entries detected"
            else -> "bass entries v${bass.version}: ${bassMarkers.size} detected"
        }
        if (inBars.isEmpty() || outBars.isEmpty() || inBeats.isEmpty() || outBeats.isEmpty())
            return Selection(null, "missing beat grid; $bassStatus")
        val firstExit = maxOf(earliestMs, durationMs - minOf(90_000L, durationMs / 3))
        val exits = outgoing.filter { it.endMs >= firstExit && (it.label in backing || it.label in vocals) }
        val profileCost = overlapCostPerMs(from.audioMuse, to.audioMuse)
        val firstSound = to.audioLevels?.firstSoundMs?.takeIf { it in 0..to.durationMs } ?: 0
        val lastSound = from.audioLevels?.lastSoundMs?.takeIf { it in 0..durationMs } ?: durationMs
        var best: BeatMixPlan? = null
        var bestCost = Double.POSITIVE_INFINITY
        var bestEndsAtFirstVocal = false
        var bestBass = BassHandoff(null, null)
        var valid = 0
        var tempoRejected = 0
        var gridRejected = 0
        var vocalRejected = 0

        fun consider(entry: Phrase, exit: Phrase, count: Int, expanded: Boolean): PhraseResult {
            val cue = inBars[entry.first]
            val anchor = inBars[entry.first + count]
            val launch = outBars[exit.first]
            val end = outBars[exit.first + count]
            val overlap = end - launch
            if (count == 2 && (!completeTwoBars(inBeats, inBars, entry.first) ||
                    !completeTwoBars(outBeats, outBars, exit.first))) return PhraseResult.Grid
            val rate = (anchor - cue).toDouble() / overlap
            if (rate !in MixTempo.supportedTempoRatios) return PhraseResult.Tempo
            val barError = (1..count).maxOf { k ->
                abs((inBars[entry.first + k] - cue) / rate - (outBars[exit.first + k] - launch))
            }
            val alignment = if (barError <= 85) beatAlignment(outBeats, inBeats, launch, end, cue, anchor, rate) else null
            if (alignment == null) return PhraseResult.Grid
            if (hasVocalOverlap(outgoing, incoming, launch, end, cue, anchor)) return PhraseResult.Vocals

            val endsAtFirstVocal = entry.vocal != null && entry.vocal == firstVocal
            if (!expanded && bestEndsAtFirstVocal && !endsAtFirstVocal) return PhraseResult.Compatible
            // Score in equivalent milliseconds of skipped audio.
            val overlapCost = abs(overlap - overlap.coerceIn(preferredOverlapMs)) * 2.0
            val tempoHintCost = tempoCost(from.audioMuse?.tempoBpm, alignment.beats * 60_000.0 / overlap) +
                tempoCost(to.audioMuse?.tempoBpm, alignment.beats * 60_000.0 / (anchor - cue))
            val skipped = (lastSound - end).coerceAtLeast(0) + (cue - firstSound).coerceAtLeast(0)
            val quietOverlap = (end - maxOf(launch, lastSound)).coerceAtLeast(0) +
                (minOf(anchor, firstSound) - cue).coerceAtLeast(0) / rate
            val levelCost = levelCost(from.audioLevels, to.audioLevels, launch, end, cue, anchor)
            val cost = skipped + quietOverlap + levelCost + overlapCost + abs(1 - rate) * 20_000 +
                maxOf(barError, alignment.errorMs) * 50 + tempoHintCost +
                profileCost * (overlap - 8_000).coerceAtLeast(0) + (if (exit.label in vocals) 3_000 else 0) -
                (if (entry.vocal != null) 2_000 else 0)
            if ((expanded || endsAtFirstVocal == bestEndsAtFirstVocal) && cost >= bestCost) return PhraseResult.Compatible
            bestCost = cost
            bestEndsAtFirstVocal = endsAtFirstVocal
            // Leave the audio at native speed if stretching would only save a few milliseconds of drift.
            val playbackRate = if (abs(anchor - cue - overlap) <= 15) 1.0 else rate
            val phaseAdvance = phaseAdvance(from, to, alignment, end, anchor, playbackRate)
            val period = overlap.toDouble() / alignment.beats
            val terminalBass = bass?.version == 2 && exit.label in backing &&
                end - period < lastSound && outgoing.none { it.label in vocals &&
                    it.startMs < end - 90 && it.endMs > end - period } &&
                localRhythm(from, end - 2 * period.roundToLong(), end).any { it.measured &&
                    it.timeMs in (end - 2 * period.roundToLong())..end } &&
                !locallyQuiet(from.audioLevels, end - period.roundToLong(), end)
            bestBass = bassHandoff(bassMarkers, inBars, entry, outBars, exit, count, period, terminalBass)
            best = BeatMixPlan(
                end, anchor + phaseAdvance, (cue + phaseAdvance).coerceAtLeast(0), to.durationMs,
                playbackRate, entry.label, exit.label, overlap, count, phaseAdvance,
                outgoingBeatPeriodMs = period,
                bassHandoffMs = bestBass.outgoingMs,
            )
            return PhraseResult.Compatible
        }

        for (count in phraseBars) {
            val outgoingPhrases = exitPhrases(exits, outBars, count, firstExit, durationMs)
            for (entry in entryPhrases(incoming, inBars, count, to.durationMs)) {
                for (exit in outgoingPhrases) {
                    when (consider(entry, exit, count, expanded = false)) {
                        PhraseResult.Compatible -> valid++
                        PhraseResult.Tempo -> tempoRejected++
                        PhraseResult.Grid -> gridRejected++
                        PhraseResult.Vocals -> vocalRejected++
                    }
                }
            }
        }
        val earlyLimit = minOf(EARLY_HANDOFF_MS, to.durationMs / 2)
        val standard = best
        val earlySearch = if (standard == null || standard.incomingStartMs > earlyLimit) {
            phraseBars.filter { it <= 12 }.map { count ->
                var compatible = 0
                var badTempo = 0
                var badGrid = 0
                var badVocals = 0
                var firstRejected: String? = null
                val entries = earlyPhrases(incoming, inBars, count, earlyLimit)
                val alternatives = (exitPhrases(exits, outBars, count, firstExit, durationMs) +
                    tailPhrases(exits, outBars, count, firstExit, lastSound, durationMs)).distinctBy { it.first }
                for (entry in entries) {
                    for (exit in alternatives) {
                        val result = consider(entry, exit, count, expanded = true)
                        when (result) {
                            PhraseResult.Compatible -> compatible++
                            PhraseResult.Tempo -> badTempo++
                            PhraseResult.Grid -> badGrid++
                            PhraseResult.Vocals -> badVocals++
                        }
                        if (result != PhraseResult.Compatible && firstRejected == null) {
                            firstRejected = "$result at ${outBars[exit.first]}..${outBars[exit.first + count]}ms -> " +
                                "${inBars[entry.first]}..${inBars[entry.first + count]}ms"
                        }
                    }
                }
                "$count bars ${entries.size} entries/${alternatives.size} exits, $compatible compatible, $badTempo tempo, " +
                    "$badGrid grid, $badVocals vocals" +
                    firstRejected?.let { " (first rejection: $it)" }.orEmpty()
            }.joinToString(" | ", prefix = "early search: ")
        } else null
        val earlyChoice = best?.takeUnless { it === standard }
        if (best == null) {
            val exitsForTwoBars = (exitPhrases(exits, outBars, 2, firstExit, durationMs) +
                tailPhrases(exits, outBars, 2, firstExit, lastSound, durationMs)).distinctBy { it.first }
            val entriesForTwoBars = (entryPhrases(incoming, inBars, 2, to.durationMs).toList() +
                earlyPhrases(incoming, inBars, 2, earlyLimit)).distinctBy { it.first }
            for (entry in entriesForTwoBars) {
                for (exit in exitsForTwoBars) consider(entry, exit, 2, expanded = true)
            }
        }
        val detail = when {
            best?.bars == 2 -> "selected a two-bar overlap after longer phrases failed"
            earlyChoice != null -> "selected an early handoff at ${earlyChoice.incomingBeatMs}ms"
            valid > 0 -> "selected from $valid compatible phrases"
            tempoRejected + gridRejected + vocalRejected > 0 ->
                "no compatible phrases ($tempoRejected outside the tempo limit, $gridRejected with mismatched grids, " +
                    "$vocalRejected with overlapping vocals)"
            else -> "no eligible entry and exit phrases after ${earliestMs}ms"
        }
        val details = listOfNotNull(
            detail,
            earlySearch,
            "$vocalRejected section-boundary pairs rejected for overlapping vocals".takeIf { vocalRejected > 0 && best != null },
            best?.takeIf { bestEndsAtFirstVocal }?.let { "handoff at first vocal (${it.incomingBeatMs}ms)" },
            best?.phaseAdvanceMs?.takeIf { it != 0L }?.let { "audio attack correction ${it}ms" },
            bassStatus,
            best?.takeIf { bassMarkers.isNotEmpty() }?.let {
                when {
                    bestBass.outgoingMs != null -> "bass entry at ${bestBass.incomingMs}ms maps to ${bestBass.outgoingMs}ms"
                    bestBass.incomingMs != null -> "bass entry at ${bestBass.incomingMs}ms is too close to the overlap edge"
                    else -> "no bass entry in the selected overlap"
                }
            },
            "AudioMuse features available".takeIf { from.audioMuse != null || to.audioMuse != null },
            "local loudness available".takeIf { from.audioLevels != null || to.audioLevels != null },
        )
        return Selection(best, details.joinToString("; "))
    }

    /** Pick section boundaries for a fade at native speed, even when the grids cannot be mixed. */
    fun crossfade(from: TrackStructure?, to: TrackStructure?, durationMs: Long, advertisedMs: Long, window: FadeWindow, earliestMs: Long, minFadeMs: Long): FadeWindow {
        val edges = window.shortFallback()
        val outgoing = from?.takeIf { MixTempo.sameDuration(it.durationMs, durationMs) }
        val incoming = to?.takeIf { MixTempo.sameDuration(it.durationMs, advertisedMs) }
        val incomingDuration = incoming?.durationMs ?: 0L
        val incomingParts = incoming?.let(::passages).orEmpty()
        val firstVocal = incomingParts.firstOrNull { it.label in vocals }?.startMs?.takeIf {
            it >= edges.cueMs + minFadeMs && it <= minOf(120_000L, incomingDuration / 2)
        }
        val introEnd = incomingParts.firstOrNull()?.takeIf { it.label in backing && it.startMs <= edges.cueMs + 1_000 }?.endMs
        val tail = edges.startMs + edges.fadeMs
        val firstExit = maxOf(earliestMs, durationMs - minOf(90_000L, durationMs / 3))
        val outgoingParts = outgoing?.let(::passages).orEmpty()
        val exits = outgoingParts.filter { it.endMs > firstExit && (it.label in backing || it.label in vocals) }
        val introLength = (firstVocal ?: introEnd)?.minus(edges.cueMs)
        val preferred = minOf(introLength ?: window.fadeMs, window.fadeMs)
        var best: FadeWindow? = null
        var bestCost = Double.POSITIVE_INFINITY
        for (exit in exits + TrackSection(firstExit, tail, null)) {
            val end = minOf(exit.endMs, tail)
            val room = minOf(preferred, end - maxOf(firstExit, exit.startMs))
            if (room < minFadeMs) continue
            val short = minOf(room, edges.fadeMs)
            val spans = if (outgoing?.audioLevels?.momentaryLufs != null || incoming?.audioLevels?.momentaryLufs != null) {
                (room downTo short step 500).toList() + short
            } else listOf(short)
            for (overlap in spans.distinct()) {
                val start = end - overlap
                val cue = firstVocal?.minus(overlap) ?: edges.cueMs
                if (cue > 75_000L || hasVocalOverlap(outgoingParts, incomingParts, start, end, cue, cue + overlap)) continue
                // At native speeds, extend the overlap only when one deck is quiet enough to stay in the background.
                if (overlap > short && !locallyQuiet(outgoing?.audioLevels, start, end) &&
                    !locallyQuiet(incoming?.audioLevels, cue, cue + overlap)) continue
                val cost = (tail - end) + (cue - edges.cueMs) + 2 * (room - overlap) +
                    levelCost(outgoing?.audioLevels, incoming?.audioLevels, start, end, cue, cue + overlap) +
                    when (exit.label) {
                        null -> 4_000
                        in vocals -> 3_000
                        else -> 0
                    }
                if (cost >= bestCost) continue
                bestCost = cost
                best = edges.copy(startMs = start, fadeMs = overlap, cueMs = cue,
                    cueRecordingDurationMs = incoming?.durationMs?.takeIf { cue > 0 }, incomingVocalMs = firstVocal)
            }
        }
        val selected = best ?: edges
        if (selected.fadeMs > edges.fadeMs || outgoing == null || incoming == null) return selected
        val end = selected.startMs + selected.fadeMs
        val outAttacks = localRhythm(outgoing, selected.startMs - 120, end)
        val inAttacks = localRhythm(incoming, selected.cueMs, selected.cueMs + selected.fadeMs + 160)
        if (outAttacks.isEmpty() || inAttacks.isEmpty()) return selected

        // With two active grids, a shorter overlap limits drift at native speeds. Align the
        // attacks nearest the gain crossover, where the two tracks are equally loud.
        val short = minOf(selected.fadeMs, maxOf(minFadeMs, 1_200L))
        val endMoves = if (selected.incomingVocalMs != null) 0L..120L step 10 else 0L..0L
        val spans = ((short..selected.fadeMs step 10).toList() + selected.fadeMs).distinct()
        var bestRhythm = selected
        var bestRhythmCost = Double.POSITIVE_INFINITY
        for (moveEnd in endMoves) {
            val stop = end - moveEnd
            if (moveEnd > 0 && clipsVocal(outgoingParts, stop, end)) continue
            for (span in spans) {
                val start = stop - span
                if (start < earliestMs) continue
                val baseCue = selected.incomingVocalMs?.minus(span) ?: selected.cueMs
                val cross = closestAttack(outAttacks, start + span / 2) - start
                val hit = closestAttack(inAttacks, baseCue + cross)
                val alignedCue = if (selected.incomingVocalMs == null) (hit - baseCue - cross).coerceIn(0L, 160L) else 0L
                for (move in listOf(0L, alignedCue).distinct()) {
                    val cue = baseCue + move
                    if (cue + span > incomingDuration ||
                        (move > 0 && clipsVocal(incomingParts, baseCue, cue)) ||
                        hasVocalOverlap(outgoingParts, incomingParts, start, stop, cue, cue + span)) continue
                    // Require corroborating loudness evidence before treating the lack of measured attacks as a quiet deck.
                    val outHits = outAttacks.any { it.measured && it.timeMs in start..stop }
                    val inHits = inAttacks.any { it.measured && it.timeMs in cue..(cue + span) }
                    val oneRhythm = (outHits && !inHits && locallyQuiet(incoming.audioLevels, cue, cue + span)) ||
                        (inHits && !outHits && locallyQuiet(outgoing.audioLevels, start, stop))
                    val phaseCost = if (oneRhythm) -200L else abs(hit - cue - cross)
                    val cost = phaseCost + (span - short) / 2.0 + move / 4.0 + moveEnd / 4.0
                    if (cost >= bestRhythmCost) continue
                    bestRhythmCost = cost
                    bestRhythm = selected.copy(startMs = start, fadeMs = span, cueMs = cue,
                        cueRecordingDurationMs = incomingDuration.takeIf { cue > 0 })
                }
            }
        }
        return bestRhythm
    }

    private fun clipsVocal(sections: List<TrackSection>, start: Long, end: Long): Boolean =
        sections.any { it.label in vocals && it.startMs < end && it.endMs > start }

    private data class Attack(val timeMs: Long, val measured: Boolean)

    private fun localRhythm(track: TrackStructure, start: Long, end: Long): List<Attack> {
        val beats = track.beatsMs.orEmpty()
        val from = nearest(beats, start, 850) ?: return emptyList()
        val to = nearest(beats, end, 850) ?: return emptyList()
        if (to - from < 2 || (from + 1..to).any { beats[it] - beats[it - 1] !in 240L..850L }) return emptyList()
        return (from..to).map { i ->
            val offset = track.beatOffsetsMs?.getOrNull(i)?.takeIf { it in -65L..85L }
            Attack(beats[i] + (offset ?: 0L), offset != null)
        }
    }

    private fun closestAttack(attacks: List<Attack>, targetMs: Long): Long = attacks.minBy {
        abs(it.timeMs - targetMs) + if (it.measured) 0 else 65
    }.timeMs

    private fun locallyQuiet(levels: TrackAudioLevels?, start: Long, end: Long): Boolean {
        val outside = maxOf(levels?.at(start - 1_500) ?: return false, levels.at(end + 1_500) ?: return false)
        return listOf(start + (end - start) / 4, (start + end) / 2, end - (end - start) / 4).all { time ->
            val level = levels.at(time) ?: return false
            level < -48 || outside - level >= 9
        }
    }

    private fun TrackAudioLevels.at(ms: Long): Double? {
        val samples = momentaryLufs?.takeIf { it.isNotEmpty() } ?: return null
        if (stepMs <= 0) return null
        val index = ((ms - startMs).toDouble() / stepMs).roundToInt().coerceIn(samples.indices)
        return samples[index].takeIf { it.isFinite() }
    }

    private fun levelCost(from: TrackAudioLevels?, to: TrackAudioLevels?, launch: Long, end: Long, cue: Long, anchor: Long): Double {
        val before = from?.at(launch - 500) ?: return 0.0
        val after = to?.at(anchor + 500) ?: return 0.0
        var worst = 0.0
        for (step in 1..7) {
            val out = from.at(launch + (end - launch) * step / 8) ?: continue
            val incoming = to.at(cue + (anchor - cue) * step / 8) ?: continue
            val (inGain, outGain) = mixGains(step / 8f)
            // Compare each deck with the nearby solo audio so ReplayGain does not need to be known here.
            val outPower = 10.0.pow((out - before).coerceIn(-24.0, 24.0) / 10) * outGain * outGain
            val inPower = 10.0.pow((incoming - after).coerceIn(-24.0, 24.0) / 10) * inGain * inGain
            worst = maxOf(worst, abs(10 * log10(outPower + inPower)))
        }
        return (worst - 2.0).coerceIn(0.0, 8.0) * 800
    }

    // AudioMuse can fold a track's tempo by an octave. A local tempo change is also plausible,
    // so favour agreement gently after the actual beat grid has already passed its checks.
    private fun tempoCost(hint: Double?, local: Double): Double {
        if (hint == null || hint !in 1.0..600.0) return 0.0
        val nearest = minOf(abs(local - hint), abs(local - hint * 2), abs(local - hint / 2))
        return (nearest / local - 0.03).coerceIn(0.0, 0.12) * 10_000
    }

    private fun overlapCostPerMs(from: AudioMuseFeatures?, to: AudioMuseFeatures?): Double {
        if (from == null || to == null) return 0.0
        val keyCost = if (harmonicClash(from, to)) 1.25 else 0.0
        val fromEnergy = from.energy?.takeIf { it in 0.0..1.0 }
        val toEnergy = to.energy?.takeIf { it in 0.0..1.0 }
        val energyCost = if (fromEnergy != null && toEnergy != null) abs(fromEnergy - toEnergy) * 0.75 else 0.0
        return keyCost + energyCost
    }

    /** Compare the vocal section intervals on the outgoing deck's timeline. */
    fun hasVocalOverlap(outgoing: List<TrackSection>, incoming: List<TrackSection>, launch: Long, end: Long,
                        cue: Long, anchor: Long): Boolean {
        val duration = end - launch
        if (duration <= 0 || anchor <= cue) return false
        val rate = (anchor - cue).toDouble() / duration
        // The other deck is quiet at either edge. Allow for uncertainty in section timestamps there.
        val margin = minOf(duration / 8, 500L)
        var total = 0L
        for (from in outgoing) {
            if (from.label !in vocals) continue
            val start = maxOf(from.startMs, launch + margin)
            val stop = minOf(from.endMs, end - margin)
            if (stop <= start) continue
            for (to in incoming) {
                if (to.label !in vocals || to.startMs >= anchor || to.endMs <= cue) continue
                val inStart = launch + ((to.startMs - cue) / rate).roundToLong()
                val inEnd = launch + ((to.endMs - cue) / rate).roundToLong()
                total += (minOf(stop, inEnd) - maxOf(start, inStart)).coerceAtLeast(0)
                if (total > MAX_VOCAL_OVERLAP_MS) return true
            }
        }
        return false
    }

    private fun harmonicClash(from: AudioMuseFeatures, to: AudioMuseFeatures): Boolean {
        val a = pitchClasses[from.key] ?: return false
        val b = pitchClasses[to.key] ?: return false
        val fromScale = from.scale?.takeIf { it == "major" || it == "minor" } ?: return false
        val toScale = to.scale?.takeIf { it == "major" || it == "minor" } ?: return false
        val distance = (b - a + 12) % 12
        if (fromScale == toScale) return distance != 0 && distance != 5 && distance != 7
        // Relative minor is three semitones below the major tonic.
        return distance != if (fromScale == "major") 9 else 3
    }

    private fun entryPhrases(sections: List<TrackSection>, bars: List<Long>, count: Int, durationMs: Long) = sequence {
        val lastCue = minOf(75_000L, durationMs / 2)
        for ((i, intro) in sections.withIndex()) {
            if (intro.label !in backing || intro.startMs > lastCue) continue
            val vocal = sections.getOrNull(i + 1)?.takeIf {
                it.label in vocals && adjacent(intro, it) && it.endMs - it.startMs >= 6_000 &&
                    it.startMs <= minOf(120_000L, durationMs / 2)
            }
            val vocalBar = vocal?.let { nearest(bars, it.startMs, 900) }
            // Try both the start of the instrumental passage and a phrase leading directly into the vocal.
            val firstBar = bars.indexOfFirst { it >= intro.startMs - 90 }
            for (first in listOfNotNull(firstBar, vocalBar?.minus(count)).distinct()) {
                if (first < 0 || first + count >= bars.size) continue
                val cue = bars[first]
                val anchor = bars[first + count]
                if (cue < maxOf(0, intro.startMs - 900) || cue > lastCue ||
                    anchor > intro.endMs + 900 || anchor - cue !in minSpanMs(count)..48_000L) continue
                val vocalAtHandoff = vocal?.takeIf { first + count == vocalBar }
                yield(Phrase(first, vocalAtHandoff?.label ?: intro.label, vocalAtHandoff))
            }
        }
    }

    /** Try four-bar boundaries near the start, including phrases that overlap a vocal passage. */
    private fun earlyPhrases(sections: List<TrackSection>, bars: List<Long>, count: Int, limit: Long): List<Phrase> {
        val boundaries = sections.mapNotNull { section ->
            if (section.label !in backing && section.label !in vocals) return@mapNotNull null
            nearest(bars, section.startMs, 900)?.let { it to section }
        }
        return buildList {
            for (handoff in count until bars.size) {
                val end = bars[handoff]
                if (end > limit) break
                val first = handoff - count
                val cue = bars[first]
                if (end - cue !in minSpanMs(count)..48_000L) continue
                val (boundary, section) = boundaries.lastOrNull { (index, _) -> index <= handoff } ?: continue
                if ((handoff - boundary) % 4 != 0 || end > section.endMs + 900) continue
                val start = boundaries.lastOrNull { (index, _) -> index <= first }?.second ?: continue
                if (cue > start.endMs + 900) continue
                val vocal = section.takeIf { handoff == boundary && it.label in vocals }
                add(Phrase(first, vocal?.label ?: start.label, vocal))
            }
        }
    }

    /** Also allow a tail phrase to cross section boundaries if it lands on a later boundary. */
    private fun tailPhrases(sections: List<TrackSection>, bars: List<Long>, count: Int, earliestMs: Long,
                            audibleEndMs: Long, durationMs: Long): List<Phrase> {
        val boundaries = (sections.map { it.endMs } + audibleEndMs).distinct()
        return boundaries.mapNotNull { target ->
            val handoff = nearest(bars, target, 900) ?: return@mapNotNull null
            val first = handoff - count
            if (first < 0 || bars[first] < earliestMs || bars[handoff] !in (bars[first] + 1)..durationMs) return@mapNotNull null
            val start = sections.lastOrNull { bars[first] in (it.startMs - 900) until it.endMs } ?: return@mapNotNull null
            Phrase(first, start.label)
        }
    }

    private fun exitPhrases(sections: List<TrackSection>, bars: List<Long>, count: Int, earliestMs: Long, durationMs: Long) =
        buildList {
            for (exit in sections) {
                val instrumental = exit.label in backing
                if (!instrumental && count > 4) continue
                // A vocal exit finishes on its boundary. An instrumental exit can blend on any full phrase.
                val first = if (instrumental) bars.indexOfFirst { it >= exit.startMs - 90 }
                    else (nearest(bars, exit.endMs, 900) ?: continue) - count
                if (first < 0) continue
                val starts = if (instrumental) first..(bars.lastIndex - count) step 4 else first..first
                for (j in starts) {
                    val launch = bars[j]
                    val end = bars[j + count]
                    if (launch >= exit.endMs || end > minOf(exit.endMs + 900, durationMs)) break
                    if (launch >= maxOf(earliestMs, exit.startMs - 900) && end > launch) add(Phrase(j, exit.label))
                }
            }
        }

    /** The analyser often splits one continuous instrumental bed into several adjacent sections. */
    private fun passages(track: TrackStructure): List<TrackSection> {
        val result = mutableListOf<TrackSection>()
        for (part in track.sections.orEmpty().sortedBy { it.startMs }) {
            if (part.startMs < 0 || part.endMs !in (part.startMs + 1)..track.durationMs) continue
            val last = result.lastOrNull()
            if (last != null && adjacent(last, part) && last.label in backing && part.label in backing)
                result[result.lastIndex] = last.copy(endMs = part.endMs)
            else result.add(part)
        }
        return result
    }

    private fun adjacent(a: TrackSection, b: TrackSection) = abs(a.endMs - b.startMs) <= 1_000

    private fun minSpanMs(bars: Int): Long = if (bars == 2) 1_920L else 4_000L

    private fun completeTwoBars(beats: List<Long>, downbeats: List<Long>, first: Int): Boolean {
        val start = nearest(beats, downbeats[first], 45) ?: return false
        val middle = nearest(beats, downbeats[first + 1], 45) ?: return false
        val end = nearest(beats, downbeats[first + 2], 45) ?: return false
        return middle - start == 4 && end - middle == 4
    }

    private data class BassHandoff(val outgoingMs: Long?, val incomingMs: Long?)

    private fun bassHandoff(strong: List<Long>, incoming: List<Long>, entry: Phrase, outgoing: List<Long>, exit: Phrase,
                            count: Int, beatPeriodMs: Double, allowTerminal: Boolean): BassHandoff {
        val start = outgoing[exit.first]
        val end = outgoing[exit.first + count]
        // The one-beat bass ramp needs room on both sides and should avoid a nearly silent deck.
        val guardMs = maxOf(beatPeriodMs, (end - start) / 5.0)
        val hits = (0..count).filter { k -> nearest(strong, incoming[entry.first + k], 45) != null }
        val selected = hits.filter { k ->
            val at = outgoing[exit.first + k]
            at - start >= guardMs && end - at >= guardMs
        }.minByOrNull { abs((outgoing[exit.first + it] - start) - (end - start) / 2.0) }
            ?: count.takeIf { allowTerminal && it in hits }
        return BassHandoff(selected?.let { outgoing[exit.first + it] },
            (selected ?: hits.firstOrNull())?.let { incoming[entry.first + it] })
    }

    private fun nearest(times: List<Long>, time: Long, tolerance: Long): Int? {
        val hit = times.binarySearch(time)
        if (hit >= 0) return hit
        val after = -hit - 1
        return listOf(after - 1, after).filter { it in times.indices }.minByOrNull { abs(times[it] - time) }
            ?.takeIf { abs(times[it] - time) <= tolerance }
    }

    private fun beatAlignment(outgoing: List<Long>, incoming: List<Long>, launch: Long, end: Long, cue: Long, anchor: Long, rate: Double): Alignment? {
        val i = nearest(incoming, cue, 85) ?: return null
        val j = nearest(outgoing, launch, 85) ?: return null
        val count = (nearest(incoming, anchor, 85) ?: return null) - i
        if (count < 8 || (nearest(outgoing, end, 85) ?: return null) - j != count) return null
        var worst = 0.0
        for (k in 0..count) {
            val a = incoming[i + k]
            val b = outgoing[j + k]
            if (k > 0 && (a - incoming[i + k - 1] !in 240L..850L || b - outgoing[j + k - 1] !in 240L..850L)) return null
            val error = abs((a - cue) / rate - (b - launch))
            if (error > 65) return null
            worst = maxOf(worst, error)
        }
        return Alignment(count, worst, j, i)
    }

    private fun phaseAdvance(from: TrackStructure, to: TrackStructure, alignment: Alignment, end: Long, anchor: Long, rate: Double): Long {
        val outOffsets = from.beatOffsetsMs ?: return 0
        val inOffsets = to.beatOffsetsMs ?: return 0
        val outBeats = from.beatsMs.orEmpty()
        val inBeats = to.beatsMs.orEmpty()
        val differences = (0 until alignment.beats).mapNotNull { k ->
            val j = alignment.outgoingIndex + k
            val i = alignment.incomingIndex + k
            val out = outOffsets.getOrNull(j)?.takeIf { it in -65L..85L } ?: return@mapNotNull null
            val incoming = inOffsets.getOrNull(i)?.takeIf { it in -65L..85L } ?: return@mapNotNull null
            (inBeats[i] + incoming - anchor) - (outBeats[j] + out - end) * rate
        }.sorted()
        if (differences.size < maxOf(4, (alignment.beats + 1) / 2)) return 0
        val middle = differences.size / 2
        val median = (differences[middle] + differences[(differences.size - 1) / 2]) / 2
        val deviation = differences.map { abs(it - median) }.sorted()[middle]
        return if (deviation <= 15 && abs(median) <= 60 && anchor + median < to.durationMs) median.roundToLong() else 0L
    }
}
