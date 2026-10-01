package com.cappielloantonio.tempo.github.utils;

import com.cappielloantonio.tempo.BuildConfig;

public class UpdateUtil {

    /**
     * Whether a release tag such as "v1.2.0" names a newer version than the one
     * running. A "v" prefix and a suffix like "-beta" are ignored, and a missing
     * part counts as zero, so "1.2" equals "1.2.0".
     */
    public static boolean isNewer(String tagName) {
        if (tagName == null) return false;

        int[] remote = parse(tagName);
        int[] local = parse(BuildConfig.VERSION_NAME);

        for (int i = 0; i < Math.max(remote.length, local.length); i++) {
            int r = i < remote.length ? remote[i] : 0;
            int l = i < local.length ? local[i] : 0;

            if (r != l) return r > l;
        }

        return false;
    }

    public static String cleanVersion(String tagName) {
        return tagName == null ? "" : tagName.trim().replaceFirst("^[vV]", "");
    }

    private static int[] parse(String version) {
        String[] parts = cleanVersion(version).split("\\.");
        int[] numbers = new int[parts.length];

        for (int i = 0; i < parts.length; i++) {
            String digits = parts[i].replaceFirst("^(\\d*).*$", "$1");

            try {
                numbers[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits);
            } catch (NumberFormatException exception) {
                numbers[i] = 0;
            }
        }

        return numbers;
    }
}
