package com.cappielloantonio.tempo.helper.view

import android.animation.ValueAnimator
import android.content.res.Resources
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.cappielloantonio.tempo.R
import java.util.Collections
import java.util.WeakHashMap

/**
 * Keeps the room left at the bottom of every screen for the mini player only
 * while there is a mini player.
 *
 * Screens end on `global_padding_bottom`: the navigation bar, the mini player
 * stacked on it, and a little air. With nothing playing the mini player is
 * gone, and its share of that was left behind as a blank band under the last
 * section - most visible at the end of home.
 *
 * Rather than every screen watching the player, the views are found as the
 * screens are built: anything whose bottom padding or margin is exactly that
 * dimension was laid out to clear the bars, so it is the thing to adjust. Only
 * screens hosted by the navigation are looked at; the player's own queue ends
 * on the same padding, but it can only be seen while there is a player.
 */
class MiniPlayerClearance(resources: Resources) {
    private val full = resources.getDimensionPixelSize(R.dimen.global_padding_bottom)
    private val miniPlayer = resources.getDimensionPixelSize(R.dimen.bottom_sheet_peek_height)

    // Weak, so a screen's views go with it rather than living on here.
    private val paddings: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    private val margins: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

    /** How much of the mini player's room the screens keep, in pixels. */
    private var shown = miniPlayer
    private var animator: ValueAnimator? = null

    val fragmentCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
            collect(v)
        }
    }

    /**
     * Gives the room back at once, and takes it away gently when [animate] is
     * set.
     *
     * The room is only ever missed at the end of a screen scrolled right down,
     * and there taking it away pulls the last section down into the space. In
     * one step that was a 56dp drop in a single frame, just after the mini
     * player had gone - so it is eased instead, once the mini player is off
     * the screen rather than while it is still being dragged there. Giving the
     * room back moves nothing (the mini player comes up over the end of the
     * screen, and nothing is scrolled to fill the space), so there is nothing
     * to ease.
     */
    fun setMiniPlayerShown(shown: Boolean, animate: Boolean) {
        val target = if (shown) miniPlayer else 0

        animator?.cancel()
        animator = null

        if (!animate || shown) {
            setShownHeight(target)
            return
        }

        animator = ValueAnimator.ofInt(this.shown, target).apply {
            duration = HIDE_DURATION_MS
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { setShownHeight(it.animatedValue as Int) }
            start()
        }
    }

    private fun setShownHeight(height: Int) {
        if (height == shown) return
        shown = height

        paddings.forEach(::applyPadding)
        margins.forEach(::applyMargin)
    }

    private fun collect(view: View) {
        if (view.paddingBottom == full) {
            paddings.add(view)
            applyPadding(view)
        }

        val params = view.layoutParams
        if (params is ViewGroup.MarginLayoutParams && params.bottomMargin == full) {
            margins.add(view)
            applyMargin(view)
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collect(view.getChildAt(i))
        }
    }

    private fun clearance() = full - miniPlayer + shown

    private fun applyPadding(view: View) {
        view.setPaddingRelative(view.paddingStart, view.paddingTop, view.paddingEnd, clearance())
    }

    private fun applyMargin(view: View) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        params.bottomMargin = clearance()
        view.layoutParams = params
    }

    private companion object {
        const val HIDE_DURATION_MS = 250L
    }
}
