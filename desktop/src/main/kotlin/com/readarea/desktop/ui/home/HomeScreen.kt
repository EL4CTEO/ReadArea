package com.readarea.desktop.ui.home

import com.readarea.core.library.BookStatus
import com.readarea.core.library.ReadingStats
import com.readarea.desktop.App
import com.readarea.desktop.data.Book
import com.readarea.desktop.i18n.I18n
import com.readarea.desktop.i18n.tr
import com.readarea.desktop.ui.MainWindow
import com.readarea.desktop.ui.Screen
import com.readarea.desktop.ui.components.ScrollingStack
import com.readarea.desktop.ui.components.ButtonKind
import com.readarea.desktop.ui.components.Widget
import com.readarea.desktop.ui.components.FocusRing
import com.readarea.desktop.ui.components.onActivate
import com.readarea.desktop.ui.components.leadingX
import com.readarea.desktop.ui.components.rtl
import com.readarea.desktop.ui.components.Card
import com.readarea.desktop.ui.components.CoverPainter
import com.readarea.desktop.ui.components.PillButton
import com.readarea.desktop.ui.components.ProgressLine
import com.readarea.desktop.ui.components.ScrollableColumn
import com.readarea.desktop.ui.components.Transparent
import com.readarea.desktop.ui.components.Ui
import com.readarea.desktop.ui.components.emptyState
import com.readarea.desktop.ui.components.pal
import com.readarea.desktop.ui.components.sectionHeader
import com.readarea.desktop.ui.components.smooth
import com.readarea.desktop.ui.library.BookDetailsDialog
import com.readarea.desktop.ui.library.BookGrid
import com.readarea.desktop.ui.theme.AppTheme
import com.readarea.desktop.ui.theme.alpha
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Arc2D
import java.awt.geom.RoundRectangle2D
import java.time.LocalTime
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import com.readarea.desktop.ui.components.LeadingBorder

/**
 * The book "Continue reading" offers: the one opened last, unless it's finished. Opening a book is enough, without
 * turning a page first, so the card follows the reader from one book to the next (the same rule as on Android).
 */
internal fun continueReadingBook(books: List<Book>): Book? =
    books.filter { it.lastOpenedAt > 0 && it.status != BookStatus.FINISHED }.maxByOrNull { it.lastOpenedAt }

/** The other books under way, most recently opened first, for the "Also reading" row. */
internal fun alsoReading(books: List<Book>, current: Book?): List<Book> =
    books.filter { it.id != current?.id && (it.status == BookStatus.READING || (it.progress > 0.005f && it.status != BookStatus.FINISHED)) }
        .sortedByDescending { it.lastOpenedAt }

@OptIn(FlowPreview::class)
class HomeScreen(private val app: App, private val window: MainWindow) : Screen {
    private val cards = CardLayout()
    private val root = Transparent(cards)
    private val column = ScrollingStack()
    override val component: JComponent = root

    init {
        column.border = LeadingBorder(26, 30, 30, 30)
        root.add(Ui.scroll(column), "content")
        root.add(Transparent(BorderLayout()).apply {
            add(emptyState("logo", tr("welcome_title"), tr("welcome_body") + "\n\n" + tr("drop_hint"),
                PillButton(tr("add_folder"), "folder").apply { addActionListener { app.chooseFolder(window) } },
                PillButton(tr("open_files"), "file", ButtonKind.TONAL).apply { addActionListener { app.chooseFiles(window) } }))
        }, "welcome")
        app.scope.launch {
            // Rebuild only while visible, and at most a couple of times a second while reading elsewhere saves progress.
            app.library.books.combine(app.library.version) { b, _ -> b }.debounce(300).collect { books ->
                latest = books
                if (root.isShowing || !rendered) books?.let { render(it) } else dirty = true
            }
        }
    }

    private var latest: List<Book>? = null
    private var dirty = false
    private var rendered = false

    override fun onShow() {
        if (dirty) {
            dirty = false
            latest?.let { app.scope.launch { render(it) } }
        }
    }

    private suspend fun render(books: List<Book>) {
        rendered = true
        if (books.isEmpty()) {
            cards.show(root, "welcome")
            return
        }
        cards.show(root, "content")
        val days = app.library.read { days(400) }
        val today = ReadingStats.today()
        val todayMs = days.firstOrNull { it.day == today }?.ms ?: 0L
        val streak = ReadingStats.streaks(days, today).current
        val hero = continueReadingBook(books)
        column.removeAll()

        val greeting = when (LocalTime.now().hour) {
            in 5..11 -> "greeting_morning"
            in 12..17 -> "greeting_afternoon"
            in 18..22 -> "greeting_evening"
            else -> "greeting_night"
        }
        val sub = if (streak > 0) I18n.plural("reading_streak", streak, streak) else tr("start_streak")
        column.add(Ui.vbox(Ui.headline(tr(greeting), 30f), Ui.gap(4), Ui.secondary(sub, 13.5f)))
        column.add(Ui.gap(22))

        val top = Transparent(BorderLayout(18, 0))
        top.add(heroCard(hero), BorderLayout.CENTER)
        top.add(goalCard(todayMs, app.settings.app.value.dailyGoalMinutes), BorderLayout.LINE_END)
        top.alignmentX = JComponent.LEFT_ALIGNMENT
        top.maximumSize = Dimension(Int.MAX_VALUE, 290)
        column.add(top)

        val also = alsoReading(books, hero).take(12)
        if (also.isNotEmpty()) {
            column.add(Ui.gap(26))
            column.add(sectionHeader(tr("home_also_reading")).apply { alignmentX = JComponent.LEFT_ALIGNMENT })
            column.add(CoverRow(also))
        }
        val recent = books.sortedByDescending { it.addedAt }.take(14)
        column.add(Ui.gap(26))
        column.add(sectionHeader(tr("home_recently_added"), PillButton(tr("see_all"), null, ButtonKind.TEXT, compact = true).apply {
            addActionListener {
                app.settings.updateApp { it.copy(sort = "added") }
                window.navigate("library")
            }
        }).apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        column.add(CoverRow(recent))
        column.revalidate()
        column.repaint()
    }

    private fun heroCard(b: Book?): JComponent {
        val card = Card(22, BorderLayout(22, 0), fill = { pal.surfaceContainer })
        card.border = LeadingBorder(22, 22, 22, 26)
        if (b == null) {
            card.add(Ui.vbox(
                Ui.label(tr("continue_reading"), 12.5f, Font.BOLD) { pal.accent },
                Ui.gap(8), Ui.headline(tr("hero_empty_title"), 22f), Ui.gap(6),
                Ui.wrapLabel(tr("hero_empty_body"), 420, 13.5f) { pal.onSurfaceVariant }, Ui.gap(16),
                PillButton(tr("browse_library"), "library").apply { addActionListener { window.navigate("library") } },
            ))
            return card
        }
        val cover = object : Widget() {
            init {
                preferredSize = Dimension(150, 225)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) = app.openBook(b.id) })
            }

            override fun paintComponent(g: Graphics) = CoverPainter.paint(g.create().smooth(), b, 2f, 2f, 146f, 219f, 7f, true) { repaint() }
        }
        card.add(cover, BorderLayout.LINE_START)
        val progress = ProgressLine(b.progress, 5).apply { maximumSize = Dimension(360, 8); preferredSize = Dimension(360, 8) }
        val left = if (b.readingMs > 0) "  ·  " + tr("time_spent", BookDetailsDialog.formatDuration(b.readingMs)) else ""
        val info = Ui.vbox(
            Ui.label(tr("continue_reading"), 12.5f, Font.BOLD) { pal.accent },
            Ui.gap(8),
            Ui.label("<html>${Ui.escape(b.title)}</html>", 24f).apply { font = AppTheme.headline(24f); maximumSize = Dimension(520, 90) },
            Ui.gap(4),
            Ui.secondary(b.author, 14f),
            Ui.gap(18),
            progress,
            Ui.gap(8),
            Ui.secondary(I18n.format("percent_read", (b.progress * 100).toInt()) + left, 12.5f),
            Ui.gap(18),
            Transparent(FlowLayout(FlowLayout.LEADING, 0, 0)).apply {
                add(PillButton(I18n.format("continue_percent", (b.progress * 100).toInt()), "read").apply { addActionListener { app.openBook(b.id) } })
                add(Ui.gap(8))
                add(PillButton(tr("details"), null, ButtonKind.TEXT).apply { addActionListener { window.showBook(b.id) } })
            },
        )
        card.add(info, BorderLayout.CENTER)
        return card
    }

    private fun goalCard(todayMs: Long, goal: Int): JComponent {
        val card = Card(22, BorderLayout(), fill = { pal.surfaceContainer })
        card.border = LeadingBorder(20, 20, 20, 20)
        card.preferredSize = Dimension(230, 270)
        val minutes = (todayMs / 60_000).toInt()
        val ring = object : Widget() {
            init {
                preferredSize = Dimension(150, 150)
            }

            override fun paintComponent(g0: Graphics) {
                val g = g0.create().smooth()
                val s = minOf(width, height) - 16f
                val x = (width - s) / 2f
                val y = (height - s) / 2f
                g.stroke = BasicStroke(12f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.color = pal.onSurface.alpha(24)
                g.draw(Arc2D.Float(x, y, s, s, 0f, 360f, Arc2D.OPEN))
                g.color = pal.accent
                val frac = (minutes.toFloat() / goal.coerceAtLeast(1)).coerceIn(0f, 1f)
                if (frac > 0f) g.draw(Arc2D.Float(x, y, s, s, 90f, -360f * frac, Arc2D.OPEN))
                g.font = AppTheme.headline(30f)
                g.color = pal.onSurface
                val t = minutes.toString()
                val fm = g.fontMetrics
                g.drawString(t, (width - fm.stringWidth(t)) / 2f, height / 2f + fm.ascent / 2f - 10f)
                g.font = AppTheme.ui(12f)
                g.color = pal.onSurfaceVariant
                val u = tr("minutes_label")
                g.drawString(u, (width - g.fontMetrics.stringWidth(u)) / 2f, height / 2f + 22f)
                g.dispose()
            }
        }
        card.add(Ui.label(tr("daily_goal"), 12.5f, Font.BOLD) { pal.accent }, BorderLayout.NORTH)
        card.add(ring, BorderLayout.CENTER)
        card.add(Ui.secondary(I18n.format("goal_progress", BookDetailsDialog.formatDuration(todayMs), goal), 12.5f).apply { horizontalAlignment = javax.swing.SwingConstants.CENTER }, BorderLayout.SOUTH)
        return card
    }

    /**
     * A row of covers with titles; click to open, right-click for details. Only covers that fit whole are shown;
     * the rest are a click away in the library.
     * Arrow keys move between covers, Enter opens one and the context-menu key shows its details.
     */
    private inner class CoverRow(private val books: List<Book>) : Widget(javax.accessibility.AccessibleRole.LIST) {
        private val cw = 118f
        private val ch = cw * 1.5f
        private val gap = 22f
        private var hover = -1
        private var focused = 0

        init {
            alignmentX = LEFT_ALIGNMENT
            preferredSize = Dimension(((cw + gap) * books.size).toInt(), (ch + 58).toInt())
            minimumSize = Dimension(0, (ch + 58).toInt())
            maximumSize = Dimension(Int.MAX_VALUE, (ch + 58).toInt())
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            val m = object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val b = books.getOrNull(index(e.x)) ?: return
                    if (e.button == MouseEvent.BUTTON3 || e.isPopupTrigger) window.showBook(b.id) else app.openBook(b.id)
                }

                override fun mouseMoved(e: MouseEvent) {
                    val i = index(e.x)
                    if (i != hover) {
                        hover = i
                        toolTipText = books.getOrNull(i)?.let { "${it.title}${if (it.author.isNotBlank()) " — " + it.author else ""}" }
                        repaint()
                    }
                }

                override fun mouseExited(e: MouseEvent) {
                    hover = -1
                    repaint()
                }
            }
            addMouseListener(m)
            addMouseMotionListener(m)
            onActivate { books.getOrNull(focused)?.let { app.openBook(it.id) } }
            key(KeyEvent.VK_LEFT) { move(if (componentOrientation.isLeftToRight) -1 else 1) }
            key(KeyEvent.VK_RIGHT) { move(if (componentOrientation.isLeftToRight) 1 else -1) }
            key(KeyEvent.VK_CONTEXT_MENU) { books.getOrNull(focused)?.let { window.showBook(it.id) } }
            describe()
        }

        private fun key(code: Int, action: () -> Unit) {
            val name = "key$code"
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(code, 0), name)
            actionMap.put(name, object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent) = action()
            })
        }

        private fun move(step: Int) {
            focused = (focused + step).coerceIn(0, (visibleCount() - 1).coerceAtLeast(0))
            describe()
            repaint()
        }

        private fun describe() {
            getAccessibleContext().accessibleName = books.getOrNull(focused)?.let { b -> if (b.author.isBlank()) b.title else "${b.title}, ${b.author}" }
        }

        /** How many covers fit whole in the current width. */
        private fun visibleCount(): Int = (((width + gap) / (cw + gap)).toInt()).coerceIn(0, books.size)

        private fun index(x: Int): Int {
            // Covers start at the leading edge: from the right in right-to-left languages.
            val lx = if (rtl) width - x else x
            return (lx / (cw + gap)).toInt().takeIf { it in 0 until visibleCount() && lx % (cw + gap) <= cw } ?: -1
        }

        override fun paintComponent(g0: Graphics) {
            val g = g0.create().smooth()
            val n = visibleCount()
            if (focused >= n) focused = (n - 1).coerceAtLeast(0)
            for (i in 0 until n) {
                val b = books[i]
                val x = leadingX(i * (cw + gap), cw)
                val lift = if (i == hover) -4f else 0f
                CoverPainter.paint(g, b, x, 4f + lift, cw, ch, 6f, true) { repaint() }
                if (i == focused) FocusRing.paint(g, this, RoundRectangle2D.Float(x - 3f, 1f + lift, cw + 6f, ch + 6f, 12f, 12f))
                if (b.progress > 0.005f && b.status != BookStatus.FINISHED) {
                    g.color = pal.onSurface.alpha(28)
                    g.fill(RoundRectangle2D.Float(x, ch + 10f, cw, 3f, 3f, 3f))
                    g.color = pal.accent
                    val fill = cw * b.progress.coerceIn(0f, 1f)
                    g.fill(RoundRectangle2D.Float(if (rtl) x + cw - fill else x, ch + 10f, fill, 3f, 3f, 3f))
                }
                g.color = pal.onSurface
                BookGrid.wrap(g, b.title, AppTheme.ui(12f, Font.BOLD), x, ch + 16f, cw, 2, alignEnd = rtl)
            }
            g.dispose()
        }
    }
}
