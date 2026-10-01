package com.cappielloantonio.tempo.ui.adapter;

import android.view.ViewGroup;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.cappielloantonio.tempo.helper.view.SkeletonView;

/**
 * Placeholders for a list whose first page is still on its way, put in the
 * list itself - in a ConcatAdapter ahead of the real adapter - rather than laid
 * over it. They then sit exactly where the rows or tiles will: under the page
 * actions, in the grid's own columns and gaps, scrolling the header away like
 * the rest. When the answer comes, {@link #hide} takes them out and the real
 * items take their places in the same frame, so nothing below moves.
 */
public class SkeletonAdapter extends RecyclerView.Adapter<SkeletonAdapter.ViewHolder> {
    @LayoutRes
    private final int item;
    private final int count;
    private boolean showing = true;

    public SkeletonAdapter(@LayoutRes int item, int count) {
        this.item = item;
        this.count = count;
    }

    /* Once the list has an answer - or a failure: a skeleton left up would say it is still loading. */
    public void hide() {
        if (!showing) return;

        showing = false;
        // A whole-list change, which RecyclerView does not animate: the
        // placeholders give way to the real rows in one frame, not a crossfade.
        notifyDataSetChanged();
    }

    /* A list read again from scratch, as a new sort order does. */
    public void show() {
        if (showing) return;

        showing = true;
        notifyDataSetChanged();
    }

    public boolean isShowing() {
        return showing;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(new SkeletonView(parent.getContext(), item));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
    }

    @Override
    public int getItemCount() {
        return showing ? count : 0;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ViewHolder(SkeletonView view) {
            super(view);
        }
    }
}
