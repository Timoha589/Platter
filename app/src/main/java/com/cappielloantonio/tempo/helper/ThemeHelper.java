package com.cappielloantonio.tempo.helper;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * Platter is a dark-only interface.
 * <p>
 * DESIGN.md: "Do not use light backgrounds or white surfaces in the core
 * interface — the dark canvas is signature." The night mode is therefore pinned
 * rather than following the system, and the theme picker has been removed from
 * settings.
 */
public class ThemeHelper {
    private ThemeHelper() {
    }

    public static void pinDarkMode() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
    }
}
