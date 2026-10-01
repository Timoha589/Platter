package com.cappielloantonio.tempo.helper.view;

import android.text.Layout;
import android.text.TextPaint;
import android.transition.ChangeBounds;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import androidx.annotation.StringRes;
import androidx.recyclerview.widget.RecyclerView;

/**
 * A long text - an artist's biography, an album's notes - that opens folded
 * to a few lines, with a toggle under it to unfold it. The full Last.fm text
 * runs to screens and pushed everything under it out of sight.
 * <p>
 * Whether there is anything to unfold is only known once the text is laid
 * out, so the toggle is decided after layout: a short text never shows one.
 */
public final class TextFold {
    private static final int FOLDED_LINES = 4;
    private static final long FOLD_DURATION = 300;

    private final TextView body;
    private final TextView toggle;
    private final ViewGroup page;
    @StringRes
    private final int expandLabel;
    @StringRes
    private final int collapseLabel;

    /** Height of the text folded, taken from its first layout. */
    private int foldedHeight;
    private boolean expanded;
    private boolean folding;

    /**
     * @param page the column the text sits in; everything in it below the
     *             text rides along as the text folds and unfolds
     */
    public TextFold(TextView body, TextView toggle, ViewGroup page, @StringRes int expandLabel, @StringRes int collapseLabel) {
        this.body = body;
        this.toggle = toggle;
        this.page = page;
        this.expandLabel = expandLabel;
        this.collapseLabel = collapseLabel;

        View.OnClickListener onToggle = v -> {
            if (toggle.getVisibility() != View.VISIBLE || folding) return;

            toggle();
        };
        toggle.setOnClickListener(onToggle);
        body.setOnClickListener(onToggle);
    }

    public void show(CharSequence text) {
        body.setText(text);
        body.setMaxLines(FOLDED_LINES);
        setBodyHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        toggle.setText(expandLabel);
        toggle.setVisibility(View.GONE);
        expanded = false;

        /*
         * One width for both labels. The toggle swaps its words as the fold
         * starts, and a width animated between the two cut "Читать полностью"
         * short for the length of the fold.
         */
        TextPaint paint = toggle.getPaint();
        String expand = toggle.getContext().getString(expandLabel);
        String collapse = toggle.getContext().getString(collapseLabel);
        float widest = Math.max(paint.measureText(expand), paint.measureText(collapse));
        toggle.setMinWidth((int) Math.ceil(widest) + toggle.getPaddingStart() + toggle.getPaddingEnd());

        body.post(() -> {
            Layout layout = body.getLayout();
            if (layout == null) return;

            int lines = layout.getLineCount();
            boolean folded = lines > 0 && layout.getEllipsisCount(lines - 1) > 0;
            toggle.setVisibility(folded ? View.VISIBLE : View.GONE);
            foldedHeight = body.getHeight();
        });
    }

    /**
     * Unfolds the text, or folds it back, by moving the edges of what is on
     * the page rather than laying it out afresh on every frame: the text is
     * cut off at its own bottom edge as that edge travels, and the sections
     * under it ride along. Relaying the page each frame would re-measure every
     * row of the lists beneath it for the whole animation.
     * <p>
     * Folding keeps the full text until the fold is finished. Cutting it to
     * four lines first would leave an empty strip, the height of everything
     * that was just hidden, sliding shut under the ellipsis.
     */
    private void toggle() {
        ChangeBounds transition = new ChangeBounds();
        transition.setDuration(FOLD_DURATION);
        transition.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        // The rows of the lists below only move with their list; left in, each
        // one would get an animator of its own for nothing.
        transition.excludeChildren(RecyclerView.class, true);

        expanded = !expanded;
        toggle.setText(expanded ? collapseLabel : expandLabel);

        if (expanded) {
            TransitionManager.beginDelayedTransition(page, transition);
            body.setMaxLines(Integer.MAX_VALUE);
            return;
        }

        folding = true;
        // The listener interface, not TransitionListenerAdapter: the framework
        // adapter only arrived in API 26.
        transition.addListener(new Transition.TransitionListener() {
            @Override
            public void onTransitionEnd(Transition transition) {
                finishFolding();
            }

            @Override
            public void onTransitionCancel(Transition transition) {
                finishFolding();
            }

            @Override
            public void onTransitionStart(Transition transition) {
            }

            @Override
            public void onTransitionPause(Transition transition) {
            }

            @Override
            public void onTransitionResume(Transition transition) {
            }
        });

        TransitionManager.beginDelayedTransition(page, transition);
        setBodyHeight(foldedHeight);
    }

    /** Swaps the pinned height for the real four lines, which are the same height. */
    private void finishFolding() {
        folding = false;
        if (expanded) return;

        body.setMaxLines(FOLDED_LINES);
        setBodyHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void setBodyHeight(int height) {
        ViewGroup.LayoutParams params = body.getLayoutParams();
        if (params.height == height) return;

        params.height = height;
        body.setLayoutParams(params);
    }
}
