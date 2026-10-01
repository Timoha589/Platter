package com.cappielloantonio.tempo.helper.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import com.cappielloantonio.tempo.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * What a heart does when it is tapped.
 *
 * Liking is the one tap on a track that says something about the listener, so
 * it answers: the heart squeezes and springs back past its size, and a ring
 * with a few sparks goes out from it and fades. Taking a like back is quieter -
 * the heart only dips and settles - because undoing is not an occasion.
 *
 * Played only for a tap. A heart that fills because the track was liked from
 * the notification or the rating dialog just changes, since nothing here was
 * done by the finger on it.
 */
object LikeAnimation {
    private const val POP_MS = 450L
    private const val DIP_MS = 200L
    private const val BURST_MS = 500L
    private const val SPARKS = 6

    @JvmStatic
    fun play(heart: View, liked: Boolean) {
        heart.animate().cancel()

        if (liked) {
            heart.scaleX = 0.6f
            heart.scaleY = 0.6f
            heart.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(POP_MS)
                    .setInterpolator(OvershootInterpolator(4f))
                    .start()
            burst(heart)
        } else {
            heart.scaleX = 0.85f
            heart.scaleY = 0.85f
            heart.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(DIP_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
        }
    }

    /**
     * The ring and sparks are drawn on the parent's overlay: they reach past
     * the button, and nothing is added to the layout to hold them.
     */
    private fun burst(heart: View) {
        val parent = heart.parent as? ViewGroup ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) return
        if (heart.width == 0 || heart.height == 0) return

        // The heart can sit inset inside a bigger touch target (the player's).
        val background = heart.background
        val drawn = (background as? InsetDrawable)?.drawable?.bounds ?: background?.bounds
        val heartRadius = if (drawn != null && !drawn.isEmpty) {
            min(drawn.width(), drawn.height()) / 2f
        } else {
            min(heart.width, heart.height) / 2f
        }

        val burst = Burst(
                color = ContextCompat.getColor(heart.context, R.color.titleTextColor),
                heartRadius = heartRadius,
                density = heart.resources.displayMetrics.density)
        val cx = (heart.left + heart.width / 2f + heart.translationX).toInt()
        val cy = (heart.top + heart.height / 2f + heart.translationY).toInt()
        val reach = (heartRadius * 3f).toInt()
        burst.setBounds(cx - reach, cy - reach, cx + reach, cy + reach)
        parent.overlay.add(burst)

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BURST_MS
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                burst.progress = it.animatedValue as Float
                burst.invalidateSelf()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    parent.overlay.remove(burst)
                }
            })
            start()
        }
    }

    private class Burst(color: Int, private val heartRadius: Float, private val density: Float) : Drawable() {
        var progress = 0f

        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.color = color
        }
        private val spark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
        }

        override fun draw(canvas: Canvas) {
            val t = progress
            val fade = 1f - t
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()

            // The ring leaves from just outside the heart, thins as it goes and is
            // mostly gone by halfway, so it reads as a flash rather than a halo.
            ring.strokeWidth = density * 2.5f * fade
            ring.alpha = (170 * fade * fade * fade).toInt()
            if (ring.strokeWidth > 0.1f) {
                canvas.drawCircle(cx, cy, heartRadius * (1f + 1.2f * t), ring)
            }

            // The sparks run a little ahead of it, and go out a little sooner.
            val sparkFade = (1f - t * 1.2f).coerceAtLeast(0f)
            spark.alpha = (255 * sparkFade).toInt()
            val distance = heartRadius * (1.2f + 1.6f * t)
            val size = density * 2f * sparkFade
            if (size > 0.1f) {
                for (i in 0 until SPARKS) {
                    // Offset by half a step, so none flies straight up out of the dip.
                    val angle = 2 * PI * (i + 0.5) / SPARKS
                    canvas.drawCircle(
                            cx + distance * cos(angle).toFloat(),
                            cy + distance * sin(angle).toFloat(),
                            size, spark)
                }
            }
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
