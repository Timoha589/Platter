package com.cappielloantonio.tempo.helper.view

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.max

/**
 * The action row of every alert dialog - Save, Cancel, Reset and the like.
 *
 * AppCompat's AlertDialogLayout gives the button panel only its "minimum
 * height" first and hands everything else to the content. For buttons that
 * had to stack because they did not fit side by side, that minimum is one
 * button plus a 16dp peek of the next, so on a narrow phone with a long list
 * in the dialog the actions shrank into a strip that had to be scrolled, with
 * Cancel cut in half and Reset out of sight.
 *
 * Reporting the whole measured height as the minimum keeps every button on
 * screen at all times; the content above is what gives way and scrolls.
 */
class DialogButtonPanel @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    override fun getMinimumHeight(): Int = max(super.getMinimumHeight(), measuredHeight)
}
