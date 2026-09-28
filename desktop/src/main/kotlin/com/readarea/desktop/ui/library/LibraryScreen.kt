package com.readarea.desktop.ui.library

import com.readarea.core.format.BookFormat
import com.readarea.core.library.BookStatus
import com.readarea.desktop.App
import com.readarea.desktop.data.Book
import com.readarea.desktop.i18n.I18n
import com.readarea.desktop.i18n.tr
import com.readarea.desktop.ui.MainWindow
import com.readarea.desktop.ui.Screen
import com.readarea.desktop.ui.components.ButtonKind
import com.readarea.desktop.ui.components.Chip
import com.readarea.desktop.ui.components.IconButton
import com.readarea.desktop.ui.components.PillButton
import com.readarea.desktop.ui.components.ScreenHeader
import com.readarea.desktop.ui.components.Transparent
import com.readarea.desktop.ui.components.Ui
import com.readarea.desktop.ui.components.WrapLayout
import com.readarea.desktop.ui.components.emptyState
import com.readarea.desktop.ui.components.searchField
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.ButtonGroup
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import com.readarea.desktop.ui.components.LeadingBorder

enum class LibraryFilter(val label: String) { ALL("filter_all"), READING("status_reading"), WANT("status_want"), FINISHED("status_finished"), FAVORITES("filter_favorites"), NEW("status_new") }

data class LibraryQuery(val text: String = "", val filter: LibraryFilter = LibraryFilter.ALL, val format: String? = null)

object LibrarySort {
    val keys = listOf("recent", "title", "author", "added", "progress", "size")
    val labels = listOf("sort_recent", "sort_title", "sort_author", "sort_added", "sort_progress", "sort_size")

    /** The same filtering and sorting as the Android library, so both apps list books alike. */
    fun apply(books: List<Book>, q: LibraryQuery, sort: String): List<Book> {
        val text = q.text.trim().lowercase()
        val filtered = books.filter { b ->
            (text.isEmpty() || b.title.lowercase().contains(text) || b.author.lowercase().contains(text) || b.series?.lowercase()?.contains(text) == true || b.fileName.lowercase().contains(text)) &&
                (q.format == null || b.format == q.format) &&
                when (q.filter) {
                    LibraryFilter.ALL -> true
                    LibraryFilter.READING -> b.status == BookStatus.READING
                    LibraryFilter.WANT -> b.status == BookStatus.WANT
                    LibraryFilter.FINISHED -> b.status == BookStatus.FINISHED
                    LibraryFilter.FAVORITES -> b.favorite
                    LibraryFilter.NEW -> b.status == BookStatus.NEW
                }
        }
        return when (sort) {
            "title" -> filtered.sortedBy { it.title.lowercase() }
            "author" -> filtered.sortedWith(compareBy({ it.author.lowercase().ifEmpty { "￿" } }, { it.series ?: "" }, { it.seriesIndex ?: 0f }, { it.title.lowercase() }))
            "added" -> filtered.sortedByDescending { it.addedAt }
            "progress" -> filtered.sortedByDescending { it.progress }
            "size" -> filtered.sortedByDescending { it.size }
            else -> filtered.sortedWith(compareByDescending<Book> { it.lastOpenedAt }.thenByDescending { it.addedAt })
        }
    }
}

class LibraryScreen(private val app: App, private val window: MainWindow) : Screen {
    private var query = LibraryQuery()
    private val grid = BookGrid(onOpen = { app.openBook(it.id) }, onContext = { books, e -> BookActions.menu(app, window, books, e.component, e.x, e.y) })
    private val count = Ui.secondary("")
    private val search = searchField(tr("search_library_hint")) { text -> update(query.copy(text = text)) }
    private val chips = LibraryFilter.entries.map { f -> Chip(tr(f.label), f == LibraryFilter.ALL) }
    private val formatBox = JComboBox<String>()
    private val sortBox = JComboBox(LibrarySort.labels.map { tr(it) }.toTypedArray())
    private val gridButton = IconButton("grid", tr("change_view"))
    private val listButton = IconButton("list", tr("change_view"))
    private val selectionLabel = Ui.label("", 13.5f, java.awt.Font.BOLD)
    private val selectionBar = Transparent(WrapLayout(FlowLayout.LEADING, 8, 4))
    private val body = JPanel(CardLayout())
    private var all: List<Book> = emptyList()

    override val component: JComponent = Transparent(BorderLayout()).apply {
        border = LeadingBorder(22, 28, 0, 28)
        val title = Ui.hbox(Ui.headline(tr("nav_library")), Ui.gap(12), count)
        search.preferredSize = Dimension(260, 34)
        search.minimumSize = Dimension(110, 34)
        search.maximumSize = Dimension(320, 34)
        val add = PillButton(tr("add_books"), "add", ButtonKind.PRIMARY, compact = true)
        add.addActionListener {
            JPopupMenu().apply {
                add(JMenuItem(tr("add_folder") + "…").apply { addActionListener { app.chooseFolder(window) } })
                add(JMenuItem(tr("open_files") + "…").apply { addActionListener { app.chooseFiles(window) } })
            }.show(add, 0, add.height + 4)
        }
        sortBox.selectedIndex = LibrarySort.keys.indexOf(app.settings.app.value.sort).coerceAtLeast(0)
        sortBox.toolTipText = tr("sort")
        sortBox.addActionListener { app.settings.updateApp { s -> s.copy(sort = LibrarySort.keys[sortBox.selectedIndex.coerceAtLeast(0)]) } }
        gridButton.addActionListener { app.settings.updateApp { it.copy(libraryGrid = true) } }
        listButton.addActionListener { app.settings.updateApp { it.copy(libraryGrid = false) } }
        // Drops the search and sort under the title when the window is too narrow to fit them beside it.
        val header = ScreenHeader(title, Ui.hbox(search, Ui.gap(8), sortBox, Ui.gap(4), gridButton, listButton), add)
        val group = ButtonGroup()
        chips.forEachIndexed { i, c ->
            group.add(c)
            c.addActionListener { update(query.copy(filter = LibraryFilter.entries[i])) }
        }
        formatBox.addActionListener {
            val i = formatBox.selectedIndex
            update(query.copy(format = if (i <= 0) null else formats.getOrNull(i - 1)))
        }
        formatBox.toolTipText = tr("formats")
        // Wraps onto a second line rather than hiding the chips that don't fit.
        val filters = Transparent(WrapLayout(FlowLayout.LEADING, 8, 4)).apply {
            chips.forEach { add(it) }
            add(formatBox)
        }
        buildSelectionBar()
        val top = Ui.vbox(header, Ui.gap(10), filters, Ui.gap(6), selectionBar)
        add(top, BorderLayout.NORTH)
        val scroll = Ui.scroll(grid)
        scroll.border = LeadingBorder(6, 0, 0, 0)
        body.isOpaque = false
        body.add(scroll, "grid")
        body.add(emptyState("library", tr("library_empty_title"), tr("library_empty_body"),
            PillButton(tr("add_folder"), "folder").apply { addActionListener { app.chooseFolder(window) } },
            PillButton(tr("open_files"), "file", ButtonKind.TONAL).apply { addActionListener { app.chooseFiles(window) } }), "empty")
        body.add(emptyState("search", tr("no_matches_title"), tr("no_matches_body")), "nomatch")
        add(body, BorderLayout.CENTER)
    }

    private var formats: List<String> = emptyList()

    init {
        grid.addListSelectionListener { updateSelection() }
        app.scope.launch {
            app.library.books.combine(app.settings.app) { b, s -> b to s }.collect { (books, s) ->
                all = books.orEmpty()
                grid.grid = s.libraryGrid
                grid.coverSize = s.coverSize
                grid.showBadges = s.showFormatBadges
                gridButton.active = s.libraryGrid
                listButton.active = !s.libraryGrid
                refreshFormats()
                refresh(s.sort)
            }
        }
    }

    private fun refreshFormats() {
        val f = all.map { it.format }.distinct().sortedBy { BookFormat.byName(it).label }
        if (f == formats) return
        formats = f
        val selected = query.format
        formatBox.removeAllItems()
        formatBox.addItem(tr("formats"))
        f.forEach { formatBox.addItem(BookFormat.byName(it).label) }
        formatBox.selectedIndex = selected?.let { formats.indexOf(it) + 1 }?.coerceAtLeast(0) ?: 0
        formatBox.isVisible = f.size > 1
    }

    private fun buildSelectionBar() {
        selectionBar.isVisible = false
        selectionBar.add(selectionLabel)
        selectionBar.add(PillButton(tr("open"), "read", ButtonKind.TONAL, compact = true).apply { addActionListener { grid.selectedValuesList.take(4).forEach { app.openBook(it.id) } } })
        selectionBar.add(PillButton(tr("add_to_shelf"), "shelves", ButtonKind.TONAL, compact = true).apply { addActionListener { BookActions.addToShelf(app, window, grid.selectedValuesList.map { it.id }) } })
        val mark = PillButton(tr("mark_as"), "check", ButtonKind.TONAL, compact = true)
        mark.addActionListener { BookActions.markMenu(app, grid.selectedValuesList.map { it.id }).show(mark, 0, mark.height + 4) }
        selectionBar.add(mark)
        selectionBar.add(PillButton(tr("favorite"), "heart", ButtonKind.TONAL, compact = true).apply {
            addActionListener {
                val sel = grid.selectedValuesList
                app.scope.launch { app.library.setFavorite(sel.map { it.id }, !sel.all { it.favorite }) }
            }
        })
        selectionBar.add(PillButton(tr("remove"), "trash", ButtonKind.TONAL, compact = true).apply { addActionListener { BookActions.remove(app, window, grid.selectedValuesList) } })
        selectionBar.add(PillButton(tr("clear_selection"), "close", ButtonKind.TEXT, compact = true).apply { addActionListener { grid.clearSelection() } })
    }

    private fun updateSelection() {
        val n = grid.selectedIndices.size
        selectionBar.isVisible = n > 1
        selectionLabel.text = I18n.plural("selected_count", n)
        component.revalidate()
    }

    private fun update(q: LibraryQuery) {
        query = q
        refresh(app.settings.app.value.sort)
    }

    private fun refresh(sort: String) {
        val list = LibrarySort.apply(all, query, sort)
        val selected = grid.selectedValuesList.map { it.id }.toSet()
        grid.bookModel.set(list)
        if (selected.isNotEmpty()) {
            val idx = list.indices.filter { list[it].id in selected }.toIntArray()
            grid.selectedIndices = idx
        }
        count.text = I18n.plural("book_count", all.size)
        (body.layout as CardLayout).show(body, when {
            all.isEmpty() -> "empty"
            list.isEmpty() -> "nomatch"
            else -> "grid"
        })
    }

    fun focusSearch() {
        search.requestFocusInWindow()
        search.selectAll()
    }

    /** Shows books by one author, used from a book's details. */
    fun showAuthor(author: String) {
        search.text = author
        chips[0].isSelected = true
        update(query.copy(text = author, filter = LibraryFilter.ALL))
    }

    override fun onShow() {
        grid.repaint()
    }
}
