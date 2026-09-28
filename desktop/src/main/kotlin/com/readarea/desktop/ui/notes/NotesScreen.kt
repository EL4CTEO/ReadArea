package com.readarea.desktop.ui.notes

import com.readarea.core.library.ExportedBook
import com.readarea.core.library.ExportedHighlight
import com.readarea.core.library.NotesExport
import com.readarea.core.theme.ReadingThemes
import com.readarea.desktop.App
import com.readarea.desktop.data.BookmarkWithBook
import com.readarea.desktop.data.HighlightWithBook
import com.readarea.desktop.i18n.I18n
import com.readarea.desktop.i18n.tr
import com.readarea.desktop.ui.MainWindow
import com.readarea.desktop.ui.Screen
import com.readarea.desktop.ui.components.ButtonKind
import com.readarea.desktop.ui.components.Card
import com.readarea.desktop.ui.components.Dialogs
import com.readarea.desktop.ui.components.IconButton
import com.readarea.desktop.ui.components.PillButton
import com.readarea.desktop.ui.components.ScrollableColumn
import com.readarea.desktop.ui.components.Segmented
import com.readarea.desktop.ui.components.Transparent
import com.readarea.desktop.ui.components.Ui
import com.readarea.desktop.ui.components.emptyState
import com.readarea.desktop.ui.components.pal
import com.readarea.desktop.ui.components.searchField
import com.readarea.desktop.ui.theme.AppTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.Graphics
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JTextArea
import javax.swing.border.EmptyBorder

class NotesScreen(private val app: App, private val window: MainWindow) : Screen {
    private var highlights: List<HighlightWithBook> = emptyList()
    private var bookmarks: List<BookmarkWithBook> = emptyList()
    private var query = ""
    private var tab = 0
    private val list = ScrollableColumn(null).apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val cards = CardLayout()
    private val body = Transparent(cards)
    private val tabs = Segmented(listOf("", ""), 0) { i -> tab = i; render() }
    private var shown = PAGE

    override val component: JComponent = Transparent(BorderLayout()).apply {
        border = EmptyBorder(22, 28, 0, 28)
        val search = searchField(tr("search_notes")) { q -> query = q; shown = PAGE; render() }
        search.preferredSize = Dimension(260, 34)
        search.maximumSize = Dimension(320, 34)
        val export = PillButton(tr("export_all"), "export", ButtonKind.TONAL, compact = true).apply { addActionListener { exportAll() } }
        val header = Transparent(BorderLayout()).apply {
            add(Ui.headline(tr("nav_notes")), BorderLayout.WEST)
            add(Ui.hbox(search, Ui.gap(8), export), BorderLayout.EAST)
        }
        tabs.maximumSize = Dimension(420, 34)
        add(Ui.vbox(header, Ui.gap(14), tabs, Ui.gap(12)), BorderLayout.NORTH)
        list.border = EmptyBorder(0, 0, 24, 8)
        body.add(Ui.scroll(list), "list")
        body.add(emptyState("highlighter", tr("no_highlights"), tr("no_highlights_body")), "no_highlights")
        body.add(emptyState("bookmark", tr("no_bookmarks"), tr("no_bookmarks_body")), "no_bookmarks")
        body.add(emptyState("search", tr("no_matches_title"), tr("no_matches_body")), "nomatch")
        add(body, BorderLayout.CENTER)
    }

    init {
        app.scope.launch {
            app.library.books.combine(app.library.version) { b, _ -> b }.collect {
                highlights = app.library.read { allHighlights() }
                bookmarks = app.library.read { allBookmarks() }
                render()
            }
        }
    }

    private fun render() {
        val q = query.trim().lowercase()
        tabs.setOptions(listOf(I18n.format("tab_highlights", highlights.size), I18n.format("tab_bookmarks", bookmarks.size)))
        list.removeAll()
        if (tab == 0) {
            val hl = highlights.filter { q.isEmpty() || it.highlight.text.lowercase().contains(q) || it.highlight.note?.lowercase()?.contains(q) == true || it.bookTitle.lowercase().contains(q) }
            when {
                highlights.isEmpty() -> cards.show(body, "no_highlights")
                hl.isEmpty() -> cards.show(body, "nomatch")
                else -> {
                    cards.show(body, "list")
                    hl.take(shown).forEach { list.add(highlightCard(it)); list.add(Ui.gap(12)) }
                    if (hl.size > shown) list.add(more())
                }
            }
        } else {
            val bm = bookmarks.filter { q.isEmpty() || it.bookmark.snippet.lowercase().contains(q) || it.bookTitle.lowercase().contains(q) }
            when {
                bookmarks.isEmpty() -> cards.show(body, "no_bookmarks")
                bm.isEmpty() -> cards.show(body, "nomatch")
                else -> {
                    cards.show(body, "list")
                    bm.take(shown).forEach { list.add(bookmarkCard(it)); list.add(Ui.gap(10)) }
                    if (bm.size > shown) list.add(more())
                }
            }
        }
        list.revalidate()
        list.repaint()
    }

    private fun more(): JComponent = Ui.flow(PillButton(tr("show_more"), null, ButtonKind.TEXT).apply { addActionListener { shown += PAGE; render() } })

    private fun textArea(text: String, size: Float, serif: Boolean, color: () -> Color): JTextArea = object : JTextArea(text) {
        override fun paintComponent(g: Graphics) {
            foreground = color()
            super.paintComponent(g)
        }
    }.apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = null
        font = if (serif) AppTheme.headline(size, bold = false) else AppTheme.ui(size)
    }

    private fun highlightCard(h: HighlightWithBook): JComponent {
        val hl = h.highlight
        val color = Color(ReadingThemes.highlightColor(hl.color))
        val card = object : Card(16, BorderLayout(14, 0), { pal.surfaceContainer }) {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.fill(java.awt.geom.RoundRectangle2D.Float(12f, 14f, 4f, height - 28f, 4f, 4f))
                g2.dispose()
            }
        }
        card.border = EmptyBorder(14, 28, 14, 14)
        card.alignmentX = JComponent.LEFT_ALIGNMENT
        val quote = textArea(hl.text, 15f, true) { pal.onSurface }
        val content = Ui.vbox(quote, gap = 6)
        hl.note?.takeIf { it.isNotBlank() }?.let { content.add(Ui.gap(6)); content.add(textArea(it, 13f, false) { pal.onSurfaceVariant }) }
        val meta = listOf(h.bookTitle, hl.chapterTitle.takeIf { it.isNotBlank() }, I18n.format("percent", (hl.progress * 100).toInt())).filterNotNull().joinToString("  ·  ")
        content.add(Ui.gap(8))
        content.add(Ui.secondary(meta, 12f))
        card.add(content, BorderLayout.CENTER)
        val actions = Ui.vbox(
            IconButton("read", tr("go_to_note"), 18).apply { addActionListener { app.openBook(hl.bookId, hl.chapter to hl.start) } },
            IconButton("note", tr("add_note"), 18).apply {
                addActionListener {
                    val note = Dialogs.input(window, tr("note"), hl.note.orEmpty(), tr("save"), multiline = true, placeholder = tr("your_thoughts"))
                    if (note != null) app.scope.launch { app.library.updateHighlight(hl.copy(note = note.trim().ifEmpty { null })) }
                }
            },
            IconButton("copy", tr("copy"), 18).apply {
                addActionListener {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(hl.text), null)
                    window.toast(tr("toast_copied"))
                }
            },
            IconButton("trash", tr("delete_highlight"), 18).apply {
                addActionListener {
                    val (ok, _) = Dialogs.confirm(window, tr("delete_highlight"), hl.text.take(200), tr("delete"), danger = true)
                    if (ok) app.scope.launch { app.library.deleteHighlight(hl.id) }
                }
            },
        )
        card.add(actions, BorderLayout.EAST)
        card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height + 400)
        return card
    }

    private fun bookmarkCard(b: BookmarkWithBook): JComponent {
        val bm = b.bookmark
        val card = Card(16, BorderLayout(14, 0), { pal.surfaceContainer })
        card.border = EmptyBorder(12, 16, 12, 12)
        card.alignmentX = JComponent.LEFT_ALIGNMENT
        val df = DateFormat.getDateInstance(DateFormat.MEDIUM, I18n.locale)
        val meta = listOf(b.bookTitle, bm.chapterTitle.takeIf { it.isNotBlank() }, I18n.format("percent", (bm.progress * 100).toInt()), df.format(Date(bm.createdAt))).filterNotNull().joinToString("  ·  ")
        card.add(javax.swing.JLabel(com.readarea.desktop.ui.theme.VectorIcon("bookmark-filled", 20) { pal.accent }), BorderLayout.WEST)
        card.add(Ui.vbox(textArea(bm.snippet, 13.5f, true) { pal.onSurface }, Ui.gap(4), Ui.secondary(meta, 12f)), BorderLayout.CENTER)
        card.add(Ui.hbox(
            IconButton("read", tr("go_to"), 18).apply { addActionListener { app.openBook(bm.bookId, bm.chapter to bm.offset) } },
            IconButton("trash", tr("delete"), 18).apply { addActionListener { app.scope.launch { app.library.deleteBookmark(bm.id) } } },
        ), BorderLayout.EAST)
        card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height + 200)
        return card
    }

    /** Saves every highlight and note as a Markdown file the reader picks. */
    private fun exportAll() {
        if (highlights.isEmpty()) {
            window.toast(tr("no_highlights"))
            return
        }
        val books = highlights.groupBy { it.highlight.bookId }.values.map { list ->
            ExportedBook(list.first().bookTitle, list.first().bookAuthor, list.map { ExportedHighlight(it.highlight.text, it.highlight.note, it.highlight.chapterTitle, it.highlight.progress) })
        }.sortedBy { it.title.lowercase() }
        val md = NotesExport.markdown(tr("export_notes_heading"), books)
        val dialog = FileDialog(window, tr("export_notes"), FileDialog.SAVE)
        dialog.file = "ReadArea notes.md"
        dialog.isVisible = true
        val name = dialog.file ?: return
        val target = File(dialog.directory, if (name.endsWith(".md", true)) name else "$name.md")
        runCatching {
            target.writeText(md)
            window.toast(tr("toast_exported", target.name))
        }.onFailure { Dialogs.message(window, tr("export_notes"), it.message ?: tr("error_generic")) }
    }

    override fun onShow() = render()

    companion object {
        private const val PAGE = 150
    }
}
