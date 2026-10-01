package com.cappielloantonio.tempo.util;

import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Picks a row up the moment its right-hand end is touched - no press-and-hold
 * first - for lists reordered with an {@code ItemTouchHelper} whose long-press
 * drag is turned off.
 * <p>
 * The rest of the row keeps its own touches: a tap still does what it did, and
 * a drag that starts left of the handle scrolls the list. A touch that is kept
 * by the handle and never moves is a pick-up put straight back down, not a
 * click on the row.
 */
public final class DragHandleTouch {
    /**
     * How far left of the handle a touch still picks the row up. The handle
     * itself is an icon's width; a thumb aiming for the right edge of a row
     * lands anywhere across its last inch.
     */
    private static final int GRAB_SLOP_DP = 16;

    private DragHandleTouch() {
    }

    /** For the row's own view, with {@code handle} the drag icon inside it. */
    @SuppressLint("ClickableViewAccessibility")
    public static View.OnTouchListener grabFrom(View handle, Runnable startDrag) {
        return (row, event) -> {
            if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return false;

            float slop = GRAB_SLOP_DP * row.getResources().getDisplayMetrics().density;
            if (event.getX() < handle.getLeft() - slop) return false;

            startDrag.run();
            return true;
        };
    }

    /**
     * Runs {@code move} - a row being carried one step - without letting the
     * list scroll after it.
     * <p>
     * A LinearLayoutManager keeps its first visible row where it was across a
     * change, and when that row is the one being carried it keeps it by
     * scrolling the list. The scroll moves the row past its neighbours, which
     * is another move, which scrolls again: picking up the top row ran it to
     * the bottom of the list on its own. Putting the list back where it was
     * after each step leaves the row under the finger.
     */
    public static void moveKeepingScroll(RecyclerView list, Runnable move) {
        if (!(list.getLayoutManager() instanceof LinearLayoutManager)) {
            move.run();
            return;
        }

        LinearLayoutManager layout = (LinearLayoutManager) list.getLayoutManager();
        int first = layout.findFirstVisibleItemPosition();
        View firstView = first == RecyclerView.NO_POSITION ? null : layout.findViewByPosition(first);
        int offset = firstView == null ? 0 : layout.getDecoratedTop(firstView) - list.getPaddingTop();

        move.run();

        if (first != RecyclerView.NO_POSITION) layout.scrollToPositionWithOffset(first, offset);
    }
}
