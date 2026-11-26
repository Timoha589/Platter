package com.cappielloantonio.tempo.helper.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Region
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
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
    private val visibleBounds = Rect()
    private val gradientMatrix = Matrix()
    private val beatUpcoming = LinearGradient(0f, 0f, 1f, 0f, BEAT_UPCOMING, null, Shader.TileMode.CLAMP)
    private val beatPlayed = LinearGradient(0f, 0f, 1f, 0f, BEAT_PLAYED, null, Shader.TileMode.CLAMP)
    private val beatGlint = LinearGradient(0f, 0f, 1f, 0f, GLINT_COLORS, GLINT_STOPS, Shader.TileMode.CLAMP)
    private val revealEasing = LinearOutSlowInInterpolator()
    private val density = resources.displayMetrics.density
    private val barHeight: Int
    private val bottomAligned: Boolean
    private val enabledThumbSize: Int
    private val disabledThumbSize: Int
    private val draggedThumbSize: Int
    private val thumbPadding: Int

    private var segments = emptyList<AutoMixTimeline.Segment>()
    private var mediaIndex = -1
    private var mediaId: String? = null
    private var fallbackDurationMs = 0L
    private val revealStarts = LongArray(AutoMixTimeline.Direction.values().size) { -1L }
    private var playing = false
    private var glintStart = -1L
    private var scrubberHidden = false
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

    fun showMixSegments(index: Int, mediaId: String?, fallbackDurationMs: Long, segments: List<AutoMixTimeline.Segment>) {
        val newTrack = mediaIndex != index || this.mediaId != mediaId
        if (newTrack) {
            mediaIndex = index
            this.mediaId = mediaId
            revealStarts.fill(-1L)
            glintStart = -1L
        }
        if (!newTrack && this.segments == segments && this.fallbackDurationMs == fallbackDurationMs) return
        this.fallbackDurationMs = fallbackDurationMs
        this.segments = segments
        updateScrubber()
        invalidate()
    }

    fun setMixPlaybackActive(playing: Boolean) {
        if (this.playing == playing) return
        this.playing = playing
        glintStart = -1L
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
        updateScrubber()
    }

    override fun onScrubMove(timeBar: TimeBar, position: Long) {
        scrubMs = position
    }

    override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
        scrubMs = null
        updateScrubber()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        updateScrubber()
    }

    private fun updateScrubber() {
        // An idle thumb can cover a short incoming highlight entirely. Keep it during touch and keyboard input.
        val hide = segments.isNotEmpty() && scrubMs == null && !isFocused
        if (scrubberHidden == hide) return
        scrubberHidden = hide
        if (hide) hideScrubber(false) else showScrubber()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val duration = durationMs.takeIf { it > 0 } ?: fallbackDurationMs
        if (segments.isEmpty() || duration <= 0) return

        // These bounds match the seekable part of DefaultTimeBar, including its inset for the thumb.
        val left = paddingLeft + thumbPadding
        val barWidth = width - paddingRight - thumbPadding - left
        if (barWidth <= 0) return
        val top = if (bottomAligned) height - paddingBottom - barHeight - (thumbPadding - barHeight / 2).coerceAtLeast(0)
            else (height - barHeight) / 2
        val center = top + barHeight / 2f
        fun xAt(ms: Long) = left + barWidth * (ms.coerceIn(0L, duration).toFloat() / duration)
        val position = scrubMs ?: positionMs
        val playhead = xAt(position)
        val now = SystemClock.uptimeMillis()
        val animate = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()
        val hasGlint = animate && playing && segments.any {
            it.mode == AutoMixTimeline.Mode.BEAT_MIX && position < minOf(it.endMs, duration)
        }
        if (hasGlint && glintStart < 0) glintStart = now
        if (!hasGlint) glintStart = -1L
        val glintMs = if (hasGlint) (now - glintStart) % GLINT_CYCLE_MS else GLINT_SWEEP_MS
        val sweep = glintMs.toFloat() / GLINT_SWEEP_MS
        var revealing = false
        val save = canvas.save()
        if (!scrubberHidden) clipOutThumb(canvas, playhead, center)
        for (segment in segments) {
            val start = xAt(segment.startMs)
            val end = xAt(segment.endMs)
            if (end <= start) continue
            val playedEnd = minOf(playhead, end)
            val key = segment.direction.ordinal
            if (revealStarts[key] < 0) revealStarts[key] = now
            val reveal = if (animate) ((now - revealStarts[key]).toFloat() / REVEAL_MS).coerceAtMost(1f) else 1f
            if (reveal < 1f) {
                revealing = true
                drawSegment(canvas, segment.mode, start, end, playedEnd, center, 0.32f, 0f)
                val lit = canvas.save()
                canvas.clipRect(start, 0f, start + (end - start) * revealEasing.getInterpolation(reveal), height.toFloat())
                drawSegment(canvas, segment.mode, start, end, playedEnd, center, 1f, 0f)
                canvas.restoreToCount(lit)
            } else {
                drawSegment(canvas, segment.mode, start, end, playedEnd, center, 1f, sweep)
            }
        }
        canvas.restoreToCount(save)
        if ((revealing || hasGlint) && getLocalVisibleRect(visibleBounds)) {
            if (revealing || glintMs < GLINT_SWEEP_MS) postInvalidateOnAnimation()
            else postInvalidateDelayed(GLINT_CYCLE_MS - glintMs)
        }
    }

    private fun drawSegment(
        canvas: Canvas, mode: AutoMixTimeline.Mode, start: Float, end: Float, playedEnd: Float,
        center: Float, alpha: Float, sweep: Float,
    ) {
        val opacity = (255 * alpha).roundToInt()
        when (mode) {
            AutoMixTimeline.Mode.BEAT_MIX -> drawBeatMix(canvas, start, end, playedEnd, center, opacity, sweep)
            AutoMixTimeline.Mode.FALLBACK_CROSSFADE -> drawCrossfade(canvas, start, end, playedEnd, center, opacity)
        }
    }

    private fun drawBeatMix(canvas: Canvas, start: Float, end: Float, playedEnd: Float, center: Float, alpha: Int, sweep: Float) {
        val halfHeight = px(6) / 2f
        val radius = minOf(halfHeight, (end - start) / 2f)
        val top = center - halfHeight
        val bottom = center + halfHeight
        gradientMatrix.setScale(end - start, 1f)
        gradientMatrix.postTranslate(start, 0f)
        paint.color = android.graphics.Color.WHITE
        paint.alpha = alpha
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
        if (playedEnd < end && sweep > 0f && sweep < 1f) {
            val halfWidth = ((end - start) * 0.30f).coerceIn(px(4).toFloat(), px(20).toFloat())
            val reflection = start - halfWidth + (end - start + 2 * halfWidth) * sweep
            gradientMatrix.setScale(2 * halfWidth, 1f)
            gradientMatrix.postSkew(-0.65f, 0f, 0f, center)
            gradientMatrix.postTranslate(reflection - halfWidth, 0f)
            beatGlint.setLocalMatrix(gradientMatrix)
            paint.shader = beatGlint
            canvas.drawRoundRect(start, top, end, bottom, radius, radius, paint)
        }
        paint.shader = null
    }

    private fun drawCrossfade(canvas: Canvas, start: Float, end: Float, playedEnd: Float, center: Float, alpha: Int) {
        val halfHeight = px(4) / 2f
        val top = center - halfHeight
        val bottom = center + halfHeight
        paint.color = CROSSFADE_UPCOMING
        paint.alpha = alpha
        canvas.drawRect(start, top, end, bottom, paint)
        if (playedEnd > start) {
            paint.color = CROSSFADE_PLAYED
            paint.alpha = alpha
            canvas.drawRect(start, top, playedEnd, bottom, paint)
        } else {
            paint.color = CROSSFADE_START
            paint.alpha = alpha
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
        const val REVEAL_MS = 300L
        const val GLINT_SWEEP_MS = 2_200L
        const val GLINT_CYCLE_MS = 3_400L
        val CROSSFADE_UPCOMING = 0xFF6B82A6.toInt()
        val CROSSFADE_PLAYED = 0xFF579DFF.toInt()
        val CROSSFADE_START = 0xFFB4CCEF.toInt()
        val BEAT_UPCOMING = intArrayOf(0xFFD83ECC.toInt(), 0xFF8D54F4.toInt(), 0xFF28C6DE.toInt(), 0xFF3DE5A2.toInt())
        val BEAT_PLAYED = intArrayOf(0xFFFF6CDE.toInt(), 0xFFAE7BFF.toInt(), 0xFF50ECFF.toInt(), 0xFF83FFBE.toInt())
        val GLINT_COLORS = intArrayOf(0x00E6FFFA, 0x29E6FFFA, 0x7DE6FFFA, 0x38E6FFFA, 0x00E6FFFA)
        val GLINT_STOPS = floatArrayOf(0f, 0.35f, 0.55f, 0.72f, 1f)
    }
}
