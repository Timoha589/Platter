package com.cappielloantonio.tempo.helper.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cappielloantonio.tempo.R

/**
 * Page dots under a paged rail on Home - one dot per page of rows.
 *
 * Drawn to DESIGN.md: filled dots with no outline (surface colour alone
 * defines boundaries), the current page in Pure White and the others in Steel,
 * the palette's colour for muted UI elements. It is a view of its own under
 * the rail rather than something painted over the list, so its spacing lives
 * in the layout like everything else's (see the PageDots style), and it is
 * simply gone while the rail has a single page.
 *
 * The white dot follows the scroll position instead of jumping once a page has
 * settled, so it slides from dot to dot under the finger as the rail moves.
 */
class PageDotsView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null
) : View(context, attrs) {

    private val diameter = resources.getDimensionPixelSize(R.dimen.page_dot_size)
    private val step = diameter + resources.getDimensionPixelSize(R.dimen.page_dot_gap)

    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.steel)
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.pure_white)
    }

    private var rail: RecyclerView? = null

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = invalidate()
    }

    private val dataObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = update()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = update()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = update()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = update()
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = update()
    }

    /** Call once the rail has its adapter. */
    fun attachTo(recyclerView: RecyclerView) {
        rail = recyclerView
        recyclerView.addOnScrollListener(scrollListener)
        recyclerView.adapter?.registerAdapterDataObserver(dataObserver)
        update()
    }

    private fun update() {
        visibility = if (pageCount() > 1) VISIBLE else GONE
        invalidate()
    }

    private fun pageCount(): Int {
        val recyclerView = rail ?: return 0
        val items = recyclerView.adapter?.itemCount ?: return 0
        val rows = (recyclerView.layoutManager as? GridLayoutManager)?.spanCount ?: 1
        return (items + rows - 1) / rows
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
                getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
                paddingTop + diameter + paddingBottom
        )
    }

    override fun onDraw(canvas: Canvas) {
        val recyclerView = rail ?: return
        val pages = pageCount()
        if (pages <= 1) return

        val radius = diameter / 2f
        val startX = (width - (pages - 1) * step) / 2f
        val centerY = paddingTop + radius

        for (i in 0 until pages) {
            canvas.drawCircle(startX + i * step, centerY, radius, inactivePaint)
        }

        val scrollable = recyclerView.computeHorizontalScrollRange() - recyclerView.computeHorizontalScrollExtent()
        val progress = if (scrollable > 0) {
            (recyclerView.computeHorizontalScrollOffset().toFloat() / scrollable).coerceIn(0f, 1f)
        } else 0f

        canvas.drawCircle(startX + progress * (pages - 1) * step, centerY, radius, activePaint)
    }
}
