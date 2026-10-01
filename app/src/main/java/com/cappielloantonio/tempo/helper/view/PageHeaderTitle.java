package com.cappielloantonio.tempo.helper.view;

import android.view.View;
import android.view.ViewParent;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;

import com.google.android.material.appbar.AppBarLayout;

/**
 * The name of an artist, album or playlist page lives in its header while the
 * header is on screen, and moves into the toolbar only once the header has
 * scrolled away - never both at once. The album and playlist pages used to
 * show it in the toolbar all along, a second copy right above the first.
 */
public final class PageHeaderTitle {
    private PageHeaderTitle() {
    }

    public static void attach(AppBarLayout appBar, Toolbar toolbar, TextView title) {
        toolbar.setTitle(null);

        /*
         * By where the page's own title is, not by how far the bar has
         * collapsed. The header scrolls one-for-one with the page now, so the
         * title is still in view until very late; going by "within a toolbar's
         * height of collapsed" put the name in the toolbar while the big one
         * was still on screen under it. It changes over once the big title has
         * gone up behind the pinned toolbar.
         */
        appBar.addOnOffsetChangedListener((layout, verticalOffset) -> {
            boolean hidden = bottomIn(layout, title) + verticalOffset <= toolbar.getHeight();

            toolbar.setTitle(hidden ? title.getText() : null);
        });
    }

    /* The bottom of a view inside the app bar, in the bar's own coordinates, before any scrolling. */
    private static int bottomIn(AppBarLayout appBar, View view) {
        int top = 0;
        View current = view;

        while (current != null && current != appBar) {
            top += current.getTop();

            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }

        return top + view.getHeight();
    }
}
