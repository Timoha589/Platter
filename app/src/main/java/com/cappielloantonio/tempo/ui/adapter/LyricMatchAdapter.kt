package com.cappielloantonio.tempo.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.databinding.ItemLyricMatchBinding
import com.cappielloantonio.tempo.deemix.DeezerPreviewPlayer
import com.cappielloantonio.tempo.glide.CustomGlideRequest
import com.cappielloantonio.tempo.lyricsearch.LyricMatch
import com.cappielloantonio.tempo.viewmodel.DeezerHit

/**
 * Songs a line was found in. Each row quotes the line back under the song, so
 * the reason it is here is on screen.
 *
 * A library track plays on a tap, like any song row. One the library lacks
 * comes from Deezer and works like the Deezer section: a tap plays its
 * 30-second preview, the button at the end downloads it.
 */
@UnstableApi
class LyricMatchAdapter(
        private val onPlay: (LyricMatch) -> Unit,
        private val onMenu: (LyricMatch) -> Unit,
        private val onPreview: (DeezerHit) -> Unit,
        private val onDownload: (DeezerHit) -> Unit
) : RecyclerView.Adapter<LyricMatchAdapter.ViewHolder>() {

    private var matches: List<LyricMatch> = emptyList()
    private var states: Map<String, DeezerHit.State> = emptyMap()
    private var previewKey: String? = null
    private var previewState = DeezerPreviewPlayer.State.STOPPED

    fun setMatches(matches: List<LyricMatch>) {
        this.matches = matches
        notifyDataSetChanged()
    }

    fun setStates(states: Map<String, DeezerHit.State>) {
        this.states = states
        notifyDataSetChanged()
    }

    fun setPreview(key: String?, state: DeezerPreviewPlayer.State) {
        previewKey = key
        previewState = state
        notifyDataSetChanged()
    }

    /** The rows as shown, for building a queue out of them. */
    fun getMatches(): List<LyricMatch> = matches

    override fun getItemCount() = matches.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ViewHolder(ItemLyricMatchBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(matches[position])
    }

    inner class ViewHolder(private val bind: ItemLyricMatchBinding) : RecyclerView.ViewHolder(bind.root) {
        fun bind(match: LyricMatch) {
            val context = bind.root.context
            val song = match.song
            val deezer = match.deezer

            bind.lyricTitle.text = match.title
            bind.lyricSubtitle.text = if (song != null) match.artist
                    else context.getString(R.string.search_lyrics_not_in_library, match.artist)
            bind.lyricSnippet.text = context.getString(R.string.search_lyrics_quote, match.snippet)
            bind.lyricSnippet.visibility = if (match.snippet.isEmpty()) View.GONE else View.VISIBLE

            if (song != null) {
                CustomGlideRequest.Builder
                        .from(context, song.coverArtId, CustomGlideRequest.ResourceType.Song)
                        .build()
                        .into(bind.lyricCover)
            } else {
                Glide.with(context)
                        .load(deezer?.image ?: match.image)
                        .placeholder(R.color.graphite)
                        .error(R.drawable.ic_placeholder_album)
                        .into(bind.lyricCover)
            }

            bindPreview(deezer)
            bindDownload(deezer)

            bind.root.setOnClickListener {
                if (song != null) onPlay(match) else if (deezer != null) onPreview(deezer)
            }
            bind.root.setOnLongClickListener {
                if (song == null) return@setOnLongClickListener false
                onMenu(match)
                true
            }
        }

        // The previewed track: green title, the cover dimmed under a spinner, then a pause mark.
        private fun bindPreview(deezer: DeezerHit?) {
            val context = bind.root.context
            val previewing = deezer != null && deezer.key == previewKey
            val loading = previewing && previewState == DeezerPreviewPlayer.State.LOADING

            bind.lyricPreviewOverlay.visibility = if (previewing) View.VISIBLE else View.GONE
            bind.lyricPreviewProgress.visibility = if (loading) View.VISIBLE else View.GONE
            bind.lyricPreviewIcon.visibility = if (previewing && !loading) View.VISIBLE else View.GONE
            bind.lyricTitle.setTextColor(ContextCompat.getColor(context,
                    if (previewing) R.color.spotify_green else R.color.titleTextColor))
        }

        private fun bindDownload(deezer: DeezerHit?) {
            val context = bind.root.context
            bind.lyricAction.visibility = if (deezer != null) View.VISIBLE else View.GONE
            if (deezer == null) return

            val state = states[deezer.key] ?: DeezerHit.State.IDLE
            val queued = state == DeezerHit.State.QUEUED
            val working = state == DeezerHit.State.WORKING

            bind.lyricActionProgress.visibility = if (working) View.VISIBLE else View.GONE
            bind.lyricActionIcon.visibility = if (working) View.INVISIBLE else View.VISIBLE
            bind.lyricActionIcon.setImageResource(if (queued) R.drawable.ic_check_circle else R.drawable.ic_download)
            bind.lyricActionIcon.imageTintList = ContextCompat.getColorStateList(context,
                    if (queued) R.color.spotify_green else R.color.pure_white)

            bind.lyricAction.isEnabled = !queued && !working
            bind.lyricAction.setOnClickListener { onDownload(deezer) }
        }
    }
}
