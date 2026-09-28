package com.readarea.desktop.ui.library

import com.readarea.core.library.BookStatus
import com.readarea.desktop.App
import com.readarea.desktop.data.Book
import com.readarea.desktop.i18n.I18n
import com.readarea.desktop.i18n.tr
import com.readarea.desktop.platform.Os
import com.readarea.desktop.platform.SystemIntegration
import com.readarea.desktop.ui.MainWindow
import com.readarea.desktop.ui.components.Dialogs
import com.readarea.desktop.ui.components.Ui
import com.readarea.desktop.ui.components.icon
import kotlinx.coroutines.launch
import java.awt.Component
import java.io.File
import javax.swing.JCheckBox
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

/** What can be done with books from the library: the context menu and its dialogs. */
object BookActions {
    fun revealLabel(): String = when (Os.current) {
        Os.MAC -> tr("reveal_finder")
        Os.WINDOWS -> tr("reveal_explorer")
        else -> tr("reveal_folder")
    }

    fun menu(app: App, window: MainWindow, books: List<Book>, invoker: Component, x: Int, y: Int) {
        if (books.isEmpty()) return
        val ids = books.map { it.id }
        val single = books.singleOrNull()
        JPopupMenu().apply {
            add(JMenuItem(tr("open"), icon("read", 16)).apply { addActionListener { books.take(4).forEach { app.openBook(it.id) } } })
            if (single != null) add(JMenuItem(tr("details"), icon("info", 16)).apply { addActionListener { window.showBook(single.id) } })
            addSeparator()
            add(JMenuItem(tr("add_to_shelf") + "…", icon("shelves", 16)).apply { addActionListener { addToShelf(app, window, ids) } })
            add(markSubmenu(app, ids))
            val fav = books.all { it.favorite }
            add(JMenuItem(tr("favorite"), icon(if (fav) "heart-filled" else "heart", 16)).apply { addActionListener { app.scope.launch { app.library.setFavorite(ids, !fav) } } })
            add(JMenuItem(tr("reset_progress"), icon("undo", 16)).apply { addActionListener { app.scope.launch { app.library.resetProgress(ids) } } })
            if (single != null) {
                addSeparator()
                add(JMenuItem(revealLabel(), icon("reveal", 16)).apply {
                    isEnabled = File(single.path).exists()
                    addActionListener { SystemIntegration.reveal(File(single.path)) }
                })
            }
            addSeparator()
            add(JMenuItem(tr("remove") + "…", icon("trash", 16)).apply { addActionListener { remove(app, window, books) } })
        }.show(invoker, x, y)
    }

    private val statuses = listOf(BookStatus.READING to "mark_reading", BookStatus.WANT to "mark_want", BookStatus.FINISHED to "mark_finished", BookStatus.NEW to "status_new")

    private fun markSubmenu(app: App, ids: List<Long>) = JMenu(tr("mark_as")).apply {
        icon = icon("check", 16)
        for ((status, label) in statuses) add(JMenuItem(tr(label)).apply { addActionListener { app.scope.launch { app.library.setStatus(ids, status) } } })
    }

    fun markMenu(app: App, ids: List<Long>) = JPopupMenu().apply {
        for ((status, label) in statuses) add(JMenuItem(tr(label)).apply { addActionListener { app.scope.launch { app.library.setStatus(ids, status) } } })
    }

    /** Lets the reader tick the shelves the books belong on, or make a new one. */
    fun addToShelf(app: App, window: MainWindow, ids: List<Long>) {
        if (ids.isEmpty()) return
        app.scope.launch {
            val shelves = app.library.read { shelves() }
            if (shelves.isEmpty()) {
                val name = Dialogs.input(window, tr("new_shelf"), "", tr("create"), placeholder = tr("name"))?.trim()
                if (!name.isNullOrEmpty()) {
                    app.library.createShelf(name, ids)
                    window.toast(tr("toast_added_to_shelf", name))
                }
                return@launch
            }
            val boxes = shelves.map { s -> JCheckBox(s.shelf.name, ids.all { it in s.bookIds }).apply { isOpaque = false } }
            val newName = javax.swing.JTextField(18).apply { putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT, tr("new_shelf")) }
            val list = Ui.vbox(*boxes.toTypedArray(), gap = 4)
            val content = Ui.vbox(Ui.scroll(list).apply { preferredSize = java.awt.Dimension(320, (boxes.size * 30).coerceIn(60, 260)) }, Ui.gap(10), newName)
            val ok = Dialogs.custom(window, tr("add_to_shelf"), content)
            if (!ok) return@launch
            shelves.forEachIndexed { i, s ->
                val want = boxes[i].isSelected
                val all = ids.all { it in s.bookIds }
                if (want && !all) app.library.addToShelf(s.shelf.id, ids)
                if (!want && ids.any { it in s.bookIds }) app.library.removeFromShelf(s.shelf.id, ids)
            }
            newName.text.trim().takeIf { it.isNotEmpty() }?.let { app.library.createShelf(it, ids) }
        }
    }

    /** Removes books after asking; the files stay unless the reader asks to move them to the trash. */
    fun remove(app: App, window: MainWindow, books: List<Book>) {
        if (books.isEmpty()) return
        val n = books.size
        val (ok, trash) = Dialogs.confirm(
            window,
            I18n.plural("remove_books_title", n, n),
            I18n.plural("remove_books_body", n),
            tr("remove"),
            danger = true,
            checkbox = if (app.library.canTrash) I18n.plural("trash_files_too", n) else null,
        )
        if (!ok) return
        app.scope.launch {
            app.library.removeBooks(books.map { it.id }, trash)
            window.toast(I18n.plural("toast_removed", n, n))
        }
    }
}
