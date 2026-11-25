package com.cappielloantonio.tempo.helper.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import com.bumptech.glide.Glide
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.glide.CustomGlideRequest
import com.google.android.material.imageview.ShapeableImageView
import kotlin.random.Random

/** Erases the outgoing sleeve in soft patches to reveal the sleeve underneath. */
class DissolvingCoverView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ShapeableImageView(context, attrs, defStyleAttr) {
    private data class Patch(val x: Float, val y: Float, val radius: Float, val start: Float, val mask: Shader)

    private val eraser = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private var progress = 1f
    private var seed = 0L
    private var patchSize = 0
    private var patches = emptyList<Patch>()

    fun loadArtwork(id: String?) {
        if (id != null) {
            CustomGlideRequest.loadCover(context, this, id, CustomGlideRequest.ResourceType.Song)
        } else {
            Glide.with(this).clear(this)
            setTag(R.id.cover_art_tag, null)
            setImageResource(R.drawable.ic_placeholder_song)
        }
    }

    fun dissolve(token: Long, fraction: Float) {
        if (token == seed && fraction == progress) return
        if (token != seed || fraction >= 1f) patches = emptyList()
        seed = token
        progress = fraction
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        val size = minOf(width, height)
        if (progress <= 0f || progress >= 1f || size == 0) {
            super.draw(canvas)
            return
        }
        if (patches.isEmpty() || patchSize != size) {
            patches = makePatches(size)
            patchSize = size
        }

        // The eraser affects only this sleeve. The new sleeve stays on the layer below.
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.draw(canvas)
        for (patch in patches) {
            val fraction = ((progress - patch.start) / PATCH_DURATION).coerceIn(0f, 1f)
            if (fraction == 0f) continue
            val eased = fraction * fraction * (3f - 2f * fraction)
            eraser.alpha = (255 * eased).toInt()
            eraser.shader = patch.mask
            canvas.drawCircle(patch.x, patch.y, patch.radius, eraser)
        }
        canvas.restoreToCount(layer)
    }

    private fun makePatches(size: Int): List<Patch> {
        val random = Random(seed)
        val cell = size / 4f
        return List(PATCH_COUNT) { index ->
            // One centre per cell, jittered so the mask covers the whole sleeve with no regular grid.
            val x = (index % 4 + 0.3f + random.nextFloat() * 0.4f) * cell
            val y = (index / 4 + 0.3f + random.nextFloat() * 0.4f) * cell
            val radius = ((1.65f + random.nextFloat() * 0.45f) * cell).coerceAtLeast(1f)
            val start = random.nextFloat() * (1f - PATCH_DURATION)
            val mask = RadialGradient(x, y, radius, MASK_COLORS, MASK_STOPS, Shader.TileMode.CLAMP)
            Patch(x, y, radius, start, mask)
        }
    }

    private companion object {
        const val PATCH_COUNT = 16
        const val PATCH_DURATION = 0.36f
        val MASK_COLORS = intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT)
        val MASK_STOPS = floatArrayOf(0f, 0.64f, 1f)
    }
}
