package com.readarea.desktop.ui

import com.readarea.desktop.App
import com.readarea.desktop.data.ScanPhase
import com.readarea.desktop.data.WindowBounds
import com.readarea.desktop.i18n.I18n
import com.readarea.desktop.i18n.tr
import com.readarea.desktop.platform.Os
import com.readarea.desktop.ui.components.Toast
import com.readarea.desktop.ui.components.addOnLayer
import com.readarea.desktop.ui.components.Widget
import com.readarea.desktop.ui.components.FocusRing
import com.readarea.desktop.ui.components.onActivate
import com.readarea.desktop.ui.components.leadingX
import com.readarea.desktop.ui.components.rtl
import com.readarea.desktop.ui.components.Ui
import com.readarea.desktop.ui.components.pal
import com.readarea.desktop.ui.components.smooth
import com.readarea.desktop.ui.home.HomeScreen
import com.readarea.desktop.ui.library.BookDetailsDialog
import com.readarea.desktop.ui.library.LibraryScreen
import com.readarea.desktop.ui.notes.NotesScreen
import com.readarea.desktop.ui.settings.SettingsScreen
import com.readarea.desktop.ui.shelves.ShelvesScreen
import com.readarea.desktop.ui.stats.StatsScreen
import com.readarea.desktop.ui.theme.AppTheme
import com.readarea.desktop.ui.theme.VectorIcon
import com.readarea.desktop.ui.theme.alpha
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.ComponentOrientation
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Frame
import java.awt.Graphics
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.RoundRectangle2D
import java.io.File
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.TransferHandler
import javax.swing.WindowConstants
import com.readarea.desktop.ui.components.LeadingBorder

/** A screen shown in the main window's content area. */
interface Screen {
    val component: JComponent
    fun onShow() {}
}

class MainWindow(val app: App) : JFrame("ReadArea") {
    private val cards = CardLayout()
    private val content = object : JPanel(cards) {
        override fun paintComponent(g: Graphics) {
            g.color = pal.background
            g.fillRect(0, 0, width, height)
        }
    }
    val library = LibraryScreen(app, this)
    private val home = HomeScreen(app, this)
    private val shelves = ShelvesScreen(app, this)
    private val notes = NotesScreen(app, this)
    private val stats = StatsScreen(app, this)
    private val settingsScreen = SettingsScreen(app, this)
    private val screens = linkedMapOf<String, Screen>("home" to home, "library" to library, "shelves" to shelves, "notes" to notes, "stats" to stats, "settings" to settingsScreen)
    private val sidebar = Sidebar()
    private val toast = Toast()
    var current = "home"
        private set

    init {
        iconImages = app.icons
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        minimumSize = Dimension(760, 540)
        if (Os.current == Os.MAC) {
            rootPane.putClientProperty("apple.awt.fullWindowContent", true)
            rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
            rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
        }
        for ((id, s) in screens) content.add(s.component, id)
        val root = JPanel(BorderLayout())
        root.add(sidebar, BorderLayout.LINE_START)
        root.add(content, BorderLayout.CENTER)
        contentPane = root
        layeredPane.addOnLayer(toast, javax.swing.JLayeredPane.POPUP_LAYER)
        // Children added from now on are mirrored as they come (see Mirroring); these exist already.
        if (I18n.rtl) applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT)
        restoreBounds()
        installKeys()
        installDrop()
        if (Os.current == Os.MAC) jMenuBar = menuBar()
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                saveBounds()
                if (app.openReaders.isEmpty() || Os.current != Os.MAC) app.shutdown() else isVisible = false
            }

            override fun windowActivated(e: WindowEvent) {
                app.refreshSystemDark()
                app.library.rescan(force = false)
            }
        })
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = toast.layoutIn(layeredPane.size)
        })
        app.scope.launch {
            app.library.scan.collect {
                val detail = when {
                    it.phase == ScanPhase.DETAILS && it.total > 0 -> "${it.processed} / ${it.total}"
                    it.found > 0 -> I18n.plural("book_count", it.found)
                    else -> ""
                }
                sidebar.scan(it.running, detail)
            }
        }
        navigate("home")
    }

    fun navigate(id: String) {
        val s = screens[id] ?: return
        current = id
        cards.show(content, id)
        sidebar.select(id)
        s.onShow()
    }

    fun showSettings(about: Boolean = false) {
        bringToFront()
        navigate("settings")
        if (about) settingsScreen.scrollToAbout()
    }

    fun showBook(id: Long) {
        BookDetailsDialog(app, this, id).isVisible = true
    }

    fun toast(message: String) {
        toast.show(message, layeredPane.size)
    }

    fun bringToFront() {
        if (!isVisible) isVisible = true
        if (extendedState and Frame.ICONIFIED != 0) extendedState = extendedState and Frame.ICONIFIED.inv()
        toFront()
        requestFocus()
    }

    private fun restoreBounds() {
        val b = app.settings.app.value.mainWindow
        val screen = Toolkit.getDefaultToolkit().screenSize
        if (b != null && b.width >= 600 && b.height >= 400 && b.x < screen.width - 100 && b.y < screen.height - 100 && b.x > -b.width + 100) {
            setBounds(b.x, b.y, b.width, b.height)
            if (b.maximized) extendedState = Frame.MAXIMIZED_BOTH
        } else {
            setSize((screen.width * 0.72).toInt().coerceIn(900, 1480), (screen.height * 0.8).toInt().coerceIn(620, 1000))
            setLocationRelativeTo(null)
        }
    }

    fun saveBounds() {
        val maximized = extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH
        val r = bounds
        app.settings.updateApp { it.copy(mainWindow = WindowBounds(r.x, r.y, r.width, r.height, maximized)) }
    }

    private fun installKeys() {
        val menu = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        val map = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val actions = rootPane.actionMap
        fun bind(key: KeyStroke, name: String, run: () -> Unit) {
            map.put(key, name)
            actions.put(name, object : AbstractAction() { override fun actionPerformed(e: java.awt.event.ActionEvent) = run() })
        }
        screens.keys.forEachIndexed { i, id -> bind(KeyStroke.getKeyStroke(KeyEvent.VK_1 + i, menu), "nav-$id") { navigate(id) } }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_O, menu), "open") { app.chooseFiles(this) }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_O, menu or InputEvent.SHIFT_DOWN_MASK), "folder") { app.chooseFolder(this) }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F, menu), "find") {
            navigate("library")
            library.focusSearch()
        }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, menu), "settings") { navigate("settings") }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_R, menu), "rescan") { app.library.rescan() }
    }

    private fun menuBar(): JMenuBar {
        val menu = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        fun item(text: String, key: Int?, mods: Int = menu, run: () -> Unit) = JMenuItem(text).apply {
            if (key != null) accelerator = KeyStroke.getKeyStroke(key, mods)
            addActionListener { run() }
        }
        return JMenuBar().apply {
            add(JMenu(tr("menu_file")).apply {
                add(item(tr("open_files") + "…", KeyEvent.VK_O) { app.chooseFiles(this@MainWindow) })
                add(item(tr("add_folder") + "…", KeyEvent.VK_O, menu or InputEvent.SHIFT_DOWN_MASK) { app.chooseFolder(this@MainWindow) })
                add(item(tr("rescan_folders"), KeyEvent.VK_R) { app.library.rescan() })
            })
            add(JMenu(tr("menu_view")).apply {
                listOf("nav_home", "nav_library", "nav_shelves", "nav_notes", "nav_stats").forEachIndexed { i, k ->
                    add(item(tr(k), KeyEvent.VK_1 + i) { navigate(screens.keys.elementAt(i)) })
                }
            })
            add(JMenu(tr("menu_window")).apply {
                add(item(tr("nav_library"), null) { bringToFront() })
            })
        }
    }

    private fun installDrop() {
        transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport) = support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                @Suppress("UNCHECKED_CAST")
                val files = runCatching { support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File> }.getOrNull() ?: return false
                val (dirs, plain) = files.partition { it.isDirectory }
                dirs.forEach { app.library.addFolder(it) }
                if (dirs.isNotEmpty()) toast(tr("toast_scanning_folder", dirs.first().name))
                if (plain.isNotEmpty()) app.openFiles(plain)
                return true
            }
        }
    }

    /** The navigation column: logo, destinations, library scan status and settings. */
    private inner class Sidebar : JPanel() {
        private val items = LinkedHashMap<String, NavItem>()
        private val status = Ui.secondary("", 11.5f)
        private val statusRow = Ui.hbox(Box.createHorizontalStrut(14), Spinner(), Box.createHorizontalStrut(8), status)

        init {
            layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
            border = LeadingBorder(if (Os.current == Os.MAC) 44 else 18, 12, 14, 12)
            preferredSize = Dimension(224, 100)
            val logo = Ui.hbox(javax.swing.JLabel(VectorIcon("logo", 30)), Ui.gap(10), Ui.label("ReadArea", 18f, Font.BOLD).apply { font = AppTheme.headline(19f) })
            logo.border = LeadingBorder(0, 10, 18, 0)
            logo.alignmentX = LEFT_ALIGNMENT
            add(logo)
            for ((id, label, icon) in listOf(
                Triple("home", "nav_home", "home"), Triple("library", "nav_library", "library"), Triple("shelves", "nav_shelves", "shelves"),
                Triple("notes", "nav_notes", "notes"), Triple("stats", "nav_stats", "stats"),
            )) {
                val item = NavItem(id, tr(label), icon)
                items[id] = item
                add(item)
                add(Box.createVerticalStrut(2))
            }
            add(Box.createVerticalGlue())
            statusRow.alignmentX = LEFT_ALIGNMENT
            statusRow.isVisible = false
            statusRow.border = LeadingBorder(0, 0, 10, 0)
            add(statusRow)
            val settings = NavItem("settings", tr("nav_settings"), "settings")
            items["settings"] = settings
            add(settings)
        }

        fun select(id: String) {
            items.forEach { (k, v) -> v.selected = k == id }
            repaint()
        }

        fun scan(running: Boolean, text: String) {
            statusRow.isVisible = running
            status.text = if (text.isBlank()) tr("scanning") else "${tr("scanning")} $text"
            revalidate()
        }

        override fun paintComponent(g: Graphics) {
            g.color = pal.sidebar
            g.fillRect(0, 0, width, height)
            g.color = pal.outlineVariant.alpha(120)
            // The divider faces the content, whichever side the sidebar is on.
            g.fillRect(if (rtl) 0 else width - 1, 0, 1, height)
        }
    }

    private inner class NavItem(private val id: String, text: String, icon: String) : Widget(javax.accessibility.AccessibleRole.PUSH_BUTTON) {
        var selected = false
        private var hover = false
        private val label = text
        private val ic = VectorIcon(icon, 20) { if (selected) pal.onAccentContainer else pal.onSurfaceVariant }

        init {
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 40)
            preferredSize = Dimension(200, 40)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = null
            getAccessibleContext().accessibleName = text
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = navigate(id)
                override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
                override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
            })
            onActivate { navigate(id) }
        }

        override fun paintComponent(g0: Graphics) {
            val g = g0.create().smooth()
            if (selected || hover) {
                g.color = if (selected) pal.accentContainer else pal.onSurface.alpha(14)
                g.fill(RoundRectangle2D.Float(0f, 2f, width.toFloat(), height - 4f, height - 4f, height - 4f))
            }
            FocusRing.paint(g, this, RoundRectangle2D.Float(1f, 3f, width - 2f, height - 6f, height - 6f, height - 6f))
            ic.paintIcon(this, g, leadingX(14f, 20f).toInt(), (height - 20) / 2)
            g.font = AppTheme.ui(13.5f, if (selected) Font.BOLD else Font.PLAIN)
            g.color = if (selected) pal.onAccentContainer else pal.onSurface
            val fm = g.fontMetrics
            g.drawString(label, leadingX(46f, fm.stringWidth(label).toFloat()), (height + fm.ascent - fm.descent) / 2f)
            g.dispose()
        }
    }

    /** A small indeterminate spinner for background work. */
    private class Spinner : Widget() {
        private var angle = 0
        private val timer = javax.swing.Timer(60) { angle = (angle + 30) % 360; repaint() }

        init {
            preferredSize = Dimension(14, 14)
            maximumSize = preferredSize
        }

        override fun addNotify() {
            super.addNotify()
            timer.start()
        }

        override fun removeNotify() {
            timer.stop()
            super.removeNotify()
        }

        override fun paintComponent(g0: Graphics) {
            if (!isShowing) return
            val g = g0.create().smooth()
            g.stroke = java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
            g.color = pal.accent
            g.draw(java.awt.geom.Arc2D.Float(1f, 1f, 12f, 12f, -angle.toFloat(), 270f, java.awt.geom.Arc2D.OPEN))
            g.dispose()
        }
    }
}
