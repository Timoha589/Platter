package com.cappielloantonio.tempo.util;

import androidx.annotation.NonNull;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.interfaces.SystemCallback;
import com.cappielloantonio.tempo.model.Server;
import com.cappielloantonio.tempo.repository.SystemRepository;

/**
 * Signs in to a server: makes it the one in use, picks its address, and asks
 * it whether the credentials are good. On a refusal nothing of the server is
 * left behind. The server list and the Timoha Premium sheet both sign in
 * through here.
 */
@UnstableApi
public final class ServerSignIn {
    public interface Callback {
        void onSignedIn();

        /** @param exception carries the server's "code - message" for a Subsonic error */
        void onFailed(@NonNull Exception exception);
    }

    /* Subsonic error 40: wrong username or password. */
    private static final String WRONG_CREDENTIALS = "40 ";

    private ServerSignIn() {
    }

    public static boolean isWrongCredentials(@NonNull Exception exception) {
        String message = exception.getMessage();
        return message != null && message.startsWith(WRONG_CREDENTIALS);
    }

    public static void signIn(@NonNull Server server, @NonNull Callback callback) {
        save(server);

        // Signing in at home works even where the main address does not reach
        // back into the home network: the local address is tried first.
        ServerAddress.check(changed -> new SystemRepository().checkUserCredential(new SystemCallback() {
            @Override
            public void onError(Exception exception) {
                reset();
                callback.onFailed(exception);
            }

            @Override
            public void onSuccess(String password, String token, String salt) {
                callback.onSignedIn();
            }
        }));
    }

    private static void save(Server server) {
        // The server is changing, so nothing is known about what it supports
        // until its own ping answers.
        Preferences.clearOpenSubsonicExtensions();

        Preferences.setServerId(server.getServerId());
        Preferences.setServer(server.getAddress());
        Preferences.setLocalAddress(server.getLocalAddress());
        // Whatever address the previous server was on means nothing for this one.
        Preferences.setInUseServerAddress(null);
        Preferences.setUser(server.getUsername());
        Preferences.setPassword(server.getPassword());
        Preferences.setLowSecurity(server.isLowSecurity());

        App.getSubsonicClientInstance(true);
    }

    private static void reset() {
        Preferences.clearOpenSubsonicExtensions();

        Preferences.setServerId(null);
        Preferences.setServer(null);
        Preferences.setLocalAddress(null);
        Preferences.setInUseServerAddress(null);
        Preferences.setUser(null);
        Preferences.setPassword(null);
        Preferences.setToken(null);
        Preferences.setSalt(null);
        Preferences.setLowSecurity(false);

        App.getSubsonicClientInstance(true);
    }
}
