package com.cappielloantonio.tempo.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.databinding.ItemDeezerArtistBinding
import com.cappielloantonio.tempo.viewmodel.DeezerHit

/**
 * The rail of Deezer artists, drawn like the library's artists rail above it.
 * A tap opens the artist's page; the badge on the portrait offers to download
 * what the library is missing of them, and the caption follows that download.
 */
class DeezerArtistAdapter(
        private val onOpen: (DeezerHit) -> Unit,
        private val onDownload: (DeezerHit) -> Unit
) :
        RecyclerView.Adapter<DeezerArtistAdapter.ViewHolder>() {

    private var artists: List<DeezerHit> = emptyList()
    private var states: Map<String, DeezerHit.State> = emptyMap()

    fun setArtists(artists: List<DeezerHit>) {
        this.artists = artists
        notifyDataSetChanged()
    }

    fun setStates(states: Map<String, DeezerHit.State>) {
        this.states = states
        notifyDataSetChanged()
    }

    override fun getItemCount() = artists.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ViewHolder(ItemDeezerArtistBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(artists[position], states[artists[position].key] ?: DeezerHit.State.IDLE)
    }

    inner class ViewHolder(private val bind: ItemDeezerArtistBinding) : RecyclerView.ViewHolder(bind.root) {
        init {
            bind.deezerArtistName.isSelected = true
        }

        fun bind(hit: DeezerHit, state: DeezerHit.State) {
            val context = bind.root.context

            bind.deezerArtistName.text = hit.title

            Glide.with(context)
                    .load(hit.image)
                    .placeholder(R.color.graphite)
                    .error(R.drawable.ic_placeholder_artist)
                    .into(bind.deezerArtistCover)

            val working = state == DeezerHit.State.WORKING
            val queued = state == DeezerHit.State.QUEUED

            bind.deezerArtistBadgeProgress.visibility = if (working) View.VISIBLE else View.GONE
            bind.deezerArtistBadgeIcon.visibility = if (working) View.INVISIBLE else View.VISIBLE
            bind.deezerArtistBadgeIcon.setImageResource(if (queued) R.drawable.ic_check_circle else R.drawable.ic_download)
            bind.deezerArtistBadgeIcon.imageTintList = ContextCompat.getColorStateList(context,
                    if (queued) R.color.spotify_green else R.color.pure_white)

            bind.deezerArtistRole.setText(when (state) {
                DeezerHit.State.WORKING -> R.string.deezer_artist_working
                DeezerHit.State.QUEUED -> R.string.deezer_artist_queued
                else -> R.string.label_role_artist
            })

            bind.root.setOnClickListener { onOpen(hit) }

            bind.deezerArtistBadge.contentDescription = context.getString(R.string.deezer_download_artist_title, hit.title)
            bind.deezerArtistBadge.isEnabled = !working && !queued
            bind.deezerArtistBadge.setOnClickListener { onDownload(hit) }
        }
    }
}
