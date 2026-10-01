package com.cappielloantonio.tempo.ui.adapter;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.ItemPlayerQueueSongBinding;
import com.cappielloantonio.tempo.glide.CustomGlideRequest;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.DragHandleTouch;
import com.cappielloantonio.tempo.util.MusicUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PlayerSongQueueAdapter extends RecyclerView.Adapter<PlayerSongQueueAdapter.ViewHolder> {
    /** Only which track is playing has changed: recolour the row, leave its cover alone. */
    private static final Object PAYLOAD_PLAYBACK_STATE = new Object();


    public interface OnStartDragListener {
        void onStartDrag(RecyclerView.ViewHolder holder);
    }

    private final ClickCallback click;

    private List<Child> songs;

    @Nullable
    private OnStartDragListener startDragListener;

    /**
     * The row that is playing, as a position in this list. Kept here rather
     * than asked of the player on every bind: the answer used to arrive after
     * the row had been drawn, and a row could show the wrong state until it did.
     */
    private int currentIndex = RecyclerView.NO_POSITION;

    public PlayerSongQueueAdapter(ClickCallback click) {
        this.click = click;
        this.songs = Collections.emptyList();
    }

    public void setOnStartDragListener(@Nullable OnStartDragListener startDragListener) {
        this.startDragListener = startDragListener;
    }

    /**
     * Rows before the playing one are dimmed, so a change of track restyles
     * every row between the old position and the new one.
     */
    public void setCurrentIndex(int index) {
        if (index == currentIndex) return;

        int previous = currentIndex;
        currentIndex = index;

        if (previous == RecyclerView.NO_POSITION || index == RecyclerView.NO_POSITION) {
            refreshPlaybackState();
            return;
        }

        int from = Math.min(previous, index);
        int to = Math.min(Math.max(previous, index), getItemCount() - 1);

        if (from <= to) notifyItemRangeChanged(from, to - from + 1, PAYLOAD_PLAYBACK_STATE);
    }

    public void refreshPlaybackState() {
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_PLAYBACK_STATE);
    }

    /**
     * Moves one row while it is being dragged. A move rather than a swap: a
     * quick drag can pass over several rows in one step, and swapping the two
     * ends of that jump reorders the rows in between differently from how the
     * player's own move will.
     * <p>
     * The playing track keeps its highlight wherever it is carried, and a
     * track carried across it keeps the playing one where it was.
     */
    public void moveItem(int from, int to) {
        songs.add(to, songs.remove(from));

        if (currentIndex == from) {
            currentIndex = to;
        } else if (from < currentIndex && to >= currentIndex) {
            currentIndex--;
        } else if (from > currentIndex && to <= currentIndex) {
            currentIndex++;
        }

        notifyItemMoved(from, to);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemPlayerQueueSongBinding view = ItemPlayerQueueSongBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.stream().allMatch(payload -> payload == PAYLOAD_PLAYBACK_STATE)) {
            applyPlaybackState(holder, position);
            return;
        }

        onBindViewHolder(holder, position);
    }

    /** Green for the track that is playing, dimmed for the ones already played. */
    private void applyPlaybackState(ViewHolder holder, int position) {
        boolean playing = position == currentIndex;
        float alpha = currentIndex != RecyclerView.NO_POSITION && position < currentIndex ? 0.2f : 1.0f;

        holder.item.queueSongTitleTextView.setTextColor(ContextCompat.getColor(holder.itemView.getContext(),
                playing ? R.color.spotify_green : R.color.titleTextColor));

        holder.item.queueSongTitleTextView.setAlpha(alpha);
        holder.item.queueSongSubtitleTextView.setAlpha(alpha);
        holder.item.ratingIndicatorImageView.setAlpha(alpha);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Child song = songs.get(position);

        holder.item.queueSongTitleTextView.setText(song.getTitle());
        holder.item.queueSongSubtitleTextView.setText(
                holder.itemView.getContext().getString(
                        R.string.song_subtitle_formatter,
                        song.artistLine(),
                        MusicUtil.getReadableDurationString(song.getDuration(), false),
                        MusicUtil.getReadableAudioQualityString(song)
                )
        );

        CustomGlideRequest.Builder
                .from(holder.itemView.getContext(), song.getCoverArtId(), CustomGlideRequest.ResourceType.Song)
                .build()
                .into(holder.item.queueSongCoverImageView);

        applyPlaybackState(holder, position);

        holder.item.ratingIndicatorImageView.setVisibility(View.GONE);
    }

    public List<Child> getItems() {
        return this.songs;
    }

    public void setItems(List<Child> songs) {
        this.songs = songs;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        if (songs == null) {
            return 0;
        }
        return songs.size();
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    public Child getItem(int id) {
        return songs.get(id);
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        ItemPlayerQueueSongBinding item;

        ViewHolder(ItemPlayerQueueSongBinding item) {
            super(item.getRoot());

            this.item = item;

            item.queueSongTitleTextView.setSelected(true);
            item.queueSongSubtitleTextView.setSelected(true);

            itemView.setOnClickListener(v -> onClick());
            itemView.setOnTouchListener(DragHandleTouch.grabFrom(item.queueSongHolderImage, () -> {
                if (startDragListener != null) startDragListener.onStartDrag(this);
            }));
        }

        public void onClick() {
            Bundle bundle = new Bundle();
            bundle.putParcelableArrayList(Constants.TRACKS_OBJECT, new ArrayList<>(songs));
            bundle.putInt(Constants.ITEM_POSITION, getBindingAdapterPosition());

            click.onMediaClick(bundle);
        }
    }
}
