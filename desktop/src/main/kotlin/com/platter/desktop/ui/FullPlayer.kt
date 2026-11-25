package com.platter.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Title
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect as DrawRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.withSaveLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.lerp as lerpDp
import androidx.compose.ui.util.lerp as lerpF
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.platter.desktop.AppController
import com.platter.desktop.Screen
import com.platter.desktop.api.Song
import com.platter.desktop.i18n.t
import com.platter.desktop.player.FADE_TICK_MS
import com.platter.desktop.player.FadeMode
import com.platter.desktop.player.FadeTransition
import com.platter.desktop.player.PlayerState
import com.platter.desktop.player.RepeatMode
import kotlin.random.Random

/** How long one track takes to give way to the next: the cover's slide, and the colours of the background turning over. */
private const val TRACK_MS = 480

/** The background takes a little longer than the cover, so it is still settling when the new cover has arrived. */
private const val BACKGROUND_MS = 800

/**
 * The player at the size of the window, opened from the cover in the bar. A window wider than it is tall puts the cover
 * beside the controls; an upright one stacks them. Behind it is a slope between the two strongest colours of the cover,
 * which turns over to the next cover's colours when the track changes - as does the cover itself, which goes the way the
 * listener did (left for the next track, right for the one before).
 */
@Composable
fun FullPlayer(app: AppController) {
    val s by app.player.state.collectAsState()
    val song = s.current

    // With nothing playing there is nothing to show.
    LaunchedEffect(song == null) { if (song == null) app.fullPlayer = false }

    val direction = remember { TrackDirection() }
    val forward = direction.update(s.queue.index, s.queue.songs, s.queue.repeat == RepeatMode.All)

    val mix = s.fadeTransition?.takeUnless { it.mode == FadeMode.Crossfade }
    val nextCover = if (app.autoMix) s.queue.after(auto = true)?.let { s.queue.songs[it].coverArtId } else null
    // Keep each loaded palette associated with its cover as the next track becomes current.
    val palettes = mutableMapOf<String, CoverPalette?>()
    for (cover in listOfNotNull(song?.coverArtId, mix?.outgoingCoverArtId, nextCover).distinct()) {
        palettes[cover] = key(cover) { rememberCoverPalette(app, cover) }
    }
    val background = rememberPlayerBackground(
        song, s.queue.index, song?.coverArtId?.let(palettes::get), mix?.outgoingCoverArtId?.let(palettes::get), s.fadeTransition,
    )

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(background.primary, background.secondary, lerp(background.secondary, Color.Black, 0.7f))))
            // Nothing under the player may be reached through it.
            .pointerInput(Unit) { detectTapGestures { } }
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                    app.fullPlayer = false
                    true
                } else {
                    false
                }
            },
    ) {
        Arranged(app, s, song, forward, upright = maxHeight > maxWidth)

        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.TopCenter) {
            Text(t("Now playing"), style = MaterialTheme.typography.labelLarge, color = PlatterColors.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 8.dp))
        }
        Box(
            Modifier.padding(16.dp).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.25f)).clickable { app.fullPlayer = false },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.ExpandMore, t("Close"), tint = PlatterColors.White, modifier = Modifier.size(28.dp))
        }
    }
}

private data class MixBackgroundTarget(val palette: CoverPalette, val loadedAt: Float)

@Composable
internal fun rememberPlayerBackground(
    song: Song?,
    queueIndex: Int,
    palette: CoverPalette?,
    outgoingPalette: CoverPalette?,
    fade: FadeTransition?,
): CoverPalette {
    val mix = fade?.takeUnless { it.mode == FadeMode.Crossfade }
    var lastPalette by remember { mutableStateOf(CoverPalette(PlatterColors.Smoke, PlatterColors.Iron)) }
    val fallback = remember(song, queueIndex, mix?.mode, mix?.outgoingCoverArtId) { lastPalette }
    var target by remember(song, queueIndex) {
        mutableStateOf(if (mix != null) palette?.let { MixBackgroundTarget(it, 0f) } else null)
    }
    LaunchedEffect(palette, mix?.mode, song, queueIndex) {
        if (palette != null) {
            lastPalette = palette
            // A palette that arrives during the mix uses the remaining audio progress.
            if (mix != null && target == null) target = MixBackgroundTarget(palette, mix.progress)
        }
    }
    val shown = if (mix != null) {
        val from = outgoingPalette ?: fallback
        val to = target
        if (to == null) from else {
            val remaining = 1f - to.loadedAt
            val fraction = if (remaining > 0f) ((mix.progress - to.loadedAt) / remaining).coerceIn(0f, 1f) else 1f
            CoverPalette(lerp(from.primary, to.palette.primary, fraction), lerp(from.secondary, to.palette.secondary, fraction))
        }
    } else palette ?: lastPalette
    // Smooth between samples, including when the player clears the completed fade on its final tick.
    val followsAudio = mix != null || target?.palette == shown
    val duration = if (followsAudio) FADE_TICK_MS.toInt() else BACKGROUND_MS
    val easing = if (followsAudio) LinearEasing else FastOutSlowInEasing
    val first by animateColorAsState(shown.primary, tween(duration, easing = easing))
    val second by animateColorAsState(shown.secondary, tween(duration, easing = easing))
    return CoverPalette(first, second)
}

/** Which way the queue has just moved: forward unless the index went down. A queue that is a new list, or one that wrapped round, counts as forward. */
internal class TrackDirection {
    private var index = -1
    private var songs: List<Song>? = null
    private var forward = true

    fun update(now: Int, list: List<Song>, repeatAll: Boolean): Boolean {
        if (now != index || list !== songs) {
            val last = list.lastIndex
            forward = when {
                list !== songs && songs != null && list.size != songs!!.size -> true
                repeatAll && index == last && now == 0 -> true
                repeatAll && index == 0 && now == last -> false
                else -> now >= index
            }
            index = now
            songs = list
        }
        return forward
    }
}

/** The room around the cover on its stage: the shadow is drawn in it, and the track change slides through it. */
private val STAGE_MARGIN = 48.dp

/** What the controls are made of, in height: the title (two lines at most), and the progress, buttons and volume. */
private val HEADER_H = 104.dp
private val GAP_HEADER_FOOTER = 24.dp
private val FOOTER_FULL = 184.dp

/** The least height the words keep in an upright window, however large the cover is dragged. */
private val WORDS_MIN_HEIGHT = 140.dp

/** How long the cover, the controls and the words take to change places. */
private const val WORDS_MS = 560

/** A place on the window's content area. */
private class Rect(val x: Dp, val y: Dp, val w: Dp, val h: Dp)

private fun lerp(a: Rect, b: Rect, t: Float) = Rect(lerpDp(a.x, b.x, t), lerpDp(a.y, b.y, t), lerpDp(a.w, b.w, t), lerpDp(a.h, b.h, t))

/**
 * Where the cover, the title and the controls stand: [header] is the title with the heart and the menu, [footer] the progress
 * and the buttons. [headerBias] is where the title sits in its place, -1 at the top and 1 at the bottom.
 */
private class Pose(val cover: Rect, val header: Rect, val footer: Rect, val headerBias: Float, val titleSp: Float)

/** The two poses a window has - with the words and without - and where the words stand in the one that has them. */
private class Poses(val plain: Pose, val words: Pose, val lyrics: Rect, val side: Dp, val wordsMin: Dp = 0.dp, val wordsMax: Dp = 0.dp)

/**
 * Wider than tall. Plain: the cover with the controls beside it. With the words: the cover on top of the controls on the
 * left, and the words in the rest of the window - the whole of its height, so they can run under and over the controls.
 */
private fun widePoses(w: Dp, h: Dp): Poses {
    val controls = HEADER_H + GAP_HEADER_FOOTER + FOOTER_FULL
    val controlsWidth = 440.dp.coerceAtMost(w * 0.45f)
    val gap = 16.dp
    val sideA = minOf(h - STAGE_MARGIN, w - controlsWidth - gap - STAGE_MARGIN, 560.dp).coerceAtLeast(120.dp)
    val left = (w - (sideA + STAGE_MARGIN + gap + controlsWidth)) / 2
    val controlsX = left + sideA + STAGE_MARGIN + gap
    val controlsY = (h - controls) / 2
    val plain = Pose(
        Rect(left + STAGE_MARGIN / 2, (h - sideA) / 2, sideA, sideA),
        Rect(controlsX, controlsY, controlsWidth, HEADER_H),
        Rect(controlsX, controlsY + HEADER_H + GAP_HEADER_FOOTER, controlsWidth, FOOTER_FULL),
        headerBias = 1f, titleSp = 32f,
    )

    val column = (w * 0.36f).coerceIn(300.dp, 480.dp).coerceAtMost(w)
    val between = 20.dp
    val titleH = 96.dp
    val under = titleH + between + FOOTER_FULL
    val sideB = minOf(column, h - between - under).coerceAtLeast(120.dp)
    val gutter = 56.dp
    val wordsW = minOf(w - column - gutter, 640.dp).coerceAtLeast(200.dp)
    val x = ((w - column - gutter - wordsW) / 2).coerceAtLeast(0.dp)
    val top = (h - (sideB + between + under)) / 2
    val words = Pose(
        Rect(x + (column - sideB) / 2, top, sideB, sideB),
        Rect(x, top + sideB + between, column, titleH),
        Rect(x, top + sideB + between + titleH + between, column, FOOTER_FULL),
        headerBias = -1f, titleSp = 28f,
    )
    return Poses(plain, words, Rect(x + column + gutter, 0.dp, wordsW, h), maxOf(sideA, sideB))
}

/**
 * Taller than wide. Plain: the cover with the controls under it. With the words: the same block, smaller, at the top, and
 * the words in the rest of the window.
 */
private fun uprightPoses(w: Dp, h: Dp, wordsHeight: Int): Poses {
    val controls = HEADER_H + GAP_HEADER_FOOTER + FOOTER_FULL
    val sideA = minOf(w, h - controls - STAGE_MARGIN, 560.dp).coerceAtLeast(120.dp)
    // As wide as the cover, but not so narrow that the buttons crowd.
    val controlsWidth = maxOf(sideA, 360.dp).coerceAtMost(w)
    val top = (h - (sideA + STAGE_MARGIN + controls)) / 2
    val controlsX = (w - controlsWidth) / 2
    val headerY = top + sideA + STAGE_MARGIN
    val plain = Pose(
        Rect((w - sideA) / 2, top + STAGE_MARGIN / 2, sideA, sideA),
        Rect(controlsX, headerY, controlsWidth, HEADER_H),
        Rect(controlsX, headerY + HEADER_H + GAP_HEADER_FOOTER, controlsWidth, FOOTER_FULL),
        headerBias = 0f, titleSp = 32f,
    )

    // The cover and the controls stay the one block they were, only smaller, at the top; the words get what is under it.
    val titleH = 84.dp
    val under = 20.dp + titleH + 16.dp + FOOTER_FULL
    // The words are as tall as the listener dragged them; with no say of theirs, the block takes 55% of the window. The
    // cover gives way for it, down to a small one - and the words can be no taller than that leaves, nor shorter than the cover allows.
    val gap = 12.dp
    // The cover can grow to the size it has without the words, as far as the words keep a few lines.
    val coverMax = (h - gap - under - WORDS_MIN_HEIGHT).coerceIn(96.dp, minOf(w, 560.dp).coerceAtLeast(96.dp))
    val coverMin = 96.dp.coerceAtMost(coverMax)
    val sideB = if (wordsHeight > 0) (h - wordsHeight.dp - gap - under).coerceIn(coverMin, coverMax)
    else (h * 0.55f - under).coerceIn(120.dp, 360.dp).coerceAtMost(w)
    val controlsB = maxOf(sideB, 360.dp).coerceAtMost(w)
    val xB = (w - controlsB) / 2
    val headerB = sideB + 20.dp
    val footerB = headerB + titleH + 16.dp
    val words = Pose(
        Rect((w - sideB) / 2, 0.dp, sideB, sideB),
        Rect(xB, headerB, controlsB, titleH),
        Rect(xB, footerB, controlsB, FOOTER_FULL),
        headerBias = -1f, titleSp = 24f,
    )
    val column = minOf(w, 640.dp)
    val wordsTop = footerB + FOOTER_FULL + gap
    return Poses(
        plain, words, Rect((w - column) / 2, wordsTop, column, (h - wordsTop).coerceAtLeast(0.dp)), maxOf(sideA, sideB),
        wordsMin = (h - gap - under - coverMax).coerceAtLeast(0.dp), wordsMax = (h - gap - under - coverMin).coerceAtLeast(0.dp),
    )
}

/**
 * The cover, the title, the controls and - when the listener asked for them - the words, each moving between the place it
 * has without the words and the one it has with them. One progress drives all of it, so nothing arrives before the thing
 * it is replacing has left.
 */
@Composable
private fun Arranged(app: AppController, s: PlayerState, song: Song?, forward: Boolean, upright: Boolean) {
    val padding = if (upright) PaddingValues(start = 32.dp, end = 32.dp, top = 72.dp, bottom = 32.dp) else PaddingValues(start = 56.dp, end = 56.dp, top = 72.dp, bottom = 48.dp)
    BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
        val poses = remember(maxWidth, maxHeight, upright, app.wordsHeight) {
            if (upright) uprightPoses(maxWidth, maxHeight, app.wordsHeight) else widePoses(maxWidth, maxHeight)
        }
        // Words are for music: an episode or a station has none, and no button to leave them by, so it shows the cover.
        val showWords = app.fullPlayerLyrics && song?.isMusic != false
        val t by animateFloatAsState(if (showWords) 1f else 0f, tween(WORDS_MS, easing = FastOutSlowInEasing), label = "full-player-words")
        val cover = lerp(poses.plain.cover, poses.words.cover, t)
        val header = lerp(poses.plain.header, poses.words.header, t)
        val footer = lerp(poses.plain.footer, poses.words.footer, t)

        // Under the controls, which cross its place on their way: a press on the button that opened it must not land on a line.
        if (showWords || t > 0.001f) {
            val words = poses.lyrics
            Box(
                Modifier.offset(words.x, words.y).size(words.w, words.h)
                    // They appear once the cover has left their place.
                    .graphicsLayer { alpha = ((t - 0.35f) / 0.65f).coerceIn(0f, 1f) },
            ) {
                LyricsView(app, Modifier.fillMaxSize(), centered = upright)
            }
            // An upright window's words can be made taller or shorter by their top edge, as the panel under the page can: the
            // handle sits in the gap above them, and a double press gives the window's own split back.
            if (upright && showWords && t > 0.99f && poses.wordsMax > poses.wordsMin) {
                Box(Modifier.offset(words.x, words.y - 10.dp).size(words.w, 8.dp)) {
                    ResizeHandle(
                        "resize-words", width = { words.h.value.roundToInt() },
                        onResize = { app.resizeWords(it.coerceIn(poses.wordsMin.value.roundToInt(), poses.wordsMax.value.roundToInt())) },
                        growsForward = false, vertical = true,
                        onDone = app::saveLayout, onReset = { app.resizeWords(0); app.saveLayout() },
                    )
                }
            }
        }

        // The stage is as big as the cover can be, and drawn smaller: the picture is asked for once, not once a frame.
        // An upright window's stage is as wide as the window, so a cover changing has the whole width to cross.
        val stageW = if (upright) maxWidth else poses.side + STAGE_MARGIN
        val stageH = poses.side + STAGE_MARGIN
        val scale = cover.w / poses.side
        Box(
            Modifier
                .offset(cover.x + cover.w / 2 - stageW / 2, cover.y + cover.w / 2 - stageH / 2)
                .size(stageW, stageH)
                .graphicsLayer { scaleX = scale; scaleY = scale },
        ) {
            // The corners stay as round on the screen as everywhere else, however far the cover is shrunk.
            CoverStage(
                app, song, s.fadeTransition, forward, poses.side,
                shape = RoundedCornerShape(6.dp / scale),
                modifier = Modifier.fillMaxSize(),
                shadow = 32.dp,
                stageWidth = stageW,
            )
        }

        Box(
            Modifier.offset(header.x, header.y).size(header.w, header.h),
            contentAlignment = BiasAlignment(-1f, lerpF(poses.plain.headerBias, poses.words.headerBias, t)),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val titleSp = lerpF(poses.plain.titleSp, poses.words.titleSp, t)
                Titles(app, song, forward, Alignment.CenterStart, TextAlign.Start, Modifier.weight(1f), titleSp.sp, (titleSp * 0.56f).coerceAtLeast(14f).sp)
                Actions(app, song)
            }
        }

        if (s.autoMixTransition != null) {
            Box(Modifier.offset(footer.x, footer.y - 19.dp).size(footer.w, 20.dp), contentAlignment = Alignment.Center) {
                AutoMixLabel()
            }
        }

        Column(Modifier.offset(footer.x, footer.y).width(footer.w)) {
            Seek(app, s)
            Spacer(Modifier.height(18.dp))
            Transport(app, s)
            Spacer(Modifier.height(28.dp))
            Volume(app, s, Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun AutoMixLabel() {
    var width by remember { mutableStateOf(1) }
    val shimmer = rememberInfiniteTransition(label = "automix-shimmer")
    val sweep by shimmer.animateFloat(
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(tween(1_900, easing = LinearEasing)),
        label = "automix-shimmer-position",
    )
    val gray = PlatterColors.Mist
    val brush = Brush.horizontalGradient(
        0f to gray, 0.5f to Color.White, 1f to gray,
        startX = (sweep - 0.25f) * width,
        endX = (sweep + 0.25f) * width,
    )
    Text(
        t("AutoMix"),
        style = MaterialTheme.typography.labelLarge.copy(brush = brush, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.onSizeChanged { width = it.width.coerceAtLeast(1) },
        maxLines = 1,
    )
}

/**
 * The cover, [side] wide, on the stage the track change happens on: the old cover slides off the way the listener went
 * while the new one slides in from the other side. An AutoMix fallback follows the fade clock. A beat mix dissolves in patches.
 */
@Composable
internal fun CoverStage(
    app: AppController,
    song: Song?,
    fade: FadeTransition?,
    forward: Boolean,
    side: Dp,
    shape: Shape = PlatterShapes.Card,
    modifier: Modifier = Modifier.size(side),
    shadow: Dp = 0.dp,
    stageWidth: Dp = side,
) {
    val artwork = song?.let { TrackArtwork(it.id, it.coverArtId) }
    val slide = with(LocalDensity.current) { stageWidth.toPx() / 2 } * if (forward) 1 else -1
    val mix = fade?.takeUnless { it.mode == FadeMode.Crossfade }
    val mode = mix?.mode
    val progress = remember(artwork, mode) { Animatable(mix?.progress ?: 1f) }
    LaunchedEffect(progress, mix?.progress) {
        if (mix != null) progress.animateTo(mix.progress, tween(FADE_TICK_MS.toInt(), easing = LinearEasing))
    }
    val track = remember {
        val initial = if (fade?.mode == FadeMode.AutoMixFallback) TrackArtwork(null, fade.outgoingCoverArtId) else artwork
        SeekableTransitionState(initial)
    }
    val transition = rememberTransition(track, label = "track-cover")
    LaunchedEffect(artwork, mode) {
        when (mode) {
            FadeMode.AutoMixFallback -> snapshotFlow { progress.value }.collect { track.seekTo(it, artwork) }
            FadeMode.AutoMix -> track.snapTo(artwork)
            else -> if (track.targetState == artwork) track.snapTo(artwork) else track.animateTo(artwork)
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        // Fading content is rendered into a bounded layer. Include the whole shadow in that layer.
        val shadowPadding = shadow * 2.5f
        val animatedBounds = if (shadow > 0.dp) {
            Modifier.requiredSize(stageWidth + shadowPadding * 2, side + shadowPadding * 2)
                .coverEdgeFade(stageWidth) {
                    val fraction = track.fraction
                    if (mode == FadeMode.AutoMix) 0f else minOf(1f, fraction * 5, (1f - fraction) * 5)
                }
        } else Modifier.fillMaxSize()
        transition.AnimatedContent(
            modifier = animatedBounds,
            contentAlignment = Alignment.Center,
            contentKey = { it?.songId ?: it },
            transitionSpec = {
                if (shadow > 0.dp) (EnterTransition.None togetherWith ExitTransition.None).using(null)
                else turn(forward, distance = 2)
            },
        ) { shown ->
            val motion = this.transition
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (shown != null) {
                    if (shadow > 0.dp) {
                        Box(Modifier.size(side + shadowPadding * 2).coverTurn(motion, slide), contentAlignment = Alignment.Center) {
                            Cover(app, shown.coverArtId, side, shape, Modifier.shadow(shadow, shape))
                        }
                    } else Cover(app, shown.coverArtId, side, shape)
                }
            }
        }
        if (fade?.mode == FadeMode.AutoMix && song != null && fade.outgoingCoverArtId != song.coverArtId) {
            val seed = remember(fade.outgoingCoverArtId, song.id) { Random.nextInt() }
            Cover(app, fade.outgoingCoverArtId, side, shape, Modifier.coverDissolve(seed) { progress.value })
        }
    }
}

@Composable
private fun Modifier.coverTurn(transition: Transition<EnterExitState>, slide: Float): Modifier {
    val movement = tween<Float>(TRACK_MS, easing = FastOutSlowInEasing)
    val offset by transition.animateFloat(transitionSpec = { movement }, label = "cover offset") {
        when (it) {
            EnterExitState.PreEnter -> slide
            EnterExitState.Visible -> 0f
            EnterExitState.PostExit -> -slide
        }
    }
    val zoom by transition.animateFloat(transitionSpec = { movement }, label = "cover scale") {
        if (it == EnterExitState.Visible) 1f else 0.9f
    }
    val fadeMs = TRACK_MS * 2 / 3
    // Bring the new cover up while the old one still casts a shadow.
    val opacity by transition.animateFloat(transitionSpec = {
        tween(fadeMs, delayMillis = if (targetState == EnterExitState.Visible) 0 else TRACK_MS - fadeMs)
    }, label = "cover opacity") { if (it == EnterExitState.Visible) 1f else 0f }
    return graphicsLayer {
        translationX = offset
        scaleX = zoom
        scaleY = zoom
        alpha = opacity
    }
}

private data class TrackArtwork(val songId: String?, val coverArtId: String?)

private fun Modifier.coverEdgeFade(stageWidth: Dp, strength: () -> Float): Modifier =
    drawWithCache {
        // Anchor the fade to the stage so the covers pass through it as they slide.
        val width = minOf(size.width, stageWidth.toPx())
        val start = (size.width - width) / 2
        val featherWidth = (STAGE_MARGIN / 2).toPx().coerceAtMost(width / 2)
        val feather = featherWidth / width
        val mask = Brush.horizontalGradient(
            0f to Color.Black, feather to Color.Transparent,
            1f - feather to Color.Transparent, 1f to Color.Black,
            startX = start, endX = start + width,
        )
        val edgeSize = Size(featherWidth + 1f, size.height)
        val leftEdge = Offset(start - 1f, 0f)
        val rightEdge = Offset(start + width - featherWidth, 0f)
        val layerPaint = Paint()
        onDrawWithContent {
            val amount = strength()
            if (amount <= 0f) {
                drawContent()
                return@onDrawWithContent
            }
            // Move the fade out into the padding at the ends of the transition to let the resting shadow through.
            val fadeWidth = lerpF(size.width, width, amount)
            val left = (size.width - fadeWidth) / 2
            val right = left + fadeWidth
            // Clip the composite where the gradient reaches zero so a scaled layer cannot leak a pixel at its edge.
            clipRect(left = left, right = right) {
                drawIntoCanvas { canvas ->
                    canvas.withSaveLayer(DrawRect(left, 0f, right, size.height), layerPaint) {
                        this@onDrawWithContent.drawContent()
                        scale(scaleX = fadeWidth / width, scaleY = 1f) {
                            drawRect(mask, topLeft = leftEdge, size = edgeSize, blendMode = BlendMode.DstOut)
                            drawRect(mask, topLeft = rightEdge, size = edgeSize, blendMode = BlendMode.DstOut)
                        }
                    }
                }
            }
        }
    }

private data class DissolvePatch(val center: Offset, val radius: Float, val start: Float, val mask: Brush)

private fun Modifier.coverDissolve(seed: Int, progress: () -> Float): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val random = Random(seed)
            val cell = size.minDimension / 4f
            // Jitter the centers while ensuring every point is covered before the outgoing layer is removed.
            val patches = List(16) { index ->
                val center = Offset(
                    (index % 4 + 0.3f + random.nextFloat() * 0.4f) * cell,
                    (index / 4 + 0.3f + random.nextFloat() * 0.4f) * cell,
                )
                val radius = ((1.65f + random.nextFloat() * 0.45f) * cell).coerceAtLeast(1f)
                val start = random.nextFloat() * 0.64f
                val mask = Brush.radialGradient(
                    0f to Color.Black, 0.64f to Color.Black, 1f to Color.Transparent,
                    center = center, radius = radius,
                )
                DissolvePatch(center, radius, start, mask)
            }
            onDrawWithContent {
                drawContent()
                val mixProgress = progress()
                for (patch in patches) {
                    val fade = ((mixProgress - patch.start) / 0.36f).coerceIn(0f, 1f)
                    if (fade > 0f) {
                        drawCircle(
                            patch.mask, patch.radius, patch.center,
                            alpha = fade * fade * (3f - 2f * fade), blendMode = BlendMode.DstOut,
                        )
                    }
                }
            }
        }

/** Title over artist, which turn over with the cover - nearer, since they are small and a long slide would be a long smear. */
@Composable
private fun Titles(app: AppController, song: Song?, forward: Boolean, align: Alignment, textAlign: TextAlign, modifier: Modifier, titleSize: TextUnit, artistSize: TextUnit) {
    AnimatedContent(
        targetState = song,
        modifier = modifier,
        contentAlignment = align,
        contentKey = { it?.id },
        transitionSpec = { turn(forward, distance = 8) },
        label = "full-player-titles",
    ) { shown ->
        if (shown != null) {
            Column {
                Text(
                    shown.title.orEmpty(),
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = titleSize, lineHeight = titleSize * 1.19f, fontWeight = FontWeight.Bold),
                    textAlign = textAlign,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(PlatterShapes.Small).clickable(enabled = shown.albumId != null) {
                        shown.albumId?.let { app.fullPlayer = false; app.navigate(Screen.Album(it)) }
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    shown.artistLine().orEmpty(),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = artistSize),
                    color = PlatterColors.White.copy(alpha = 0.75f),
                    textAlign = textAlign,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(PlatterShapes.Small).clickable(enabled = shown.artistId != null) {
                        // A joint credit offers its artists one by one; the player stays up under the choice.
                        app.openArtist(shown.artistLine(), shown.artistId) { app.fullPlayer = false }
                    },
                )
            }
        }
    }
}

/**
 * One track giving way to the next: the old one leaves the way the listener went and the new one comes from where they
 * were going, both fading and changing size with it; [distance] is the share of the stage's width it slides, as 1/[distance].
 */
private fun <S> AnimatedContentTransitionScope<S>.turn(forward: Boolean, distance: Int): ContentTransform {
    val way = if (forward) 1 else -1
    val move = tween<IntOffset>(TRACK_MS, easing = FastOutSlowInEasing)
    val size = tween<Float>(TRACK_MS, easing = FastOutSlowInEasing)
    val coming = slideInHorizontally(move) { way * it / distance } + fadeIn(tween(TRACK_MS, delayMillis = TRACK_MS / 5)) + scaleIn(size, initialScale = 0.82f)
    val going = slideOutHorizontally(move) { -way * it / distance } + fadeOut(tween(TRACK_MS * 2 / 3)) + scaleOut(size, targetScale = 0.82f)
    return (coming togetherWith going).using(SizeTransform(clip = true) { _, _ -> snap() })
}

/** The heart and the menu, which go with the track but are not part of what turns over. */
@Composable
private fun Actions(app: AppController, song: Song?) {
    if (song == null || !song.isMusic) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(start = 16.dp)) {
        IconAction(
            Icons.Outlined.Title, t("Lyrics"),
            tint = if (app.fullPlayerLyrics) PlatterColors.Green else PlatterColors.White, size = 28.dp,
            modifier = Modifier.testTag("full-player-lyrics"),
        ) { app.fullPlayerLyrics = !app.fullPlayerLyrics }
        Heart(app.isLiked(song.id, song.starred), { app.toggleLike(song) }, Modifier.size(28.dp))
        var menu by remember { mutableStateOf(false) }
        Box {
            IconAction(Icons.Outlined.MoreHoriz, t("More"), tint = PlatterColors.White, size = 28.dp) { menu = true }
            SongMenu(app, song, menu, { menu = false })
        }
    }
}

@Composable
private fun Seek(app: AppController, s: PlayerState) {
    if (s.current?.isLive == true) {
        Text(t("LIVE"), style = MaterialTheme.typography.labelMedium, color = PlatterColors.Green, modifier = Modifier.height(16.dp))
        return
    }
    Column {
        PlaybackProgressBar(
            state = s,
            onSeek = { app.player.seek(it) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatClock(s.positionMs), style = MaterialTheme.typography.labelSmall, color = PlatterColors.White.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
            Text(formatClock(s.durationMs), style = MaterialTheme.typography.labelSmall, color = PlatterColors.White.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun Transport(app: AppController, s: PlayerState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconAction(
            Icons.Outlined.Shuffle, t("Shuffle"),
            tint = if (s.queue.shuffle) PlatterColors.Green else PlatterColors.White.copy(alpha = 0.8f), size = 26.dp,
        ) { app.player.toggleShuffle() }
        IconAction(Icons.Filled.SkipPrevious, t("Previous"), tint = PlatterColors.White, size = 40.dp) { app.player.previous() }
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(PlatterColors.White).clickable { app.player.toggle() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (s.isPlaying) t("Pause") else t("Play"),
                tint = PlatterColors.Black, modifier = Modifier.size(36.dp),
            )
        }
        IconAction(Icons.Filled.SkipNext, t("Next"), tint = PlatterColors.White, size = 40.dp) { app.player.next() }
        val repeat = s.queue.repeat
        IconAction(
            if (repeat == RepeatMode.One) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
            t("Repeat"),
            tint = if (repeat == RepeatMode.Off) PlatterColors.White.copy(alpha = 0.8f) else PlatterColors.Green, size = 26.dp,
        ) { app.player.cycleRepeat() }
    }
}

@Composable
private fun Volume(app: AppController, s: PlayerState, modifier: Modifier) {
    Row(modifier.wheelVolume(app), verticalAlignment = Alignment.CenterVertically) {
        IconAction(
            if (s.volume == 0) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
            t("Mute"), tint = PlatterColors.White.copy(alpha = 0.8f), size = 22.dp,
        ) { app.toggleMute() }
        Spacer(Modifier.width(8.dp))
        ProgressBar(s.volume / 100f, { app.setVolume((it * 100).toInt()) }, Modifier.width(160.dp))
    }
}
