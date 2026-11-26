package com.cappielloantonio.tempo.service

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlin.math.roundToLong

/** AutoMix overlaps, keyed to the queue entry whose time bar should show them. */
object AutoMixTimeline {
    enum class Mode { BEAT_MIX, FALLBACK_CROSSFADE }
    enum class Direction { INCOMING, OUTGOING }

    data class Segment(
        val index: Int,
        val mediaId: String,
        val startMs: Long,
        val endMs: Long,
        val mode: Mode,
        val direction: Direction,
    ) {
        fun heardThrough(positionMs: Long): Segment? {
            val end = minOf(positionMs, endMs)
            return if (end > startMs) copy(endMs = end) else null
        }

        companion object {
            fun incoming(
                index: Int,
                mediaId: String,
                startMs: Long,
                outgoingOverlapMs: Long,
                tempoRatio: Double,
                durationMs: Long?,
                mode: Mode,
            ): Segment? {
                val estimatedEnd = startMs + (outgoingOverlapMs * tempoRatio).roundToLong()
                val end = minOf(durationMs ?: estimatedEnd, estimatedEnd)
                return if (end > startMs) Segment(index, mediaId, startMs, end, mode, Direction.INCOMING) else null
            }
        }
    }

    data class State(val outgoing: Segment? = null, val incoming: Segment? = null, val incomingPreview: Segment? = null) {
        fun segmentsFor(index: Int, mediaId: String): List<Segment> {
            fun Segment?.matches() = this?.let { it.index == index && it.mediaId == mediaId } == true
            val entry = incomingPreview.takeIf { it.matches() } ?: incoming.takeIf { it.matches() }
            return listOfNotNull(entry, outgoing.takeIf { it.matches() })
        }
    }

    private val state = MutableLiveData(State())

    @JvmStatic
    fun updates(): LiveData<State> = state

    internal fun set(next: State) {
        if (state.value != next) state.value = next
    }
}
