package com.cappielloantonio.tempo.ui.adapter;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.cappielloantonio.tempo.databinding.ItemHorizontalHomeSectorBinding;
import com.cappielloantonio.tempo.databinding.ItemHorizontalPlaylistDialogTrackBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.model.HomeSector;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.DragHandleTouch;
import com.cappielloantonio.tempo.util.MusicUtil;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

public class HomeSectorHorizontalAdapter extends RecyclerView.Adapter<HomeSectorHorizontalAdapter.ViewHolder> {
    private List<HomeSector> sectors;

    @Nullable
    private Consumer<RecyclerView.ViewHolder> startDrag;

    public void setOnStartDrag(@Nullable Consumer<RecyclerView.ViewHolder> startDrag) {
        this.startDrag = startDrag;
    }

    /**
     * A move rather than a swap: a quick drag can pass over several rows in
     * one step, and swapping the two ends of that jump reshuffles the rows in
     * between.
     */
    public void moveItem(int from, int to) {
        sectors.add(to, sectors.remove(from));
        notifyItemMoved(from, to);
    }

    public HomeSectorHorizontalAdapter() {
        this.sectors = Collections.emptyList();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemHorizontalHomeSectorBinding view = ItemHorizontalHomeSectorBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        HomeSector sector = sectors.get(position);

        holder.item.homeSectorTitleCheckBox.setText(sector.getSectorTitle());

        /*
         * The box is set with nobody listening, then handed a listener that
         * writes to this sector itself. The old listener looked its sector up
         * by the row's position, and setChecked calls it: while a drag was
         * moving rows and rebinding them, a position could already name the
         * neighbour, and one section's tick was written into another.
         */
        holder.item.homeSectorTitleCheckBox.setOnCheckedChangeListener(null);
        holder.item.homeSectorTitleCheckBox.setChecked(sector.isVisible());
        holder.item.homeSectorTitleCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> sector.setVisible(isChecked));
    }

    @Override
    public int getItemCount() {
        return sectors.size();
    }

    public List<HomeSector> getItems() {
        return this.sectors;
    }

    public void setItems(List<HomeSector> sectors) {
        this.sectors = sectors;
        notifyDataSetChanged();
    }

    public HomeSector getItem(int id) {
        return sectors.get(id);
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        ItemHorizontalHomeSectorBinding item;

        ViewHolder(ItemHorizontalHomeSectorBinding item) {
            super(item.getRoot());

            this.item = item;

            itemView.setOnTouchListener(DragHandleTouch.grabFrom(item.homeSectorRearrangerImageView, () -> {
                if (startDrag != null) startDrag.accept(this);
            }));
        }
    }
}
