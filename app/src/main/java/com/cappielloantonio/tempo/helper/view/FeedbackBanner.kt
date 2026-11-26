package com.cappielloantonio.tempo.helper.view

import android.animation.ValueAnimator
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityManager
import androidx.core.view.isVisible
import androidx.core.view.marginEnd
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.databinding.ViewFeedbackBannerBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlin.math.roundToInt

class FeedbackBanner(
    private val binding: ViewFeedbackBannerBinding,
    private val playerSheet: ViewGroup,
    private val navigation: View,
    private val playerBehavior: BottomSheetBehavior<*>,
) {
    private val view = binding.root
    private val parent get() = view.parent as ViewGroup
    private val spacing = view.resources.getDimensionPixelSize(R.dimen.spacing_8)
    private val maxWidth = view.resources.getDimensionPixelSize(R.dimen.feedback_banner_max_width)
    private val mixWidth = view.resources.getDimensionPixelSize(R.dimen.feedback_banner_mix_width)
    private val easing = FastOutSlowInInterpolator()
    private val location = IntArray(2)

    private var loadingMessage: CharSequence? = null
    private var mixMessageShown = false
    private var hiding = false
    private val positionListener = ViewTreeObserver.OnPreDrawListener(::position)
    private val showLoadingMessage = Runnable {
        loadingMessage?.let { present(it, loading = true, mix = true) }
    }
    private val expireMessage = Runnable {
        val loading = loadingMessage
        if (loading == null) hide() else present(loading, loading = true, mix = true)
    }

    fun showLoading(message: CharSequence) {
        loadingMessage = message
        view.removeCallbacks(showLoadingMessage)
        view.removeCallbacks(expireMessage)
        // Fast requests can go straight to the result without flashing a spinner.
        view.postDelayed(showLoadingMessage, 150)
    }

    fun finishLoading(message: CharSequence) {
        loadingMessage = null
        showMessage(message, 3000, mix = true)
    }

    fun show(message: CharSequence, duration: Int) {
        showMessage(message, duration, mix = false)
    }

    private fun showMessage(message: CharSequence, duration: Int, mix: Boolean) {
        view.removeCallbacks(showLoadingMessage)
        view.removeCallbacks(expireMessage)
        present(message, loading = false, mix = mix)
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
        view.removeCallbacks(showLoadingMessage)
        view.removeCallbacks(expireMessage)
        conceal()
    }

    private fun present(message: CharSequence, loading: Boolean, mix: Boolean) {
        val entering = !view.isVisible
        val reveal = entering || hiding
        hiding = false
        val animate = motionEnabled()
        val content = binding.content
        content.animate().cancel()

        if (entering) {
            view.alpha = if (animate) 0f else 1f
            view.scaleX = if (animate) REST_SCALE else 1f
            view.scaleY = view.scaleX
            view.isVisible = true
            view.viewTreeObserver.addOnPreDrawListener(positionListener)
        }
        if (animate && reveal) {
            view.animate().cancel()
            view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(easing).start()
        } else if (!animate) {
            view.animate().cancel()
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
        }

        val changed = binding.message.text != message || binding.spinner.isVisible != loading || mixMessageShown != mix
        val updateContent = {
            mixMessageShown = mix
            binding.spinner.isVisible = loading
            binding.message.text = message
        }
        if (animate && !reveal && changed) {
            content.animate().alpha(0f).setDuration(75).setInterpolator(easing).withEndAction {
                updateContent()
                content.animate().alpha(1f).setDuration(125).setInterpolator(easing).start()
            }.start()
        } else {
            if (changed) updateContent()
            content.alpha = 1f
        }
    }

    private fun hide() {
        if (!motionEnabled()) {
            conceal()
            return
        }
        hiding = true
        view.animate().cancel()
        view.animate().alpha(0f).scaleX(REST_SCALE).scaleY(REST_SCALE).setDuration(140).setInterpolator(easing)
            .withEndAction(::conceal).start()
    }

    private fun conceal() {
        hiding = false
        view.animate().cancel()
        view.viewTreeObserver.removeOnPreDrawListener(positionListener)
        binding.content.animate().cancel()
        view.isVisible = false
    }

    private fun motionEnabled() = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

    private fun position(): Boolean {
        val host = parent
        host.getLocationInWindow(location)
        val hostX = location[0]
        val hostY = location[1]
        val sheetY = windowTop(playerSheet)
        val slide = if (playerSheet.isShown) playerBehavior.calculateSlideOffset().coerceIn(-1f, 1f) else -1f
        val navigationTop = if (navigation.isVisible) {
            windowTop(navigation) - hostY - navigation.translationY.roundToInt()
        } else {
            host.height
        }
        val collapsedTop = host.height - playerBehavior.peekHeight
        var center = host.width / 2
        var bottom = interpolate(navigationTop, collapsedTop, slide + 1f) - spacing
        var widthLimit = host.width - 2 * spacing

        if (slide > 0f) {
            val sheetOffset = sheetY - playerBehavior.expandedOffset
            val transport = playerView(R.id.exo_play_pause, sheetY)
            val queueButton = playerView(R.id.player_open_queue_button, sheetY)
            var expandedBottom = host.height - spacing
            if (transport != null) {
                val transportBottom = windowTop(transport) - sheetOffset + transport.height
                // The row has empty padding above its buttons. Use the actual touch bounds.
                val actionsTop = queueButton?.takeIf { (it.parent as? View)?.id == R.id.player_quick_action_view }
                    ?.let { windowTop(it) - sheetOffset } ?: transportBottom
                val gap = actionsTop - transportBottom
                if (gap >= view.height + spacing / 2) {
                    expandedBottom = transportBottom + (gap + view.height) / 2
                } else {
                    playerView(R.id.player_media_cover_view_pager, sheetY)?.let { artwork ->
                        artwork.getLocationInWindow(location)
                        center = interpolate(center, location[0] - hostX + artwork.width / 2, slide)
                        expandedBottom = location[1] - sheetOffset + artwork.height - spacing
                        if (slide == 1f) widthLimit = minOf(widthLimit, artwork.width - 2 * spacing)
                    }
                }
            } else {
                playerView(R.id.player_shuffle_queue_fab, sheetY)?.let {
                    expandedBottom = windowTop(it) - sheetOffset - spacing
                }
            }
            bottom = interpolate(bottom, expandedBottom, slide)
        }

        widthLimit = minOf(widthLimit, if (mixMessageShown) mixWidth else maxWidth).coerceAtLeast(1)
        val minimumWidth = if (mixMessageShown) widthLimit else 0
        val spinnerWidth = if (binding.spinner.isVisible) binding.spinner.indicatorSize + binding.spinner.marginEnd else 0
        val textWidth = (widthLimit - view.paddingStart - view.paddingEnd - spinnerWidth).coerceAtLeast(1)
        var needsLayout = false
        if (view.minimumWidth != minimumWidth) {
            view.minimumWidth = minimumWidth
            needsLayout = true
        }
        if (binding.message.maxWidth != textWidth) {
            binding.message.maxWidth = textWidth
            needsLayout = true
        }
        if (needsLayout) return false

        val left = (center - view.width / 2).coerceIn(spacing, maxOf(spacing, host.width - view.width - spacing))
        val top = (bottom - view.height).coerceIn(spacing, maxOf(spacing, host.height - view.height - spacing))
        if (left != view.left) view.offsetLeftAndRight(left - view.left)
        if (top != view.top) view.offsetTopAndBottom(top - view.top)
        return true
    }

    private fun windowTop(child: View): Int {
        child.getLocationInWindow(location)
        return location[1]
    }

    private fun playerView(id: Int, sheetY: Int): View? = playerSheet.findViewById<View>(id)?.takeIf {
        // Read bounds inside the moving sheet, even when the window clips it during a drag.
        it.isShown && it.height > 0 && windowTop(it) - sheetY + it.height / 2 in 0 until playerSheet.height
    }

    private fun interpolate(start: Int, end: Int, fraction: Float) =
        start + ((end - start) * fraction.coerceIn(0f, 1f)).roundToInt()

    private companion object {
        const val REST_SCALE = 0.97f
    }
}
