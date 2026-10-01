package com.cappielloantonio.tempo.util;

import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

/**
 * Derives the player background tint from the artwork of the playing track.
 * <p>
 * The tint is the plain arithmetic mean of the cover's pixels, pulled back into
 * a band of lightness that keeps the white type and monoline icons of the
 * player legible on top of it — an average is usually either a muddy mid-grey
 * or, on a bright sleeve, light enough to swallow white text.
 */
public class CoverColorUtil {
    /**
     * Pixels this translucent are the rounded corners Glide crops into the
     * cover, not artwork, and averaging them drags every colour towards black.
     */
    private static final int MIN_OPAQUE_ALPHA = 128;

    /**
     * Averages wash out saturation, so what little the cover has is pushed back
     * up. The existing saturation is scaled rather than floored: a black and
     * white sleeve has to stay grey instead of being given a colour it hasn't got.
     */
    private static final float SATURATION_GAIN = 1.4f;

    private static final float MIN_LIGHTNESS = 0.16f;
    private static final float MAX_LIGHTNESS = 0.34f;

    /**
     * The primary action sits on the tinted background and has to stay clearly
     * above it, so its band of lightness starts well past where the background's
     * ends. Its icon is drawn black, which this range also keeps legible.
     */
    private static final float MIN_ACCENT_LIGHTNESS = 0.62f;
    private static final float MAX_ACCENT_LIGHTNESS = 0.78f;

    private CoverColorUtil() {
    }

    /**
     * @return the mean colour of {@code bitmap}, or {@code fallback} when it
     * holds no opaque pixel to average
     */
    @ColorInt
    public static int averageColor(@Nullable Bitmap bitmap, @ColorInt int fallback) {
        if (bitmap == null || bitmap.getWidth() == 0 || bitmap.getHeight() == 0) return fallback;

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        long red = 0, green = 0, blue = 0;
        int counted = 0;

        for (int pixel : pixels) {
            if (Color.alpha(pixel) < MIN_OPAQUE_ALPHA) continue;

            red += Color.red(pixel);
            green += Color.green(pixel);
            blue += Color.blue(pixel);
            counted++;
        }

        if (counted == 0) return fallback;

        return Color.rgb((int) (red / counted), (int) (green / counted), (int) (blue / counted));
    }

    /**
     * Conditions an average so it can be used as a background: saturation is
     * lifted and lightness clamped into a dark band, leaving the hue alone.
     */
    @ColorInt
    public static int toBackgroundTint(@ColorInt int color) {
        return withLightnessBetween(color, MIN_LIGHTNESS, MAX_LIGHTNESS);
    }

    /**
     * The same hue as the background, taken up to a brightness that reads as the
     * foreground element it belongs to rather than as part of the backdrop.
     */
    @ColorInt
    public static int toAccentTint(@ColorInt int color) {
        return withLightnessBetween(color, MIN_ACCENT_LIGHTNESS, MAX_ACCENT_LIGHTNESS);
    }

    @ColorInt
    private static int withLightnessBetween(@ColorInt int color, float min, float max) {
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);

        hsl[1] = Math.min(1f, hsl[1] * SATURATION_GAIN);
        hsl[2] = Math.max(min, Math.min(max, hsl[2]));

        return ColorUtils.HSLToColor(hsl);
    }
}
