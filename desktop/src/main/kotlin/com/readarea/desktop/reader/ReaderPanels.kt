package com.readarea.desktop.reader

import com.readarea.core.text.SearchHit
import com.readarea.core.theme.ReadingTheme
import com.readarea.core.theme.ReadingThemes
import com.readarea.desktop.App
import com.readarea.desktop.data.ReaderSettings
import com.readarea.desktop.i18n.I18n
import com.readarea.desktop.i18n.tr
import com.readarea.desktop.reader.engine.PageChrome
import com.readarea.desktop.reader.engine.ReaderFonts
import com.readarea.desktop.ui.components.ScrollingStack
import com.readarea.desktop.ui.components.FocusRing
import com.readarea.desktop.ui.components.onActivate
import com.readarea.desktop.ui.components.drawAt
import com.readarea.desktop.ui.components.leadingX
import com.readarea.desktop.ui.components.ButtonKind
import com.readarea.desktop.ui.components.Widget
import com.readarea.desktop.ui.components.Dialogs
import com.readarea.desktop.ui.components.IconButton
import com.readarea.desktop.ui.components.PillButton
import com.readarea.desktop.ui.components.ScrollableColumn
import com.readarea.desktop.ui.components.Segmented
import com.readarea.desktop.ui.components.Switch
import com.readarea.desktop.ui.components.Transparent
import com.readarea.desktop.ui.components.Ui
import com.readarea.desktop.ui.components.pal
import com.readarea.desktop.ui.components.searchField
import com.readarea.desktop.ui.components.smooth
import com.readarea.desktop.ui.theme.AppTheme
import com.readarea.desktop.ui.theme.alpha
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import com.readarea.desktop.ui.components.LeadingBorder

/** The frame the side panels share: a title, a close button and a scrolling body. */
abstract class SidePanel(title: String, onClose: () -> Unit) : JPanel(BorderLayout()) {
    init {
        border = LeadingBorder(16, 18, 12, 14)
        val header = Transparent(BorderLayout())
        header.add(Ui.label(title, 16f, Font.BOLD).apply { font = AppTheme.headline(18f) }, BorderLayout.LINE_START)
        header.add(IconButton("close", tr("close"), 18).apply { addActionListener { onClose() } }, BorderLayout.LINE_END)
        add(Ui.padded(header, 0, 0, 10, 0), BorderLayout.NORTH)
        preferredSize = Dimension(340, 400)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create().smooth()
        g2.color = pal.surface
        g2.fillRect(0, 0, width, height)
        g2.color = pal.outlineVariant
        g2.fillRect(0, 0, 1, height)
        g2.fillRect(width - 1, 0, 1, height)
        g2.dispose()
    }
}

/** A list row renderer painting a title with optional secondary text, indent and a leading color mark. */
private class RowRenderer<T>(private val content: (T) -> Row) : Widget(), ListCellRenderer<T> {
    data class Row(val title: String, val sub: String? = null, val depth: Int = 0, val mark: Color? = null, val current: Boolean = false)

    private var row = Row("")
    private var selected = false

    override fun getListCellRendererComponent(list: JList<out T>, value: T, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
        row = content(value)
        selected = isSelected
        componentOrientation = list.componentOrientation
        getAccessibleContext().accessibleName = row.title
        return this
    }

    override fun getPreferredSize() = Dimension(280, if (row.sub == null) 36 else 58)

    override fun paintComponent(g0: Graphics) {
        val g = g0.create().smooth()
        if (selected || row.current) {
            g.color = if (selected) pal.accentContainer else pal.accent.alpha(24)
            g.fill(RoundRectangle2D.Float(0f, 2f, width.toFloat(), height - 4f, 14f, 14f))
        }
        var x = 12f + row.depth * 14f
        row.mark?.let {
            g.color = it
            g.fill(RoundRectangle2D.Float(leadingX(x - 4f, 4f), 8f, 4f, height - 16f, 4f, 4f))
            x += 8f
        }
        g.font = AppTheme.ui(13f, if (row.current || row.depth == 0) Font.BOLD else Font.PLAIN)
        g.color = if (selected) pal.onAccentContainer else pal.onSurface
        val fm = g.fontMetrics
        var t = row.title
        while (t.length > 2 && fm.stringWidth(t) > width - x - 12) t = t.dropLast(2) + "…"
        g.drawString(t, leadingX(x, fm.stringWidth(t).toFloat()), if (row.sub == null) (height + fm.ascent - fm.descent) / 2f else 24f)
        row.sub?.let { s ->
            g.font = AppTheme.ui(11.5f)
            g.color = pal.onSurfaceVariant
            var st = s
            val f2 = g.fontMetrics
            while (st.length > 2 && f2.stringWidth(st) > width - x - 12) st = st.dropLast(2) + "…"
            g.drawString(st, leadingX(x, f2.stringWidth(st).toFloat()), 44f)
        }
        g.dispose()
    }
}

class ContentsPanel(private val c: ReaderController, onClose: () -> Unit) : SidePanel(tr("contents_notes"), onClose) {
    private val tabs = Segmented(listOf(tr("tool_contents"), "", ""), 0) { tab = it; render() }
    private var tab = 0
    private val tocModel = DefaultListModel<TocEntry>()
    private val bmModel = DefaultListModel<com.readarea.desktop.data.Bookmark>()
    private val hlModel = DefaultListModel<com.readarea.desktop.data.Highlight>()
    private val toc = JList(tocModel)
    private val bookmarks = JList(bmModel)
    private val highlights = JList(hlModel)
    private val body = Transparent(java.awt.CardLayout())
    private var currentToc: TocEntry? = null

    init {
        toc.cellRenderer = RowRenderer<TocEntry> { RowRenderer.Row(it.title, depth = it.depth.coerceAtMost(4), current = it == currentToc) }
        bookmarks.cellRenderer = RowRenderer { b -> RowRenderer.Row(b.snippet.ifBlank { b.chapterTitle }, listOf(b.chapterTitle, I18n.format("percent", (b.progress * 100).toInt())).filter { it.isNotBlank() }.joinToString(" · "), mark = pal.accent) }
        highlights.cellRenderer = RowRenderer { h -> RowRenderer.Row(h.text.replace('\n', ' '), listOfNotNull(h.note?.takeIf { it.isNotBlank() }, I18n.format("percent", (h.progress * 100).toInt())).joinToString(" · "), mark = Color(ReadingThemes.highlightColor(h.color))) }
        for (l in listOf(toc, bookmarks, highlights)) {
            l.selectionMode = ListSelectionModel.SINGLE_SELECTION
            l.isOpaque = false
            l.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val idx = l.locationToIndex(e.point)
                    if (idx < 0 || l.getCellBounds(idx, idx)?.contains(e.point) != true) return
                    if (e.button == MouseEvent.BUTTON3) return context(l, idx, e)
                    open(l, idx)
                }
            })
            l.addKeyListener(object : java.awt.event.KeyAdapter() {
                override fun keyPressed(e: java.awt.event.KeyEvent) {
                    if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER && l.selectedIndex >= 0) open(l, l.selectedIndex)
                }
            })
        }
        body.add(Ui.scroll(toc), "0")
        body.add(Ui.scroll(bookmarks), "1")
        body.add(Ui.scroll(highlights), "2")
        add(Transparent(BorderLayout()).apply {
            add(tabs, BorderLayout.NORTH)
            add(Ui.padded(body, 8, 0, 0, 0), BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        c.scope.launch { c.ui.collect { render() } }
    }

    private fun open(l: JList<*>, idx: Int) {
        when (l) {
            toc -> c.goToToc(tocModel[idx])
            bookmarks -> bmModel[idx].let { c.goTo(it.chapter, it.offset, rememberJump = true) }
            highlights -> hlModel[idx].let { c.goTo(it.chapter, it.start, rememberJump = true) }
        }
    }

    private fun context(l: JList<*>, idx: Int, e: MouseEvent) {
        val menu = javax.swing.JPopupMenu()
        when (l) {
            bookmarks -> menu.add(javax.swing.JMenuItem(tr("delete")).apply { addActionListener { c.deleteBookmark(bmModel[idx].id) } })
            highlights -> {
                val h = hlModel[idx]
                menu.add(javax.swing.JMenuItem(tr("add_note")).apply {
                    addActionListener { Dialogs.input(this@ContentsPanel, tr("note"), h.note.orEmpty(), tr("save"), multiline = true, placeholder = tr("your_thoughts"))?.let { c.updateHighlightNote(h, it) } }
                })
                menu.add(javax.swing.JMenuItem(tr("delete_highlight")).apply { addActionListener { c.deleteHighlight(h.id) } })
            }
            else -> return
        }
        menu.show(l, e.x, e.y)
    }

    private fun render() {
        val u = c.ui.value
        tabs.setOptions(listOf(tr("tool_contents"), I18n.format("tab_bookmarks", u.bookmarks.size), I18n.format("tab_highlights", u.highlights.size)))
        if (tocModel.size() != u.toc.size || (0 until tocModel.size()).any { tocModel[it] != u.toc[it] }) {
            tocModel.clear()
            u.toc.forEach { tocModel.addElement(it) }
        }
        val e = c.engine
        currentToc = u.toc.lastOrNull { t ->
            if (t.page >= 0) t.page <= c.pos.page
            else t.chapter < c.pos.chapter || (t.chapter == c.pos.chapter && (t.anchor == null || (c.text?.anchors(t.chapter)?.get(t.anchor) ?: 0) <= (e?.endOffsetOf(c.pos) ?: 0)))
        }
        toc.repaint()
        bmModel.clear()
        u.bookmarks.forEach { bmModel.addElement(it) }
        hlModel.clear()
        u.highlights.forEach { hlModel.addElement(it) }
        (body.layout as java.awt.CardLayout).show(body, tab.toString())
    }

    fun focusCurrent() {
        val i = (0 until tocModel.size()).firstOrNull { tocModel[it] == currentToc } ?: return
        toc.ensureIndexIsVisible(i)
    }
}

class SearchPanel(private val c: ReaderController, onClose: () -> Unit) : SidePanel(tr("search_in_book"), onClose) {
    private val model = DefaultListModel<SearchHit>()
    private val list = JList(model)
    private val status = Ui.secondary("", 12f)
    val field = searchField(tr("search_in_book")) { c.search(it) }

    init {
        list.cellRenderer = HitRenderer()
        list.isOpaque = false
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val i = list.locationToIndex(e.point)
                if (i >= 0) c.openSearchHit(model[i])
            }
        })
        field.addActionListener { if (model.size() > 0) c.openSearchHit(model[0]) }
        add(Transparent(BorderLayout()).apply {
            add(Ui.vbox(field, Ui.gap(8), status), BorderLayout.NORTH)
            add(Ui.padded(Ui.scroll(list), 8, 0, 0, 0), BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        c.scope.launch {
            c.ui.collect { u ->
                if (model.size() != u.searchResults.size || (model.size() > 0 && model[0] != u.searchResults.firstOrNull())) {
                    model.clear()
                    u.searchResults.forEach { model.addElement(it) }
                }
                status.text = when {
                    u.searching -> tr("searching")
                    u.searchQuery.trim().length in 1..1 -> tr("type_two_chars")
                    u.searchQuery.isBlank() -> ""
                    u.searchResults.size >= 500 -> I18n.format("results_many", 500)
                    else -> I18n.plural("results", u.searchResults.size, u.searchResults.size)
                }
            }
        }
    }

    private inner class HitRenderer : Widget(), ListCellRenderer<SearchHit> {
        private var hit: SearchHit? = null
        private var sel = false

        override fun getListCellRendererComponent(list: JList<out SearchHit>, value: SearchHit, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
            hit = value
            sel = isSelected
            componentOrientation = list.componentOrientation
            return this
        }

        override fun getPreferredSize() = Dimension(280, 74)

        override fun paintComponent(g0: Graphics) {
            val h = hit ?: return
            val g = g0.create().smooth()
            if (sel) {
                g.color = pal.accentContainer
                g.fill(RoundRectangle2D.Float(0f, 2f, width.toFloat(), height - 4f, 14f, 14f))
            }
            g.font = AppTheme.ui(11.5f, Font.BOLD)
            g.color = pal.accent
            val where = if (c.ui.value.fixed) I18n.format("page_number", h.start + 1) else c.text?.sectionTitleAt(h.chapter, h.start).orEmpty()
            val label = where.take(60)
            g.drawString(label, leadingX(12f, g.fontMetrics.stringWidth(label).toFloat()), 18f)
            val attr = java.text.AttributedString(h.snippet.ifEmpty { " " })
            attr.addAttribute(java.awt.font.TextAttribute.FONT, AppTheme.ui(12.5f))
            attr.addAttribute(java.awt.font.TextAttribute.FOREGROUND, pal.onSurface)
            val ms = h.matchStart.coerceIn(0, h.snippet.length)
            val me = h.matchEnd.coerceIn(ms, h.snippet.length)
            if (me > ms) {
                attr.addAttribute(java.awt.font.TextAttribute.BACKGROUND, pal.accent.alpha(70), ms, me)
                attr.addAttribute(java.awt.font.TextAttribute.WEIGHT, java.awt.font.TextAttribute.WEIGHT_BOLD, ms, me)
            }
            val lbm = java.awt.font.LineBreakMeasurer(attr.iterator, g.fontRenderContext)
            var y = 26f
            var lines = 0
            while (lbm.position < h.snippet.length && lines < 2) {
                val l = lbm.nextLayout(width - 24f)
                y += l.ascent
                l.drawAt(g, leadingX(12f, l.visibleAdvance), y)
                y += l.descent + l.leading
                lines++
            }
            g.dispose()
        }
    }
}

/** Typeface, size, spacing, margins and layout. */
class TextPanel(private val app: App, private val c: ReaderController, onClose: () -> Unit) : SidePanel(tr("tool_text"), onClose) {
    private val column = ScrollingStack()

    init {
        add(Ui.scroll(column), BorderLayout.CENTER)
        build()
    }

    private fun upd(block: (ReaderSettings) -> ReaderSettings) = c.updateSettings(block)

    private fun build() {
        column.removeAll()
        val s = c.settings.value
        val fonts = ReaderFonts.builtIn.map { it.key to it.label } +
            app.settings.app.value.customFonts.map { "file:$it" to it.substringBeforeLast('.') } +
            ReaderFonts.installedFamilies().map { "family:$it" to it }
        val fontBox = JComboBox(fonts.map { it.second }.toTypedArray()).apply {
            selectedIndex = fonts.indexOfFirst { it.first == s.fontFamily }.coerceAtLeast(0)
            maximumRowCount = 18
            renderer = object : javax.swing.DefaultListCellRenderer() {
                override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
                    val r = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    val key = fonts.getOrNull(if (index < 0) selectedIndex else index)?.first
                    if (key != null && index >= 0 && index < 200) font = ReaderFonts.base(key, app.dirs.fonts).deriveFont(14f)
                    return r
                }
            }
            addActionListener { fonts.getOrNull(selectedIndex)?.let { f -> upd { it.copy(fontFamily = f.first) } } }
        }
        section(tr("typeface"), fontBox)
        val sizeLabel = Ui.label(I18n.format("font_size_value", s.fontSize.toInt()), 13f, Font.BOLD)
        val size = Ui.hbox(
            IconButton("minus", tr("smaller"), 18).apply { addActionListener { upd { it.copy(fontSize = it.fontSize - 1) }; sizeLabel.text = I18n.format("font_size_value", c.settings.value.fontSize.toInt()) } },
            Ui.glue(), sizeLabel, Ui.glue(),
            IconButton("plus", tr("larger"), 18).apply { addActionListener { upd { it.copy(fontSize = it.fontSize + 1) }; sizeLabel.text = I18n.format("font_size_value", c.settings.value.fontSize.toInt()) } },
        )
        section(tr("font_size"), size)
        slider(tr("weight"), 300, 700, s.fontWeight, 100) { v -> upd { it.copy(fontWeight = v) } }
        sliderF(tr("line_spacing"), 1f, 2.4f, s.lineSpacing) { v -> upd { it.copy(lineSpacing = v) } }
        sliderF(tr("paragraph_spacing"), 0f, 1.5f, s.paragraphSpacing) { v -> upd { it.copy(paragraphSpacing = v) } }
        sliderF(tr("first_line_indent"), 0f, 3f, s.indent) { v -> upd { it.copy(indent = v) } }
        sliderF(tr("letter_spacing"), -0.03f, 0.15f, s.letterSpacing) { v -> upd { it.copy(letterSpacing = v) } }
        slider(tr("side_margins"), 0, 200, s.marginH, 4) { v -> upd { it.copy(marginH = v) } }
        slider(tr("vertical_margins"), 0, 160, s.marginV, 4) { v -> upd { it.copy(marginV = v) } }
        slider(tr("line_width"), 420, 1400, s.columnWidth, 20) { v -> upd { it.copy(columnWidth = v) } }
        toggle(tr("justified"), s.justify) { v -> upd { it.copy(justify = v) } }
        toggle(tr("hyphenation"), s.hyphenation, tr("hyphenation_hint_desktop")) { v -> upd { it.copy(hyphenation = v) } }
        toggle(tr("publisher_styles"), s.publisherStyles, tr("publisher_styles_hint")) { v -> upd { it.copy(publisherStyles = v) } }
        column.add(Ui.gap(8))
        column.add(Ui.label(tr("layout"), 13f, Font.BOLD).apply { alignmentX = LEFT_ALIGNMENT })
        val anims = listOf("curl", "slide", "fade", "instant", "scroll")
        segmented(tr("page_turn"), listOf(tr("anim_curl"), tr("anim_slide"), tr("anim_fade"), tr("anim_instant"), tr("anim_scroll")), anims.indexOf(s.pageAnim).coerceAtLeast(0)) { i -> upd { it.copy(pageAnim = anims[i]) } }
        sliderF(tr("animation_speed"), 0.4f, 2.5f, s.animSpeed) { v -> upd { it.copy(animSpeed = v) } }
        val spreads = listOf("auto", "single", "double")
        segmented(tr("two_page_spread"), listOf(tr("auto"), "1", "2"), spreads.indexOf(s.spread).coerceAtLeast(0)) { i -> upd { it.copy(spread = spreads[i]) } }
        val dirs = listOf("auto", "ltr", "rtl")
        segmented(tr("page_direction"), listOf(tr("auto"), tr("left_to_right"), tr("right_to_left")), dirs.indexOf(s.pageDirection).coerceAtLeast(0)) { i -> upd { it.copy(pageDirection = dirs[i]) } }
        if (c.ui.value.bookCjk) {
            val modes = listOf("auto", "horizontal", "vertical")
            segmented(tr("writing_mode"), listOf(tr("auto"), tr("writing_horizontal"), tr("writing_vertical")), modes.indexOf(s.writingMode).coerceAtLeast(0)) { i -> upd { it.copy(writingMode = modes[i]) } }
        }
        toggle(tr("click_to_turn"), s.clickToTurn, tr("click_to_turn_hint")) { v -> upd { it.copy(clickToTurn = v) } }
        toggle(tr("wheel_turns_pages"), s.wheelTurnsPages) { v -> upd { it.copy(wheelTurnsPages = v) } }
        toggle(tr("chapter_title_top"), s.showHeader) { v -> upd { it.copy(showHeader = v) } }
        toggle(tr("page_info_bottom"), s.showFooter) { v -> upd { it.copy(showFooter = v) } }
        toggle(tr("progress_line"), s.showProgressLine) { v -> upd { it.copy(showProgressLine = v) } }
        column.add(Ui.gap(10))
        column.add(PillButton(tr("reset_text_settings"), "undo", ButtonKind.TEXT, compact = true).apply {
            alignmentX = LEFT_ALIGNMENT
            addActionListener {
                val d = ReaderSettings()
                upd { it.copy(fontFamily = d.fontFamily, fontSize = d.fontSize, fontWeight = d.fontWeight, lineSpacing = d.lineSpacing, paragraphSpacing = d.paragraphSpacing, indent = d.indent, marginH = d.marginH, marginV = d.marginV, justify = d.justify, hyphenation = d.hyphenation, letterSpacing = d.letterSpacing, publisherStyles = d.publisherStyles, columnWidth = d.columnWidth) }
                build()
            }
        })
        column.revalidate()
        column.repaint()
    }

    private fun section(title: String, control: JComponent) {
        column.add(Ui.label(title, 12.5f, Font.BOLD) { pal.onSurfaceVariant }.apply { alignmentX = LEFT_ALIGNMENT })
        column.add(Ui.gap(4))
        control.alignmentX = LEFT_ALIGNMENT
        control.maximumSize = Dimension(Int.MAX_VALUE, control.preferredSize.height)
        column.add(control)
        column.add(Ui.gap(12))
    }

    private fun slider(title: String, min: Int, max: Int, value: Int, step: Int, onChange: (Int) -> Unit) {
        val s = JSlider(min, max, value.coerceIn(min, max)).apply {
            isOpaque = false
            addChangeListener { if (!valueIsAdjusting) onChange((this.value / step) * step) }
        }
        section(title, s)
    }

    private fun sliderF(title: String, min: Float, max: Float, value: Float, onChange: (Float) -> Unit) {
        val steps = 100
        val s = JSlider(0, steps, (((value - min) / (max - min)) * steps).toInt().coerceIn(0, steps)).apply {
            isOpaque = false
            addChangeListener { if (!valueIsAdjusting) onChange(((min + (max - min) * this.value / steps) * 100).toInt() / 100f) }
        }
        section(title, s)
    }

    private fun toggle(title: String, on: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
        column.add(Ui.settingRow(title, hint, Switch(on).apply { addActionListener { onChange(isSelected) } }, 13f, 11.5f))
    }

    private fun segmented(title: String, options: List<String>, selected: Int, onChange: (Int) -> Unit) {
        section(title, Segmented(options, selected, onChange))
    }
}

/** Page color, paper texture, night mode, dimming and warm light, PDF colors. */
class ThemePanel(private val c: ReaderController, onClose: () -> Unit) : SidePanel(tr("reading_theme"), onClose) {
    private val column = ScrollingStack()

    init {
        add(Ui.scroll(column), BorderLayout.CENTER)
        build()
    }

    private fun upd(block: (ReaderSettings) -> ReaderSettings) = c.updateSettings(block)

    private fun build() {
        column.removeAll()
        val s = c.settings.value
        val grid = Transparent(java.awt.GridLayout(0, 3, 10, 10))
        grid.alignmentX = LEFT_ALIGNMENT
        val all = ReadingThemes.all + ReadingThemes.custom(s.customBg, s.customFg, s.texture)
        for (t in all) grid.add(Swatch(t, s.theme == t.id) {
            if (t.id == "custom") customColors()
            upd { it.copy(theme = t.id) }
            build()
        })
        grid.maximumSize = Dimension(Int.MAX_VALUE, ((all.size + 2) / 3) * 78)
        column.add(grid)
        column.add(Ui.gap(14))
        toggle(tr("paper_texture"), s.texture, tr("paper_texture_hint")) { v -> upd { it.copy(texture = v) } }
        toggle(tr("follow_system_dark"), s.autoNight, tr("follow_system_dark_hint_desktop")) { v -> upd { it.copy(autoNight = v) } }
        if (s.autoNight) {
            val dark = ReadingThemes.all.filter { it.dark }
            val box = JComboBox(dark.map { tr("theme_" + it.id) }.toTypedArray()).apply {
                selectedIndex = dark.indexOfFirst { it.id == s.nightTheme }.coerceAtLeast(0)
                addActionListener { upd { it.copy(nightTheme = dark[selectedIndex].id) } }
            }
            box.alignmentX = LEFT_ALIGNMENT
            box.maximumSize = Dimension(Int.MAX_VALUE, box.preferredSize.height)
            column.add(box)
            column.add(Ui.gap(8))
        }
        column.add(Ui.gap(8))
        sliderRow(tr("dim"), s.dim, 0.8f, tr("dim_hint_desktop")) { v -> upd { it.copy(dim = v) } }
        sliderRow(tr("warm_light"), s.warmth, 1f, null) { v -> upd { it.copy(warmth = v) } }
        if (c.ui.value.fixed) {
            column.add(Ui.gap(8))
            column.add(Ui.label(tr("pdf_comics"), 13f, Font.BOLD).apply { alignmentX = LEFT_ALIGNMENT })
            toggle(tr("match_theme_colors"), s.pdfInvert, tr("match_theme_colors_hint")) { v -> upd { it.copy(pdfInvert = v) } }
            toggle(tr("crop_margins"), s.pdfCrop, tr("crop_margins_hint")) { v -> upd { it.copy(pdfCrop = v) } }
        }
        column.revalidate()
        column.repaint()
    }

    private fun customColors() {
        val s = c.settings.value
        val bg = javax.swing.JColorChooser.showDialog(this, tr("page_color"), Color(s.customBg)) ?: return
        val fg = javax.swing.JColorChooser.showDialog(this, tr("text_color"), Color(s.customFg)) ?: return
        upd { it.copy(customBg = bg.rgb or (0xFF shl 24), customFg = fg.rgb or (0xFF shl 24)) }
    }

    private fun toggle(title: String, on: Boolean, hint: String?, onChange: (Boolean) -> Unit) {
        column.add(Ui.settingRow(title, hint, Switch(on).apply { addActionListener { onChange(isSelected); build() } }, 13f, 11.5f))
    }

    private fun sliderRow(title: String, value: Float, max: Float, hint: String?, onChange: (Float) -> Unit) {
        column.add(Ui.label(title, 12.5f, Font.BOLD) { pal.onSurfaceVariant }.apply { alignmentX = LEFT_ALIGNMENT })
        hint?.let { column.add(Ui.wrapLabel(it, size = 11.5f) { pal.onSurfaceVariant }.apply { alignmentX = LEFT_ALIGNMENT }) }
        val s = JSlider(0, 100, ((value / max) * 100).toInt().coerceIn(0, 100)).apply {
            isOpaque = false
            addChangeListener { onChange(this.value / 100f * max) }
            alignmentX = LEFT_ALIGNMENT
        }
        column.add(s)
        column.add(Ui.gap(10))
    }

    private class Swatch(private val t: ReadingTheme, private val selected: Boolean, private val onPick: () -> Unit) : Widget(javax.accessibility.AccessibleRole.PUSH_BUTTON) {
        init {
            preferredSize = Dimension(90, 68)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = if (t.id == "custom") tr("theme_custom") else tr("theme_" + t.id)
            getAccessibleContext().accessibleName = toolTipText
            addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) = onPick() })
            onActivate(onPick)
        }

        override fun paintComponent(g0: Graphics) {
            val g = g0.create().smooth()
            val shape = RoundRectangle2D.Float(1f, 1f, width - 2f, height - 2f, 18f, 18f)
            g.color = PageChrome.color(t.background, 255)
            g.fill(shape)
            g.color = if (selected) pal.accent else pal.outlineVariant
            g.stroke = java.awt.BasicStroke(if (selected) 2.5f else 1f)
            g.draw(shape)
            g.font = AppTheme.headline(20f, bold = false)
            g.color = PageChrome.color(t.text, 255)
            val fm = g.fontMetrics
            g.drawString("Aa", (width - fm.stringWidth("Aa")) / 2f, height / 2f + 4f)
            g.font = AppTheme.ui(10.5f)
            g.color = PageChrome.color(t.secondary, 255)
            val name = toolTipText
            g.drawString(name, (width - g.fontMetrics.stringWidth(name)) / 2f, height - 9f)
            if (t.id == "custom") {
                g.color = PageChrome.color(t.accent, 255)
                g.fill(Ellipse2D.Float(width - 18f, 8f, 9f, 9f))
            }
            FocusRing.paint(g, this, RoundRectangle2D.Float(3f, 3f, width - 6f, height - 6f, 14f, 14f))
            g.dispose()
        }
    }
}
