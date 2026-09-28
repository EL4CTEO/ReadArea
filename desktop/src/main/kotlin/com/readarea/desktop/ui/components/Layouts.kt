package com.readarea.desktop.ui.components

import com.readarea.desktop.ui.theme.AppTheme
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.Timer

/** A flow layout that grows vertically to fit its wrapped rows, so it works inside a scroll pane. */
class WrapLayout(align: Int = LEFT, hgap: Int = 16, vgap: Int = 16) : FlowLayout(align, hgap, vgap) {
    override fun preferredLayoutSize(target: Container): Dimension = size(target, true)
    override fun minimumLayoutSize(target: Container): Dimension = size(target, false).also { it.width -= hgap + 1 }

    private fun size(target: Container, preferred: Boolean): Dimension = synchronized(target.treeLock) {
        var width = target.size.width
        var parent: Container? = target
        while (width == 0 && parent?.parent != null) {
            parent = parent.parent
            width = parent.size.width
        }
        if (width == 0) width = Int.MAX_VALUE
        val insets = target.insets
        val maxWidth = width - (insets.left + insets.right + hgap * 2)
        val dim = Dimension(0, 0)
        var rowWidth = 0
        var rowHeight = 0
        for (m in target.components) {
            if (!m.isVisible) continue
            val d = if (preferred) m.preferredSize else m.minimumSize
            if (rowWidth + d.width > maxWidth) {
                dim.width = maxOf(dim.width, rowWidth)
                dim.height += rowHeight + vgap
                rowWidth = 0
                rowHeight = 0
            }
            if (rowWidth != 0) rowWidth += hgap
            rowWidth += d.width
            rowHeight = maxOf(rowHeight, d.height)
        }
        dim.width = maxOf(dim.width, rowWidth)
        dim.height += rowHeight
        dim.width += insets.left + insets.right + hgap * 2
        dim.height += insets.top + insets.bottom + vgap * 2
        val scroll = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, target)
        if (scroll != null && target.isValid) dim.width -= hgap + 1
        dim
    }
}

/**
 * The heading row of a screen: the title at the leading edge, its main action at the trailing edge and the tools
 * (search, sort, view switches) between them. When they don't fit on one line, as in a narrow window or with a long
 * translation, the tools drop to a second row under the title, so nothing is printed over anything else or cut off.
 */
class ScreenHeader(private val title: Component, private val tools: Component, private val action: Component? = null, private val gap: Int = 12) : Transparent(null) {
    /** Whether the last preferred height was worked out for two rows; the height depends on the width it is given. */
    private var assumedStacked = false

    init {
        add(title)
        add(tools)
        action?.let { add(it) }
    }

    private fun room(): Int = width - insets.left - insets.right

    /** The tools may shrink (a search field gives way) but the title and the action keep their size. */
    private fun fitsOnOneLine(room: Int): Boolean =
        title.preferredSize.width + gap + tools.minimumSize.width + (action?.let { gap + it.preferredSize.width } ?: 0) <= room

    override fun getPreferredSize(): Dimension {
        val t = title.preferredSize
        val tl = tools.preferredSize
        val a = action?.preferredSize ?: Dimension()
        val stacked = width > 0 && !fitsOnOneLine(room())
        assumedStacked = stacked
        val natural = t.width + gap + tl.width + (if (action != null) gap + a.width else 0)
        val h = if (stacked) maxOf(t.height, a.height) + gap + tl.height else maxOf(t.height, tl.height, a.height)
        return Dimension(natural + insets.left + insets.right, h + insets.top + insets.bottom)
    }

    override fun getMinimumSize(): Dimension {
        val stackedWidth = maxOf(title.preferredSize.width + (action?.let { gap + it.preferredSize.width } ?: 0), tools.minimumSize.width)
        return Dimension(stackedWidth + insets.left + insets.right, preferredSize.height)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun doLayout() {
        val ins = insets
        val w = room()
        val ltr = componentOrientation.isLeftToRight
        val t = title.preferredSize
        val tl = tools.preferredSize
        val a = action?.preferredSize ?: Dimension()
        val stacked = !fitsOnOneLine(w)
        // Positions run from the leading edge, so a right-to-left window is the mirror image.
        fun put(c: Component, x: Int, y: Int, cw: Int, ch: Int) = c.setBounds(if (ltr) ins.left + x else width - ins.right - x - cw, ins.top + y, cw, ch)
        val firstRow = if (stacked) maxOf(t.height, a.height) else maxOf(t.height, tl.height, a.height)
        put(title, 0, (firstRow - t.height) / 2, t.width, t.height)
        var right = w
        if (action != null) {
            put(action, w - a.width, (firstRow - a.height) / 2, a.width, a.height)
            right = w - a.width - gap
        }
        if (stacked) {
            put(tools, 0, firstRow + gap, minOf(tl.width, w), tl.height)
        } else {
            val tw = minOf(tl.width, right - t.width - gap).coerceAtLeast(0)
            put(tools, right - tw, (firstRow - tl.height) / 2, tw, tl.height)
        }
        // The height changes with the mode: ask the parent to lay this out again with the right one.
        if (stacked != assumedStacked) SwingUtilities.invokeLater { revalidate() }
    }
}

/** A short message floating at the bottom of a window for a few seconds. */
class Toast : Widget() {
    private var text = ""
    private val timer = Timer(3200) { isVisible = false }.apply { isRepeats = false }

    init {
        isVisible = false
        font = AppTheme.ui(13f, Font.BOLD)
    }

    fun show(message: String, host: Dimension) {
        text = message
        font = AppTheme.ui(13f, Font.BOLD)
        layoutIn(host)
        isVisible = true
        repaint()
        timer.restart()
    }

    fun layoutIn(host: Dimension) {
        val fm = getFontMetrics(font)
        val w = (fm.stringWidth(text) + 40).coerceAtMost(host.width - 40)
        val h = 40
        setBounds((host.width - w) / 2, host.height - h - 28, w, h)
    }

    override fun paintComponent(g0: Graphics) {
        val g = g0.create().smooth()
        g.color = Color(0x2A2622).let { if (AppTheme.palette.dark) Color(0xEDE6DA) else it }
        g.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), height.toFloat(), height.toFloat()))
        g.color = if (AppTheme.palette.dark) Color(0x1E1B17) else Color(0xF7F2EA)
        g.font = font
        val fm = g.fontMetrics
        val t = ellipsize(text, fm, width - 32f)
        g.drawString(t, (width - fm.stringWidth(t)) / 2f, (height + fm.ascent - fm.descent) / 2f)
        g.dispose()
    }
}

/** Keeps a component's width in step with its scroll pane's viewport, so wrapped content reflows. */
open class ScrollableColumn(layout: java.awt.LayoutManager?) : Transparent(layout), javax.swing.Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int) = 24
    override fun getScrollableBlockIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int) = visibleRect.height - 48
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
}

/** A vertical stack (see [VBox]) that follows its scroll pane's width, for scrolling pages and panels. */
class ScrollingStack : VBox(), javax.swing.Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int) = 24
    override fun getScrollableBlockIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int) = visibleRect.height - 48
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
}

fun Component.maxWidth(w: Int): Component = apply { (this as? JComponent)?.maximumSize = Dimension(w, maximumSize.height) }

/**
 * Adds [c] on [layer]. Calling `add(c, JLayeredPane.X_LAYER)` from Kotlin picks the `add(Component, int)`
 * overload, which treats the layer as a position and leaves the component on the default layer.
 */
fun javax.swing.JLayeredPane.addOnLayer(c: Component, layer: Int) {
    setLayer(c, layer)
    add(c)
}
