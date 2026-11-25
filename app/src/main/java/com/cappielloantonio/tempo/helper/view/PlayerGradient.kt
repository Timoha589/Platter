package com.cappielloantonio.tempo.helper.view

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils.blendARGB
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.glide.CustomGlideRequest
import com.cappielloantonio.tempo.service.CoverTransition
import com.cappielloantonio.tempo.util.CoverColorUtil
import com.cappielloantonio.tempo.util.Preferences
import java.util.function.IntConsumer

/** Owns the large player's gradient and the thumbnail requests for its current and upcoming covers. */
internal class PlayerGradient(view: View, private val images: RequestManager, private val tint: IntConsumer) {
    private data class Palette(val first: Int, val second: Int) {
        fun blend(to: Palette, fraction: Float) = Palette(
            blendARGB(first, to.first, fraction), blendARGB(second, to.second, fraction),
        )
    }

    private class Mix(val from: Palette, var to: Palette?, var loadedAt: Float = 0f)

    private val context = view.context
    private val neutral = Palette(ContextCompat.getColor(context, R.color.steel), ContextCompat.getColor(context, R.color.iron))
    private val canvas = ContextCompat.getColor(context, R.color.void_black)
    private val background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors(neutral)).apply { setDither(true) }
    private var shown = neutral
    private var normalTarget: Palette? = neutral
    private var animator: ValueAnimator? = null
    private val requests = mutableMapOf<String, CustomTarget<Bitmap>>()
    private val ready = mutableMapOf<String, Palette>()
    private var currentCover: String? = null
    private var outgoingCover: String? = null
    private var onIncoming = false
    private var transition: CoverTransition? = null
    private var mix: Mix? = null

    init {
        view.background = background
        tint.accept(shown.first)
    }

    fun updateTrack(player: Player, overlap: CoverTransition?) {
        val index = player.currentMediaItemIndex
        val id = player.currentMediaItem?.mediaId
        onIncoming = overlap != null && index == overlap.toIndex && id == overlap.toId
        val onOutgoing = overlap != null && index == overlap.fromIndex && id == overlap.fromId
        val nextMix = overlap?.takeIf { it.autoMix && (onIncoming || onOutgoing) }
        if (nextMix?.token != transition?.token) mix = null
        transition = nextMix
        outgoingCover = nextMix?.let { coverAt(player, it.fromIndex, it.fromId) }
        val incoming = nextMix?.let { coverAt(player, it.toIndex, it.toId) }
        currentCover = (if (onIncoming) incoming else outgoingCover) ?: coverOf(player.mediaMetadata)
        val upcoming = if (Preferences.getTransitionMode() == Preferences.TransitionMode.AUTO_MIX) {
            coverAt(player, player.nextMediaItemIndex)
        } else null
        val wanted = setOfNotNull(currentCover, outgoingCover, incoming, upcoming)
        for (cover in requests.keys.toList()) {
            if (cover !in wanted) requests.remove(cover)?.let(images::clear)
        }
        ready.keys.retainAll(wanted)
        wanted.forEach(::load)
        redraw()
    }

    fun updateOverlap(player: Player, overlap: CoverTransition?) {
        val active = transition
        if (active != null && active.token == overlap?.token) {
            transition = overlap
            redraw()
        } else if (overlap?.autoMix == true || active != null) {
            updateTrack(player, overlap)
        }
    }

    fun dispose() {
        animator?.cancel()
        val loading = requests.values.toList()
        requests.clear()
        loading.forEach(images::clear)
        ready.clear()
    }

    private fun coverAt(player: Player, index: Int, id: String? = null): String? {
        if (index !in 0 until player.mediaItemCount) return null
        val item = player.getMediaItemAt(index)
        return if (id == null || item.mediaId == id) coverOf(item.mediaMetadata) else null
    }

    private fun coverOf(metadata: MediaMetadata): String? = metadata.extras?.getString("coverArtId")

    private fun palette(cover: String?): Palette? = if (cover == null) neutral else ready[cover]

    private fun load(cover: String) {
        if (cover in requests || cover in ready) return
        val model = CustomGlideRequest.coverModel(context, cover, !Preferences.isDataSavingMode())
        if (model == null) {
            ready[cover] = neutral
            return
        }
        val request = object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                val colors = CoverColorUtil.palette(resource, neutral.first, neutral.second)
                available(Palette(colors[0], colors[1]))
            }

            override fun onLoadFailed(errorDrawable: Drawable?) = available(neutral)

            override fun onLoadCleared(placeholder: Drawable?) = Unit

            private fun available(palette: Palette) {
                if (requests[cover] !== this) return
                ready[cover] = palette
                redraw()
            }
        }
        requests[cover] = request
        images.asBitmap().load(model).dontTransform().override(64, 64).diskCacheStrategy(DiskCacheStrategy.ALL).into(request)
    }

    private fun redraw() {
        val overlap = transition
        if (overlap == null || overlap.mode == CoverTransition.Mode.FAST_SLIDE) {
            palette(currentCover)?.let(::animateTo)
            return
        }
        if (overlap.mode == CoverTransition.Mode.PENDING || !onIncoming) {
            if (outgoingCover != null || !onIncoming) palette(outgoingCover)?.let(::animateTo)
            return
        }

        val incoming = palette(currentCover)
        val blend = mix ?: Mix(shown, incoming).also {
            animator?.cancel()
            normalTarget = null
            mix = it
        }
        if (blend.to == null && incoming != null) {
            blend.to = incoming
            blend.loadedAt = overlap.progress
        }
        val target = blend.to ?: return
        val remaining = 1f - blend.loadedAt
        val fraction = if (remaining > 0f) ((overlap.progress - blend.loadedAt) / remaining).coerceIn(0f, 1f) else 1f
        show(blend.from.blend(target, fraction))
    }

    private fun animateTo(target: Palette) {
        if (normalTarget == target) return
        animator?.cancel()
        normalTarget = target
        val from = shown
        if (from == target) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { show(from.blend(target, it.animatedValue as Float)) }
            start()
        }
    }

    private fun show(palette: Palette) {
        if (palette == shown) return
        shown = palette
        background.colors = colors(palette)
        tint.accept(palette.first)
    }

    private fun colors(palette: Palette) = intArrayOf(palette.first, palette.second, blendARGB(palette.second, canvas, 0.7f))
}
