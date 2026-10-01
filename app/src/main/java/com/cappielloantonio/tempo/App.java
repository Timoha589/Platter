package com.cappielloantonio.tempo;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.cappielloantonio.tempo.github.Github;
import com.cappielloantonio.tempo.helper.ThemeHelper;
import com.cappielloantonio.tempo.subsonic.Subsonic;
import com.cappielloantonio.tempo.subsonic.SubsonicPreferences;
import com.cappielloantonio.tempo.util.Preferences;
import com.cappielloantonio.tempo.util.ServerAddress;

public class App extends Application {
    private static App instance;
    private static Context context;
    private static Subsonic subsonic;
    private static Github github;
    private static SharedPreferences preferences;

    @Override
    public void onCreate() {
        super.onCreate();

        ThemeHelper.pinDarkMode();

        instance = this;
        context = getApplicationContext();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);

        // Home or away: follows the network for as long as the process lives,
        // which is as long as the player does.
        ServerAddress.watchNetwork(context);
    }

    public static App getInstance() {
        return instance;
    }

    public static Context getContext() {
        return context;
    }

    public static Subsonic getSubsonicClientInstance(boolean override) {
        if (subsonic == null || override) {
            subsonic = getSubsonicClient();
        }
        return subsonic;
    }

    public static Github getGithubClientInstance() {
        if (github == null) {
            github = new Github();
        }
        return github;
    }

    public SharedPreferences getPreferences() {
        if (preferences == null) {
            preferences = PreferenceManager.getDefaultSharedPreferences(context);
        }

        return preferences;
    }

    public static void refreshSubsonicClient() {
        subsonic = getSubsonicClient();
    }

    private static Subsonic getSubsonicClient() {
        SubsonicPreferences preferences = getSubsonicPreferences();

        if (preferences.getAuthentication() != null) {
            if (preferences.getAuthentication().getPassword() != null)
                Preferences.setPassword(preferences.getAuthentication().getPassword());
            if (preferences.getAuthentication().getToken() != null)
                Preferences.setToken(preferences.getAuthentication().getToken());
            if (preferences.getAuthentication().getSalt() != null)
                Preferences.setSalt(preferences.getAuthentication().getSalt());
        }

        return new Subsonic(preferences);
    }

    @NonNull
    private static SubsonicPreferences getSubsonicPreferences() {
        String server = Preferences.getInUseServerAddress();
        String username = Preferences.getUser();
        String password = Preferences.getPassword();
        String token = Preferences.getToken();
        String salt = Preferences.getSalt();
        boolean isLowSecurity = Preferences.isLowScurity();

        SubsonicPreferences preferences = new SubsonicPreferences();
        preferences.setServerUrl(server);
        preferences.setUsername(username);
        preferences.setAuthentication(password, token, salt, isLowSecurity);

        return preferences;
    }
}
