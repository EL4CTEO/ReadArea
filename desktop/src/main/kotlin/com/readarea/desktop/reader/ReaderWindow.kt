package com.readarea.desktop.reader

import com.readarea.core.theme.ReadingThemes
import com.readarea.desktop.App
import com.readarea.desktop.data.WindowBounds
import com.readarea.desktop.i18n.I18n
import com.readarea.desktop.i18n.tr
import com.readarea.desktop.platform.Os
import com.readarea.desktop.platform.SystemIntegration
import com.readarea.desktop.reader.engine.PagePos
import com.readarea.desktop.ui.components.ButtonKind
import com.readarea.desktop.ui.components.Widget
import com.readarea.desktop.ui.components.Dialogs
import com.readarea.desktop.ui.components.IconButton
import com.readarea.desktop.ui.components.PillButton
import com.readarea.desktop.ui.components.Toast
import com.readarea.desktop.ui.components.addOnLayer
import com.readarea.desktop.ui.components.Transparent
import com.readarea.desktop.ui.components.Ui
import com.readarea.desktop.ui.components.pal
import com.readarea.desktop.ui.components.smooth
import com.readarea.desktop.ui.theme.AppTheme
import com.readarea.desktop.ui.theme.alpha
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Frame
import java.awt.Graphics
import java.awt.GraphicsEnvironment
import java.awt.LayoutManager
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants
import javax.swing.border.EmptyBorder

class ReaderWindow(private val app: App, private val bookId: Long, at: Pair<Int, Int>?) : JFrame(), ReaderHost {
    val controller = ReaderController(app, bookId, at)
    private val pageView = PageView(controller)
    private val scrollView = ScrollView(controller)
    private val viewCards = CardLayout()
    private val views = JPanel(viewCards)
    private val layers = JLayeredPane()
    private val status = StatusPanel()
    private val topBar = TopBar()
    private val bottomBar = BottomBar()
    private val speechBar = SpeechBar()
    private val toast = Toast()
    private var leftPanel: JComponent? = null
    private var rightPanel: JComponent? = null
    private var selectionPopup: JComponent? = null
    private var footnotePopup: JComponent? = null
    private var endCard: JComponent? = null
    private var barsVisible = true
    private val hideTimer: Timer = Timer(2800) { if (!controller.ui.value.menu && !pointerOnBar()) setBars(false) else if (barsVisible) restartHide() }.apply { isRepeats = false }
    private var fullscreen = false
    private var closed = false

    init {
        iconImages = app.icons
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        minimumSize = Dimension(480, 420)
        title = tr("opening")
        if (Os.current == Os.MAC) rootPane.putClientProperty("apple.awt.fullscreenable", true)
        views.add(pageView, "page")
        views.add(scrollView, "scroll")
        views.add(status, "status")
        layers.layout = ReaderLayout()
        layers.addOnLayer(views, JLayeredPane.DEFAULT_LAYER)
        layers.addOnLayer(topBar, JLayeredPane.PALETTE_LAYER)
        layers.addOnLayer(bottomBar, JLayeredPane.PALETTE_LAYER)
        layers.addOnLayer(speechBar, JLayeredPane.PALETTE_LAYER)
        layers.addOnLayer(toast, JLayeredPane.DRAG_LAYER)
        contentPane = layers
        viewCards.show(views, "status")
        controller.host = this
        pageView.onPointer = { y -> onPointer(y) }
        scrollView.onPointer = { y -> onPointer(y) }
        restoreBounds()
        installKeys()
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = saveAndClose()
            override fun windowActivated(e: WindowEvent) {
                controller.onSessionStart()
                app.refreshSystemDark()
                pageView.requestFocusInWindow()
            }

            override fun windowDeactivated(e: WindowEvent) {
                if (!controller.ui.value.speaking) controller.onSessionEnd()
                controller.savePosition()
            }
        })
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = onResize()
        })
        controller.scope.launch { controller.ui.collect { render(it) } }
        controller.scope.launch {
            controller.settings.map { it.pageAnim == "scroll" }.distinctUntilChanged().collect { pageView.refresh() }
        }
        controller.open()
        setBars(true)
        hideTimer.start()
    }

    // Host callbacks from the controller

    override fun refresh() {
        pageView.refresh()
        scrollView.repaint()
    }

    override fun flip(forward: Boolean) {
        if (controller.scrollMode) scrollView.scrollBy(if (forward) scrollView.height * 0.88f else -scrollView.height * 0.88f) else pageView.flip(forward)
    }

    override fun scrollTo(pos: PagePos, fraction: Float) = scrollView.scrollTo(pos, fraction)
    override fun scrollBy(dy: Float) = scrollView.scrollBy(dy)
    override fun autoScroll(pxPerSecond: Float) = scrollView.autoScroll(pxPerSecond)

    override fun askPassword(retry: Boolean): String? {
        val field = javax.swing.JPasswordField(22)
        val body = Ui.vbox(Ui.wrapLabel(tr(if (retry) "password_wrong" else "password_body"), 320, 13f) { pal.onSurfaceVariant }, Ui.gap(10), field)
        SwingUtilities.invokeLater { field.requestFocusInWindow() }
        return if (Dialogs.custom(this, tr("password_title"), body, tr("open"))) String(field.password) else null
    }

    fun onSystemTheme(dark: Boolean) {
        controller.onSystemTheme(dark)
        status.repaint()
    }

    fun bringToFront() {
        if (extendedState and Frame.ICONIFIED != 0) extendedState = extendedState and Frame.ICONIFIED.inv()
        toFront()
        requestFocus()
    }

    // Rendering state

    private var lastUi: ReaderUi? = null

    private fun render(u: ReaderUi) {
        val prev = lastUi
        lastUi = u
        title = if (u.title.isNotBlank()) u.title else "ReadArea"
        when {
            u.error != null -> {
                status.show(u.error)
                viewCards.show(views, "status")
            }
            u.loading || !u.laidOut -> {
                status.show(null)
                viewCards.show(views, "status")
            }
            else -> viewCards.show(views, if (u.scrollMode) "scroll" else "page")
        }
        topBar.update(u)
        bottomBar.update(u)
        speechBar.isVisible = u.speaking || u.autoTurn
        speechBar.update(u)
        if (prev?.menu != u.menu) setBars(u.menu || barsVisible && prev == null)
        if (prev?.panel != u.panel) showPanel(u.panel)
        if (prev?.selection != u.selection) showSelection(u)
        if (prev?.footnote != u.footnote) showFootnote(u)
        if (prev?.endReached != u.endReached) showEnd(u)
        u.message?.let { msg ->
            controller.dismissMessage()
            if (msg.startsWith("link:")) confirmLink(msg.removePrefix("link:")) else toast(msg)
        }
        layers.revalidate()
        layers.repaint()
    }

    private fun toast(msg: String) = toast.show(msg, layers.size)

    private fun confirmLink(url: String) {
        val (ok, _) = Dialogs.confirm(this, tr("open_link"), url.take(300), tr("open"))
        if (ok && !SystemIntegration.openLink(url)) toast(tr("error_generic"))
    }

    private fun onResize() {
        val w = layers.width
        val h = layers.height
        if (w <= 0 || h <= 0) return
        val scale = graphicsConfiguration?.defaultTransform?.scaleX?.toFloat() ?: 1f
        controller.onViewport(Viewport(w, h, scale.coerceIn(1f, 4f)))
        toast.layoutIn(layers.size)
    }

    /**
     * The bars come up when the pointer nears the top or bottom edge, where they appear, and go away a
     * little after it leaves. Moving the mouse over the text, or dragging a page, leaves the page clear.
     */
    private fun onPointer(y: Int) {
        val h = layers.height
        if (y < EDGE_TOP || y > h - EDGE_BOTTOM) {
            if (!barsVisible) setBars(true)
            restartHide()
        }
    }

    private fun restartHide() {
        hideTimer.restart()
    }

    private fun pointerOnBar(): Boolean {
        val p = java.awt.MouseInfo.getPointerInfo()?.location ?: return false
        return listOf(topBar, bottomBar).any { bar ->
            bar.isShowing && java.awt.Rectangle(bar.locationOnScreen, bar.size).contains(p)
        }
    }

    private fun setBars(visible: Boolean) {
        barsVisible = visible || controller.ui.value.menu
        topBar.isVisible = barsVisible && controller.ui.value.error == null
        bottomBar.isVisible = barsVisible && controller.ui.value.laidOut
        layers.repaint()
    }

    private fun showPanel(p: Panel) {
        leftPanel?.let { layers.remove(it) }
        rightPanel?.let { layers.remove(it) }
        leftPanel = null
        rightPanel = null
        when (p) {
            Panel.CONTENTS -> leftPanel = ContentsPanel(controller) { controller.closePanel() }.also { SwingUtilities.invokeLater { it.focusCurrent() } }
            Panel.SEARCH -> rightPanel = SearchPanel(controller) { controller.closePanel() }.also { panel ->
                panel.field.text = controller.ui.value.searchQuery
                SwingUtilities.invokeLater { panel.field.requestFocusInWindow(); panel.field.selectAll() }
            }
            Panel.TEXT -> rightPanel = TextPanel(app, controller) { controller.closePanel() }
            Panel.THEME -> rightPanel = ThemePanel(controller) { controller.closePanel() }
            Panel.NONE -> pageView.requestFocusInWindow()
        }
        leftPanel?.let { layers.addOnLayer(it, JLayeredPane.MODAL_LAYER) }
        rightPanel?.let { layers.addOnLayer(it, JLayeredPane.MODAL_LAYER) }
        layers.revalidate()
        layers.repaint()
    }

    private fun showSelection(u: ReaderUi) {
        selectionPopup?.let { layers.remove(it) }
        selectionPopup = null
        val sel = u.selection ?: return layers.repaint()
        val popup = SelectionPopup(sel)
        selectionPopup = popup
        layers.addOnLayer(popup, JLayeredPane.POPUP_LAYER)
        layers.revalidate()
        layers.repaint()
    }

    private fun showFootnote(u: ReaderUi) {
        footnotePopup?.let { layers.remove(it) }
        footnotePopup = null
        val f = u.footnote ?: return layers.repaint()
        val card = FootnoteCard(f)
        footnotePopup = card
        layers.addOnLayer(card, JLayeredPane.POPUP_LAYER)
        layers.revalidate()
        layers.repaint()
    }

    private fun showEnd(u: ReaderUi) {
        endCard?.let { layers.remove(it) }
        endCard = null
        if (!u.endReached) return layers.repaint()
        val card = RoundCard(BorderLayout())
        card.border = EmptyBorder(22, 24, 18, 24)
        card.add(Ui.vbox(
            Ui.headline(tr("the_end"), 26f),
            Ui.gap(6),
            Ui.wrapLabel(I18n.format("you_finished", u.title), 320, 13.5f) { pal.onSurfaceVariant },
            Ui.gap(16),
            Ui.flow(
                PillButton(tr("mark_finished"), "check").apply { addActionListener { controller.markFinished(); toast(tr("status_finished")) } },
                PillButton(tr("keep_reading"), null, ButtonKind.TEXT).apply { addActionListener { controller.dismissEnd() } },
                PillButton(tr("back_to_library"), null, ButtonKind.TEXT).apply { addActionListener { saveAndClose(); app.main.bringToFront() } },
                gap = 6,
            ),
        ), BorderLayout.CENTER)
        card.name = "end"
        endCard = card
        layers.addOnLayer(card, JLayeredPane.POPUP_LAYER)
        layers.revalidate()
        layers.repaint()
    }

    // Keyboard

    private fun installKeys() {
        val menu = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        val map = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val actions = rootPane.actionMap
        fun bind(stroke: KeyStroke, name: String, run: () -> Unit) {
            map.put(stroke, name)
            actions.put(name, object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent) {
                    if (typing() && stroke.modifiers == 0) return
                    run()
                }
            })
        }
        fun key(k: Int, mods: Int = 0) = KeyStroke.getKeyStroke(k, mods)
        val forward = { controller.keyFlip(!controller.ui.value.rtl) }
        val backward = { controller.keyFlip(controller.ui.value.rtl) }
        bind(key(KeyEvent.VK_RIGHT), "right", forward)
        bind(key(KeyEvent.VK_LEFT), "left", backward)
        bind(key(KeyEvent.VK_PAGE_DOWN), "pgdn") { controller.keyFlip(true) }
        bind(key(KeyEvent.VK_PAGE_UP), "pgup") { controller.keyFlip(false) }
        bind(key(KeyEvent.VK_SPACE), "space") { controller.keyFlip(true) }
        bind(key(KeyEvent.VK_SPACE, InputEvent.SHIFT_DOWN_MASK), "shiftspace") { controller.keyFlip(false) }
        bind(key(KeyEvent.VK_DOWN), "down") { if (controller.scrollMode) scrollView.scrollBy(60f) else controller.keyFlip(true) }
        bind(key(KeyEvent.VK_UP), "up") { if (controller.scrollMode) scrollView.scrollBy(-60f) else controller.keyFlip(false) }
        bind(key(KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK), "nextch") { controller.chapterStep(!controller.ui.value.rtl) }
        bind(key(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK), "prevch") { controller.chapterStep(controller.ui.value.rtl) }
        bind(key(KeyEvent.VK_HOME, menu), "start") { controller.goTo(0, 0, rememberJump = true) }
        bind(key(KeyEvent.VK_END, menu), "end") { controller.goToProgress(1f) }
        bind(key(KeyEvent.VK_F, menu), "search") { controller.openPanel(Panel.SEARCH) }
        bind(key(KeyEvent.VK_T, menu), "toc") { controller.openPanel(Panel.CONTENTS) }
        bind(key(KeyEvent.VK_D, menu), "bookmark") { controller.toggleBookmark() }
        bind(key(KeyEvent.VK_B, menu), "bookmark2") { controller.toggleBookmark() }
        bind(key(KeyEvent.VK_G, menu), "goto") { goToDialog() }
        bind(key(KeyEvent.VK_EQUALS, menu), "bigger") { controller.updateSettings { it.copy(fontSize = it.fontSize + 1) } }
        bind(key(KeyEvent.VK_PLUS, menu), "bigger2") { controller.updateSettings { it.copy(fontSize = it.fontSize + 1) } }
        bind(key(KeyEvent.VK_ADD, menu), "bigger3") { controller.updateSettings { it.copy(fontSize = it.fontSize + 1) } }
        bind(key(KeyEvent.VK_MINUS, menu), "smaller") { controller.updateSettings { it.copy(fontSize = it.fontSize - 1) } }
        bind(key(KeyEvent.VK_SUBTRACT, menu), "smaller2") { controller.updateSettings { it.copy(fontSize = it.fontSize - 1) } }
        bind(key(KeyEvent.VK_0, menu), "resetsize") { controller.updateSettings { it.copy(fontSize = com.readarea.desktop.data.ReaderSettings().fontSize) } }
        bind(key(KeyEvent.VK_C, menu), "copy") { controller.ui.value.selection?.let { copy(it.text) } }
        bind(key(KeyEvent.VK_W, menu), "close") { saveAndClose() }
        bind(key(KeyEvent.VK_L, menu or InputEvent.SHIFT_DOWN_MASK), "listen") { controller.toggleSpeech() }
        bind(key(KeyEvent.VK_F11), "fullscreen") { toggleFullscreen() }
        bind(key(KeyEvent.VK_F, menu or InputEvent.CTRL_DOWN_MASK), "fullscreen-mac") { toggleFullscreen() }
        bind(key(KeyEvent.VK_ESCAPE), "escape") { escape() }
        bind(key(KeyEvent.VK_BACK_SPACE, InputEvent.ALT_DOWN_MASK), "back") { controller.goBack() }
    }

    private fun typing(): Boolean = focusOwner is javax.swing.text.JTextComponent

    private fun escape() {
        val u = controller.ui.value
        when {
            u.selection != null -> controller.clearSelection()
            u.footnote != null -> controller.dismissFootnote()
            u.panel != Panel.NONE -> controller.closePanel()
            u.endReached -> controller.dismissEnd()
            fullscreen -> toggleFullscreen()
            else -> controller.toggleMenu()
        }
    }

    private fun copy(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        toast(tr("toast_copied"))
    }

    private fun goToDialog() {
        val total = controller.totalPages()
        val hint = if (total != null) I18n.format("page_range", total) else I18n.format("percent_range")
        val value = Dialogs.input(this, tr("go_to_page_percent"), "", tr("go"), placeholder = hint)?.trim() ?: return
        when {
            value.endsWith("%") -> value.removeSuffix("%").trim().toFloatOrNull()?.let { controller.goToProgress((it / 100f).coerceIn(0f, 1f)) }
            total == null -> value.toFloatOrNull()?.let { controller.goToProgress((it / 100f).coerceIn(0f, 1f)) }
            else -> value.toIntOrNull()?.let { controller.goToPage(it.coerceIn(1, total)) }
        }
    }

    private fun toggleFullscreen() {
        val device = graphicsConfiguration?.device ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        fullscreen = !fullscreen
        device.fullScreenWindow = if (fullscreen) this else null
        topBar.update(controller.ui.value)
    }

    // Window lifecycle

    private fun restoreBounds() {
        val b = app.settings.app.value.readerWindow
        val screen = Toolkit.getDefaultToolkit().screenSize
        val offset = app.openReaders.size * 28
        if (b != null && b.width >= 480 && b.height >= 400 && b.x < screen.width - 100 && b.y < screen.height - 100) {
            setBounds(b.x + offset, b.y + offset, b.width, b.height)
            if (b.maximized) extendedState = Frame.MAXIMIZED_BOTH
        } else {
            setSize((screen.width * 0.6).toInt().coerceIn(760, 1400), (screen.height * 0.85).toInt().coerceIn(620, 1100))
            setLocationRelativeTo(app.main)
        }
    }

    fun saveAndClose() {
        if (closed) return
        closed = true
        if (fullscreen) runCatching { graphicsConfiguration?.device?.fullScreenWindow = null }
        val maximized = extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH
        if (!fullscreen) app.settings.updateApp { it.copy(readerWindow = WindowBounds(x, y, width, height, maximized)) }
        controller.close()
        hideTimer.stop()
        app.readerClosed(bookId)
        dispose()
    }

    // Pieces of the reading screen

    /** Positions the page, bars, panels and pop-ups inside the window. */
    private inner class ReaderLayout : LayoutManager {
        override fun addLayoutComponent(name: String?, comp: Component?) {}
        override fun removeLayoutComponent(comp: Component?) {}
        override fun preferredLayoutSize(parent: Container) = Dimension(900, 700)
        override fun minimumLayoutSize(parent: Container) = Dimension(400, 300)

        override fun layoutContainer(parent: Container) {
            val w = parent.width
            val h = parent.height
            views.setBounds(0, 0, w, h)
            val topH = 58
            topBar.setBounds(0, 0, w, topH)
            val bottomH = 76
            bottomBar.setBounds(0, h - bottomH, w, bottomH)
            val sw = 380.coerceAtMost(w - 40)
            speechBar.setBounds((w - sw) / 2, h - (if (bottomBar.isVisible) bottomH else 0) - 66, sw, 56)
            val panelTop = if (topBar.isVisible) topH else 0
            // Side panels grow a little on wide windows and never cover the whole page.
            val pw = (w * 0.3f).toInt().coerceIn(360, 420).coerceAtMost(w - 60)
            leftPanel?.setBounds(0, panelTop, pw, h - panelTop)
            rightPanel?.setBounds(w - pw, panelTop, pw, h - panelTop)
            selectionPopup?.let { p ->
                val sel = controller.ui.value.selection ?: return@let
                val ps = p.preferredSize
                var x = sel.bounds.centerX.toInt() - ps.width / 2
                var y = sel.bounds.y - ps.height - 10
                if (y < 8) y = sel.bounds.y + sel.bounds.height + 10
                x = x.coerceIn(8, (w - ps.width - 8).coerceAtLeast(8))
                y = y.coerceIn(8, (h - ps.height - 8).coerceAtLeast(8))
                p.setBounds(x, y, ps.width, ps.height)
            }
            footnotePopup?.let { p ->
                val ps = p.preferredSize
                val width = ps.width.coerceAtMost(w - 32)
                val height = ps.height.coerceAtMost(h / 2)
                val anchor = controller.ui.value.footnote?.bounds
                var y = (anchor?.y ?: h / 2) + 24
                if (y + height > h - 16) y = ((anchor?.y ?: h / 2) - height - 24).coerceAtLeast(16)
                p.setBounds((w - width) / 2, y, width, height)
            }
            endCard?.let { p ->
                val ps = p.preferredSize
                p.setBounds((w - ps.width) / 2, (h - ps.height) / 2, ps.width, ps.height)
            }
        }
    }

    /** The bar across the top: back, title, and the reading tools. */
    private inner class TopBar : JPanel(BorderLayout()) {
        private val title = Ui.label("", 14f, Font.BOLD)
        private val chapter = Ui.secondary("", 12f)
        private val bookmark = IconButton("bookmark", tr("bookmark"))
        private val speak = IconButton("speak", tr("read_aloud"))
        private val auto = IconButton("timer", tr("auto_page_turn"))
        private val full = IconButton("fullscreen", tr("full_screen"))
        private val toc = IconButton("toc", tr("tool_contents"))
        private val search = IconButton("search", tr("search_in_book"))
        private val text = IconButton("text", tr("tool_text"))
        private val theme = IconButton("sun", tr("reading_theme"))
        private val back = IconButton("back", tr("back_to_library"))

        init {
            isOpaque = false
            border = EmptyBorder(6, if (Os.current == Os.MAC && !fullscreen) 10 else 10, 6, 10)
            back.addActionListener {
                app.main.bringToFront()
                app.main.navigate("library")
            }
            toc.addActionListener { controller.openPanel(Panel.CONTENTS) }
            search.addActionListener { controller.openPanel(Panel.SEARCH) }
            text.addActionListener { controller.openPanel(Panel.TEXT) }
            theme.addActionListener { controller.openPanel(Panel.THEME) }
            bookmark.addActionListener { controller.toggleBookmark() }
            speak.addActionListener { controller.toggleSpeech() }
            auto.addActionListener { controller.toggleAutoTurn() }
            full.addActionListener { toggleFullscreen() }
            val more = IconButton("more", tr("more"))
            more.addActionListener {
                javax.swing.JPopupMenu().apply {
                    add(javax.swing.JMenuItem(tr("go_to") + "…").apply { addActionListener { goToDialog() } })
                    add(javax.swing.JMenuItem(tr("details")).apply { addActionListener { app.main.showBook(bookId); app.main.bringToFront() } })
                    add(javax.swing.JMenuItem(com.readarea.desktop.ui.library.BookActions.revealLabel()).apply {
                        addActionListener { app.scope.launch { app.library.get(bookId)?.let { SystemIntegration.reveal(java.io.File(it.path)) } } }
                    })
                    addSeparator()
                    add(javax.swing.JMenuItem(tr("keyboard_shortcuts")).apply { addActionListener { Dialogs.message(this@ReaderWindow, tr("keyboard_shortcuts"), shortcutsText()) } })
                }.show(more, 0, more.height)
            }
            val left = Ui.hbox(back, Ui.gap(8), Ui.vbox(title, chapter))
            add(left, BorderLayout.WEST)
            add(Ui.hbox(toc, search, text, theme, speak, auto, bookmark, full, more, gap = 2), BorderLayout.EAST)
        }

        fun update(u: ReaderUi) {
            title.text = u.title
            chapter.text = u.chapterTitle.takeIf { it != u.title }.orEmpty()
            chapter.isVisible = chapter.text.isNotEmpty()
            bookmark.iconName = if (u.bookmarked) "bookmark-filled" else "bookmark"
            bookmark.active = u.bookmarked
            speak.isVisible = u.ttsAvailable
            speak.active = u.speaking
            auto.active = u.autoTurn
            toc.active = u.panel == Panel.CONTENTS
            search.active = u.panel == Panel.SEARCH
            text.active = u.panel == Panel.TEXT
            text.isVisible = !u.fixed
            theme.active = u.panel == Panel.THEME
            full.iconName = if (fullscreen) "fullscreen-exit" else "fullscreen"
        }

        override fun paintComponent(g0: Graphics) {
            val g = g0.create().smooth()
            // Opaque, so the page's running header doesn't ghost through the bar.
            g.color = controller.engine?.theme?.let { Color(it.background) } ?: pal.surface
            g.fillRect(0, 0, width, height)
            g.color = controller.engine?.theme?.let { Color(it.secondary).alpha(70) } ?: pal.outlineVariant.alpha(120)
            g.fillRect(0, height - 1, width, 1)
            g.dispose()
        }
    }

    /** The bottom bar: progress slider with chapter steps and the page count. */
    private inner class BottomBar : JPanel(BorderLayout()) {
        private val slider = JSlider(0, 1000, 0)
        private val page = Ui.secondary("", 12f)
        private val left = Ui.secondary("", 12f)
        private val jump = PillButton(tr("back"), "undo", ButtonKind.TONAL, compact = true)
        private var updating = false

        init {
            isOpaque = false
            border = EmptyBorder(8, 18, 10, 18)
            slider.isOpaque = false
            slider.addChangeListener {
                if (updating) return@addChangeListener
                val p = slider.value / 1000f
                slider.toolTipText = controller.titleAt(p)
                if (!slider.valueIsAdjusting) controller.goToProgress(p)
            }
            jump.addActionListener { controller.goBack() }
            jump.isVisible = false
            val prev = IconButton("chevron-left", tr("previous_chapter"), 18).apply { addActionListener { controller.chapterStep(controller.ui.value.rtl) } }
            val next = IconButton("chevron-right", tr("next_chapter"), 18).apply { addActionListener { controller.chapterStep(!controller.ui.value.rtl) } }
            add(Ui.hbox(prev, slider, next), BorderLayout.CENTER)
            val info = Transparent(BorderLayout())
            info.add(page, BorderLayout.WEST)
            info.add(Ui.hbox(jump, Ui.gap(10), left), BorderLayout.EAST)
            add(info, BorderLayout.SOUTH)
        }

        fun update(u: ReaderUi) {
            updating = true
            if (!slider.valueIsAdjusting) slider.value = (u.progress * 1000).toInt()
            slider.componentOrientation = if (u.rtl) java.awt.ComponentOrientation.RIGHT_TO_LEFT else java.awt.ComponentOrientation.LEFT_TO_RIGHT
            slider.inverted = u.rtl
            updating = false
            page.text = u.pageLabel
            left.text = if (u.fixed || u.pagesLeftInChapter <= 0) I18n.format("percent", (u.progress * 100).toInt()) else I18n.plural("pages_left_chapter", u.pagesLeftInChapter, u.pagesLeftInChapter)
            jump.isVisible = u.jumpBack != null
        }

        override fun paintComponent(g0: Graphics) {
            val g = g0.create().smooth()
            // Opaque, so the page's running header doesn't ghost through the bar.
            g.color = controller.engine?.theme?.let { Color(it.background) } ?: pal.surface
            g.fillRect(0, 0, width, height)
            g.color = controller.engine?.theme?.let { Color(it.secondary).alpha(70) } ?: pal.outlineVariant.alpha(120)
            g.fillRect(0, 0, width, 1)
            g.dispose()
        }
    }

    /** Floating controls while reading aloud or turning pages automatically. */
    private inner class SpeechBar : RoundCard(FlowLayout(FlowLayout.CENTER, 6, 8)) {
        private val label = Ui.label("", 13f, Font.BOLD)
        private val play = IconButton("pause", tr("play_pause"))
        private val stop = IconButton("stop", tr("stop"))
        private val slower = IconButton("minus", tr("slower_label"), 16)
        private val faster = IconButton("plus", tr("faster_label"), 16)

        init {
            isVisible = false
            play.addActionListener { controller.toggleSpeech() }
            stop.addActionListener { if (controller.ui.value.speaking) controller.stopTts() else controller.stopAutoTurn() }
            slower.addActionListener { adjust(-0.1f) }
            faster.addActionListener { adjust(0.1f) }
            add(label)
            add(slower)
            add(play)
            add(faster)
            add(stop)
        }

        private fun adjust(delta: Float) {
            val u = controller.ui.value
            if (u.speaking) controller.updateSettings { it.copy(ttsRate = ((it.ttsRate + delta) * 10).toInt() / 10f) }
            else controller.updateSettings { it.copy(autoTurnSeconds = it.autoTurnSeconds - (delta * 50).toInt()) }
        }

        fun update(u: ReaderUi) {
            val s = controller.settings.value
            label.text = if (u.speaking) I18n.format("reading_aloud", s.ttsRate) else if (controller.scrollMode) tr("auto_scrolling_short") else I18n.format("auto_turning_short", s.autoTurnSeconds)
            play.isVisible = u.speaking
            play.iconName = if (u.ttsPaused) "play" else "pause"
        }
    }

    private inner class SelectionPopup(private val sel: SelectionUi) : RoundCard(FlowLayout(FlowLayout.LEFT, 4, 6)) {
        init {
            ReadingThemes.highlightColors.forEachIndexed { i, argb ->
                val dot = object : Widget() {
                    init {
                        preferredSize = Dimension(28, 28)
                        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                        toolTipText = tr("highlight_" + ReadingThemes.highlightNames[i].lowercase())
                        getAccessibleContext().accessibleName = toolTipText
                        addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) = controller.highlightSelection(i) })
                    }

                    override fun paintComponent(g0: Graphics) {
                        val g = g0.create().smooth()
                        g.color = Color(argb)
                        g.fillOval(3, 3, 22, 22)
                        if (sel.color == i) {
                            g.color = pal.onSurface
                            g.stroke = java.awt.BasicStroke(2f)
                            g.drawOval(1, 1, 26, 26)
                        }
                        g.dispose()
                    }
                }
                add(dot)
            }
            add(Ui.gap(4))
            add(IconButton("note", tr("add_note"), 18).apply {
                addActionListener {
                    val note = Dialogs.input(this@ReaderWindow, tr("note"), sel.note.orEmpty(), tr("save"), multiline = true, placeholder = tr("your_thoughts"))
                    if (note != null) controller.highlightSelection(if (sel.color >= 0) sel.color else 0, note.trim())
                }
            })
            add(IconButton("copy", tr("copy"), 18).apply { addActionListener { copy(sel.text); controller.clearSelection() } })
            if (controller.ui.value.ttsAvailable) add(IconButton("speak", tr("from_this_page"), 18).apply { addActionListener { controller.speakFromSelection() } })
            add(IconButton("search", tr("find"), 18).apply {
                addActionListener {
                    val q = sel.text.take(80)
                    controller.clearSelection()
                    controller.openPanel(Panel.SEARCH)
                    controller.search(q)
                }
            })
            sel.highlightId?.let { id -> add(IconButton("trash", tr("delete_highlight"), 18).apply { addActionListener { controller.deleteHighlight(id) } }) }
        }
    }

    private inner class FootnoteCard(f: FootnoteUi) : RoundCard(BorderLayout()) {
        init {
            border = EmptyBorder(14, 18, 12, 18)
            val area = JTextArea(f.text).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                isOpaque = false
                font = Font(AppTheme.headlineFamily, Font.PLAIN, 15)
                foreground = pal.onSurface
                border = null
            }
            val scroll = Ui.scroll(area)
            scroll.preferredSize = Dimension(520, (area.preferredSize.height + 8).coerceIn(40, 260))
            add(scroll, BorderLayout.CENTER)
            add(Transparent(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                add(PillButton(tr("go_to_note"), "arrow-right", ButtonKind.TEXT, compact = true).apply { addActionListener { controller.followFootnote() } })
                add(PillButton(tr("close"), null, ButtonKind.TEXT, compact = true).apply { addActionListener { controller.dismissFootnote() } })
            }, BorderLayout.SOUTH)
        }
    }

    /** Loading and error states before a book is on screen. */
    private inner class StatusPanel : JPanel(java.awt.GridBagLayout()) {
        private val icon = javax.swing.JLabel()
        private val title = Ui.headline("", 22f)
        private val body = Ui.secondary("", 13.5f)
        private val back = PillButton(tr("back_to_library"), "back", ButtonKind.TONAL)

        init {
            back.addActionListener {
                saveAndClose()
                app.main.bringToFront()
            }
            add(Ui.vbox(icon, Ui.gap(10), title, Ui.gap(6), body, Ui.gap(16), back, align = CENTER_ALIGNMENT))
        }

        fun show(error: String?) {
            icon.icon = com.readarea.desktop.ui.theme.VectorIcon(if (error != null) "info" else "book", 44) { pal.accent }
            title.text = if (error != null) tr("error_title") else tr("opening")
            body.text = error ?: tr("preparing_pages")
            back.isVisible = error != null
        }

        override fun paintComponent(g: Graphics) {
            g.color = controller.engine?.theme?.let { Color(it.background) } ?: pal.background
            g.fillRect(0, 0, width, height)
        }
    }

    private fun shortcutsText(): String {
        val m = if (Os.current == Os.MAC) "⌘" else "Ctrl+"
        return listOf(
            "→ / Space / Page Down" to tr("next"),
            "← / Shift+Space / Page Up" to tr("previous"),
            "Alt+→ / Alt+←" to tr("next_chapter") + " / " + tr("previous_chapter"),
            "${m}F" to tr("search_in_book"),
            "${m}T" to tr("tool_contents"),
            "${m}D" to tr("bookmark"),
            "${m}G" to tr("go_to"),
            "$m+ / $m−" to tr("font_size"),
            "${m}Shift+L" to tr("read_aloud"),
            (if (Os.current == Os.MAC) "⌃⌘F" else "F11") to tr("full_screen"),
            "Esc" to tr("menu"),
        ).joinToString("\n") { "${it.first}   —   ${it.second}" }
    }

    private companion object {
        /** How close to the top and bottom edges the pointer brings up the bars. */
        const val EDGE_TOP = 72
        const val EDGE_BOTTOM = 90
    }
}

/** A floating rounded card with a soft outline, used for the reader's pop-ups. */
open class RoundCard(layout: LayoutManager) : JPanel(layout) {
    init {
        isOpaque = false
        border = EmptyBorder(4, 10, 4, 10)
    }

    override fun paintComponent(g0: Graphics) {
        val g = g0.create().smooth()
        for (i in 4 downTo 1) {
            g.color = Color(0, 0, 0, 10)
            g.fill(RoundRectangle2D.Float(-i.toFloat() + 4, i.toFloat(), width.toFloat() - 8 + i * 2, height.toFloat() - 4, 22f, 22f))
        }
        g.color = pal.surface
        g.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat() - 4, 20f, 20f))
        g.color = pal.outlineVariant
        g.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 5f, 20f, 20f))
        g.dispose()
    }
}
