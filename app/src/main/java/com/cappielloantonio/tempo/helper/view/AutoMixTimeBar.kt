package com.cappielloantonio.tempo.helper.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Region
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.R.styleable
import androidx.media3.ui.TimeBar
import com.cappielloantonio.tempo.service.AutoMixTimeline
import kotlin.math.roundToInt

/** Keeps Media3's scrubbing and draws the desktop player's AutoMix and fallback highlights. */
@UnstableApi
class AutoMixTimeBar(context: Context, attrs: AttributeSet?) :
    DefaultTimeBar(context, attrs), TimeBar.OnScrubListener {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Path()
    private val gradientMatrix = Matrix()
    private val beatUpcoming = LinearGradient(0f, 0f, 1f, 0f, BEAT_UPCOMING, null, Shader.TileMode.CLAMP)
    private val beatPlayed = LinearGradient(0f, 0f, 1f, 0f, BEAT_PLAYED, null, Shader.TileMode.CLAMP)
    private val density = resources.displayMetrics.density
    private val barHeight: Int
    private val bottomAligned: Boolean
    private val enabledThumbSize: Int
    private val disabledThumbSize: Int
    private val draggedThumbSize: Int
    private val thumbPadding: Int

    private var segments = emptyList<AutoMixTimeline.Segment>()
    private var positionMs = 0L
    private var durationMs = 0L
    private var scrubMs: Long? = null

    init {
        val style = context.obtainStyledAttributes(attrs, styleable.DefaultTimeBar)
        try {
            barHeight = style.getDimensionPixelSize(styleable.DefaultTimeBar_bar_height, px(DEFAULT_BAR_HEIGHT_DP))
            bottomAligned = style.getInt(styleable.DefaultTimeBar_bar_gravity, BAR_GRAVITY_CENTER) == BAR_GRAVITY_BOTTOM
            enabledThumbSize = style.getDimensionPixelSize(styleable.DefaultTimeBar_scrubber_enabled_size, px(DEFAULT_SCRUBBER_ENABLED_SIZE_DP))
            disabledThumbSize = style.getDimensionPixelSize(styleable.DefaultTimeBar_scrubber_disabled_size, px(DEFAULT_SCRUBBER_DISABLED_SIZE_DP))
            draggedThumbSize = style.getDimensionPixelSize(styleable.DefaultTimeBar_scrubber_dragged_size, px(DEFAULT_SCRUBBER_DRAGGED_SIZE_DP))
        } finally {
            style.recycle()
        }
        thumbPadding = (maxOf(enabledThumbSize, disabledThumbSize, draggedThumbSize) + 1) / 2
        addListener(this)
    }

    fun showMixSegments(segments: List<AutoMixTimeline.Segment>) {
        if (this.segments == segments) return
        this.segments = segments
        invalidate()
    }

    override fun setPosition(position: Long) {
        positionMs = position
        super.setPosition(position)
    }

    override fun setDuration(duration: Long) {
        durationMs = duration
        super.setDuration(duration)
    }

    override fun onScrubStart(timeBar: TimeBar, position: Long) {
        scrubMs = position
    }

    override fun onScrubMove(timeBar: TimeBar, position: Long) {
        scrubMs = position
    }

    override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
        scrubMs = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (segments.isEmpty() || durationMs <= 0) return

        // These bounds match the seekable part of DefaultTimeBar, including its inset for the thumb.
        val left = paddingLeft + thumbPadding
        val barWidth = width - paddingRight - thumbPadding - left
        if (barWidth <= 0) return
        val top = if (bottomAligned) height - paddingBottom - barHeight - (thumbPadding - barHeight / 2).coerceAtLeast(0)
            else (height - barHeight) / 2
        val bottom = top + barHeight
        fun xAt(ms: Long) = left + (ms.coerceIn(0L, durationMs) * barWidth / durationMs).toFloat()
        val playhead = xAt(scrubMs ?: positionMs)
        val save = canvas.save()
        clipOutThumb(canvas, playhead, ((top + bottom) / 2).toFloat())
        for (segment in segments) {
            val start = xAt(segment.startMs)
            val end = xAt(segment.endMs)
            if (end <= start) continue
            val playedEnd = minOf(playhead, end)
            when (segment.mode) {
                AutoMixTimeline.Mode.BEAT_MIX -> drawBeatMix(canvas, start, end, playedEnd, (top + bottom) / 2f)
                AutoMixTimeline.Mode.FALLBACK_CROSSFADE -> drawCrossfade(canvas, start, end, playedEnd, top.toFloat(), bottom.toFloat())
            }
        }
        canvas.restoreToCount(save)
    }

    private fun drawBeatMix(canvas: Canvas, start: Float, end: Float, playedEnd: Float, center: Float) {
        val halfHeight = px(6) / 2f
        val radius = minOf(halfHeight, (end - start) / 2f)
        val top = center - halfHeight
        val bottom = center + halfHeight
        gradientMatrix.setScale(end - start, 1f)
        gradientMatrix.postTranslate(start, 0f)
        paint.color = android.graphics.Color.WHITE
        if (playedEnd < end) {
            beatUpcoming.setLocalMatrix(gradientMatrix)
            paint.shader = beatUpcoming
            canvas.drawRoundRect(start, top, end, bottom, radius, radius, paint)
        }
        if (playedEnd > start) {
            beatPlayed.setLocalMatrix(gradientMatrix)
            paint.shader = beatPlayed
            val save = canvas.save()
            canvas.clipRect(start, top, playedEnd, bottom)
            canvas.drawRoundRect(start, top, end, bottom, radius, radius, paint)
            canvas.restoreToCount(save)
        }
        paint.shader = null
    }

    private fun drawCrossfade(canvas: Canvas, start: Float, end: Float, playedEnd: Float, top: Float, bottom: Float) {
        paint.color = CROSSFADE_UPCOMING
        canvas.drawRect(start, top, end, bottom, paint)
        if (playedEnd > start) {
            paint.color = CROSSFADE_PLAYED
            canvas.drawRect(start, top, playedEnd, bottom, paint)
        } else {
            paint.color = CROSSFADE_START
            canvas.drawRect(start, top, minOf(start + px(1), end), bottom, paint)
        }
    }

    @Suppress("DEPRECATION")
    private fun clipOutThumb(canvas: Canvas, x: Float, y: Float) {
        val diameter = when {
            scrubMs != null || isFocused -> draggedThumbSize
            isEnabled -> enabledThumbSize
            else -> disabledThumbSize
        }
        if (diameter < 2) return
        thumb.rewind()
        thumb.addCircle(x, y, (diameter / 2).toFloat(), Path.Direction.CW)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) canvas.clipOutPath(thumb)
        else canvas.clipPath(thumb, Region.Op.DIFFERENCE)
    }

    private fun px(dp: Int) = (dp * density).roundToInt()

    private companion object {
        val CROSSFADE_UPCOMING = 0xFF6B82A6.toInt()
        val CROSSFADE_PLAYED = 0xFF579DFF.toInt()
        val CROSSFADE_START = 0xFFB4CCEF.toInt()
        val BEAT_UPCOMING = intArrayOf(0xFFD83ECC.toInt(), 0xFF8D54F4.toInt(), 0xFF28C6DE.toInt(), 0xFF3DE5A2.toInt())
        val BEAT_PLAYED = intArrayOf(0xFFFF6CDE.toInt(), 0xFFAE7BFF.toInt(), 0xFF50ECFF.toInt(), 0xFF83FFBE.toInt())
    }
}
