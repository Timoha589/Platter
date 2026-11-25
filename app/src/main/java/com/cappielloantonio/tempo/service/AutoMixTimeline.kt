package com.cappielloantonio.tempo.service

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/** AutoMix overlaps, keyed to the queue entry whose time bar should show them. */
object AutoMixTimeline {
    enum class Mode { BEAT_MIX, FALLBACK_CROSSFADE }

    data class Segment(val index: Int, val mediaId: String, val startMs: Long, val endMs: Long, val mode: Mode)

    data class State(val outgoing: Segment?, val incoming: Segment?) {
        fun segmentsFor(index: Int, mediaId: String): List<Segment> =
            listOfNotNull(outgoing, incoming).filter { it.index == index && it.mediaId == mediaId }
    }

    private val state = MutableLiveData(State(null, null))

    @JvmStatic
    fun updates(): LiveData<State> = state

    internal fun set(outgoing: Segment?, incoming: Segment?) {
        val next = State(outgoing, incoming)
        if (state.value != next) state.value = next
    }
}
