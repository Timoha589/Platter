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

    private static final int HUE_BUCKETS = 12;

    /** Below these a pixel is black or grey, whatever hue the rounding gave it. */
    private static final float MIN_COLOUR_SATURATION = 0.15f;
    private static final float MIN_COLOUR_VALUE = 0.2f;

    /** A cover with less colour than this share of its pixels is a grey one. */
    private static final float MIN_COLOUR_SHARE = 0.03f;

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

    /**
     * The two colours a cover is made of, for a background that is a slope
     * between them - the player's, as on the desktop.
     * <p>
     * The pixels are sorted into twelve hues, each counted by how vivid and
     * bright it is. The strongest hue is the first colour; the second is the
     * strongest one at least 60 degrees away from it, or - on a cover of a
     * single hue - the first, turned a little. The first is the livelier and the
     * second sits darker, so the slope reads as light falling off rather than as
     * two stripes, and both are dark enough for white type.
     * <p>
     * Black and grey are not colours. A sleeve that is mostly black has its
     * black in every hue by rounding, and counted, it outvotes the few real
     * colours - which, brought up to a dark-but-visible lightness, turned into a
     * loud olive. Those pixels are left out, and a cover with next to no colour
     * in it gets a neutral grey slope of its own brightness.
     *
     * @return {primary, secondary}; the fallbacks when the bitmap holds nothing
     * to count
     */
    public static int[] palette(@Nullable Bitmap bitmap, @ColorInt int fallbackPrimary, @ColorInt int fallbackSecondary) {
        int[] fallback = {fallbackPrimary, fallbackSecondary};
        if (bitmap == null || bitmap.getWidth() == 0 || bitmap.getHeight() == 0) return fallback;

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        double[] weight = new double[HUE_BUCKETS];
        double[] red = new double[HUE_BUCKETS];
        double[] green = new double[HUE_BUCKETS];
        double[] blue = new double[HUE_BUCKETS];
        float[] hsv = new float[3];

        double greyValue = 0;
        int greyCount = 0;
        int colourCount = 0;
        int opaqueCount = 0;

        for (int pixel : pixels) {
            if (Color.alpha(pixel) < MIN_OPAQUE_ALPHA) continue;

            Color.colorToHSV(pixel, hsv);
            opaqueCount++;

            if (hsv[1] < MIN_COLOUR_SATURATION || hsv[2] < MIN_COLOUR_VALUE) {
                greyValue += hsv[2];
                greyCount++;
                continue;
            }

            colourCount++;

            double w = hsv[1] * hsv[2];
            int bucket = Math.min(HUE_BUCKETS - 1, (int) (hsv[0] / 360f * HUE_BUCKETS));

            weight[bucket] += w;
            red[bucket] += Color.red(pixel) * w;
            green[bucket] += Color.green(pixel) * w;
            blue[bucket] += Color.blue(pixel) * w;
        }

        if (opaqueCount == 0) return fallback;

        // Hardly any colour: a grey of the cover's own brightness, darker at the far corner.
        if (colourCount < opaqueCount * MIN_COLOUR_SHARE) {
            float value = greyCount > 0 ? (float) (greyValue / greyCount) : 0.5f;

            return new int[]{
                    Color.HSVToColor(new float[]{0f, 0f, clamp(value, 0.45f, 0.65f)}),
                    Color.HSVToColor(new float[]{0f, 0f, clamp(value * 0.6f, 0.25f, 0.4f)}),
            };
        }

        int first = -1;
        for (int bucket = 0; bucket < HUE_BUCKETS; bucket++) {
            if (weight[bucket] > 0 && (first < 0 || weight[bucket] > weight[first])) first = bucket;
        }

        if (first < 0) return fallback;

        // The strongest of the others that is far enough round the wheel and
        // has a real share of the cover, not a stray pixel.
        int apart = -1;
        for (int bucket = 0; bucket < HUE_BUCKETS; bucket++) {
            int gap = Math.abs(bucket - first);
            gap = Math.min(gap, HUE_BUCKETS - gap);

            if (gap < 2 || weight[bucket] < weight[first] * 0.12) continue;
            if (apart < 0 || weight[bucket] > weight[apart]) apart = bucket;
        }

        float[] a = meanHsv(first, weight, red, green, blue);
        float[] b = apart >= 0
                ? meanHsv(apart, weight, red, green, blue)
                : new float[]{(a[0] + 0.07f * 360f) % 360f, a[1], a[2]};

        return new int[]{
                Color.HSVToColor(new float[]{a[0], Math.min(a[1], 0.85f), clamp(a[2], 0.45f, 0.65f)}),
                Color.HSVToColor(new float[]{b[0], Math.min(b[1], 0.8f), clamp(b[2], 0.25f, 0.4f)}),
        };
    }

    private static float[] meanHsv(int bucket, double[] weight, double[] red, double[] green, double[] blue) {
        float[] hsv = new float[3];
        Color.RGBToHSV(
                (int) Math.round(red[bucket] / weight[bucket]),
                (int) Math.round(green[bucket] / weight[bucket]),
                (int) Math.round(blue[bucket] / weight[bucket]),
                hsv
        );
        return hsv;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
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
