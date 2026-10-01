package com.cappielloantonio.tempo.util;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import androidx.annotation.Nullable;

/**
 * Tells a track-changing swipe apart from the vertical drags that belong to the
 * bottom sheet. The player stacks a horizontal gesture on top of two vertical
 * ones — collapsing the sheet and paging to the queue — so the test is shared
 * by the full player and the mini player rather than written twice.
 */
public class SwipeGestureUtil {
    private SwipeGestureUtil() {
    }

    /**
     * A vertical drag, deliberate enough to act on: past several times the
     * system's touch slop, and clearly more vertical than sideways. Distance
     * rather than velocity, so a slow press-and-pull counts the same as a flick.
     */
    public static boolean isVerticalDrag(@Nullable MotionEvent from, @Nullable MotionEvent to, Context context) {
        if (from == null || to == null) return false;

        float deltaX = to.getX() - from.getX();
        float deltaY = to.getY() - from.getY();

        if (Math.abs(deltaY) <= Math.abs(deltaX)) return false;

        return Math.abs(deltaY) > ViewConfiguration.get(context).getScaledTouchSlop() * 3f;
    }

    /**
     * Lets a scrolling view keep the vertical drags it can actually use, and
     * hand back the ones it cannot.
     * <p>
     * The lyrics sit inside a vertical pager inside the bottom sheet, and both
     * of those take a vertical drag before the text ever sees it. The page used
     * to answer that by switching the sheet and the pager off entirely while the
     * words were up, which bought scrolling at the price of every other gesture
     * on the screen. Instead the view claims the gesture on the way down, only
     * while it has somewhere left to scroll, and lets go the moment it runs out
     * - so the text scrolls to its end and the next pull carries the sheet.
     * <p>
     * Returned rather than installed, so a caller with gestures of its own can
     * put them in front of this one on the same view. Nothing here consumes an
     * event, so composing the two is a matter of calling both.
     */
    public static View.OnTouchListener verticalScrollClaim(View view) {
        final int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();

        return new View.OnTouchListener() {
            private float downY;

            @Override
            public boolean onTouch(View scroller, MotionEvent event) {
                ViewParent parent = scroller.getParent();

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = event.getY();

                        /*
                         * Claimed before anything is known about the direction:
                         * the parents make up their minds on the first move past
                         * their own slop, which is too early to ask them to wait.
                         * A drag that turns out to be going nowhere is handed
                         * back below, well before it could have been acted on.
                         */
                        if (parent != null && canScroll(scroller)) {
                            parent.requestDisallowInterceptTouchEvent(true);
                        }
                        break;

                    case MotionEvent.ACTION_MOVE:
                        if (parent == null) break;

                        float dy = event.getY() - downY;
                        if (Math.abs(dy) <= slop) break;

                        // Dragging the finger up pulls the content up, which is
                        // a scroll towards the end of it.
                        if (!scroller.canScrollVertically(dy < 0 ? 1 : -1)) {
                            parent.requestDisallowInterceptTouchEvent(false);
                        }
                        break;

                    default:
                        break;
                }

                // Never consumed here: the view still scrolls itself.
                return false;
            }
        };
    }

    private static boolean canScroll(View view) {
        return view.canScrollVertically(1) || view.canScrollVertically(-1);
    }
}
