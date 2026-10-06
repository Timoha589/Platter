package com.platter.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.platter.desktop.AppController
import com.platter.desktop.api.LyricsPicker
import com.platter.desktop.api.SongLyrics
import com.platter.desktop.i18n.t
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

/** How long the words stay where the reader put them once they stop scrolling. Following the song again the instant a wheel stops makes a lyric impossible to read ahead in. */
private const val AUTO_SCROLL_HOLD_MS = 3000L

/**
 * The sung line is at full size and strength. The rest are 92% of its size and a third as bright, and blurrier the further
 * they are from it - the blur is what tells the eye where the song is without anything being highlighted.
 */
private const val INACTIVE_SCALE = 0.92f
private const val INACTIVE_ALPHA = 0.34f
private const val BLUR_STEP_DP = 0.9f
private const val BLUR_LINES = 5
private const val UNSYNCED_ALPHA = 0.9f

/** While the reader is looking around (or pointing at a line) nothing is blurred: they are reading, not following. */
private const val BROWSE_ALPHA = 0.6f
private const val LINE_MS = 320

/** The sung line rests this far down the view, not in the middle: what is coming matters more than what has gone. */
private const val ANCHOR = 0.26f

/**
 * A change of line moves every line up together, the ones under the new line a little behind their upper neighbour, so the
 * column pours into place instead of sliding as one board.
 */
private const val MOVE_MS = 700
private const val STAGGER_MS = 85
private const val STAGGER_LINES = 7
private const val MOVE_TOTAL_MS = MOVE_MS + STAGGER_MS * STAGGER_LINES

/** How much smaller the words are set in the panel under the page than in the one beside it and the full-screen player. */
private const val UNDER_PAGE_TEXT_SCALE = 0.7f

/** Gets going at once but takes its time arriving: a front-loaded curve would finish each line before the next had left, and the delay between them would not show. */
private val Settle = CubicBezierEasing(0.3f, 0f, 0.15f, 1f)

/**
 * [centered]: the words down the middle of the panel, as when it lies under the page; beside it they keep to the left.
 * Lying under the page the panel is wide and short, and the words there are set smaller than anywhere else.
 */
@Composable
fun LyricsPanel(app: AppController, centered: Boolean = false) {
    Column(Modifier.fillMaxSize().background(PlatterColors.Carbon).padding(top = 8.dp)) {
        Text(t("Lyrics"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
        LyricsView(app, centered = centered, textScale = if (centered) UNDER_PAGE_TEXT_SCALE else 1f)
    }
}

/** The words of what is playing, or why there are none: the part of the panel under its heading, which the full-screen player shows too. */
@Composable
fun LyricsView(app: AppController, modifier: Modifier = Modifier.fillMaxSize(), centered: Boolean = false, textScale: Float = 1f) {
    val s by app.player.state.collectAsState()
    val song = s.current

    Box(modifier) {
        when {
            song == null -> Message(t("Play something to see its words"))
            !song.isMusic -> Message(t("There are no lyrics for this"))
            else -> {
                val loader = rememberLoader(app.client to song.id) { app.lyricsFor(song) }
                LoadView(loader) { lyrics ->
                    if (lyrics.isEmpty) Message(t("No lyrics for this track")) else key(lyrics) { LyricsBody(app, lyrics, s.positionMs, centered, textScale) }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = PlatterColors.Mist)
    }
}

/** One change of the sung line: the column was scrolled [delta] px at once at [start], and each line walks back to where it was. */
private class Move(val start: Long, val delta: Float, val active: Int)

/** The moves still under way and the frame clock they are read against; a line asks [shift] for how far it still has to go. */
private class LineMotion {
    var now by mutableLongStateOf(0L)
    var moves by mutableStateOf(emptyList<Move>())

    fun shift(line: Int): Float {
        var sum = 0f
        for (m in moves) {
            // Lines above the new one go first, the new one with them, the lines below one after another.
            val wait = if (line <= m.active) 0 else min(line - m.active, STAGGER_LINES) * STAGGER_MS
            val progress = ((now - m.start - wait) / MOVE_MS.toFloat()).coerceIn(0f, 1f)
            sum += m.delta * (1f - Settle.transform(progress))
        }
        return sum
    }
}

@Composable
private fun LyricsBody(app: AppController, lyrics: SongLyrics, positionMs: Long, centered: Boolean, textScale: Float) {
    val synced = lyrics.isSynced
    val layer = lyrics.layer
    val offset = layer?.offset ?: 0
    val timed = remember(lyrics) { if (synced) LyricsPicker.withIntro(layer?.line.orEmpty()) else emptyList() }
    val texts = remember(lyrics) { if (synced) timed.map { it.value.orEmpty() } else lyrics.lines }
    val clock = positionMs + offset
    val active = if (synced) LyricsPicker.activeLine(timed, positionMs, offset) else -1

    val listState = rememberLazyListState()
    val motion = remember { LineMotion() }
    var placed by remember { mutableStateOf(false) }

    // A scroll the reader makes (wheel or drag - not the ones this panel makes) holds the column still and unblurs it.
    var browsing by remember { mutableStateOf(false) }
    var userScrolls by remember { mutableIntStateOf(0) }
    val userScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    browsing = true
                    userScrolls++
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(userScrolls) {
        if (userScrolls == 0) return@LaunchedEffect
        kotlinx.coroutines.delay(AUTO_SCROLL_HOLD_MS)
        browsing = false
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heightPx = constraints.maxHeight
        val anchorPx = (heightPx * ANCHOR).toInt()
        val density = LocalDensity.current
        val fontSize = ((maxWidth.value * 0.075f).coerceIn(22f, 34f) * textScale).sp

        // Moving the column is one jump, and the pour into place is drawn on top of it.
        LaunchedEffect(active, browsing) {
            if (!synced || active < 0 || browsing) return@LaunchedEffect
            val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == active }
            val delta = item?.let { it.offset - (listState.layoutInfo.viewportStartOffset + anchorPx).toFloat() }
            if (!placed || delta == null || abs(delta) > heightPx * 1.5f) {
                // The first placement, or a seek far away: nothing to pour, the line is simply there.
                placed = true
                listState.scrollToItem(active)
                return@LaunchedEffect
            }
            val t = withFrameMillis { it }
            withContext(NonCancellable) {
                listState.scrollBy(delta)
                motion.now = t
                motion.moves = motion.moves + Move(t, delta, active)
            }
        }
        // The clock the lines are read against ticks once a frame for as long as any move is under way, and sleeps otherwise.
        LaunchedEffect(motion) {
            while (true) {
                if (motion.moves.isEmpty()) snapshotFlow { motion.moves.isNotEmpty() }.first { it }
                val t = withFrameMillis { it }
                motion.now = t
                val live = motion.moves.filter { t - it.start < MOVE_TOTAL_MS }
                if (live.size != motion.moves.size) motion.moves = live
            }
        }

        val top = if (synced) with(density) { anchorPx.toDp() } else 24.dp
        val bottom = if (synced) with(density) { (heightPx - anchorPx).toDp() } else 24.dp
        LazyColumn(
            Modifier.fillMaxSize().fadingEdges(FADE_EDGE).nestedScroll(userScroll),
            state = listState,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = top, bottom = bottom),
        ) {
            itemsIndexed(texts) { i, text ->
                val dots = if (text.isBlank() && synced) gapFill(timed, i, active, clock) else 0f
                val seek = if (synced) timed.getOrNull(i)?.let { LyricsPicker.seekPosition(it, offset) } else null
                // Tapping a line moves the track to it - the nearest line, so the gaps are not dead.
                LyricRow(text, i, active, synced, browsing, motion, fontSize, dots, centered, seek?.let { { app.player.seekMs(it) } })
            }
        }
    }
}

/** How much of the wait that line [i] stands for has gone: 1 once it is past, 0 before it, the share of its time while it is sung. */
private fun gapFill(timed: List<com.platter.desktop.api.LyricLine>, i: Int, active: Int, clock: Long): Float = when {
    i < active -> 1f
    i > active -> 0f
    else -> {
        val start = timed[i].start
        val end = timed.getOrNull(i + 1)?.start
        if (start == null || end == null || end <= start) 0f else ((clock - start).toFloat() / (end - start)).coerceIn(0f, 1f)
    }
}

@Composable
private fun LyricRow(
    text: String,
    index: Int,
    active: Int,
    synced: Boolean,
    browsing: Boolean,
    motion: LineMotion,
    fontSize: TextUnit,
    gapFill: Float,
    centered: Boolean,
    onSeek: (() -> Unit)?,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val sung = !synced || index == active
    val calm = browsing || hovered

    val alpha by animateFloatAsState(
        when {
            !synced -> UNSYNCED_ALPHA
            sung -> 1f
            calm -> BROWSE_ALPHA
            else -> INACTIVE_ALPHA
        },
        tween(LINE_MS),
    )
    val blur by animateFloatAsState(if (sung || calm) 0f else min(abs(index - active), BLUR_LINES) * BLUR_STEP_DP, tween(LINE_MS))
    val scale by animateFloatAsState(if (sung) 1f else INACTIVE_SCALE, tween(LINE_MS))
    val wash by animateColorAsState(if (hovered && onSeek != null) Color.White.copy(alpha = 0.1f) else Color.Transparent, tween(160))

    Box(
        Modifier
            .fillMaxWidth()
            // Drawn into a layer, so the words are rasterised once and the animation only stretches, moves and blurs the picture.
            .graphicsLayer {
                translationY = motion.shift(index)
                scaleX = scale
                scaleY = scale
                // Lines shrink toward the edge they are read from: the left one, or the middle when they are centred.
                transformOrigin = TransformOrigin(if (centered) 0.5f else 0f, 0.5f)
                this.alpha = alpha
                val radius = blur * density
                renderEffect = if (radius > 0.05f) BlurEffect(radius, radius, TileMode.Decal) else null
            }
            .then(if (onSeek != null) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .hoverable(hover)
            .clickable(interactionSource = hover, indication = null, enabled = onSeek != null) { onSeek?.invoke() }
            .background(wash, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (text.isBlank()) {
            Dots(gapFill, fontSize, centered)
        } else {
            Text(
                text,
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = fontSize, lineHeight = fontSize * 1.22f, fontWeight = FontWeight.Bold),
                color = PlatterColors.White,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** An instrumental stretch: three dots that fill one after another as the wait runs out, as long as the wait is. */
@Composable
private fun Dots(fill: Float, fontSize: TextUnit, centered: Boolean) {
    val shown by animateFloatAsState(fill, tween(300, easing = LinearEasing))
    val height = with(LocalDensity.current) { (fontSize * 1.22f).toDp() }
    val dot = (fontSize.value * 0.34f).dp
    Row(
        Modifier.height(height).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (centered) Arrangement.spacedBy(dot * 0.7f, Alignment.CenterHorizontally) else Arrangement.spacedBy(dot * 0.7f),
    ) {
        repeat(3) { k ->
            Box(
                Modifier
                    .size(dot)
                    .graphicsLayer {
                        val lit = (shown * 3f - k).coerceIn(0f, 1f)
                        alpha = 0.3f + 0.7f * lit
                        scaleX = 0.8f + 0.2f * lit
                        scaleY = scaleX
                    }
                    .background(PlatterColors.White, CircleShape),
            )
        }
    }
}

/** The words dim to nothing over this much of the top and the bottom, as on the phone. */
private val FADE_EDGE = 56.dp

/** Fades the content out to transparent over [length] at the top and at the bottom: a mask drawn over it, so the words under it need no change. */
private fun Modifier.fadingEdges(length: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = (length.toPx() / size.height).coerceAtMost(0.5f)
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, edge to Color.Black, 1f - edge to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }
