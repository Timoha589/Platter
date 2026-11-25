package com.cappielloantonio.tempo.helper.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import com.cappielloantonio.tempo.R

/** A moving highlight across the status text while a beat mix is audible. */
class AutoMixLabel(context: Context, attrs: AttributeSet?) : AppCompatTextView(context, attrs) {
    private val matrix = Matrix()
    private val mist = ContextCompat.getColor(context, R.color.mist)
    private val white = ContextCompat.getColor(context, R.color.pure_white)
    private val highlight = LinearGradient(-0.25f, 0f, 0.25f, 0f, intArrayOf(mist, white, mist), null, Shader.TileMode.CLAMP)
    private var sweep = -0.3f
    private val animation = ValueAnimator.ofFloat(-0.3f, 1.3f).apply {
        duration = 1_900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            sweep = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled())) {
            if (!animation.isStarted) animation.start()
        } else {
            animation.cancel()
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        animation.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val textLayout = layout
        if (animation.isRunning && textLayout != null && textLayout.lineCount > 0) {
            val width = textLayout.getLineRight(0) - textLayout.getLineLeft(0)
            matrix.setScale(width.coerceAtLeast(1f), 1f)
            matrix.postTranslate(textLayout.getLineLeft(0) + width * sweep, 0f)
            highlight.setLocalMatrix(matrix)
            paint.shader = highlight
        }
        super.onDraw(canvas)
        paint.shader = null
    }
}
