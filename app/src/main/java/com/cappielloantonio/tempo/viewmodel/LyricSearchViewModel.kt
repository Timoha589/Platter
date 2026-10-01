package com.cappielloantonio.tempo.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.helper.search.QueryVariants
import com.cappielloantonio.tempo.lyricsearch.LyricMatch
import com.cappielloantonio.tempo.lyricsearch.LyricSearch
import com.cappielloantonio.tempo.lyricsearch.LyricSearchRequest
import com.cappielloantonio.tempo.util.NetworkUtil

/**
 * The part of search that reads the query as a line of a song.
 *
 * Runs beside the library search rather than instead of it: nothing says
 * whether "love in a bottle" is a title or a lyric, and it is often both.
 * Activity-scoped for the same reason the library search is - backing out of
 * a page lands on the same rows.
 */
@UnstableApi
class LyricSearchViewModel(application: Application) : AndroidViewModel(application) {
    private val matches = MutableLiveData<List<LyricMatch>>(emptyList())
    private val pending = MutableLiveData(false)

    private var request: LyricSearchRequest? = null
    private var query = ""

    fun getMatches(): LiveData<List<LyricMatch>> = matches

    /**
     * Whether an answer is still coming - a second or two, Genius and the
     * library asked in turn. While it is, the screen shows the section as
     * loading rather than the rows of the last query, and does not say
     * "nothing found" on the library's word alone.
     */
    fun getPending(): LiveData<Boolean> = pending

    val isPending: Boolean get() = pending.value == true

    /** Starts looking [query] up as a line, replacing whatever search was running. */
    fun search(query: String) {
        if (!isLine(query) || NetworkUtil.isOffline()) {
            clear()
            return
        }

        if (query == this.query && !matches.value.isNullOrEmpty()) return
        this.query = query

        request?.cancel()
        pending.value = true
        request = LyricSearch.search(query) { found ->
            matches.value = found
            pending.value = false
        }
    }

    fun clear() {
        query = ""
        request?.cancel()
        request = null
        if (isPending) pending.value = false
        if (!matches.value.isNullOrEmpty()) matches.value = emptyList()
    }

    override fun onCleared() {
        request?.cancel()
    }

    private companion object {
        /*
         * A line is a phrase. One or two words are a name far more often than
         * a lyric, and a lyrics index answers them with whatever song repeats
         * that word most - so they are left to the library.
         */
        const val SHORTEST_LINE_WORDS = 3

        fun isLine(query: String) = QueryVariants.wordsOf(query).size >= SHORTEST_LINE_WORDS
    }
}
