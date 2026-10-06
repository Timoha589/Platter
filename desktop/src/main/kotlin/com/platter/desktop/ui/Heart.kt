package com.platter.desktop.ui

import com.platter.desktop.i18n.t
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/*
 * What a heart does when it is tapped - the Android app's LikeAnimation, number for number.
 *
 * Liking is the one tap on a track that says something about the listener, so it answers: the heart squeezes and
 * springs back past its size, and a ring with a few sparks goes out from it and fades. Taking a like back is
 * quieter - the heart only dips and settles - because undoing is not an occasion.
 *
 * Played for a tap only. A heart that fills because the song was liked somewhere else just changes.
 */
private const val POP_MS = 450
private const val DIP_MS = 200
private const val BURST_MS = 500
private const val SPARKS = 6

/** Android's OvershootInterpolator(4): runs past 1 and settles back. */
private val Overshoot = Easing { t ->
    val x = t - 1f
    x * x * (5f * x + 4f) + 1f
}

/** Android's DecelerateInterpolator(factor): fast out of the start, slowing down. */
private fun decelerate(factor: Float) = Easing { t -> 1f - Math.pow((1f - t).toDouble(), 2.0 * factor).toFloat() }

@Composable
fun Heart(liked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scale = remember { Animatable(1f) }
    // 0 at the start of the burst, 1 at its end - and 1 is also where it rests, drawing nothing.
    val burst = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier
            .size(20.dp)
            // Behind and outside the clip below: the ring and the sparks reach past the heart.
            .drawBehind { if (burst.value < 1f) drawBurst(burst.value) }
            .clip(CircleShape)
            .clickable {
                val willBeLiked = !liked
                scope.launch {
                    if (willBeLiked) {
                        scale.snapTo(0.6f)
                        scale.animateTo(1f, tween(POP_MS, easing = Overshoot))
                    } else {
                        scale.snapTo(0.85f)
                        scale.animateTo(1f, tween(DIP_MS, easing = decelerate(1f)))
                    }
                }
                if (willBeLiked) scope.launch {
                    burst.snapTo(0f)
                    burst.animateTo(1f, tween(BURST_MS, easing = decelerate(1.5f)))
                }
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = if (liked) t("Remove from Liked") else t("Like"),
            tint = if (liked) PlatterColors.Liked else PlatterColors.Mist,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        )
    }
}

private fun DrawScope.drawBurst(t: Float) {
    val heartRadius = 10.dp.toPx()
    val fade = 1f - t

    // The ring leaves from just outside the heart, thins as it goes and is mostly gone by halfway,
    // so it reads as a flash rather than a halo.
    val width = 2.5.dp.toPx() * fade
    if (width > 0.1f) {
        drawCircle(
            PlatterColors.White.copy(alpha = 170f / 255f * fade * fade * fade),
            radius = heartRadius * (1f + 1.2f * t),
            style = Stroke(width),
        )
    }

    // The sparks run a little ahead of it, and go out a little sooner.
    val sparkFade = max(1f - t * 1.2f, 0f)
    val size = 2.dp.toPx() * sparkFade
    if (size > 0.1f) {
        val distance = heartRadius * (1.2f + 1.6f * t)
        for (i in 0 until SPARKS) {
            // Offset by half a step, so none flies straight up out of the dip.
            val angle = 2 * PI * (i + 0.5) / SPARKS
            drawCircle(
                PlatterColors.White.copy(alpha = sparkFade),
                radius = size,
                center = center + Offset(distance * cos(angle).toFloat(), distance * sin(angle).toFloat()),
            )
        }
    }
}
