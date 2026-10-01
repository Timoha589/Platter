package com.cappielloantonio.tempo.service

import android.content.Context
import androidx.annotation.MainThread
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.repository.SongRepository
import com.cappielloantonio.tempo.util.NetworkUtil
import com.cappielloantonio.tempo.util.Preferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Plays the server has not heard about yet.
 *
 * A play made with no connection - on a flight, from downloads - used to be
 * sent anyway, fail, and be forgotten. Every play is written down here first,
 * with the time it started, and crossed off once the server has it; whatever
 * could not be sent goes the next time a track starts or the player comes up.
 * A play belongs to the server it was made against, and waits for that one if
 * the app has since been switched to another.
 */
object PendingScrobbles {
    private const val FILE = "pending_scrobbles"
    private const val KEY = "plays"

    /* Weeks of listening. Past it the oldest plays give way. */
    private const val LIMIT = 1000

    private data class Play(val server: String?, val id: String, val time: Long)

    private val songRepository = SongRepository()
    private var sending = false

    @MainThread
    fun submit(id: String, time: Long) {
        save((load() + Play(Preferences.getServerId(), id, time)).takeLast(LIMIT))
        flush()
    }

    /*
     * One at a time and oldest first, so the server files them in the order
     * they were heard, and stops at the first that does not get through: the
     * rest would not either.
     */
    @MainThread
    fun flush() {
        if (sending || NetworkUtil.isOffline() || !Preferences.isScrobblingEnabled()) return

        val server = Preferences.getServerId()
        val play = load().firstOrNull { it.server == server } ?: return

        sending = true
        songRepository.scrobble(play.id, true, play.time) { answered ->
            sending = false
            if (answered) {
                save(load() - play)
                flush()
            }
        }
    }

    private fun load(): List<Play> {
        val json = preferences().getString(KEY, null) ?: return emptyList()

        return try {
            val array = JSONArray(json)
            List(array.length()) { i ->
                val play = array.getJSONObject(i)
                Play(
                        if (play.isNull("server")) null else play.getString("server"),
                        play.getString("id"),
                        play.getLong("time")
                )
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun save(plays: List<Play>) {
        val array = JSONArray()
        plays.forEach {
            array.put(JSONObject()
                    .put("server", it.server ?: JSONObject.NULL)
                    .put("id", it.id)
                    .put("time", it.time))
        }
        preferences().edit().putString(KEY, array.toString()).apply()
    }

    private fun preferences() = App.getContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
