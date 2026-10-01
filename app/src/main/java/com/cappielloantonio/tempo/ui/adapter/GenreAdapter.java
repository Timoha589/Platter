package com.cappielloantonio.tempo.ui.adapter;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.ItemLibraryGenreBinding;
import com.cappielloantonio.tempo.databinding.ViewGenreTileBinding;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.subsonic.models.Genre;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.GenreNames;

import java.util.Collections;
import java.util.List;

public class GenreAdapter extends RecyclerView.Adapter<GenreAdapter.ViewHolder> {
    private final ClickCallback click;

    private List<Genre> genres;

    public GenreAdapter(ClickCallback click) {
        this.click = click;
        this.genres = Collections.emptyList();
    }

    /** Fills a genre tile (view_genre_tile) - the one the library's rail and the catalogue share. */
    static void bindTile(ViewGenreTileBinding tile, Genre genre) {
        Context context = tile.getRoot().getContext();

        tile.genreLabel.setText(GenreNames.label(context, genre.getGenre()));

        // A server that does not count a genre's tracks reports none; that is
        // a missing number, not an empty genre, so the line is left out.
        int songs = genre.getSongCount();
        tile.genreMeta.setVisibility(songs > 0 ? View.VISIBLE : View.GONE);
        if (songs > 0) {
            tile.genreMeta.setText(context.getResources().getQuantityString(R.plurals.album_page_tracks_count, songs, songs));
        }
    }

    static Bundle genreBundle(Genre genre) {
        Bundle bundle = new Bundle();
        bundle.putString(Constants.MEDIA_BY_GENRE, Constants.MEDIA_BY_GENRE);
        bundle.putParcelable(Constants.GENRE_OBJECT, genre);
        return bundle;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemLibraryGenreBinding view = ItemLibraryGenreBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        bindTile(holder.item.tile, genres.get(position));
    }

    @Override
    public int getItemCount() {
        return genres.size();
    }

    public Genre getItem(int position) {
        return genres.get(position);
    }

    public void setItems(List<Genre> genres) {
        this.genres = genres;
        notifyDataSetChanged();
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        ItemLibraryGenreBinding item;

        ViewHolder(ItemLibraryGenreBinding item) {
            super(item.getRoot());

            this.item = item;

            item.tile.getRoot().setOnClickListener(v -> click.onGenreClick(genreBundle(genres.get(getBindingAdapterPosition()))));
        }
    }
}
