package com.cappielloantonio.tempo.helper.view

import android.animation.ValueAnimator
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityManager
import androidx.core.view.isVisible
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.databinding.ViewFeedbackBannerBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import java.util.function.IntSupplier

class FeedbackBanner(
    private val binding: ViewFeedbackBannerBinding,
    private val playerSheet: ViewGroup,
    private val navigation: View,
    private val playerState: IntSupplier,
) {
    private val view = binding.root
    private val parent get() = view.parent as ViewGroup
    private val spacing = view.resources.getDimensionPixelSize(R.dimen.spacing_8)
    private val slide = view.resources.getDimensionPixelSize(R.dimen.spacing_4).toFloat()
    private val maxTextWidth = (352 * view.resources.displayMetrics.density).toInt()
    private val spinnerWidth = view.resources.getDimensionPixelSize(R.dimen.spacing_16) +
        view.resources.getDimensionPixelSize(R.dimen.spacing_12)
    private val easing = FastOutSlowInInterpolator()
    private val visibleRect = Rect()
    private val location = IntArray(2)

    private var loadingMessage: CharSequence? = null
    private val positionListener = ViewTreeObserver.OnPreDrawListener(::position)
    private val expireMessage = Runnable {
        val loading = loadingMessage
        if (loading == null) hide() else present(loading, loading = true)
    }

    fun showLoading(message: CharSequence) {
        loadingMessage = message
        view.removeCallbacks(expireMessage)
        present(message, loading = true)
    }

    fun finishLoading(message: CharSequence) {
        loadingMessage = null
        show(message, 3000)
    }

    fun show(message: CharSequence, duration: Int) {
        view.removeCallbacks(expireMessage)
        present(message, loading = false)
        val timeout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            view.context.getSystemService(AccessibilityManager::class.java)
                .getRecommendedTimeoutMillis(duration, AccessibilityManager.FLAG_CONTENT_TEXT)
        } else {
            duration
        }
        view.postDelayed(expireMessage, timeout.toLong())
    }

    fun clear() {
        loadingMessage = null
        view.removeCallbacks(expireMessage)
        view.animate().withEndAction(null).cancel()
        conceal()
    }

    private fun present(message: CharSequence, loading: Boolean) {
        val entering = !view.isVisible
        val changed = binding.message.text != message || binding.spinner.isVisible != loading
        view.animate().withEndAction(null).cancel()
        if (entering) {
            view.isVisible = true
            view.viewTreeObserver.addOnPreDrawListener(positionListener)
        }
        binding.spinner.isVisible = loading
        binding.message.text = message
        binding.message.animate().cancel()
        binding.message.alpha = 1f

        if (!motionEnabled()) {
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        if (entering) {
            view.alpha = 0f
            view.translationY = slide
        } else if (changed) {
            binding.message.alpha = 0.4f
            binding.message.animate().alpha(1f).setDuration(160).setInterpolator(easing).start()
        }
        view.animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(easing).start()
    }

    private fun hide() {
        if (!motionEnabled()) {
            conceal()
            return
        }
        view.animate().alpha(0f).translationY(slide).setDuration(140).setInterpolator(easing)
            .withEndAction(::conceal).start()
    }

    private fun conceal() {
        view.viewTreeObserver.removeOnPreDrawListener(positionListener)
        binding.message.animate().cancel()
        view.isVisible = false
    }

    private fun motionEnabled() = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

    private fun position(): Boolean {
        val state = playerState.asInt
        if (state == BottomSheetBehavior.STATE_DRAGGING || state == BottomSheetBehavior.STATE_SETTLING) return true

        val host = parent
        host.getLocationInWindow(location)
        val hostX = location[0]
        val hostY = location[1]
        var center = host.width / 2
        var bottom = host.height - spacing
        var textWidth = maxTextWidth

        if (state == BottomSheetBehavior.STATE_COLLAPSED) {
            playerSheet.getLocationInWindow(location)
            bottom = location[1] - hostY - spacing
        } else if (state == BottomSheetBehavior.STATE_EXPANDED) {
            val transport = visiblePlayerView(R.id.exo_play_pause)
            val quickActions = visiblePlayerView(R.id.player_quick_action_view)
            if (transport != null) {
                val transportBottom = windowTop(transport) - hostY + transport.height
                val actionsTop = quickActions?.let { windowTop(it) - hostY } ?: transportBottom
                val gap = actionsTop - transportBottom
                if (gap >= view.height + slide * 2) {
                    bottom = transportBottom + (gap + view.height) / 2
                } else {
                    // Compact windows and landscape place feedback over the cover or lyrics.
                    // The transport stays clear even when the message takes several lines.
                    val artwork = visiblePlayerView(R.id.player_media_cover_view_pager)
                    if (artwork != null) {
                        artwork.getLocationInWindow(location)
                        center = location[0] - hostX + artwork.width / 2
                        bottom = location[1] - hostY + artwork.height - spacing
                        val occupiedWidth = view.paddingStart + view.paddingEnd +
                            if (binding.spinner.isVisible) spinnerWidth else 0
                        textWidth = minOf(maxTextWidth, (artwork.width - 2 * spacing - occupiedWidth).coerceAtLeast(1))
                    }
                }
            } else {
                visiblePlayerView(R.id.player_shuffle_queue_fab)?.let { bottom = windowTop(it) - hostY - spacing }
            }
        } else if (navigation.isVisible) {
            bottom = windowTop(navigation) - hostY - spacing
        }

        if (binding.message.maxWidth != textWidth) {
            binding.message.maxWidth = textWidth
            return false
        }
        val left = (center - view.width / 2).coerceIn(spacing, maxOf(spacing, host.width - view.width - spacing))
        val top = (bottom - view.height).coerceAtLeast(spacing)
        if (left != view.left) view.offsetLeftAndRight(left - view.left)
        if (top != view.top) view.offsetTopAndBottom(top - view.top)
        return true
    }

    private fun windowTop(child: View): Int {
        child.getLocationInWindow(location)
        return location[1]
    }

    private fun visiblePlayerView(id: Int): View? = playerSheet.findViewById<View>(id)?.takeIf {
        it.isShown && it.height > 0 && it.getLocalVisibleRect(visibleRect)
    }
}
