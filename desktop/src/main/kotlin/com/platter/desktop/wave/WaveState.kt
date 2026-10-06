package com.platter.desktop.wave

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the app knows about the wave that is playing.
 *
 * There is no "wave mode" switch to keep in step with the rest of the app. A
 * track belongs to the wave if the wave served it, and the wave is playing
 * while the player is on one of its tracks: start an album and the wave has
 * stopped, with nothing to reset; go back to a wave track in the queue and it
 * picks up again. The same test tells the controller when to fetch more and
 * the home screen what to draw.
 *
 * Unlike the phone's, this lives in memory only: the desktop player does not
 * restore its queue after a restart, so there is no wave to resume either.
 */
class WaveState {
    data class Info(val energy: Double?, val tempo: Double?)

    private var session: String? = null
    private val tracks = LinkedHashMap<String, Info>()
    private val pending = mutableListOf<WaveEvent>()

    private val _vibe = MutableStateFlow<String?>(null)
    val vibe: StateFlow<String?> = _vibe.asStateFlow()

    @Synchronized
    fun begin(batch: WaveBatch) {
        session = null
        tracks.clear()
        pending.clear()
        _vibe.value = null
        adopt(batch)
    }

    @Synchronized
    fun adopt(batch: WaveBatch) {
        // The server may have started a new session for an expired one.
        session = batch.session ?: session
        batch.vibe?.label?.let { _vibe.value = it }
        batch.tracks.orEmpty().forEach { track ->
            val id = track.id ?: track.song?.id ?: return@forEach
            tracks.remove(id)
            tracks[id] = Info(track.energy, track.tempo)
        }
        while (tracks.size > MAX_TRACKS) tracks.remove(tracks.keys.first())
    }

    @Synchronized
    fun isWaveTrack(id: String?): Boolean = id != null && session != null && tracks.containsKey(id)

    @Synchronized
    fun info(id: String?): Info? = id?.let { tracks[it] }

    @Synchronized
    fun session(): String? = session

    @Synchronized
    fun addEvent(event: WaveEvent) {
        pending.add(event)
        while (pending.size > MAX_PENDING) pending.removeAt(0)
    }

    /** Takes the feedback gathered so far, to send with the next request. */
    @Synchronized
    fun drainEvents(): List<WaveEvent> = pending.toList().also { pending.clear() }

    /** Puts feedback back when the request carrying it failed. */
    @Synchronized
    fun restoreEvents(events: List<WaveEvent>) {
        if (events.isEmpty()) return
        val merged = (events + pending).takeLast(MAX_PENDING)
        pending.clear()
        pending.addAll(merged)
    }

    /** A heart tapped on a wave track. */
    fun onFavoriteToggled(id: String?, favorite: Boolean) {
        if (!isWaveTrack(id)) return
        addEvent(WaveEvent(id!!, if (favorite) WaveEvent.LIKE else WaveEvent.UNLIKE))
    }

    /** The thumbs-down on a wave track. */
    fun onDisliked(id: String?) {
        if (!isWaveTrack(id)) return
        addEvent(WaveEvent(id!!, WaveEvent.DISLIKE))
    }

    private companion object {
        /* A few hours of listening, far past the queue's reach. */
        const val MAX_TRACKS = 400
        const val MAX_PENDING = 100
    }
}
