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
import com.cappielloantonio.tempo.databinding.ItemLoginServerBinding;
import com.cappielloantonio.tempo.interfaces.ClickCallback;
import com.cappielloantonio.tempo.model.Server;
import com.cappielloantonio.tempo.util.PremiumServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ServerAdapter extends RecyclerView.Adapter<ServerAdapter.ViewHolder> {
    private final ClickCallback click;

    private List<Server> servers;

    /* The server being signed in to, whose row shows it is working; null while none is. */
    @Nullable
    private String signingIn;

    public ServerAdapter(ClickCallback click) {
        this.click = click;
        this.servers = new ArrayList<>();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemLoginServerBinding view = ItemLoginServerBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        Server server = servers.get(position);

        holder.item.serverNameTextView.setText(server.getServerName());
        holder.item.serverAddressTextView.setText(holder.itemView.getContext().getString(
                R.string.login_server_account, server.getUsername(), displayAddress(server.getAddress())));

        // Timoha Premium wears the Platter disc; any other server, the rack glyph.
        boolean premium = PremiumServer.isPremium(server.getAddress());
        holder.item.serverIconImageView.setImageResource(premium ? R.drawable.ic_platter_mark : R.drawable.ic_server);
        holder.item.serverIconImageView.setImageTintList(premium ? null
                : ContextCompat.getColorStateList(holder.itemView.getContext(), R.color.mist));
        int icon = holder.itemView.getResources().getDimensionPixelSize(premium ? R.dimen.login_server_mark_size : R.dimen.login_server_glyph_size);
        ViewGroup.LayoutParams params = holder.item.serverIconImageView.getLayoutParams();
        params.width = icon;
        params.height = icon;
        holder.item.serverIconImageView.setLayoutParams(params);

        boolean working = Objects.equals(signingIn, server.getServerId());
        holder.item.serverProgress.setVisibility(working ? View.VISIBLE : View.GONE);
        holder.item.serverMoreButton.setVisibility(working ? View.INVISIBLE : View.VISIBLE);
    }

    /* The address as people say it: without the scheme or a trailing slash. */
    private static String displayAddress(String address) {
        if (address == null) return "";
        return address.replaceFirst("^https?://", "").replaceAll("/+$", "");
    }

    @Override
    public int getItemCount() {
        return servers.size();
    }

    public void setItems(List<Server> servers) {
        this.servers = servers;
        notifyDataSetChanged();
    }

    public Server getItem(int id) {
        return servers.get(id);
    }

    public boolean isSigningIn() {
        return signingIn != null;
    }

    public void setSigningIn(@Nullable String serverId) {
        signingIn = serverId;
        notifyDataSetChanged();
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        ItemLoginServerBinding item;

        ViewHolder(ItemLoginServerBinding item) {
            super(item.getRoot());

            this.item = item;

            itemView.setOnClickListener(v -> onClick());
            itemView.setOnLongClickListener(v -> onLongClick());
            item.serverMoreButton.setOnClickListener(v -> onLongClick());
        }

        public void onClick() {
            Bundle bundle = new Bundle();
            bundle.putParcelable("server_object", servers.get(getBindingAdapterPosition()));

            click.onServerClick(bundle);
        }

        public boolean onLongClick() {
            Bundle bundle = new Bundle();
            bundle.putParcelable("server_object", servers.get(getBindingAdapterPosition()));

            click.onServerLongClick(bundle);

            return true;
        }
    }
}
