package com.platter.desktop.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.util.Locale

/** The remote sees only a short suffix before the caret in the focused text field. */
internal class FocusedEditor {
    var windowMatches: (Long) -> Boolean = { false }
    private var read: () -> TextFieldValue? = { null }
    private var write: (TextFieldValue, Locale) -> Unit = { _, _ -> }
    private var owner: Any? = null
    private var revision = 0L
    private var expiresAt: Long? = null

    fun attach(owner: Any, read: () -> TextFieldValue?, write: (TextFieldValue, Locale) -> Unit) {
        this.owner = owner
        this.read = read
        this.write = write
        invalidate()
    }

    fun detach(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        read = { null }
        write = { _, _ -> }
        invalidate()
    }

    fun changed(owner: Any) {
        if (this.owner === owner) invalidate()
    }

    private fun invalidate() {
        revision++
        expiresAt = null
    }

    fun snapshot(window: Long): Map<String, Any>? {
        val value = active(window) ?: return null
        val caret = value.selection.end
        var start = (caret - MAX_PREFIX).coerceAtLeast(0)
        if (start < caret && value.text[start].isLowSurrogate()) start++
        expiresAt = System.nanoTime() + 400_000_000
        return mapOf("active" to true, "revision" to revision, "prefix" to value.text.substring(start, caret))
    }

    fun replace(window: Long, expectedRevision: Long, before: String, after: String, layout: String): Boolean {
        val deadline = expiresAt ?: return false
        if (expectedRevision != revision || System.nanoTime() - deadline >= 0) return false
        val value = active(window) ?: return false
        if (before.isEmpty() || before.length > MAX_PREFIX || after.length > 8192 || after.any { it == '\r' || it == '\n' }) {
            return false
        }
        val end = value.selection.end
        val start = end - before.length
        if (start < 0 || value.text.substring(start, end) != before) return false
        val language = when (layout) {
            "en" -> Locale.US
            "ru" -> Locale.forLanguageTag("ru-RU")
            else -> return false
        }
        invalidate()
        write(TextFieldValue(value.text.replaceRange(start, end, after), TextRange(start + after.length)), language)
        return true
    }

    private fun active(window: Long): TextFieldValue? =
        if (windowMatches(window)) read()?.takeIf { it.selection.collapsed && it.composition == null } else null

    private companion object {
        const val MAX_PREFIX = 386
    }
}
