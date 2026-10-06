package com.platter.desktop.ui

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference

private interface Dwmapi : Library {
    fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int
}

/**
 * Paints the bar Windows puts over the window - the title, minimise, maximise and close - in a dark grey of the app's palette, with
 * white text and glyphs, so it reads as part of the window and not as a white strip over it. The window keeps its
 * native frame: snapping, resizing by the edges and the buttons are Windows' own.
 *
 * Needs Windows 11 for the colours (earlier ones only get the dark mode, where it is supported) and does nothing
 * anywhere else, or if the system refuses: the bar is then simply the usual one.
 */
fun tintTitleBar(window: java.awt.Window, background: Int = TITLE_BAR, text: Int = 0xFFFFFF) {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return
    try {
        val dwm = Native.load("dwmapi", Dwmapi::class.java)
        val hwnd = WinDef.HWND(Native.getComponentPointer(window))
        fun set(attribute: Int, value: Int) = dwm.DwmSetWindowAttribute(hwnd, attribute, IntByReference(value), 4)
        set(DWMWA_USE_IMMERSIVE_DARK_MODE, 1)
        set(DWMWA_BORDER_COLOR, colorRef(background))
        set(DWMWA_CAPTION_COLOR, colorRef(background))
        set(DWMWA_TEXT_COLOR, colorRef(text))
    } catch (e: Throwable) {
        // Not Windows 11, or no DWM: the bar stays as the system draws it.
    }
}

/** A dark grey, a step lighter than the black of the top bar below it, so the two read as two things. */
private const val TITLE_BAR = 0x292929

private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
private const val DWMWA_BORDER_COLOR = 34
private const val DWMWA_CAPTION_COLOR = 35
private const val DWMWA_TEXT_COLOR = 36

/** 0xRRGGBB to the COLORREF Windows wants, which is 0x00BBGGRR. */
private fun colorRef(rgb: Int): Int = ((rgb and 0xFF) shl 16) or (rgb and 0xFF00) or ((rgb shr 16) and 0xFF)
