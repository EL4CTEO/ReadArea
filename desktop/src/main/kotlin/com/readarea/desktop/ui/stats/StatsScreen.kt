package com.readarea.desktop.ui.stats

import com.readarea.core.library.BookStatus
import com.readarea.core.library.ReadingStats
import com.readarea.desktop.App
import com.readarea.desktop.data.BookTime
import com.readarea.desktop.i18n.I18n
import com.readarea.desktop.i18n.tr
import com.readarea.desktop.ui.MainWindow
import com.readarea.desktop.ui.Screen
import com.readarea.desktop.ui.components.ScrollingStack
import com.readarea.desktop.ui.components.leadingX
import com.readarea.desktop.ui.components.Card
import com.readarea.desktop.ui.components.Widget
import com.readarea.desktop.ui.components.ScrollableColumn
import com.readarea.desktop.ui.components.Transparent
import com.readarea.desktop.ui.components.Ui
import com.readarea.desktop.ui.components.WrapLayout
import com.readarea.desktop.ui.components.emptyState
import com.readarea.desktop.ui.components.pal
import com.readarea.desktop.ui.components.sectionHeader
import com.readarea.desktop.ui.components.smooth
import com.readarea.desktop.ui.library.BookDetailsDialog
import com.readarea.desktop.ui.theme.AppTheme
import com.readarea.desktop.ui.theme.VectorIcon
import com.readarea.desktop.ui.theme.alpha
import com.readarea.desktop.ui.theme.lerp
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.GridLayout
import java.awt.geom.RoundRectangle2D
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JSlider
import com.readarea.desktop.ui.components.LeadingBorder
import com.readarea.desktop.ui.components.ellipsize

class StatsScreen(private val app: App, @Suppress("UNUSED_PARAMETER") window: MainWindow) : Screen {
    private val column = ScrollingStack()
    private val cards = CardLayout()
    private val root = Transparent(cards)
    override val component: JComponent = root
    private var dirty = true

    init {
        column.border = LeadingBorder(22, 28, 28, 28)
        root.add(Ui.scroll(column), "content")
        root.add(Transparent(BorderLayout()).apply {
            border = LeadingBorder(22, 28, 0, 28)
            add(Ui.headline(tr("nav_stats")), BorderLayout.NORTH)
            add(emptyState("stats", tr("reading_stats"), tr("stats_empty")), BorderLayout.CENTER)
        }, "empty")
        app.scope.launch {
            app.library.books.combine(app.library.version) { b, _ -> b }.collect {
                dirty = true
                if (root.isShowing) render()
            }
        }
    }

    override fun onShow() {
        if (dirty) app.scope.launch { render() }
    }

    private suspend fun render() {
        dirty = false
        val days = app.library.read { days(400) }
        val top = app.library.read { topBooks(8) }
        val totalMs = app.library.read { totalMs() }
        val totalPages = app.library.read { totalPages() }
        val books = app.library.books.value.orEmpty()
        if (days.isEmpty() && books.none { it.status == BookStatus.FINISHED }) {
            cards.show(root, "empty")
            return
        }
        cards.show(root, "content")
        val today = ReadingStats.today()
        val streaks = ReadingStats.streaks(days, today)
        val year = LocalDate.now().year
        val finished = books.filter { it.status == BookStatus.FINISHED }
        val finishedYear = finished.count { it.finishedAt > 0 && java.time.Instant.ofEpochMilli(it.finishedAt).atZone(ZoneId.systemDefault()).year == year }
        val byDay = days.associate { it.day to it.ms }
        column.removeAll()
        column.add(Ui.headline(tr("nav_stats")).apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        column.add(Ui.gap(18))
        val tiles = Transparent(WrapLayout(java.awt.FlowLayout.LEADING, 0, 0)).apply { alignmentX = JComponent.LEFT_ALIGNMENT }
        (tiles.layout as WrapLayout).hgap = 14
        (tiles.layout as WrapLayout).vgap = 14
        // FlowLayout puts its gap before the first tile too; pull the row back so it lines up with the headings.
        tiles.border = LeadingBorder(0, -14, 0, -14)
        tiles.add(tile("clock", tr("today"), BookDetailsDialog.formatDuration(byDay[today] ?: 0)))
        tiles.add(tile("flame", tr("current_streak"), I18n.plural("days_short", streaks.current, streaks.current)))
        tiles.add(tile("trophy", tr("best_streak"), I18n.plural("days_short", streaks.best, streaks.best)))
        tiles.add(tile("stats", tr("total_time"), BookDetailsDialog.formatDuration(totalMs)))
        tiles.add(tile("pages", tr("pages_turned"), "%,d".format(I18n.locale, totalPages)))
        tiles.add(tile("check", tr("books_finished"), finished.size.toString()))
        tiles.add(tile("target", tr("finished_this_year"), finishedYear.toString()))
        column.add(tiles)
        column.add(Ui.gap(24))
        column.add(sectionHeader(tr("last_7_days")).apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        column.add(chartCard(WeekChart(today, byDay, app.settings.app.value.dailyGoalMinutes)))
        column.add(Ui.gap(24))
        column.add(sectionHeader(tr("last_20_weeks")).apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        column.add(chartCard(Heatmap(today, byDay, app.settings.app.value.dailyGoalMinutes)))
        if (top.isNotEmpty()) {
            column.add(Ui.gap(24))
            column.add(sectionHeader(tr("most_time_spent")).apply { alignmentX = JComponent.LEFT_ALIGNMENT })
            column.add(topList(top))
        }
        column.add(Ui.gap(24))
        column.add(sectionHeader(tr("daily_goal")).apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        column.add(goalEditor())
        column.revalidate()
        column.repaint()
    }

    private fun tile(icon: String, label: String, value: String): JComponent {
        val c = Card(18, BorderLayout(), { pal.surfaceContainer })
        c.border = LeadingBorder(16, 18, 16, 18)
        c.preferredSize = Dimension(196, 108)
        c.add(JLabel(VectorIcon(icon, 20) { pal.accent }), BorderLayout.NORTH)
        c.add(Ui.vbox(Ui.label(value, 22f).apply { font = AppTheme.headline(22f) }, Ui.secondary(label, 12f)), BorderLayout.SOUTH)
        return c
    }

    private fun chartCard(chart: JComponent): JComponent = Card(18, BorderLayout(), { pal.surfaceContainer }).apply {
        border = LeadingBorder(16, 18, 14, 18)
        add(chart)
        alignmentX = JComponent.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, chart.preferredSize.height + 32)
    }

    private fun topList(top: List<BookTime>): JComponent {
        val max = top.maxOf { it.ms }.coerceAtLeast(1)
        val panel = Card(18, GridLayout(0, 1, 0, 6), { pal.surfaceContainer })
        panel.border = LeadingBorder(14, 18, 14, 18)
        panel.alignmentX = JComponent.LEFT_ALIGNMENT
        for (t in top) {
            val row = object : Widget() {
                init {
                    preferredSize = Dimension(400, 34)
                    toolTipText = t.title
                }

                override fun paintComponent(g0: Graphics) {
                    val g = g0.create().smooth()
                    val barW = (width * 0.45f)
                    g.font = AppTheme.ui(13f, Font.BOLD)
                    g.color = pal.onSurface
                    val fm = g.fontMetrics
                    val title = ellipsize(t.title, fm, width - barW - 24)
                    g.drawString(title, leadingX(0f, fm.stringWidth(title).toFloat()), 21f)
                    val x = width - barW
                    val track = barW - 70f
                    val fill = track * t.ms / max
                    g.color = pal.onSurface.alpha(22)
                    g.fill(RoundRectangle2D.Float(leadingX(x, track), 12f, track, 8f, 8f, 8f))
                    g.color = pal.accent
                    g.fill(RoundRectangle2D.Float(leadingX(x, fill), 12f, fill, 8f, 8f, 8f))
                    g.font = AppTheme.ui(12f)
                    g.color = pal.onSurfaceVariant
                    val d = BookDetailsDialog.formatDuration(t.ms)
                    val dw = g.fontMetrics.stringWidth(d).toFloat()
                    g.drawString(d, leadingX(width - dw, dw), 21f)
                    g.dispose()
                }
            }
            panel.add(row)
        }
        panel.maximumSize = Dimension(Int.MAX_VALUE, top.size * 40 + 30)
        return panel
    }

    private fun goalEditor(): JComponent {
        val goal = app.settings.app.value.dailyGoalMinutes
        val label = Ui.label(I18n.format("minutes_short", goal), 14f, Font.BOLD)
        val slider = JSlider(5, 180, goal.coerceIn(5, 180)).apply {
            isOpaque = false
            majorTickSpacing = 5
            snapToTicks = true
            addChangeListener {
                label.text = I18n.format("minutes_short", value)
                if (!valueIsAdjusting) app.settings.updateApp { s -> s.copy(dailyGoalMinutes = value) }
            }
        }
        return Card(18, BorderLayout(14, 0), { pal.surfaceContainer }).apply {
            border = LeadingBorder(12, 18, 12, 18)
            add(slider, BorderLayout.CENTER)
            add(label, BorderLayout.LINE_END)
            alignmentX = JComponent.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 64)
        }
    }

    /** Minutes read on each of the last seven days, with the daily goal as a dashed line. */
    private class WeekChart(private val today: Long, private val byDay: Map<Long, Long>, private val goal: Int) : Widget() {
        init {
            preferredSize = Dimension(500, 190)
        }

        override fun paintComponent(g0: Graphics) {
            val g = g0.create().smooth()
            val days = (6 downTo 0).map { today - it }
            val mins = days.map { (byDay[it] ?: 0L) / 60_000f }
            val max = maxOf(mins.maxOrNull() ?: 0f, goal.toFloat(), 1f) * 1.15f
            val chartH = height - 34f
            val slot = width / 7f
            val barW = minOf(slot * 0.46f, 46f)
            val goalY = chartH - chartH * goal / max
            g.color = pal.onSurfaceVariant.alpha(90)
            g.stroke = java.awt.BasicStroke(1f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_ROUND, 1f, floatArrayOf(4f, 4f), 0f)
            g.drawLine(0, goalY.toInt(), width, goalY.toInt())
            g.stroke = java.awt.BasicStroke(1f)
            days.forEachIndexed { i, d ->
                val h = chartH * mins[i] / max
                // Days run from the leading edge: right to left in right-to-left languages.
                val x = leadingX(slot * i + (slot - barW) / 2, barW)
                val slotX = leadingX(slot * i, slot)
                g.color = if (mins[i] >= goal) pal.accent else lerp(pal.accent, pal.surfaceContainer, 0.45f)
                if (h > 0.5f) g.fill(RoundRectangle2D.Float(x, chartH - h, barW, h, 10f, 10f))
                else {
                    g.color = pal.onSurface.alpha(24)
                    g.fill(RoundRectangle2D.Float(x, chartH - 3f, barW, 3f, 3f, 3f))
                }
                g.font = AppTheme.ui(11.5f, if (d == today) Font.BOLD else Font.PLAIN)
                g.color = if (d == today) pal.onSurface else pal.onSurfaceVariant
                val label = LocalDate.ofEpochDay(d).dayOfWeek.getDisplayName(TextStyle.SHORT, I18n.locale)
                val fm = g.fontMetrics
                g.drawString(label, slotX + (slot - fm.stringWidth(label)) / 2, height - 10f)
                if (mins[i] >= 1f) {
                    val v = I18n.format("minutes_short", mins[i].toInt())
                    g.font = AppTheme.ui(11f)
                    g.color = pal.onSurfaceVariant
                    g.drawString(v, slotX + (slot - g.fontMetrics.stringWidth(v)) / 2, chartH - h - 6f)
                }
            }
            g.dispose()
        }
    }

    /** Twenty weeks of reading as a grid of days, darker for more minutes. */
    private class Heatmap(private val today: Long, private val byDay: Map<Long, Long>, private val goal: Int) : Widget() {
        init {
            preferredSize = Dimension(560, 7 * 18 + 20)
        }

        override fun paintComponent(g0: Graphics) {
            val g = g0.create().smooth()
            val weeks = 20
            val todayDate = LocalDate.ofEpochDay(today)
            val dow = todayDate.dayOfWeek.value - 1
            val start = today - dow - (weeks - 1) * 7
            val cell = minOf((width - 40f) / weeks, 18f)
            val size = cell - 4f
            for (w in 0 until weeks) for (d in 0 until 7) {
                val day = start + w * 7 + d
                if (day > today) continue
                val m = (byDay[day] ?: 0L) / 60_000f
                val t = (m / goal.coerceAtLeast(1)).coerceIn(0f, 1f)
                g.color = if (m <= 0f) pal.onSurface.alpha(18) else lerp(lerp(pal.accent, pal.surfaceContainer, 0.75f), pal.accent, t)
                g.fill(RoundRectangle2D.Float(leadingX(32f + w * cell, size), d * cell, size, size, 5f, 5f))
            }
            g.font = AppTheme.ui(10.5f)
            g.color = pal.onSurfaceVariant
            for (d in listOf(0, 2, 4)) {
                val name = java.time.DayOfWeek.of(d + 1).getDisplayName(TextStyle.SHORT, I18n.locale)
                g.drawString(name, leadingX(0f, g.fontMetrics.stringWidth(name).toFloat()), d * cell + size - 2f)
            }
            g.dispose()
        }
    }
}
