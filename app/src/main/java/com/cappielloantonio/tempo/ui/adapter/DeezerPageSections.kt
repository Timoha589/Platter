package com.cappielloantonio.tempo.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.databinding.ItemDeezerPageActionsBinding
import com.cappielloantonio.tempo.databinding.ItemDeezerSectionHeaderBinding
import com.cappielloantonio.tempo.viewmodel.DeezerHit

/*
 * The one-row pieces of the Deezer page's list, put together with the track
 * and album rows in a ConcatAdapter. Each holds at most one row, and none
 * while it has nothing to show.
 */

/** A section heading, with an optional action at its end ("Show all"). */
class DeezerSectionHeaderAdapter(@StringRes private val title: Int) :
        RecyclerView.Adapter<DeezerSectionHeaderAdapter.ViewHolder>() {

    private var shown = false
    @StringRes
    private var action: Int = 0
    private var onAction: (() -> Unit)? = null

    fun show(shown: Boolean) {
        if (this.shown == shown) return
        this.shown = shown
        if (shown) notifyItemInserted(0) else notifyItemRemoved(0)
    }

    fun setAction(@StringRes action: Int, onAction: (() -> Unit)?) {
        this.action = action
        this.onAction = onAction
        if (shown) notifyItemChanged(0)
    }

    override fun getItemCount() = if (shown) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ViewHolder(ItemDeezerSectionHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind.deezerSectionTitle.setText(title)

        val callback = onAction
        holder.bind.deezerSectionAction.visibility = if (action != 0 && callback != null) View.VISIBLE else View.GONE
        if (action != 0) holder.bind.deezerSectionAction.setText(action)
        holder.bind.deezerSectionAction.setOnClickListener { callback?.invoke() }
    }

    class ViewHolder(val bind: ItemDeezerSectionHeaderBinding) : RecyclerView.ViewHolder(bind.root)
}

/** The download button at the top of the page, following that download's state. */
class DeezerPageActionsAdapter(
        @StringRes private val label: Int,
        private val onDownload: () -> Unit
) : RecyclerView.Adapter<DeezerPageActionsAdapter.ViewHolder>() {

    private var shown = false
    private var state = DeezerHit.State.IDLE
    private var quota = ""

    fun show(shown: Boolean) {
        if (this.shown == shown) return
        this.shown = shown
        if (shown) notifyItemInserted(0) else notifyItemRemoved(0)
    }

    fun setState(state: DeezerHit.State) {
        this.state = state
        if (shown) notifyItemChanged(0)
    }

    fun setQuota(quota: String) {
        this.quota = quota
        if (shown) notifyItemChanged(0)
    }

    override fun getItemCount() = if (shown) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ViewHolder(ItemDeezerPageActionsBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val button = holder.bind.deezerPageDownloadButton

        button.setText(when (state) {
            DeezerHit.State.WORKING -> R.string.deezer_artist_working
            DeezerHit.State.QUEUED -> R.string.deezer_artist_queued
            else -> label
        })
        button.isEnabled = state == DeezerHit.State.IDLE
        button.setOnClickListener { onDownload() }

        holder.bind.deezerPageQuota.text = quota
        holder.bind.deezerPageQuota.visibility = if (quota.isEmpty()) View.GONE else View.VISIBLE
    }

    class ViewHolder(val bind: ItemDeezerPageActionsBinding) : RecyclerView.ViewHolder(bind.root)
}
