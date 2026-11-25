package com.platter.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.awt.KeyboardFocusManager
import java.util.Locale

internal val LocalFocusedEditor = staticCompositionLocalOf<FocusedEditor?> { null }

internal data class CorrectableInput(
    val value: TextFieldValue,
    val onChange: (TextFieldValue) -> Unit,
    val modifier: Modifier,
)

private data class AutomaticEdit(val before: TextFieldValue, val after: TextFieldValue, val layout: Locale?)

@Composable
internal fun rememberCorrectableInput(
    text: () -> String,
    change: (String) -> Unit,
    enabled: Boolean = true,
    available: () -> Boolean = { true },
    normalize: (TextFieldValue) -> TextFieldValue = { it },
): CorrectableInput {
    val currentText by rememberUpdatedState(text)
    val onTextChange by rememberUpdatedState(change)
    val canCorrect by rememberUpdatedState(available)
    val editor = LocalFocusedEditor.current.takeIf { enabled }
    val window = LocalWindowInfo.current
    val owner = remember { Any() }
    val shift = remember { DoubleShift() }
    var draft by remember { mutableStateOf(TextFieldValue(text(), TextRange(text().length))) }
    var automaticEdit by remember { mutableStateOf<AutomaticEdit?>(null) }

    fun invalidate() {
        shift.reset()
        automaticEdit = null
        editor?.changed(owner)
    }

    fun update(value: TextFieldValue) {
        if (value != draft) {
            invalidate()
            draft = value
        }
        if (value.text != currentText()) onTextChange(value.text)
    }

    val model = text()
    val value = if (draft.text == model) draft else TextFieldValue(model, TextRange(model.length))
    SideEffect {
        if (draft.text != currentText() && value.text == currentText()) {
            invalidate()
            draft = value
        }
    }
    LaunchedEffect(window.isWindowFocused) {
        if (!window.isWindowFocused) invalidate()
    }
    DisposableEffect(editor, owner) { onDispose { editor?.detach(owner) } }

    val modifier = if (enabled) Modifier
        .onFocusChanged {
            invalidate()
            if (it.isFocused) {
                editor?.attach(
                    owner,
                    read = { draft.takeIf { window.isWindowFocused && canCorrect() && it.text == currentText() } },
                    write = { corrected, layout ->
                        val original = draft
                        val context = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner?.inputContext
                        val previousLayout = context?.locale
                        update(corrected)
                        automaticEdit = AutomaticEdit(original, corrected, previousLayout)
                        if (previousLayout?.language != layout.language) context?.selectInputMethod(layout)
                    },
                )
            } else {
                editor?.detach(owner)
            }
        }
        .onPreviewKeyEvent {
            if (!shift.onKey(it) || !canCorrect() || draft.text != currentText()) return@onPreviewKeyEvent false
            val context = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner?.inputContext
            val undo = automaticEdit?.takeIf { it.after == draft }
            if (undo != null) {
                update(undo.before)
                undo.layout?.let { context?.selectInputMethod(it) }
            } else {
                val correction = correctSearchLayout(draft, context?.locale) ?: return@onPreviewKeyEvent false
                update(correction.value)
                if (context?.locale?.language != correction.locale.language) context?.selectInputMethod(correction.locale)
            }
            true
        }
    else Modifier

    return CorrectableInput(value, { shift.reset(); update(normalize(it)) }, modifier)
}
