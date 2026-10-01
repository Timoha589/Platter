package com.cappielloantonio.tempo.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.cappielloantonio.tempo.App;

public class NetworkUtil {
    /**
     * @return true when there is no network worth trying a request on.
     * <p>
     * This used to also require {@code NET_CAPABILITY_VALIDATED}, which Android
     * sets only once it has reached the public internet through a captive
     * portal probe. A Subsonic server is very often on the same LAN as the
     * phone, so home Wi-Fi with a dead WAN connection - or any network Android
     * has not validated yet - put the app into offline mode against a server it
     * could reach perfectly well. Reachability of the server is what matters
     * here, and only a request can establish that.
     */
    public static boolean isOffline() {
        ConnectivityManager connectivityManager = (ConnectivityManager) App.getContext().getSystemService(Context.CONNECTIVITY_SERVICE);

        if (connectivityManager != null) {
            Network network = connectivityManager.getActiveNetwork();

            if (network != null) {
                NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);

                if (capabilities != null) {
                    return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                }
            }
        }

        return true;
    }
}
