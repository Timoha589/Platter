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
 * It is kept with the queue across a restart (see [memory]): the queue comes
 * back, and without this its wave tracks would no longer be known as the
 * wave's, so the wave would neither refill nor hear what was skipped.
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

    /** What a restart needs to know the wave's tracks again; null while there is no wave. */
    class Memory(var session: String? = null, var vibe: String? = null, var tracks: List<Track> = emptyList()) {
        class Track(var id: String? = null, var energy: Double? = null, var tempo: Double? = null)
    }

    @Synchronized
    fun memory(): Memory? = session?.let { s ->
        Memory(s, _vibe.value, tracks.map { (id, info) -> Memory.Track(id, info.energy, info.tempo) })
    }

    /** Takes back what [memory] kept, unless a wave has started since. An expired session is renewed by the server. */
    @Synchronized
    fun restore(memory: Memory) {
        if (session != null || memory.session == null) return
        session = memory.session
        _vibe.value = memory.vibe
        tracks.clear()
        memory.tracks.forEach { t -> t.id?.let { tracks[it] = Info(t.energy, t.tempo) } }
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
