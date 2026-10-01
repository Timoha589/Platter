package com.cappielloantonio.tempo.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.databinding.ItemDeezerResultBinding
import com.cappielloantonio.tempo.deemix.DeezerPreviewPlayer
import com.cappielloantonio.tempo.viewmodel.DeezerHit

/**
 * Deezer tracks and albums, in search and on the Deezer pages. A tap on a
 * track plays its preview, a tap on an album opens it. A download spends the
 * day's quota, so it happens only on the button that says so.
 */
class DeezerResultAdapter(
        private val onPreview: (DeezerHit) -> Unit,
        private val onDownload: (DeezerHit) -> Unit,
        private val onOpen: (DeezerHit) -> Unit
) : RecyclerView.Adapter<DeezerResultAdapter.ViewHolder>() {

    private var hits: List<DeezerHit> = emptyList()
    private var states: Map<String, DeezerHit.State> = emptyMap()
    private var previewKey: String? = null
    private var previewState = DeezerPreviewPlayer.State.STOPPED

    fun setHits(hits: List<DeezerHit>) {
        this.hits = hits
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

    override fun getItemCount() = hits.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ViewHolder(ItemDeezerResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(hits[position], states[hits[position].key] ?: DeezerHit.State.IDLE)
    }

    inner class ViewHolder(private val bind: ItemDeezerResultBinding) : RecyclerView.ViewHolder(bind.root) {
        fun bind(hit: DeezerHit, state: DeezerHit.State) {
            val context = bind.root.context
            val isTrack = hit.kind == DeezerHit.Kind.TRACK

            bind.deezerTitle.text = hit.title
            bind.deezerSubtitle.text = when {
                hit.caption != null -> hit.caption
                !isTrack -> context.getString(R.string.deezer_kind_album, hit.artist)
                hit.detail.isEmpty() -> hit.artist
                else -> context.getString(R.string.deezer_kind_track, hit.artist, hit.detail)
            }

            Glide.with(context)
                    .load(hit.image)
                    .placeholder(R.color.graphite)
                    .error(R.drawable.ic_placeholder_album)
                    .into(bind.deezerCover)

            // The previewed track: green title, the cover dimmed under a spinner, then a pause mark.
            val previewing = isTrack && hit.key == previewKey
            val loading = previewing && previewState == DeezerPreviewPlayer.State.LOADING
            bind.deezerPreviewOverlay.visibility = if (previewing) View.VISIBLE else View.GONE
            bind.deezerPreviewProgress.visibility = if (loading) View.VISIBLE else View.GONE
            bind.deezerPreviewIcon.visibility = if (previewing && !loading) View.VISIBLE else View.GONE
            bind.deezerTitle.setTextColor(ContextCompat.getColor(context,
                    if (previewing) R.color.spotify_green else R.color.titleTextColor))

            bind.root.setOnClickListener { if (isTrack) onPreview(hit) else onOpen(hit) }

            val done = hit.inLibrary || state == DeezerHit.State.QUEUED
            val working = state == DeezerHit.State.WORKING

            bind.deezerActionProgress.visibility = if (working) View.VISIBLE else View.GONE
            bind.deezerActionIcon.visibility = if (working) View.INVISIBLE else View.VISIBLE
            bind.deezerActionIcon.setImageResource(if (done) R.drawable.ic_check_circle else R.drawable.ic_download)
            bind.deezerActionIcon.imageTintList = ContextCompat.getColorStateList(context, when {
                state == DeezerHit.State.QUEUED -> R.color.spotify_green
                hit.inLibrary -> R.color.mist
                else -> R.color.pure_white
            })

            bind.deezerAction.contentDescription = context.getString(
                    if (hit.inLibrary) R.string.deezer_in_library else R.string.deezer_download)
            bind.deezerAction.isEnabled = !done && !working
            bind.deezerAction.setOnClickListener { onDownload(hit) }
        }
    }
}
