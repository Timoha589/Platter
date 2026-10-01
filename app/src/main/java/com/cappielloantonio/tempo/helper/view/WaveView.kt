package com.cappielloantonio.tempo.helper.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.cappielloantonio.tempo.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The waves on the "My Wave" card - drawn from what is playing, not looped.
 *
 * Every movement stands for something:
 *  - idle, the water barely moves: nothing is playing, a tap starts it;
 *  - while the first batch is being fetched it swells - the answer to the tap;
 *  - playing, it travels at the track's own tempo (one crest every two
 *    beats), stands as high as the track's energy, and lifts on every beat;
 *  - paused, it slows to a standstill and stays there, the way the music did.
 * Changes between these ease rather than jump, over about a third of a second.
 *
 * It is drawn as light rather than paint. Each layer is a sheet of the promo
 * gradient that is brightest along its crest and thins out towards the floor
 * of the card, and the layers add up where they cross (screen blending), so
 * the water reads as depth instead of a flat band. The surface is the tempo
 * wave with a smaller one running against it, which keeps it from looking
 * like a sine. Under it all a glow, as bright as the water is high, flares on
 * every beat - the same beat that lifts the waves.
 *
 * Only animates while it can be seen, and not at all with animations turned
 * off in the system settings - then it is drawn once, still.
 */
class WaveView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null
) : View(context, attrs) {

    private enum class Mode { IDLE, LOADING, PLAYING, PAUSED }

    private var mode = Mode.IDLE
    private var bpm = 0.0

    // Height of the water (0..1 of the card's lower half) and travel speed in rad/s.
    private var amplitude = IDLE_AMPLITUDE
    private var targetAmplitude = IDLE_AMPLITUDE
    private var speed = IDLE_SPEED
    private var targetSpeed = IDLE_SPEED

    private var phase = 0.0
    private var beatPhase = 0.0
    private var lastFrame = 0L
    private var shown = false

    private val density = resources.displayMetrics.density

    private val fillPaths = Array(LAYERS) { Path() }
    private val crestPaths = Array(LAYERS) { Path() }
    private val fills = Array(LAYERS) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            // Light over the dark card: where two layers cross they brighten.
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        }
    }
    private val crests = Array(LAYERS) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density * (if (it == 0) 1.5f else 1f)
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            alpha = CREST_ALPHAS[it]
        }
    }
    // A soft halo along the front crest, the line the eye follows.
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 7f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        alpha = 40
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

    fun showIdle() = setMode(Mode.IDLE, IDLE_AMPLITUDE, IDLE_SPEED)

    fun showLoading() = setMode(Mode.LOADING, LOADING_AMPLITUDE, LOADING_SPEED)

    /**
     * @param tempo  beats per minute of the track, null when the server did not know it
     * @param energy the track's energy among the listener's likes, 0..1
     */
    fun showPlaying(tempo: Double?, energy: Double?) {
        bpm = tempo?.takeIf { it in 40.0..220.0 } ?: DEFAULT_BPM
        val level = (energy ?: 0.5).coerceIn(0.0, 1.0).toFloat()
        // One crest every two beats.
        setMode(Mode.PLAYING, 0.30f + 0.55f * level, (2 * PI * bpm / 120.0).toFloat())
    }

    fun showPaused() = setMode(Mode.PAUSED, targetAmplitude, 0f)

    private fun setMode(newMode: Mode, amplitude: Float, speed: Float) {
        mode = newMode
        targetAmplitude = amplitude
        targetSpeed = speed
        if (!animationsOn()) {
            this.amplitude = amplitude
            this.speed = speed
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val width = w.toFloat()
        val height = h.toFloat()
        val start = ContextCompat.getColor(context, R.color.magenta_glow)
        val end = ContextCompat.getColor(context, R.color.promo_blue)

        for (i in 0 until LAYERS) {
            val depth = i / (LAYERS - 1f)
            // The further back, the further its colours are carried to the left,
            // so the layers do not line up hue for hue.
            val shift = width * 0.3f * depth
            val across = LinearGradient(-shift, 0f, width - shift, 0f,
                    withAlpha(start, FILL_ALPHAS[i]), withAlpha(end, FILL_ALPHAS[i]), Shader.TileMode.CLAMP)

            fills[i].shader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Brightest at the crest, gone by the floor of the card. Two
                // gradients in one shader need the renderer Android 9 brought in.
                val top = baseline(height, depth) - height * 0.24f
                val down = LinearGradient(0f, top, 0f, height,
                        Color.WHITE, withAlpha(Color.WHITE, 0), Shader.TileMode.CLAMP)
                ComposeShader(across, down, PorterDuff.Mode.DST_IN)
            } else {
                across
            }

            crests[i].shader = LinearGradient(-shift, 0f, width - shift, 0f,
                    start, end, Shader.TileMode.CLAMP)
        }
        halo.shader = crests[0].shader

        val middle = ColorUtils.blendARGB(start, end, 0.5f)
        glow.shader = RadialGradient(width * 0.45f, height * 1.1f, max(width, 1f) * 0.8f,
                intArrayOf(middle, withAlpha(middle, 0)), null, Shader.TileMode.CLAMP)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shown = isVisible
        lastFrame = 0L
        if (isVisible) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val animate = shown && animationsOn()
        if (animate) step()

        // The beat: a quick lift, then back to rest before the next.
        val beat = if (mode == Mode.PLAYING && animate) {
            max(0.0, cos(beatPhase)).let { (it * it * it).toFloat() }
        } else 0f
        val pulse = 1f + 0.12f * beat

        glow.alpha = (255 * min(1f, 0.16f + 0.40f * amplitude + 0.45f * amplitude * beat)).toInt()
        canvas.drawRect(0f, 0f, w, h, glow)

        for (layer in LAYERS - 1 downTo 0) {
            val depth = layer / (LAYERS - 1f)                 // 0 front .. 1 back
            val wavelength = w * (0.95f + 0.55f * depth)
            // Kept below the title and subtitle even at full energy on a beat.
            val height = amplitude * h * 0.24f * (1f - 0.25f * depth) * pulse
            val baseline = baseline(h, depth)
            val layerPhase = phase * (1.0 - 0.2 * depth) + layer * 1.7

            val fill = fillPaths[layer]
            val crest = crestPaths[layer]
            fill.reset()
            crest.reset()
            fill.moveTo(0f, h)

            var x = 0f
            while (x <= w + STEP) {
                val y = surface(x, w, wavelength, height, baseline, layerPhase, layer)
                fill.lineTo(x, y)
                if (x == 0f) crest.moveTo(x, y) else crest.lineTo(x, y)
                x += STEP
            }
            fill.lineTo(w, h)
            fill.close()

            canvas.drawPath(fill, fills[layer])
            if (layer == 0) canvas.drawPath(crest, halo)
            canvas.drawPath(crest, crests[layer])
        }

        if (animate && isMoving()) postInvalidateOnAnimation()
    }

    /**
     * The tempo wave, with a smaller one at about twice the frequency running
     * the other way under it. Tapered at the edges so the water meets the
     * card's corners calmly.
     */
    private fun surface(x: Float, w: Float, wavelength: Float, height: Float, baseline: Float,
                        layerPhase: Double, layer: Int): Float {
        val taper = 0.55f + 0.45f * sin(PI * min(x, w) / w).toFloat()
        val k = 2 * PI * x / wavelength
        val swell = 0.72 * sin(k + layerPhase) + 0.28 * sin(2.1 * k - 0.7 * layerPhase + layer * 2.3)
        return baseline - height * taper * swell.toFloat()
    }

    // The back layers stand higher, so they show over the ones in front.
    private fun baseline(h: Float, depth: Float) = h * (0.78f - 0.06f * depth)

    private fun step() {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.0 else min(0.05, (now - lastFrame) / 1000.0)
        lastFrame = now

        val ease = min(1.0, dt * EASE_PER_SECOND).toFloat()
        amplitude += (targetAmplitude - amplitude) * ease
        speed += (targetSpeed - speed) * ease
        // Wrapped at a multiple of every period drawn from it - each layer's
        // rate (1 - 0.2 * depth over thirds) and the undertow's 0.7 of that
        // come to fifteenths and hundred-fiftieths - so nothing ever jumps.
        phase = (phase + speed * dt) % (300 * PI * 100)
        if (mode == Mode.PLAYING) beatPhase = (beatPhase + 2 * PI * bpm / 60.0 * dt) % (2 * PI)
    }

    private fun isMoving(): Boolean =
            mode != Mode.PAUSED || abs(speed) > 0.01f || abs(amplitude - targetAmplitude) > 0.002f

    private fun animationsOn(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    private companion object {
        const val LAYERS = 4
        const val STEP = 6f
        const val DEFAULT_BPM = 110.0
        const val EASE_PER_SECOND = 3.0
        const val IDLE_AMPLITUDE = 0.36f
        const val IDLE_SPEED = 0.5f
        const val LOADING_AMPLITUDE = 0.62f
        const val LOADING_SPEED = 3.2f

        // Front to back: the nearer the layer, the more light it holds.
        val FILL_ALPHAS = intArrayOf(190, 110, 70, 50)
        val CREST_ALPHAS = intArrayOf(255, 130, 90, 60)
    }
}
