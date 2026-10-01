package com.cappielloantonio.tempo.wave

import android.os.Looper
import androidx.annotation.Keep
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.subsonic.models.Child
import com.google.gson.Gson

/**
 * What the app knows about the wave that is playing.
 *
 * There is no "wave mode" switch to keep in step with the rest of the app. A
 * track belongs to the wave if the wave served it, and the wave is playing
 * while the player is on one of its tracks: start an album and the wave has
 * stopped, with nothing to reset; go back to a wave track in the queue and it
 * picks up again. The same test tells the player service when to fetch more
 * and the home screen what to draw.
 *
 * Kept in the app's preferences, so a queue restored after the process was
 * killed still knows its wave, and feedback not yet sent is not lost.
 */
object WaveState {
    private const val KEY = "wave_state"

    /* The tracks remembered - a few hours of listening, far past the queue's reach. */
    private const val MAX_TRACKS = 400
    private const val MAX_PENDING = 100

    /*
     * Both are read back by Gson from the preferences. Left to R8, a release
     * build strips the generic types off Stored's fields, Gson then fills
     * tracks with bare maps where Infos belong, and the home screen threw
     * ClassCastException on every launch after a wave had played - until the
     * app's data was wiped.
     */
    @Keep
    data class Info(val energy: Double?, val tempo: Double?)

    @Keep
    private class Stored {
        var session: String? = null
        var vibe: String? = null
        var tracks: LinkedHashMap<String, Info>? = LinkedHashMap()
        var pending: MutableList<WaveEvent>? = mutableListOf()
    }

    private val gson = Gson()
    private var stored: Stored = load()

    private val changes = MutableLiveData<Unit>()

    /** Fires when the wave's tracks or vibe change, for views to redraw. */
    @JvmStatic
    fun changes(): LiveData<Unit> = changes

    @JvmStatic
    @Synchronized
    fun begin(batch: WaveBatch) {
        stored = Stored()
        adoptLocked(batch)
    }

    @JvmStatic
    @Synchronized
    fun adopt(batch: WaveBatch) = adoptLocked(batch)

    private fun adoptLocked(batch: WaveBatch) {
        // The server may have started a new session for an expired one.
        stored.session = batch.session ?: stored.session
        stored.vibe = batch.vibe?.label ?: stored.vibe
        val tracks = stored.tracks ?: LinkedHashMap<String, Info>().also { stored.tracks = it }
        batch.tracks.orEmpty().forEach { track ->
            val id = track.id ?: track.song?.id ?: return@forEach
            tracks.remove(id)
            tracks[id] = Info(track.energy, track.tempo)
        }
        while (tracks.size > MAX_TRACKS) tracks.remove(tracks.keys.first())
        save()
    }

    @JvmStatic
    @Synchronized
    fun isWaveTrack(id: String?): Boolean =
            id != null && stored.session != null && stored.tracks?.containsKey(id) == true

    @JvmStatic
    @Synchronized
    fun info(id: String?): Info? = id?.let { stored.tracks?.get(it) }

    @JvmStatic
    @Synchronized
    fun session(): String? = stored.session

    @JvmStatic
    @Synchronized
    fun vibe(): String? = stored.vibe

    @JvmStatic
    @Synchronized
    fun addEvent(event: WaveEvent) {
        val pending = stored.pending ?: mutableListOf<WaveEvent>().also { stored.pending = it }
        pending.add(event)
        while (pending.size > MAX_PENDING) pending.removeAt(0)
        save(announce = false)
    }

    /** Takes the feedback gathered so far, to send with the next request. */
    @JvmStatic
    @Synchronized
    fun drainEvents(): List<WaveEvent> {
        val out = stored.pending.orEmpty().toList()
        stored.pending = mutableListOf()
        save(announce = false)
        return out
    }

    /** Puts feedback back when the request carrying it failed. */
    @JvmStatic
    @Synchronized
    fun restoreEvents(events: List<WaveEvent>) {
        if (events.isEmpty()) return
        stored.pending = (events + stored.pending.orEmpty()).takeLast(MAX_PENDING).toMutableList()
        save(announce = false)
    }

    /* A heart tapped on a wave track - in the player or the notification. */
    @JvmStatic
    fun onFavoriteToggled(id: String?, favorite: Boolean) {
        if (!isWaveTrack(id)) return
        addEvent(WaveEvent(id!!, if (favorite) WaveEvent.LIKE else WaveEvent.UNLIKE))
    }

    /* The thumbs-down in the player, on a wave track. */
    @JvmStatic
    fun onDisliked(id: String?) {
        if (!isWaveTrack(id)) return
        addEvent(WaveEvent(id!!, WaveEvent.DISLIKE))
    }

    @JvmStatic
    fun songs(batch: WaveBatch): List<Child> = batch.tracks.orEmpty().mapNotNull { it.song }

    private fun load(): Stored = try {
        App.getInstance().preferences.getString(KEY, null)
                ?.let { gson.fromJson(it, Stored::class.java) }
                ?.takeIf { it.isWhole() } ?: Stored()
    } catch (e: Exception) {
        Stored()
    }

    /*
     * Erasure lets Gson put anything in the collections without a word; this
     * is where a state written by an older build is caught, and dropped - the
     * wave only forgets which tracks were its own - rather than handed on to
     * fail wherever it is next read.
     */
    private fun Stored.isWhole(): Boolean =
            tracks.orEmpty().values.all { (it as Any?) is Info } && pending.orEmpty().all { (it as Any?) is WaveEvent }

    private fun save(announce: Boolean = true) {
        App.getInstance().preferences.edit().putString(KEY, gson.toJson(stored)).apply()
        if (!announce) return
        if (Looper.myLooper() == Looper.getMainLooper()) changes.value = Unit else changes.postValue(Unit)
    }
}
