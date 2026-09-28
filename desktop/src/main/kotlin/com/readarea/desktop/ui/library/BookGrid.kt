package com.readarea.desktop.ui.library

import com.readarea.core.library.BookStatus
import com.readarea.desktop.data.Book
import com.readarea.desktop.i18n.I18n
import com.readarea.desktop.i18n.tr
import com.readarea.desktop.ui.components.CoverPainter
import com.readarea.desktop.ui.components.Widget
import com.readarea.desktop.ui.components.ellipsize
import com.readarea.desktop.ui.components.pal
import com.readarea.desktop.ui.components.smooth
import com.readarea.desktop.ui.theme.AppTheme
import com.readarea.desktop.ui.theme.VectorIcon
import com.readarea.desktop.ui.theme.alpha
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.geom.RoundRectangle2D
import java.text.AttributedString
import javax.swing.AbstractAction
import javax.swing.AbstractListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

/** A list model that can be swapped in one go when the library changes. */
class BookModel : AbstractListModel<Book>() {
    var books: List<Book> = emptyList()
        private set

    fun set(list: List<Book>) {
        val old = books.size
        books = list
        if (old > 0) fireIntervalRemoved(this, 0, old - 1)
        if (list.isNotEmpty()) fireIntervalAdded(this, 0, list.size - 1)
    }

    override fun getSize() = books.size
    override fun getElementAt(index: Int): Book = books[index]
}

/**
 * Books as a grid of covers or as a list. Built on JList, so thousands of books scroll smoothly and
 * selection, keyboard navigation and screen readers work as everywhere else.
 */
class BookGrid(private val onOpen: (Book) -> Unit, private val onContext: (List<Book>, MouseEvent) -> Unit) : JList<Book>(BookModel()) {
    val bookModel get() = model as BookModel
    var grid = true
        set(v) {
            field = v
            applyMode()
        }
    var coverSize = 1
        set(v) {
            field = v
            applyMode()
        }
    var showBadges = true

    init {
        selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        isOpaque = false
        cellRenderer = Renderer()
        applyMode()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) bookAt(e)?.let(onOpen)
            }

            override fun mousePressed(e: MouseEvent) = popup(e)
            override fun mouseReleased(e: MouseEvent) = popup(e)
        })
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open")
        actionMap.put("open", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                selectedValue?.let(onOpen)
            }
        })
        getAccessibleContext().accessibleName = tr("nav_library")
    }

    private fun popup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val idx = locationToIndex(e.point)
        if (idx < 0 || getCellBounds(idx, idx)?.contains(e.point) != true) return
        if (!isSelectedIndex(idx)) selectedIndex = idx
        onContext(selectedValuesList, e)
    }

    private fun bookAt(e: MouseEvent): Book? {
        val idx = locationToIndex(e.point)
        if (idx < 0 || getCellBounds(idx, idx)?.contains(e.point) != true) return null
        return model.getElementAt(idx)
    }

    val coverWidth: Int get() = when (coverSize) { 0 -> 118; 2 -> 188; else -> 148 }

    private fun applyMode() {
        if (grid) {
            layoutOrientation = HORIZONTAL_WRAP
            visibleRowCount = -1
            fixedCellWidth = coverWidth + 28
            fixedCellHeight = (coverWidth * 1.5f).toInt() + 86
        } else {
            layoutOrientation = VERTICAL
            visibleRowCount = 8
            fixedCellWidth = -1
            fixedCellHeight = 76
        }
        revalidate()
        repaint()
    }

    override fun getScrollableTracksViewportWidth() = true

    override fun getToolTipText(event: MouseEvent): String? {
        val idx = locationToIndex(event.point)
        if (idx < 0 || getCellBounds(idx, idx)?.contains(event.point) != true) return null
        val b = model.getElementAt(idx)
        return if (b.author.isBlank()) b.title else "${b.title} — ${b.author}"
    }

    private inner class Renderer : Widget(), ListCellRenderer<Book> {
        private var book: Book? = null
        private var selected = false
        private var focused = false

        override fun getListCellRendererComponent(list: JList<out Book>, value: Book, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
            book = value
            selected = isSelected
            focused = cellHasFocus
            getAccessibleContext().accessibleName = value.title
            return this
        }

        override fun getPreferredSize(): Dimension = if (grid) Dimension(coverWidth + 28, (coverWidth * 1.5f).toInt() + 86) else Dimension(400, 76)

        override fun paintComponent(g0: Graphics) {
            val b = book ?: return
            val g = g0.create().smooth()
            if (selected || focused) {
                g.color = if (selected) pal.accentContainer.alpha(if (pal.dark) 200 else 255) else pal.onSurface.alpha(10)
                g.fill(RoundRectangle2D.Float(2f, 2f, width - 4f, height - 4f, 18f, 18f))
            }
            if (grid) paintGrid(g, b) else paintRow(g, b)
            g.dispose()
        }

        private fun paintGrid(g: Graphics2D, b: Book) {
            val cw = coverWidth.toFloat()
            val ch = cw * 1.5f
            val x = 14f
            val y = 12f
            CoverPainter.paint(g, b, x, y, cw, ch, 6f, true) { this@BookGrid.repaint() }
            if (b.missing) {
                g.color = Color(0, 0, 0, 110)
                g.fill(RoundRectangle2D.Float(x, y, cw, ch, 12f, 12f))
            }
            if (b.favorite) VectorIcon("heart-filled", 16) { Color(0xE05A47) }.paintIcon(this, g, (x + cw - 22).toInt(), (y + 6).toInt())
            if (b.status == BookStatus.FINISHED) VectorIcon("check", 14) { Color.WHITE }.let { ic ->
                g.color = pal.success
                g.fillOval((x + 6).toInt(), (y + 6).toInt(), 20, 20)
                ic.paintIcon(this, g, (x + 9).toInt(), (y + 9).toInt())
            }
            var ty = y + ch + 10f
            if (b.progress > 0.005f && b.status != BookStatus.FINISHED) {
                g.color = pal.onSurface.alpha(28)
                g.fill(RoundRectangle2D.Float(x, y + ch + 6f, cw, 3f, 3f, 3f))
                g.color = pal.accent
                g.fill(RoundRectangle2D.Float(x, y + ch + 6f, cw * b.progress.coerceIn(0f, 1f), 3f, 3f, 3f))
                ty += 4f
            }
            g.color = pal.onSurface
            val used = wrap(g, b.title, AppTheme.ui(12.5f, Font.BOLD), x, ty, cw, 2)
            g.font = AppTheme.ui(11.5f)
            g.color = pal.onSurfaceVariant
            val fm = g.fontMetrics
            val author = ellipsize(b.author.ifBlank { if (b.progress > 0) I18n.format("percent_read", (b.progress * 100).toInt()) else "" }, fm, cw)
            g.drawString(author, x, ty + used + fm.ascent + 2)
        }

        private fun paintRow(g: Graphics2D, b: Book) {
            val ch = 60f
            val cw = ch / 1.5f
            CoverPainter.paint(g, b, 16f, 8f, cw, ch, 3f, false) { this@BookGrid.repaint() }
            val x = 16f + cw + 16f
            val right = width - 16f
            g.font = AppTheme.ui(13.5f, Font.BOLD)
            g.color = if (b.missing) pal.onSurfaceVariant else pal.onSurface
            val fm = g.fontMetrics
            val infoW = 220f
            g.drawString(ellipsize(b.title, fm, right - x - infoW), x, 30f)
            g.font = AppTheme.ui(12f)
            g.color = pal.onSurfaceVariant
            val fm2 = g.fontMetrics
            val sub = listOfNotNull(b.author.takeIf { it.isNotBlank() }, b.series?.let { s -> b.seriesIndex?.let { "$s #${formatIndex(it)}" } ?: s }).joinToString(" · ")
            g.drawString(ellipsize(sub, fm2, right - x - infoW), x, 50f)
            val status = when (b.status) {
                BookStatus.FINISHED -> tr("status_finished")
                BookStatus.WANT -> tr("status_want")
                BookStatus.READING -> I18n.format("percent", (b.progress * 100).toInt())
                else -> tr("status_new")
            }
            g.drawString(status, right - fm2.stringWidth(status), 30f)
            if (showBadges) badge(g, b.bookFormat.label.uppercase(), right, 52f)
            if (b.progress > 0.005f && b.status == BookStatus.READING) {
                g.color = pal.onSurface.alpha(28)
                g.fill(RoundRectangle2D.Float(right - 120f, 60f, 120f, 3f, 3f, 3f))
                g.color = pal.accent
                g.fill(RoundRectangle2D.Float(right - 120f, 60f, 120f * b.progress, 3f, 3f, 3f))
            }
        }

        private fun badge(g: Graphics2D, text: String, right: Float, bottom: Float) {
            g.font = AppTheme.ui(9.5f, Font.BOLD)
            val fm = g.fontMetrics
            val w = fm.stringWidth(text) + 10f
            val h = 16f
            g.color = Color(0, 0, 0, 140)
            g.fill(RoundRectangle2D.Float(right - w, bottom - h, w, h, 8f, 8f))
            g.color = Color.WHITE
            g.drawString(text, right - w + 5f, bottom - h + (h + fm.ascent - fm.descent) / 2f)
        }
    }

    companion object {
        fun formatIndex(f: Float): String = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()

        /** Draws up to [maxLines] wrapped lines and returns the height used. */
        fun wrap(g: Graphics2D, text: String, font: Font, x: Float, y: Float, width: Float, maxLines: Int): Float {
            if (text.isEmpty()) return 0f
            val attr = AttributedString(text, mapOf(TextAttribute.FONT to font))
            val lbm = LineBreakMeasurer(attr.iterator, g.fontRenderContext)
            var yy = y
            var lines = 0
            while (lbm.position < text.length && lines < maxLines) {
                val start = lbm.position
                var layout = lbm.nextLayout(width)
                lines++
                if (lines == maxLines && lbm.position < text.length) {
                    g.font = font
                    val rest = ellipsize(text.substring(start), g.fontMetrics, width)
                    layout = java.awt.font.TextLayout(rest, font, g.fontRenderContext)
                }
                yy += layout.ascent
                layout.draw(g, x, yy)
                yy += layout.descent + layout.leading
            }
            return yy - y
        }
    }
}
