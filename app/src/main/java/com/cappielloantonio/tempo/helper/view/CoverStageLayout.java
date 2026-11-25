package com.cappielloantonio.tempo.helper.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

/** The full player's cover slide, shared by button presses and audio transitions. */
public class CoverStageLayout extends ConstraintLayout {
    public static final long TURN_DURATION_MS = 480;
    private static final float FADE_FRACTION = 2f / 3f;
    private static final FastOutSlowInInterpolator EASING = new FastOutSlowInInterpolator();

    private final float gutter;

    public CoverStageLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        gutter = 24f * getResources().getDisplayMetrics().density;
    }

    public void renderTurn(@NonNull View strip, @NonNull View outgoing, @NonNull View incoming, int direction, float progress) {
        float width = Math.min(getWidth(), strip.getWidth() + gutter * 2f);
        float slide = direction * width / 2f;
        float movement = EASING.getInterpolation(progress);
        move(outgoing, -slide * movement, 1f - 0.1f * movement,
                1f - fade((progress - (1f - FADE_FRACTION)) / FADE_FRACTION));
        move(incoming, slide * (1f - movement), 0.9f + 0.1f * movement,
                fade(progress / FADE_FRACTION));
    }

    private static float fade(float fraction) {
        return EASING.getInterpolation(Math.max(0f, Math.min(1f, fraction)));
    }

    private static void move(View view, float x, float scale, float opacity) {
        view.setTranslationX(x);
        view.setScaleX(scale);
        view.setScaleY(scale);
        view.setAlpha(opacity);
    }
}
