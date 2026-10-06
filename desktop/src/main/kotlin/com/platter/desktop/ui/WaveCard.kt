package com.platter.desktop.ui

import com.platter.desktop.i18n.t
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.platter.desktop.AppController
import androidx.compose.runtime.withFrameNanos
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class WaveMode { Idle, Loading, Playing, Paused }

/**
 * The My Wave hero card of the home screen. It says what the wave is doing,
 * and nothing else: idle while the player is on something else, the vibe while
 * it plays, still while it is paused. The green button is the play action
 * DESIGN.md reserves green for.
 */
@Composable
fun WaveCard(app: AppController) {
    val player by app.player.state.collectAsState()
    val vibe by app.waveState.vibe.collectAsState()

    val current = player.current
    val onWave = app.waveState.isWaveTrack(current?.id)
    val mode = when {
        app.waveStarting -> WaveMode.Loading
        !onWave -> WaveMode.Idle
        player.isPlaying -> WaveMode.Playing
        else -> WaveMode.Paused
    }
    val info = app.waveState.info(current?.id)
    val subtitle = when (mode) {
        WaveMode.Loading -> t("Tuning in to your wave…")
        WaveMode.Idle -> t("An endless stream shaped by your taste")
        WaveMode.Playing -> vibe?.takeIf { it.isNotEmpty() }?.let { t("Now: %s", it) } ?: t("Playing")
        WaveMode.Paused -> t("Paused")
    }

    Column(Modifier.padding(start = PlatterSpacing.Gutter, end = PlatterSpacing.Gutter, top = PlatterSpacing.Gutter, bottom = 8.dp)) {
        Box(
            Modifier.fillMaxWidth().height(160.dp).clip(PlatterShapes.Card).background(PlatterColors.Graphite).clickable { app.onWaveTapped() },
        ) {
            WaveCanvas(mode, info?.tempo, info?.energy, Modifier.fillMaxSize())
            Column(Modifier.padding(start = 16.dp, top = 16.dp)) {
                Text(t("My Wave"), style = MaterialTheme.typography.headlineMedium)
                Text(subtitle, style = MaterialTheme.typography.titleSmall, color = PlatterColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(
                Modifier.align(Alignment.BottomEnd).padding(16.dp).size(56.dp).clip(CircleShape).background(PlatterColors.Green),
                contentAlignment = Alignment.Center,
            ) {
                if (mode == WaveMode.Loading) {
                    CircularProgressIndicator(color = PlatterColors.Black, strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
                } else {
                    Icon(
                        if (mode == WaveMode.Playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (mode == WaveMode.Playing) t("Pause My Wave") else t("Play My Wave"),
                        tint = PlatterColors.Black,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
        app.waveError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = PlatterColors.Liked, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/**
 * The waves on the card - drawn from what is playing, not looped. Every
 * movement stands for something:
 *  - idle, the water barely moves: nothing is playing, a tap starts it;
 *  - while the first batch is being fetched it swells - the answer to the tap;
 *  - playing, it travels at the track's own tempo (one crest every two beats),
 *    stands as high as the track's energy, and lifts on every beat;
 *  - paused, it slows to a standstill and stays there, the way the music did.
 * Changes between these ease rather than jump.
 *
 * It is drawn as light rather than paint: each layer is a sheet of the promo
 * gradient that is brightest along its crest and thins out towards the floor
 * of the card, and the layers add up where they cross (screen blending).
 */
@Composable
fun WaveCanvas(mode: WaveMode, tempo: Double?, energy: Double?, modifier: Modifier = Modifier) {
    val sim = remember { WaveSim() }
    var frame by remember { mutableStateOf(0L) }

    LaunchedEffect(mode, tempo, energy) {
        sim.setMode(mode, tempo, energy)
        // Frames only while something moves: a paused wave that has settled costs nothing.
        var last = 0L
        do {
            withFrameNanos { t ->
                val dt = if (last == 0L) 0.0 else min(0.05, (t - last) / 1e9)
                last = t
                sim.step(dt)
                frame = t
            }
        } while (sim.isMoving())
    }

    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame
        sim.draw(this)
    }
}

/** The wave's physics, apart from the drawing, so the modes can be seen to do what they say. */
internal class WaveSim {
    var mode = WaveMode.Idle
        private set
    var bpm = DEFAULT_BPM
        private set
    var amplitude = IDLE_AMPLITUDE
        private set
    var speed = IDLE_SPEED
        private set
    var targetAmplitude = IDLE_AMPLITUDE
        private set
    var targetSpeed = IDLE_SPEED
        private set
    private var phase = 0.0
    private var beatPhase = 0.0

    fun setMode(newMode: WaveMode, tempo: Double?, energy: Double?) {
        mode = newMode
        when (newMode) {
            WaveMode.Idle -> { targetAmplitude = IDLE_AMPLITUDE; targetSpeed = IDLE_SPEED }
            WaveMode.Loading -> { targetAmplitude = LOADING_AMPLITUDE; targetSpeed = LOADING_SPEED }
            WaveMode.Playing -> {
                bpm = tempo?.takeIf { it in 40.0..220.0 } ?: DEFAULT_BPM
                val level = (energy ?: 0.5).coerceIn(0.0, 1.0).toFloat()
                targetAmplitude = 0.30f + 0.55f * level
                // One crest every two beats.
                targetSpeed = (2 * PI * bpm / 120.0).toFloat()
            }
            WaveMode.Paused -> targetSpeed = 0f // stays at the height it had
        }
    }

    fun step(dt: Double) {
        val ease = min(1.0, dt * EASE_PER_SECOND).toFloat()
        amplitude += (targetAmplitude - amplitude) * ease
        speed += (targetSpeed - speed) * ease
        // Wrapped at a multiple of every period drawn from it, so nothing ever jumps.
        phase = (phase + speed * dt) % (300 * PI * 100)
        if (mode == WaveMode.Playing) beatPhase = (beatPhase + 2 * PI * bpm / 60.0 * dt) % (2 * PI)
    }

    fun isMoving(): Boolean = mode != WaveMode.Paused || abs(speed) > 0.01f || abs(amplitude - targetAmplitude) > 0.002f

    fun draw(scope: DrawScope) = with(scope) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return

        // The beat: a quick lift, then back to rest before the next.
        val beat = if (mode == WaveMode.Playing) max(0.0, cos(beatPhase)).let { (it * it * it).toFloat() } else 0f
        val pulse = 1f + 0.12f * beat

        // A soft glow under it all, as bright as the water is high, flaring on every beat.
        val middle = lerp(MAGENTA, BLUE, 0.5f)
        val glowAlpha = min(1f, 0.16f + 0.40f * amplitude + 0.45f * amplitude * beat)
        drawRect(
            Brush.radialGradient(listOf(middle.copy(alpha = glowAlpha), Color.Transparent), center = Offset(w * 0.45f, h * 1.1f), radius = max(w, 1f) * 0.8f),
        )

        for (layer in LAYERS - 1 downTo 0) {
            val depth = layer / (LAYERS - 1f) // 0 front .. 1 back
            val wavelength = w * (0.95f + 0.55f * depth)
            // Kept below the title and subtitle even at full energy on a beat.
            val height = amplitude * h * 0.24f * (1f - 0.25f * depth) * pulse
            val baseline = baseline(h, depth)
            val layerPhase = phase * (1.0 - 0.2 * depth) + layer * 1.7

            val fill = Path().apply { moveTo(0f, h) }
            val crest = Path()
            var x = 0f
            while (x <= w + STEP) {
                val y = surface(x, w, wavelength, height, baseline, layerPhase, layer)
                fill.lineTo(x, y)
                if (x == 0f) crest.moveTo(x, y) else crest.lineTo(x, y)
                x += STEP
            }
            fill.lineTo(w, h)
            fill.close()

            // The further back, the further its colours are carried to the left, so the layers do not line up hue for hue.
            val shift = w * 0.3f * depth
            val across = Brush.horizontalGradient(
                listOf(MAGENTA.copy(alpha = FILL_ALPHAS[layer] / 255f), BLUE.copy(alpha = FILL_ALPHAS[layer] / 255f)),
                startX = -shift, endX = w - shift,
            )
            // Brightest at the crest, gone by the floor of the card: the gradient across, faded downwards.
            val top = baseline - h * 0.24f
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(0f, 0f, w, h), Paint().apply { blendMode = BlendMode.Screen })
                drawPath(fill, across)
                drawRect(
                    Brush.verticalGradient(listOf(Color.White, Color.Transparent), startY = top, endY = h),
                    blendMode = BlendMode.DstIn,
                )
                canvas.restore()
            }

            val crestBrush = Brush.horizontalGradient(
                listOf(MAGENTA.copy(alpha = CREST_ALPHAS[layer] / 255f), BLUE.copy(alpha = CREST_ALPHAS[layer] / 255f)),
                startX = -shift, endX = w - shift,
            )
            // A soft halo along the front crest, the line the eye follows.
            if (layer == 0) drawPath(crest, crestBrush, alpha = 0.16f, style = Stroke(7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(crest, crestBrush, style = Stroke((if (layer == 0) 1.5f else 1f).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    /** The tempo wave, with a smaller one at about twice the frequency running the other way under it; tapered at the edges so the water meets the card's corners calmly. */
    private fun surface(x: Float, w: Float, wavelength: Float, height: Float, baseline: Float, layerPhase: Double, layer: Int): Float {
        val taper = 0.55f + 0.45f * sin(PI * min(x, w) / w).toFloat()
        val k = 2 * PI * x / wavelength
        val swell = 0.72 * sin(k + layerPhase) + 0.28 * sin(2.1 * k - 0.7 * layerPhase + layer * 2.3)
        return baseline - height * taper * swell.toFloat()
    }

    /** The back layers stand higher, so they show over the ones in front. */
    private fun baseline(h: Float, depth: Float) = h * (0.78f - 0.06f * depth)

    private fun lerp(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t)

    companion object {
        const val LAYERS = 4
        const val STEP = 6f
        const val DEFAULT_BPM = 110.0
        const val EASE_PER_SECOND = 3.0
        const val IDLE_AMPLITUDE = 0.36f
        const val IDLE_SPEED = 0.5f
        const val LOADING_AMPLITUDE = 0.62f
        const val LOADING_SPEED = 3.2f

        val MAGENTA = Color(0xFFAF2896)
        val BLUE = Color(0xFF509BF5)

        /** Front to back: the nearer the layer, the more light it holds. */
        val FILL_ALPHAS = intArrayOf(190, 110, 70, 50)
        val CREST_ALPHAS = intArrayOf(255, 130, 90, 60)
    }
}
