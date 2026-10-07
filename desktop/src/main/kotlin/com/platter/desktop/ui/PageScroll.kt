package com.platter.desktop.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** How long a page is given to grow tall enough (its lists load as they come) before the place it was left at is let go. */
private const val RESTORE_WAIT_MS = 5_000L

/** A scrolled position: the first item and how far into it, or for a plain scrolled column just the offset. */
data class ScrollSpot(val first: Int, val offset: Int) {
    val isTop: Boolean get() = first == 0 && offset == 0
}

/**
 * Where each page on the Back/Forward trail was scrolled to. Keyed by how deep in the trail the page is: stepping back
 * or forward lands on the same depth again, and a new page pushed there starts from the top.
 */
class ScrollMemory {
    private val spots = HashMap<String, ScrollSpot>()

    operator fun get(key: String): ScrollSpot? = spots[key]

    operator fun set(key: String, spot: ScrollSpot) {
        spots[key] = spot
    }

    /** Forgets the pages at [depth] and deeper: they are about to be replaced by pages that have not been scrolled yet. */
    fun forgetFrom(depth: Int) {
        spots.keys.removeAll { it.substringBefore('|').toInt() >= depth }
    }
}

/** The page being shown, as the scrollers inside it need to know it. */
class PageScroll(val memory: ScrollMemory, val depth: Int) {
    fun key(slot: String) = "$depth|$slot"
}

/** Absent where a screen is shown outside the app's shell (a test), where scrollers just start at the top. */
val LocalPageScroll = staticCompositionLocalOf<PageScroll?> { null }

/**
 * Remembers where the scroller was left and puts it back when the page comes up again. A page that is still loading
 * is waited for: the position is restored once it is tall enough, and nothing is written down until then, so the
 * short page that is there first does not overwrite the place being returned to.
 */
@Composable
private fun KeepPosition(
    page: PageScroll,
    key: String,
    ready: (ScrollSpot) -> Boolean,
    there: (ScrollSpot) -> Boolean,
    jump: suspend (ScrollSpot) -> Unit,
    now: () -> ScrollSpot,
) {
    val saved = remember(key) { page.memory[key] }
    LaunchedEffect(key) {
        if (saved != null && !saved.isTop) {
            withTimeoutOrNull(RESTORE_WAIT_MS) {
                snapshotFlow { ready(saved) }.first { it }
                if (!there(saved)) jump(saved)
            }
        }
        snapshotFlow(now).collect { page.memory[key] = it }
    }
}

/** The state of a list on a page: put back where it was left when the page is stepped back or forward to. [slot] tells apart several lists on one page. */
@Composable
fun rememberPageListState(slot: String = ""): LazyListState {
    val page = LocalPageScroll.current ?: return rememberLazyListState()
    val key = page.key(slot)
    val state = remember(key) { page.memory[key].let { LazyListState(it?.first ?: 0, it?.offset ?: 0) } }
    KeepPosition(
        page, key,
        ready = { state.layoutInfo.totalItemsCount > it.first },
        there = { state.firstVisibleItemIndex == it.first && state.firstVisibleItemScrollOffset == it.offset },
        jump = { state.scrollToItem(it.first, it.offset) },
        now = { ScrollSpot(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) },
    )
    return state
}

@Composable
fun rememberPageGridState(slot: String = ""): LazyGridState {
    val page = LocalPageScroll.current ?: return rememberLazyGridState()
    val key = page.key(slot)
    val state = remember(key) { page.memory[key].let { LazyGridState(it?.first ?: 0, it?.offset ?: 0) } }
    KeepPosition(
        page, key,
        ready = { state.layoutInfo.totalItemsCount > it.first },
        there = { state.firstVisibleItemIndex == it.first && state.firstVisibleItemScrollOffset == it.offset },
        jump = { state.scrollToItem(it.first, it.offset) },
        now = { ScrollSpot(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) },
    )
    return state
}

/** The state of a page that is one scrolled column. */
@Composable
fun rememberPageScrollState(slot: String = ""): ScrollState {
    val page = LocalPageScroll.current ?: return rememberScrollState()
    val key = page.key(slot)
    val state = remember(key) { ScrollState(page.memory[key]?.first ?: 0) }
    KeepPosition(
        page, key,
        // Until the column has been measured once its end is not known (it reads as the largest number there is).
        ready = { state.maxValue != Int.MAX_VALUE && state.maxValue >= it.first },
        there = { state.value == it.first },
        jump = { state.scrollTo(it.first) },
        now = { ScrollSpot(state.value, 0) },
    )
    return state
}
