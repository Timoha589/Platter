package com.cappielloantonio.tempo.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.Filterable;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.cappielloantonio.tempo.databinding.ItemLibraryCatalogueGenreBinding;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.subsonic.models.Genre;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.GenreNames;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class GenreCatalogueAdapter extends RecyclerView.Adapter<GenreCatalogueAdapter.ViewHolder> implements Filterable {
    private final ClickCallback click;
    /* For the names genres are shown, searched and sorted by. */
    private final Context context;

    /** Every genre, in the order last chosen. */
    private List<Genre> genresFull = new ArrayList<>();
    /** What is on screen: genresFull, narrowed by the search. */
    private List<Genre> genres = new ArrayList<>();
    private String query = "";
    private String order = Constants.GENRE_ORDER_BY_NAME;

    private final Filter filtering = new Filter() {
        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            String pattern = constraint == null ? "" : constraint.toString().toLowerCase(Locale.getDefault()).trim();

            List<Genre> filtered = new ArrayList<>();
            for (Genre genre : genresFull) {
                // By the name shown, and by the server's names too: someone who
                // tagged a release "Hip Hop" may well search for that.
                boolean matches = pattern.isEmpty() || label(genre).toLowerCase(Locale.getDefault()).contains(pattern)
                        || genre.names().stream().anyMatch(name -> name.toLowerCase(Locale.getDefault()).contains(pattern));

                if (matches) {
                    filtered.add(genre);
                }
            }

            FilterResults results = new FilterResults();
            results.values = filtered;
            results.count = filtered.size();
            return results;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void publishResults(CharSequence constraint, FilterResults results) {
            query = constraint == null ? "" : constraint.toString();
            genres = (List<Genre>) results.values;
            notifyDataSetChanged();
        }
    };

    public GenreCatalogueAdapter(Context context, ClickCallback click) {
        this.context = context;
        this.click = click;
    }

    private String label(Genre genre) {
        return GenreNames.label(context, genre.getGenre());
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemLibraryCatalogueGenreBinding view = ItemLibraryCatalogueGenreBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        GenreAdapter.bindTile(holder.item.tile, genres.get(position));
    }

    @Override
    public int getItemCount() {
        return genres.size();
    }

    public Genre getItem(int position) {
        return genres.get(position);
    }

    /**
     * Takes a copy. The list used to be kept as handed over - the view
     * model's own - and searching cleared and refilled it in place, so the
     * genres the page came back to were the last search's.
     * <p>
     * A genre with no name has nowhere to lead and is left out: sorting and
     * searching by name both failed on one.
     */
    public void setItems(List<Genre> genres) {
        this.genresFull = genres.stream()
                .filter(genre -> genre.getGenre() != null && !genre.getGenre().isBlank())
                .collect(Collectors.toCollection(ArrayList::new));

        sortFull();
        filtering.filter(query);
    }

    /** How many genres there are, whatever the search is showing. */
    public int getFullCount() {
        return genresFull.size();
    }

    @Override
    public Filter getFilter() {
        return filtering;
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        ItemLibraryCatalogueGenreBinding item;

        ViewHolder(ItemLibraryCatalogueGenreBinding item) {
            super(item.getRoot());

            this.item = item;

            item.tile.getRoot().setOnClickListener(v -> click.onGenreClick(GenreAdapter.genreBundle(genres.get(getBindingAdapterPosition()))));
        }
    }

    public void sort(String order) {
        this.order = order;

        sortFull();
        filtering.filter(query);
    }

    /**
     * By name, the way a reader orders words: case and accents set aside, so
     * "breakcore" sits with the Bs rather than after "Vocaloid", where a
     * plain string comparison put every lowercase name.
     */
    private void sortFull() {
        Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.PRIMARY);
        Comparator<Genre> byName = (a, b) -> collator.compare(label(a), label(b));

        switch (order) {
            case Constants.GENRE_ORDER_BY_SONG_COUNT:
                genresFull.sort(Comparator.comparingInt(Genre::getSongCount).reversed().thenComparing(byName));
                break;
            case Constants.GENRE_ORDER_BY_RANDOM:
                Collections.shuffle(genresFull);
                break;
            default:
                genresFull.sort(byName);
                break;
        }
    }
}
