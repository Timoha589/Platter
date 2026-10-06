package com.platter.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.platter.desktop.AppController
import com.platter.desktop.Screen
import com.platter.desktop.api.Song
import com.platter.desktop.i18n.t
import com.platter.desktop.player.PlayerState
import com.platter.desktop.player.RepeatMode

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

    val direction = remember { Direction() }
    val forward = direction.update(s.queue.index, s.queue.songs, s.queue.repeat == RepeatMode.All)

    // Colours arrive after the cover does; until they do the last ones stay, and every change is a turn from one to the other.
    val palette = rememberCoverPalette(app, song?.coverArtId)
    var shown by remember { mutableStateOf(CoverPalette(PlatterColors.Smoke, PlatterColors.Iron)) }
    LaunchedEffect(palette) { palette?.let { shown = it } }
    val first by animateColorAsState(shown.primary, tween(BACKGROUND_MS))
    val second by animateColorAsState(shown.secondary, tween(BACKGROUND_MS))

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(first, second, lerp(second, Color.Black, 0.7f))))
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

/** Which way the queue has just moved: forward unless the index went down. A queue that is a new list, or one that wrapped round, counts as forward. */
private class Direction {
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
        val t by animateFloatAsState(if (app.fullPlayerLyrics) 1f else 0f, tween(WORDS_MS, easing = FastOutSlowInEasing), label = "full-player-words")
        val cover = lerp(poses.plain.cover, poses.words.cover, t)
        val header = lerp(poses.plain.header, poses.words.header, t)
        val footer = lerp(poses.plain.footer, poses.words.footer, t)

        // Under the controls, which cross its place on their way: a press on the button that opened it must not land on a line.
        if (app.fullPlayerLyrics || t > 0.001f) {
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
            if (upright && app.fullPlayerLyrics && t > 0.99f && poses.wordsMax > poses.wordsMin) {
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
            CoverStage(app, song, forward, poses.side, RoundedCornerShape(6.dp / scale), Modifier.fillMaxSize())
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

        Column(Modifier.offset(footer.x, footer.y).width(footer.w)) {
            Seek(app, s)
            Spacer(Modifier.height(18.dp))
            Transport(app, s)
            Spacer(Modifier.height(28.dp))
            Volume(app, s, Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

/**
 * The cover, [side] wide, on the stage the track change happens on: the old cover slides off the way the listener went
 * while the new one slides in from the other side, each shrinking as it leaves and growing as it arrives.
 */
@Composable
private fun CoverStage(app: AppController, song: Song?, forward: Boolean, side: Dp, shape: Shape, modifier: Modifier) {
    AnimatedContent(
        targetState = song,
        modifier = modifier,
        contentAlignment = Alignment.Center,
        contentKey = { it?.id },
        transitionSpec = { turn(forward, distance = 2) },
        label = "full-player-cover",
    ) { shown ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (shown != null) {
                Cover(app, shown.coverArtId, side, shape, Modifier.shadow(32.dp, shape))
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
                        shown.artistId?.let { app.fullPlayer = false; app.navigate(Screen.Artist(it)) }
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
        ProgressBar(
            fraction = if (s.durationMs > 0) s.positionMs.toFloat() / s.durationMs else 0f,
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
        ) { app.setVolume(if (s.volume == 0) 100 else 0) }
        Spacer(Modifier.width(8.dp))
        ProgressBar(s.volume / 100f, { app.setVolume((it * 100).toInt()) }, Modifier.width(160.dp))
    }
}
