package com.cappielloantonio.tempo.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.cappielloantonio.tempo.databinding.ViewPageActionsBinding;

/**
 * Play and Shuffle as the first row of a page's track list, put in front of
 * the tracks with a ConcatAdapter - where the album page, whose tracks sit in
 * a scroll view, simply includes the same row above them.
 */
public class PageActionsAdapter extends RecyclerView.Adapter<PageActionsAdapter.ViewHolder> {
    private final View.OnClickListener onPlay;
    private final View.OnClickListener onShuffle;
    private boolean enabled = true;

    public PageActionsAdapter(View.OnClickListener onPlay, View.OnClickListener onShuffle) {
        this.onPlay = onPlay;
        this.onShuffle = onShuffle;
    }

    /** Off while there is nothing to play. */
    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;

        this.enabled = enabled;
        notifyItemChanged(0);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(ViewPageActionsBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind.pagePlayButton.setOnClickListener(onPlay);
        holder.bind.pageShuffleButton.setOnClickListener(onShuffle);
        holder.bind.pagePlayButton.setEnabled(enabled);
        holder.bind.pageShuffleButton.setEnabled(enabled);
    }

    @Override
    public int getItemCount() {
        return 1;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ViewPageActionsBinding bind;

        ViewHolder(ViewPageActionsBinding bind) {
            super(bind.getRoot());
            this.bind = bind;
        }
    }
}
