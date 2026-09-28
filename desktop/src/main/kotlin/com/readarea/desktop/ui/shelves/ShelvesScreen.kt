package com.readarea.desktop.ui.shelves

import com.readarea.desktop.App
import com.readarea.desktop.data.Book
import com.readarea.desktop.data.ShelfSummary
import com.readarea.desktop.i18n.I18n
import com.readarea.desktop.i18n.tr
import com.readarea.desktop.ui.MainWindow
import com.readarea.desktop.ui.Screen
import com.readarea.desktop.ui.components.ButtonKind
import com.readarea.desktop.ui.components.ellipsize
import com.readarea.desktop.ui.components.Widget
import com.readarea.desktop.ui.components.CoverPainter
import com.readarea.desktop.ui.components.Dialogs
import com.readarea.desktop.ui.components.IconButton
import com.readarea.desktop.ui.components.PillButton
import com.readarea.desktop.ui.components.ScrollableColumn
import com.readarea.desktop.ui.components.Transparent
import com.readarea.desktop.ui.components.Ui
import com.readarea.desktop.ui.components.WrapLayout
import com.readarea.desktop.ui.components.emptyState
import com.readarea.desktop.ui.components.icon
import com.readarea.desktop.ui.components.pal
import com.readarea.desktop.ui.components.smooth
import com.readarea.desktop.ui.library.BookActions
import com.readarea.desktop.ui.library.BookGrid
import com.readarea.desktop.ui.library.LibrarySort
import com.readarea.desktop.ui.library.LibraryQuery
import com.readarea.desktop.ui.theme.AppTheme
import com.readarea.desktop.ui.theme.alpha
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import com.readarea.desktop.ui.components.LeadingBorder

class ShelvesScreen(private val app: App, private val window: MainWindow) : Screen {
    private val cards = CardLayout()
    private val root = Transparent(cards)
    // The negative leading inset cancels the gap FlowLayout puts before the first card, lining it up with the title.
    private val grid = ScrollableColumn(WrapLayout(java.awt.FlowLayout.LEADING, 18, 18)).apply { border = LeadingBorder(0, -18, 0, 0) }
    private val listCards = CardLayout()
    private val listBody = Transparent(listCards)
    private val detailTitle = Ui.headline("")
    private val detailCount = Ui.secondary("")
    private val books = BookGrid(onOpen = { app.openBook(it.id) }, onContext = { list, e -> detailMenu(list, e) })
    private val detailCards = CardLayout()
    private val detailBody = Transparent(detailCards)
    private var shelves: List<ShelfSummary> = emptyList()
    private var all: List<Book> = emptyList()
    private var openShelf: Long? = null
    override val component: JComponent = root

    init {
        val newShelf = PillButton(tr("new_shelf"), "add", ButtonKind.PRIMARY, compact = true).apply { addActionListener { create() } }
        val header = Transparent(BorderLayout()).apply {
            add(Ui.headline(tr("nav_shelves")), BorderLayout.LINE_START)
            add(newShelf, BorderLayout.LINE_END)
        }
        listBody.add(Ui.scroll(grid), "grid")
        listBody.add(emptyState("shelves", tr("shelves_empty_title"), tr("shelves_empty_body"), PillButton(tr("new_shelf"), "add").apply { addActionListener { create() } }), "empty")
        root.add(Transparent(BorderLayout()).apply {
            border = LeadingBorder(22, 28, 0, 28)
            add(Ui.padded(header, 0, 0, 12, 0), BorderLayout.NORTH)
            add(listBody, BorderLayout.CENTER)
        }, "list")

        val back = IconButton("back", tr("back"))
        back.addActionListener { closeShelf() }
        val options = IconButton("more", tr("shelf_options"))
        options.addActionListener {
            val id = openShelf ?: return@addActionListener
            val s = shelves.firstOrNull { it.shelf.id == id } ?: return@addActionListener
            JPopupMenu().apply {
                add(JMenuItem(tr("rename_shelf"), icon("edit", 16)).apply { addActionListener { rename(s) } })
                add(JMenuItem(tr("delete_shelf"), icon("trash", 16)).apply { addActionListener { delete(s) } })
            }.show(options, 0, options.height)
        }
        val detailHeader = Transparent(BorderLayout()).apply {
            add(Ui.hbox(back, Ui.gap(6), detailTitle, Ui.gap(12), detailCount), BorderLayout.LINE_START)
            add(options, BorderLayout.LINE_END)
        }
        root.add(Transparent(BorderLayout()).apply {
            border = LeadingBorder(18, 22, 0, 28)
            add(Ui.padded(detailHeader, 0, 0, 10, 0), BorderLayout.NORTH)
            detailBody.add(Ui.scroll(books), "books")
            detailBody.add(emptyState("shelves", tr("shelf_empty_title"), tr("shelf_empty_body")), "empty")
            add(detailBody, BorderLayout.CENTER)
        }, "detail")

        app.scope.launch {
            app.library.books.combine(app.library.version) { b, _ -> b }.collect { b ->
                all = b.orEmpty()
                shelves = app.library.read { shelves() }
                render()
            }
        }
    }

    private fun render() {
        grid.removeAll()
        shelves.forEach { grid.add(ShelfCard(it)) }
        listCards.show(listBody, if (shelves.isEmpty()) "empty" else "grid")
        grid.revalidate()
        grid.repaint()
        openShelf?.let { id -> if (shelves.none { it.shelf.id == id }) closeShelf() else showShelf(id) }
    }

    private fun showShelf(id: Long) {
        val s = shelves.firstOrNull { it.shelf.id == id } ?: return
        openShelf = id
        detailTitle.text = s.shelf.name
        detailCount.text = I18n.plural("book_count", s.count, s.count)
        val members = s.bookIds.toSet()
        val list = LibrarySort.apply(all.filter { it.id in members }, LibraryQuery(), app.settings.app.value.sort)
        books.bookModel.set(list)
        books.coverSize = app.settings.app.value.coverSize
        detailCards.show(detailBody, if (list.isEmpty()) "empty" else "books")
        cards.show(root, "detail")
    }

    private fun closeShelf() {
        openShelf = null
        cards.show(root, "list")
    }

    private fun detailMenu(list: List<Book>, e: MouseEvent) {
        val id = openShelf ?: return
        JPopupMenu().apply {
            add(JMenuItem(tr("open"), icon("read", 16)).apply { addActionListener { list.take(4).forEach { app.openBook(it.id) } } })
            list.singleOrNull()?.let { b -> add(JMenuItem(tr("details"), icon("info", 16)).apply { addActionListener { window.showBook(b.id) } }) }
            add(JMenuItem(tr("remove_from_shelf"), icon("close", 16)).apply { addActionListener { app.scope.launch { app.library.removeFromShelf(id, list.map { it.id }) } } })
            addSeparator()
            add(JMenuItem(tr("remove") + "…", icon("trash", 16)).apply { addActionListener { BookActions.remove(app, window, list) } })
        }.show(e.component, e.x, e.y)
    }

    private fun create() {
        val name = Dialogs.input(window, tr("new_shelf"), "", tr("create"), placeholder = tr("name"))?.trim()
        if (!name.isNullOrEmpty()) app.scope.launch { app.library.createShelf(name) }
    }

    private fun rename(s: ShelfSummary) {
        val name = Dialogs.input(window, tr("rename_shelf"), s.shelf.name, tr("save"))?.trim()
        if (!name.isNullOrEmpty()) app.scope.launch { app.library.renameShelf(s.shelf.id, name) }
    }

    private fun delete(s: ShelfSummary) {
        val (ok, _) = Dialogs.confirm(window, tr("delete_shelf_title", s.shelf.name), tr("delete_shelf_body"), tr("delete"), danger = true)
        if (ok) app.scope.launch { app.library.deleteShelf(s.shelf.id) }
    }

    override fun onShow() {
        if (openShelf != null) showShelf(openShelf!!)
    }

    /** A shelf as a card: up to three covers fanned out, its name and how many books it holds. */
    private inner class ShelfCard(private val s: ShelfSummary) : Widget(javax.accessibility.AccessibleRole.PUSH_BUTTON) {
        private var hover = false
        private val covers = s.bookIds.mapNotNull { id -> all.firstOrNull { it.id == id } }.sortedByDescending { it.lastOpenedAt }.take(3)

        init {
            preferredSize = Dimension(220, 250)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = s.shelf.name
            getAccessibleContext().accessibleName = s.shelf.name
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON3) {
                        JPopupMenu().apply {
                            add(JMenuItem(tr("rename_shelf")).apply { addActionListener { rename(s) } })
                            add(JMenuItem(tr("delete_shelf")).apply { addActionListener { delete(s) } })
                        }.show(this@ShelfCard, e.x, e.y)
                    } else showShelf(s.shelf.id)
                }

                override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
                override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
            })
        }

        override fun paintComponent(g0: Graphics) {
            val g = g0.create().smooth()
            g.color = if (hover) pal.surfaceHigh else pal.surfaceContainer
            g.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 26f, 26f))
            val cw = 96f
            val ch = cw * 1.5f
            val cx = width / 2f
            if (covers.isEmpty()) {
                g.color = pal.onSurface.alpha(20)
                g.fill(RoundRectangle2D.Float(cx - cw / 2, 22f, cw, ch, 10f, 10f))
            }
            covers.reversed().forEachIndexed { i, b ->
                val n = covers.size
                val offset = (i - (n - 1) / 2f) * 34f
                val saved = g.transform
                g.rotate(Math.toRadians(offset * 0.12), (cx + offset).toDouble(), (22 + ch).toDouble())
                CoverPainter.paint(g, b, cx + offset - cw / 2, 22f + kotlin.math.abs(offset) * 0.15f, cw, ch, 5f, true) { repaint() }
                g.transform = saved
            }
            g.font = AppTheme.ui(14f, Font.BOLD)
            g.color = pal.onSurface
            val fm = g.fontMetrics
            val name = ellipsize(s.shelf.name, fm, width - 24f)
            g.drawString(name, (width - fm.stringWidth(name)) / 2f, height - 38f)
            g.font = AppTheme.ui(12f)
            g.color = pal.onSurfaceVariant
            val count = I18n.plural("book_count", s.count, s.count)
            g.drawString(count, (width - g.fontMetrics.stringWidth(count)) / 2f, height - 18f)
            g.dispose()
        }
    }
}
