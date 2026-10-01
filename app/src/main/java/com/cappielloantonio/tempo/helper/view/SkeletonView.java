package com.cappielloantonio.tempo.helper.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.LinearLayout;

import androidx.annotation.LayoutRes;
import androidx.annotation.Nullable;

import com.cappielloantonio.tempo.R;

/**
 * The loading placeholder a home section shows until its request comes back.
 * <p>
 * Home used to start with every section {@code gone} and reveal them one at a
 * time as observables delivered, in whatever order the server answered, so the
 * page reflowed under the reader several times on a slow library. A skeleton
 * claims the section's final height up front: the swap to real content moves
 * nothing, and a section that turns out to be empty collapses once instead of
 * appearing and then being pushed around.
 * <p>
 * It is a plain LinearLayout that inflates {@code skeletonItem} {@code
 * skeletonCount} times. The pulse is a single alpha animator that runs only
 * while the view is attached and visible - a skeleton that has been swapped out
 * costs nothing.
 */
public class SkeletonView extends LinearLayout {
    private static final long PULSE_DURATION = 900;
    private static final float PULSE_FLOOR = 0.72f;

    @Nullable
    private ValueAnimator pulse;

    public SkeletonView(Context context) {
        this(context, null);
    }

    public SkeletonView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        TypedArray attributes = context.obtainStyledAttributes(attrs, R.styleable.SkeletonView);
        @LayoutRes int item = attributes.getResourceId(R.styleable.SkeletonView_skeletonItem, 0);
        int count = attributes.getInt(R.styleable.SkeletonView_skeletonCount, 1);
        attributes.recycle();

        fill(item, count);
    }

    /** One placeholder item, built in code - the cell a SkeletonAdapter puts in a list. */
    public SkeletonView(Context context, @LayoutRes int item) {
        super(context);

        setOrientation(VERTICAL);
        setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        fill(item, 1);
    }

    private void fill(@LayoutRes int item, int count) {
        // A placeholder has nothing to announce, and TalkBack should not walk it.
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        if (item != 0) {
            LayoutInflater inflater = LayoutInflater.from(getContext());

            for (int i = 0; i < count; i++) {
                inflater.inflate(item, this, true);
            }
        }
    }

    /*
     * Pages used to start with every section gone and reveal them one at a
     * time, in whatever order the server answered, so they reflowed under the
     * reader several times on a slow library. A section now claims its final
     * height with a skeleton the moment its request goes out, and swaps that
     * for its content - or collapses, once - when the answer arrives.
     */
    public static void startLoading(View sector, View skeleton, View content) {
        sector.setVisibility(View.VISIBLE);
        skeleton.setVisibility(View.VISIBLE);
        content.setVisibility(View.GONE);
    }

    public static void finishLoading(View sector, View skeleton, View content, boolean hasContent) {
        skeleton.setVisibility(View.GONE);
        content.setVisibility(hasContent ? View.VISIBLE : View.GONE);
        sector.setVisibility(hasContent ? View.VISIBLE : View.GONE);
    }

    /*
     * The placeholder blocks never overlap, so alpha can be applied to each
     * child directly instead of through an offscreen layer.
     */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updatePulse();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopPulse();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updatePulse();
    }

    private void updatePulse() {
        if (isAttachedToWindow() && getVisibility() == VISIBLE) {
            startPulse();
        } else {
            stopPulse();
        }
    }

    private void startPulse() {
        if (pulse != null) return;

        pulse = ValueAnimator.ofFloat(1f, PULSE_FLOOR);
        pulse.setDuration(PULSE_DURATION);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setInterpolator(new AccelerateDecelerateInterpolator());
        pulse.addUpdateListener(animation -> setAlpha((float) animation.getAnimatedValue()));
        pulse.start();

        // Every skeleton on screen breathes together, whenever it was attached:
        // a list of placeholders each on its own beat read as a flicker.
        pulse.setCurrentPlayTime(SystemClock.uptimeMillis() % (2 * PULSE_DURATION));
    }

    private void stopPulse() {
        if (pulse == null) return;

        pulse.cancel();
        pulse = null;
        setAlpha(1f);
    }
}
