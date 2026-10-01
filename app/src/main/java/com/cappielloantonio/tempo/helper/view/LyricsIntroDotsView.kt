package com.cappielloantonio.tempo.helper.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.cappielloantonio.tempo.R
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * What a timed lyric shows before its first line: three hopping dots while the
 * intro plays, then a 3, 2, 1 over its last three seconds, so the first words
 * do not arrive unannounced.
 *
 * The count is kept here, frame by frame, from the time left when it was last
 * told and how long ago that was. The player's clock is only read four times a
 * second, which is fine for choosing a line but would let a digit change up to
 * a quarter of a second late.
 *
 * While paused the hop settles and the count holds; a still view draws nothing
 * and costs no frames.
 */
class LyricsIntroDotsView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private val radius = 7f * density
    private val step = 26f * density
    private val hop = 7f * density

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.lyricsTextColor)
    }

    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.lyricsTextColor)
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.inter_bold)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 32f, resources.displayMetrics)
    }

    /** Time left before the first line when last told, and when that was. */
    private var remaining = Long.MAX_VALUE
    private var toldAt = 0L

    private var playing = false

    /** How high the hop is right now: eases to full while playing and to nothing when paused. */
    private var amplitude = 0f

    /** The digit on screen, 0 for the dots, and when it appeared - each one pops in. */
    private var shownDigit = 0
    private var digitSince = 0L

    private var lastFrame = 0L

    /** How long until the first line, and whether that time is running down. */
    fun setTimeLeft(millis: Long, isPlaying: Boolean) {
        remaining = millis
        toldAt = SystemClock.uptimeMillis()
        playing = isPlaying

        animateIfNeeded()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val dots = 2 * radius + 2 * hop
        val digit = digitPaint.fontMetrics.let { it.descent - it.ascent }
        val height = paddingTop + paddingBottom + max(dots, digit).toInt()

        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animateIfNeeded()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val elapsed = if (lastFrame == 0L) 16L else min(now - lastFrame, 100L)
        lastFrame = now

        amplitude += ((if (playing) 1f else 0f) - amplitude) * min(1f, elapsed / EASE_MS)

        val digit = digitAt(now)
        if (digit != shownDigit) {
            shownDigit = digit
            digitSince = now
        }

        val centreX = width / 2f
        val centreY = paddingTop + (height - paddingTop - paddingBottom) / 2f

        when {
            digit > 0 -> drawDigit(canvas, digit, centreX, centreY, now)
            digit == 0 -> drawDots(canvas, centreX, centreY, now)
            // Past zero: the first line is about to light up, and dots coming
            // back for that moment would read as the count starting over.
        }

        if (isAnimating(now)) postInvalidateOnAnimation() else lastFrame = 0L
    }

    private fun drawDots(canvas: Canvas, centreX: Float, centreY: Float, now: Long) {
        val baseline = centreY + hop / 2f

        for (index in 0 until DOTS) {
            // A hop is the upper half of a sine; the lower half is the dot at rest.
            val phase = ((now % HOP_PERIOD_MS) / HOP_PERIOD_MS.toFloat() - index * HOP_STAGGER) * 2 * PI
            val lift = max(0.0, sin(phase)).toFloat() * hop * amplitude

            canvas.drawCircle(centreX + (index - (DOTS - 1) / 2f) * step, baseline - lift, radius, dotPaint)
        }
    }

    private fun drawDigit(canvas: Canvas, digit: Int, centreX: Float, centreY: Float, now: Long) {
        // Each number pops in: up from a little smaller, and from nothing.
        val t = min(1f, (now - digitSince) / POP_MS)
        val eased = 1f - (1f - t) * (1f - t)
        val scale = 0.7f + 0.3f * eased

        digitPaint.alpha = (255 * eased).toInt()

        val metrics = digitPaint.fontMetrics
        val baseline = centreY - (metrics.ascent + metrics.descent) / 2f

        canvas.save()
        canvas.scale(scale, scale, centreX, centreY)
        canvas.drawText(digit.toString(), centreX, baseline, digitPaint)
        canvas.restore()
    }

    /** 3, 2 or 1 in the last three seconds before the words; 0 means dots, -1 nothing. */
    private fun digitAt(now: Long): Int {
        if (remaining == Long.MAX_VALUE) return 0

        val left = if (playing) remaining - (now - toldAt) else remaining
        if (left <= 0) return -1
        if (left > COUNTDOWN_MS) return 0

        return ceil(left / 1000.0).toInt()
    }

    private fun isAnimating(now: Long): Boolean {
        if (!isAttachedToWindow) return false

        val hopping = playing || amplitude > 0.01f
        val popping = shownDigit > 0 && now - digitSince < POP_MS

        return hopping || popping
    }

    private fun animateIfNeeded() {
        if (isAnimating(SystemClock.uptimeMillis())) postInvalidateOnAnimation()
    }

    private companion object {
        const val DOTS = 3
        const val HOP_PERIOD_MS = 1200L
        const val HOP_STAGGER = 0.15f
        const val EASE_MS = 180f
        const val POP_MS = 220f
        const val COUNTDOWN_MS = 3000L
    }
}
