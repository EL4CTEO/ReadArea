package com.readarea.desktop.ui.library

import com.readarea.core.book.BookOpener
import com.readarea.core.format.BlockKind
import com.readarea.core.library.BookStatus
import com.readarea.desktop.App
import com.readarea.desktop.data.Book
import com.readarea.desktop.i18n.I18n
import com.readarea.desktop.i18n.tr
import com.readarea.desktop.platform.SystemIntegration
import com.readarea.desktop.ui.MainWindow
import com.readarea.desktop.ui.components.ButtonKind
import com.readarea.desktop.ui.components.Widget
import com.readarea.desktop.ui.components.Chip
import com.readarea.desktop.ui.components.CoverPainter
import com.readarea.desktop.ui.components.Dialogs
import com.readarea.desktop.ui.components.IconButton
import com.readarea.desktop.ui.components.PillButton
import com.readarea.desktop.ui.components.ScrollableColumn
import com.readarea.desktop.ui.components.WrapText
import com.readarea.desktop.ui.components.Segmented
import com.readarea.desktop.ui.components.Transparent
import com.readarea.desktop.ui.components.Ui
import com.readarea.desktop.ui.components.pal
import com.readarea.desktop.ui.components.smooth
import com.readarea.desktop.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.KeyEvent
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.border.EmptyBorder

class BookDetailsDialog(private val app: App, private val window: MainWindow, private val bookId: Long) : JDialog(window, "", ModalityType.MODELESS) {
    private val root = object : JPanel(BorderLayout()) {
        override fun paintComponent(g: Graphics) {
            g.color = pal.surface
            g.fillRect(0, 0, width, height)
        }
    }

    init {
        contentPane = root
        root.border = EmptyBorder(24, 26, 20, 26)
        size = Dimension(760, 560)
        minimumSize = Dimension(620, 440)
        setLocationRelativeTo(window)
        rootPane.registerKeyboardAction({ dispose() }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)
        app.scope.launch { load() }
    }

    private suspend fun load() {
        val book = app.library.get(bookId) ?: return dispose()
        val shelves = app.library.read { shelves() }
        val onShelves = app.library.read { shelvesFor(bookId) }.toSet()
        title = book.title
        build(book, shelves.map { it.shelf.id to it.shelf.name }, onShelves)
    }

    private fun build(b: Book, shelves: List<Pair<Long, String>>, onShelves: Set<Long>) {
        root.removeAll()
        val cover = object : Widget() {
            init {
                preferredSize = Dimension(190, 285)
            }

            override fun paintComponent(g: Graphics) = CoverPainter.paint(g.create().smooth(), b, 4f, 2f, 182f, 273f, 8f, true) { repaint() }
        }
        val read = PillButton(if (b.progress > 0.005f) I18n.format("continue_percent", (b.progress * 100).toInt()) else tr("start_reading"), "read").apply {
            isEnabled = !b.missing
            addActionListener {
                app.openBook(b.id)
                dispose()
            }
        }
        val left = Ui.vbox(cover, Ui.gap(14), read, gap = 0, align = CENTER_ALIGNMENT)
        left.border = EmptyBorder(0, 0, 0, 24)

        val titleLabel = WrapText(b.title).apply { font = AppTheme.headline(23f) }
        val authorRow = Transparent(FlowLayout(FlowLayout.LEFT, 0, 0))
        if (b.author.isNotBlank()) authorRow.add(PillButton(b.author, null, ButtonKind.TEXT, compact = true).apply {
            border = EmptyBorder(2, 0, 2, 6)
            toolTipText = tr("more_by_author")
            addActionListener {
                window.navigate("library")
                window.library.showAuthor(b.author)
                dispose()
            }
        })
        val series = b.series?.let { s -> Ui.secondary(tr("series") + ": " + s + (b.seriesIndex?.let { " #" + BookGrid.formatIndex(it) } ?: "")) }

        val stars = Transparent(FlowLayout(FlowLayout.LEFT, 0, 0))
        var rating = b.rating
        val starButtons = (1..5).map { n ->
            IconButton(if (n <= rating) "star-filled" else "star", I18n.format("rate_stars", n), 18) { if (n <= rating) Color(0xE8A33D) else pal.outline }
        }
        starButtons.forEachIndexed { i, btn ->
            btn.addActionListener {
                rating = if (rating == i + 1) 0 else i + 1
                starButtons.forEachIndexed { k, s -> s.iconName = if (k < rating) "star-filled" else "star" }
                app.scope.launch { app.library.setRating(b.id, rating) }
            }
            stars.add(btn)
        }
        var favorite = b.favorite
        val fav = IconButton(if (favorite) "heart-filled" else "heart", tr("favorite"), 18) { if (favorite) Color(0xE05A47) else pal.outline }
        fav.addActionListener {
            favorite = !favorite
            fav.iconName = if (favorite) "heart-filled" else "heart"
            app.scope.launch { app.library.setFavorite(listOf(b.id), favorite) }
        }
        stars.add(Ui.gap(10))
        stars.add(fav)

        val statusOrder = listOf(BookStatus.NEW, BookStatus.READING, BookStatus.WANT, BookStatus.FINISHED)
        val status = Segmented(listOf(tr("status_new"), tr("status_reading"), tr("status_want"), tr("status_finished")), statusOrder.indexOf(b.status).coerceAtLeast(0)) { i ->
            app.scope.launch { app.library.setStatus(listOf(b.id), statusOrder[i]) }
        }
        status.maximumSize = Dimension(460, 34)

        val shelfRow = Transparent(FlowLayout(FlowLayout.LEFT, 6, 4))
        for ((id, name) in shelves) {
            val c = Chip(name, id in onShelves, "shelves")
            c.addActionListener { app.scope.launch { if (c.isSelected) app.library.addToShelf(id, listOf(b.id)) else app.library.removeFromShelf(id, listOf(b.id)) } }
            shelfRow.add(c)
        }
        shelfRow.add(PillButton(tr("new_shelf"), "add", ButtonKind.TEXT, compact = true).apply {
            addActionListener {
                val name = Dialogs.input(this@BookDetailsDialog, tr("new_shelf"), "", tr("create"), placeholder = tr("name"))?.trim()
                if (!name.isNullOrEmpty()) app.scope.launch {
                    app.library.createShelf(name, listOf(b.id))
                    load()
                }
            }
        })

        val description = b.description?.takeIf { it.isNotBlank() }?.let { d -> WrapText(d, 13f) { pal.onSurfaceVariant } }

        val info = infoGrid(b)
        val right = Ui.vbox(titleLabel, Ui.gap(2), authorRow)
        series?.let { right.add(it) }
        right.add(Ui.gap(8))
        right.add(stars)
        right.add(Ui.gap(10))
        right.add(status)
        right.add(Ui.gap(14))
        right.add(Ui.label(tr("nav_shelves"), 12.5f, Font.BOLD))
        right.add(shelfRow)
        description?.let {
            right.add(Ui.gap(10))
            right.add(Ui.label(tr("about_book"), 12.5f, Font.BOLD))
            right.add(Ui.gap(4))
            right.add(it)
        }
        right.add(Ui.gap(14))
        right.add(info)
        right.border = EmptyBorder(0, 0, 0, 8)

        val actions = Transparent(FlowLayout(FlowLayout.LEFT, 6, 0))
        if (!b.bookFormat.fixedLayout) actions.add(PillButton(tr("look_inside"), "eye", ButtonKind.TONAL, compact = true).apply { addActionListener { lookInside(b) } })
        actions.add(PillButton(BookActions.revealLabel(), "reveal", ButtonKind.TONAL, compact = true).apply {
            isEnabled = File(b.path).exists()
            addActionListener { SystemIntegration.reveal(File(b.path)) }
        })
        actions.add(PillButton(tr("reset_progress"), "undo", ButtonKind.TEXT, compact = true).apply {
            isEnabled = b.progress > 0f || b.status != BookStatus.NEW
            addActionListener { app.scope.launch { app.library.resetProgress(listOf(b.id)); load() } }
        })
        actions.add(PillButton(tr("remove"), "trash", ButtonKind.TEXT, compact = true).apply {
            addActionListener {
                BookActions.remove(app, window, listOf(b))
                if (app.library.books.value?.none { it.id == b.id } != false) dispose()
            }
        })
        val body = Transparent(BorderLayout())
        body.add(left, BorderLayout.WEST)
        // The column follows the viewport's width, so long titles and descriptions wrap instead of clipping.
        body.add(Ui.scroll(ScrollableColumn(BorderLayout()).apply { add(right, BorderLayout.NORTH) }), BorderLayout.CENTER)
        root.add(body, BorderLayout.CENTER)
        root.add(Ui.padded(actions, 14, 0, 0, 0), BorderLayout.SOUTH)
        root.revalidate()
        root.repaint()
    }

    private fun infoGrid(b: Book): JComponent {
        val p = Transparent(GridBagLayout())
        val c = GridBagConstraints().apply { anchor = GridBagConstraints.NORTHWEST; insets = Insets(2, 0, 2, 14); fill = GridBagConstraints.HORIZONTAL }
        val df = DateFormat.getDateInstance(DateFormat.MEDIUM, I18n.locale)
        val rows = listOfNotNull(
            tr("formats") to b.bookFormat.label,
            if (b.pageCount > 0) tr("info_pages") to b.pageCount.toString() else null,
            tr("storage") to humanSize(b.size),
            b.language?.let { tr("info_language") to (java.util.Locale.forLanguageTag(it).getDisplayLanguage(I18n.locale).ifBlank { it }) },
            b.publisher?.let { tr("info_publisher") to it },
            tr("info_added") to df.format(Date(b.addedAt)),
            if (b.lastOpenedAt > 0) tr("info_last_read") to df.format(Date(b.lastOpenedAt)) else null,
            if (b.readingMs > 60_000) tr("total_time") to formatDuration(b.readingMs) else null,
            tr("info_file") to b.path,
        )
        rows.forEachIndexed { i, (k, v) ->
            c.gridx = 0
            c.gridy = i
            c.weightx = 0.0
            p.add(Ui.secondary(k, 12.5f), c)
            c.gridx = 1
            c.weightx = 1.0
            p.add(WrapText(v, 12.5f), c)
        }
        return p
    }

    private fun lookInside(b: Book) {
        val area = JTextArea(tr("opening")).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = Font(AppTheme.headlineFamily, Font.PLAIN, 15)
            border = EmptyBorder(8, 8, 8, 8)
            caretPosition = 0
        }
        val scroll = Ui.scroll(area).apply { preferredSize = Dimension(520, 420) }
        val d = Dialogs.show(this, b.title, scroll)
        app.scope.launch {
            val text = withContext(Dispatchers.IO) { excerpt(b) } ?: tr("error_generic")
            area.text = text
            area.caretPosition = 0
        }
        d.isVisible = true
    }

    private fun excerpt(b: Book, maxChars: Int = 2400): String? = runCatching {
        BookOpener.open(File(b.path), b.bookFormat, b.title).use { opened ->
            val kinds = setOf(BlockKind.PARAGRAPH, BlockKind.QUOTE, BlockKind.VERSE, BlockKind.HEADING, BlockKind.LIST_ITEM)
            val chapters = opened.book.chapters
            val start = chapters.indexOfFirst { c -> c.blocks.filter { it.kind == BlockKind.PARAGRAPH }.sumOf { it.length } > 400 }.coerceAtLeast(0)
            val sb = StringBuilder()
            loop@ for (c in chapters.drop(start)) for (bl in c.blocks) {
                if (bl.kind !in kinds) continue
                val t = bl.text.replace(' ', ' ').replace("­", "").trim()
                if (t.isEmpty()) continue
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append(t)
                if (sb.length >= maxChars) break@loop
            }
            sb.toString().let { if (it.length > maxChars) it.take(maxChars).substringBeforeLast(' ') + "…" else it }.ifBlank { null }
        }
    }.getOrNull()

    companion object {
        fun humanSize(bytes: Long): String = when {
            bytes <= 0 -> "—"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }

        fun formatDuration(ms: Long): String {
            val minutes = ms / 60_000
            return when {
                minutes < 1 -> tr("duration_under_minute")
                minutes < 60 -> I18n.format("duration_minutes", minutes.toInt())
                else -> I18n.format("duration_hours_minutes", (minutes / 60).toInt(), (minutes % 60).toInt())
            }
        }
    }
}
