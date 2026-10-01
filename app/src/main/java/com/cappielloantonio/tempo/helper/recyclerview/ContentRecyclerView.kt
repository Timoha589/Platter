package com.cappielloantonio.tempo.helper.recyclerview

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.widget.EdgeEffect
import androidx.annotation.RequiresApi
import androidx.recyclerview.widget.RecyclerView

/**
 * A RecyclerView whose edge only stretches when there is something to scroll.
 *
 * Every other scrolling view on Android reads the default overscroll mode,
 * `OVER_SCROLL_IF_CONTENT_SCROLLS`, the way it is spelt: a list that fits has
 * no edge to pull against. RecyclerView ignores the second half and stretches
 * whenever the mode is not `NEVER` - so a list that fits in full wobbles under
 * a drag it can do nothing with. On the home page that was the playlists row
 * jiggling on its own while the page around it stood still; in the player it
 * was the queue, but only while it was short enough to fit, which is why it
 * was hard to catch.
 *
 * `NEVER` would fix the short lists by taking the stretch away from the long
 * ones too, where it is the only sign the end has been reached. This keeps it
 * there and nowhere else. Every list in the app is one of these for that
 * reason; a plain RecyclerView brings the wobble back.
 */
class ContentRecyclerView : RecyclerView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    init {
        stretchOnlyWithContent(this)
    }

    companion object {
        /**
         * For RecyclerViews that are not declared in a layout - ViewPager2
         * keeps its own as its only child, and a pager with a single page is
         * a list that fits.
         */
        @JvmStatic
        fun stretchOnlyWithContent(list: RecyclerView) {
            list.edgeEffectFactory = ContentEdgeEffectFactory
        }
    }
}

private object ContentEdgeEffectFactory : RecyclerView.EdgeEffectFactory() {
    override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect {
        val vertical = direction == DIRECTION_TOP || direction == DIRECTION_BOTTOM
        return ContentEdgeEffect(view, vertical)
    }
}

private class ContentEdgeEffect(
    private val list: RecyclerView,
    private val vertical: Boolean,
) : EdgeEffect(list.context) {

    /*
     * Asked at the moment of the pull rather than once, since the same list
     * fits or overflows as its contents change. An edge that is already out
     * is always let back in, whatever the contents did meanwhile.
     */
    private fun canStretch(): Boolean {
        if (!isFinished || list.overScrollMode == View.OVER_SCROLL_ALWAYS) return true
        return if (vertical) {
            list.canScrollVertically(1) || list.canScrollVertically(-1)
        } else {
            list.canScrollHorizontally(1) || list.canScrollHorizontally(-1)
        }
    }

    override fun onPull(deltaDistance: Float) {
        if (canStretch()) super.onPull(deltaDistance)
    }

    override fun onPull(deltaDistance: Float, displacement: Float) {
        if (canStretch()) super.onPull(deltaDistance, displacement)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onPullDistance(deltaDistance: Float, displacement: Float): Float {
        return if (canStretch()) super.onPullDistance(deltaDistance, displacement) else 0f
    }

    override fun onAbsorb(velocity: Int) {
        if (canStretch()) super.onAbsorb(velocity)
    }
}
