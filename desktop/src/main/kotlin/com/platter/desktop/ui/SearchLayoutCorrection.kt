package com.platter.desktop.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.TextFieldValue
import com.platter.desktop.search.QueryVariants
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Two standalone Shift presses in the focused search field. */
internal class DoubleShift {
    private var pressed: Key? = null
    private var pressedAt = 0L
    private var releasedAt: Long? = null

    fun reset() {
        pressed = null
        releasedAt = null
    }

    fun onKey(event: KeyEvent): Boolean {
        val shift = event.key == Key.ShiftLeft || event.key == Key.ShiftRight
        if (!shift || event.isAltPressed || event.isCtrlPressed || event.isMetaPressed) {
            if (event.type == KeyEventType.KeyDown) reset()
            return false
        }
        val now = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
        if (event.type == KeyEventType.KeyDown) {
            when (pressed) {
                null -> {
                    pressed = event.key
                    pressedAt = now
                }
                event.key -> Unit
                else -> reset()
            }
        } else if (event.type == KeyEventType.KeyUp && pressed == event.key) {
            pressed = null
            if (now - pressedAt > 800) {
                releasedAt = null
                return false
            }
            val double = releasedAt?.let { pressedAt - it in 0L..400L } == true
            releasedAt = if (double) null else now
            return double
        }
        return false
    }
}

internal data class SearchLayoutCorrection(val value: TextFieldValue, val locale: Locale)

internal fun correctSearchLayout(value: TextFieldValue, currentLocale: Locale?): SearchLayoutCorrection? {
    if (value.composition != null) return null
    var start = value.selection.min
    var end = value.selection.max
    if (start == end) {
        // Include the last word before the caret, with or without a trailing space.
        while (end > 0 && value.text[end - 1].isWhitespace()) end--
        start = end
        while (start > 0 && !value.text[start - 1].isWhitespace()) start--
    }
    val before = value.text.substring(start, end)
    val russian = before.count { it.lowercaseChar() in 'а'..'я' || it.lowercaseChar() == 'ё' }
    val english = before.count { it.lowercaseChar() in 'a'..'z' }
    val toRussian = when {
        english > russian -> true
        russian > english -> false
        currentLocale?.language == "en" -> true
        currentLocale?.language == "ru" -> false
        else -> return null
    }
    val after = QueryVariants.swapKeyboardLayout(before, toRussian)
    if (after == before) return null
    val locale = if (toRussian) Locale.forLanguageTag("ru-RU") else Locale.US
    return SearchLayoutCorrection(value.copy(text = value.text.replaceRange(start, end, after)), locale)
}
