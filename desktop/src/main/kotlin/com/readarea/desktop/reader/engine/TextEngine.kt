package com.readarea.desktop.reader.engine

import com.readarea.core.format.ParsedBook
import com.readarea.core.text.BookText
import com.readarea.core.theme.ReadingTheme
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Shape
import java.awt.font.TextHitInfo
import java.awt.font.TextLayout
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import java.io.File
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * A chapter ready to draw and hit-test. Coordinates are relative to the top-left of the column the page
 * sits in.
 */
interface ChapterView : PagedText {
    fun draw(g: Graphics2D, page: Int, left: Float, top: Float, ctx: DrawContext)
    fun drawScroll(g: Graphics2D, page: Int, left: Float, ctx: DrawContext)
    fun scrollHeight(page: Int): Float
    fun offsetAt(page: Int, x: Float, y: Float): Int?
    fun rangeShape(page: Int, start: Int, end: Int): Shape?
    fun linkAt(page: Int, x: Float, y: Float, slop: Float): String?
    fun caretPoint(page: Int, offset: Int, end: Boolean): java.awt.geom.Point2D.Float?
}

class DrawContext(val theme: ReadingTheme, val colors: SpanColors, val images: ImageCache, val deco: Decorations, val chapter: Int, val setup: PageSetup)

private class HorizontalChapter(val layout: ChapterLayout) : ChapterView, PagedText by layout.metrics {
    private val m = layout.metrics

    override fun scrollHeight(page: Int): Float = m.bottom(page) - m.top(page)

    override fun draw(g: Graphics2D, page: Int, left: Float, top: Float, ctx: DrawContext) {
        val pageTop = m.top(page)
        val saved = g.transform
        val clip = g.clip
        g.translate(left.toDouble(), (top - pageTop).toDouble())
        val margin = ctx.setup.marginH
        g.clip(Rectangle2D.Float(-margin, pageTop, ctx.setup.contentWidth + margin * 2, m.bottom(page) - pageTop + 1f))
        paint(g, page, ctx)
        g.transform = saved
        g.clip = clip
    }

    override fun drawScroll(g: Graphics2D, page: Int, left: Float, ctx: DrawContext) = draw(g, page, left, 0f, ctx)

    private fun paint(g: Graphics2D, page: Int, ctx: DrawContext) {
        val range = layout.lineRange(page)
        if (range.isEmpty()) return
        val theme = ctx.theme
        val start = m.startOffset(page)
        val end = m.endOffset(page)
        val deco = ctx.deco
        val alpha = if (theme.dark) 0x55 else 0x70
        for (h in deco.highlights) {
            if (h.chapter != ctx.chapter || h.end <= start || h.start >= end) continue
            fill(g, page, maxOf(h.start, start), minOf(h.end, end), PageChrome.color(h.color, alpha))
        }
        deco.search?.let { h -> if (h.chapter == ctx.chapter && h.end > start && h.start < end) fill(g, page, maxOf(h.start, start), minOf(h.end, end), PageChrome.color(theme.accent, 0x66)) }
        deco.speaking?.let { h -> if (h.chapter == ctx.chapter && h.end > start && h.start < end) fill(g, page, maxOf(h.start, start), minOf(h.end, end), PageChrome.color(theme.accent, 0x33)) }
        deco.selection?.let { h -> if (h.chapter == ctx.chapter && h.end > start && h.start < end) fill(g, page, maxOf(h.start, start), minOf(h.end, end), PageChrome.color(theme.accent, 0x4C)) }
        val text = PageChrome.color(theme.text, 255)
        val secondary = PageChrome.color(theme.secondary, 110)
        val width = ctx.setup.contentWidth.toFloat()
        for (i in range) {
            val line = layout.lines[i]
            if (!line.quoteX.isNaN()) {
                g.color = secondary
                g.fill(Rectangle2D.Float(line.quoteX, line.top, 2f, line.bottom - line.top))
            }
            when (line.kind) {
                Line.TEXT -> {
                    val tl = line.layout ?: continue
                    g.color = text
                    tl.draw(g, line.x, line.baseline)
                    if (line.hyphen && line.hyphenFont != null) {
                        g.font = line.hyphenFont
                        g.drawString("-", line.x + tl.visibleAdvance, line.baseline)
                    }
                }
                Line.IMAGE -> {
                    val path = line.image ?: continue
                    val scale = ctx.setup.scale
                    val img = ctx.images.get(path, (line.imageW * scale).toInt().coerceAtLeast(1), (line.imageH * scale).toInt().coerceAtLeast(1))
                    if (img != null) g.drawImage(img, line.x.toInt(), line.top.toInt(), line.imageW.toInt(), line.imageH.toInt(), null)
                }
                Line.RULE -> PageChrome.drawRule(g, width / 2f, (line.top + line.bottom) / 2f, width * 0.36f, line.bottom - line.top, theme)
                Line.ORNAMENT -> PageChrome.drawRule(g, width / 2f, (line.top + line.bottom) / 2f, width * 0.1f, (line.bottom - line.top) * 0.6f, theme)
            }
        }
    }

    private fun fill(g: Graphics2D, page: Int, a: Int, b: Int, color: java.awt.Color) {
        val shape = chapterShape(page, a, b) ?: return
        g.color = color
        g.fill(shape)
    }

    /** The outline of a range on [page], in chapter coordinates (y from the chapter's top). */
    private fun chapterShape(page: Int, start: Int, end: Int): Area? {
        if (end <= start) return null
        val area = Area()
        for (i in layout.lineRange(page)) {
            val line = layout.lines[i]
            if (line.end <= start || line.start >= end) continue
            val tl = line.layout
            if (line.kind != Line.TEXT || tl == null) {
                val w = if (line.kind == Line.IMAGE) line.imageW else layout.width
                area.add(Area(Rectangle2D.Float(line.x, line.top, w.coerceAtLeast(4f), line.bottom - line.top)))
                continue
            }
            val n = tl.characterCount
            val la = (maxOf(start, line.start) - line.start).coerceIn(0, n)
            val lb = (minOf(end, line.end) - line.start).coerceIn(0, n)
            if (lb <= la) continue
            val bounds = Rectangle2D.Float(0f, line.top - line.baseline, tl.advance, line.bottom - line.top)
            val shape = tl.getLogicalHighlightShape(la, lb, bounds)
            area.add(Area(java.awt.geom.AffineTransform.getTranslateInstance(line.x.toDouble(), line.baseline.toDouble()).createTransformedShape(shape)))
        }
        return if (area.isEmpty) null else area
    }

    override fun rangeShape(page: Int, start: Int, end: Int): Shape? {
        val area = chapterShape(page, start, end) ?: return null
        return java.awt.geom.AffineTransform.getTranslateInstance(0.0, -m.top(page).toDouble()).createTransformedShape(area)
    }

    override fun offsetAt(page: Int, x: Float, y: Float): Int? {
        val idx = layout.lineAtY(page, y + m.top(page)) ?: return null
        val line = layout.lines[idx]
        val tl = line.layout ?: return line.start
        val hit = tl.hitTestChar(x - line.x, 0f)
        val max = (line.end - line.start).coerceAtLeast(1)
        return line.start + hit.insertionIndex.coerceIn(0, max - (if (line.end - line.start > tl.characterCount) 1 else 0))
    }

    override fun linkAt(page: Int, x: Float, y: Float, slop: Float): String? {
        val py = y + m.top(page)
        val idx = layout.lineAtY(page, py) ?: return null
        val line = layout.lines[idx]
        val tl = line.layout ?: return null
        if (py < line.top || py > line.bottom) return null
        val lx = x - line.x
        if (lx < -slop || lx > tl.advance + slop) return null
        val off = line.start + tl.hitTestChar(lx, 0f).charIndex
        return layout.links.filter { off >= it.start - 1 && off < it.end + 1 }.minByOrNull { if (off in it.start until it.end) 0 else 1 }?.href
    }

    override fun caretPoint(page: Int, offset: Int, end: Boolean): java.awt.geom.Point2D.Float? {
        val range = layout.lineRange(page)
        if (range.isEmpty()) return null
        var idx = range.firstOrNull { offset >= layout.lines[it].start && offset < layout.lines[it].end } ?: return null
        if (end && idx > range.first && layout.lines[idx].start == offset) idx--
        val line = layout.lines[idx]
        val tl = line.layout ?: return java.awt.geom.Point2D.Float(line.x, line.bottom - m.top(page))
        val local = (offset - line.start).coerceIn(0, tl.characterCount)
        val caret = tl.getCaretShapes(local)[0].bounds2D
        return java.awt.geom.Point2D.Float((line.x + caret.x).toFloat(), line.bottom - m.top(page))
    }
}

class TextEngine(val book: ParsedBook, val text: BookText, private val fontsDir: File?, maxImageBytes: Long) : PageEngine() {
    override val fixed = false
    override val chapterCount: Int = book.chapters.size
    val colors = SpanColors()
    val images = ImageCache(book.resources, maxImageBytes)

    private class Gen(val setup: PageSetup, val paged: AtomicReferenceArray<PagedText?>, val views: LinkedHashMap<Int, ChapterView>, val baseFont: Font, val monoFont: Font)

    @Volatile private var gen: Gen? = null

    /** Chapters whose full layout stays in memory: the one being read and its neighbours. */
    @Volatile var pinned: IntRange = 0..0

    override fun chapterTitle(chapter: Int): String = text.chapterTitle(chapter)

    override fun configure(setup: PageSetup, theme: ReadingTheme) {
        applyTheme(theme)
        val old = gen
        if (old != null && setup.sameLayout(old.setup)) {
            currentSetup = setup
            return
        }
        val base = ReaderFonts.base(setup.settings.fontFamily, fontsDir)
        gen = Gen(setup, AtomicReferenceArray(chapterCount), LinkedHashMap(8, 0.75f, true), base, ReaderFonts.monoBase())
        currentSetup = setup
    }

    override fun applyTheme(theme: ReadingTheme) {
        this.theme = theme
        colors.text = PageChrome.color(theme.text, 255)
        colors.secondary = PageChrome.color(theme.secondary, 255)
        colors.accent = PageChrome.color(theme.accent, 255)
        colors.link.color = PageChrome.color(theme.link, 255)
    }

    override fun isReady(chapter: Int): Boolean = gen?.paged?.takeIf { chapter in 0 until chapterCount }?.get(chapter) != null

    private fun paged(chapter: Int): PagedText? = gen?.paged?.takeIf { chapter in 0 until chapterCount }?.get(chapter)

    override fun ensure(chapter: Int) {
        val g = gen ?: return
        if (chapter !in 0 until chapterCount || g.paged.get(chapter) != null) return
        val view = build(g, chapter)
        if (gen === g) {
            val metrics: PagedText = (view as? HorizontalChapter)?.layout?.metrics ?: view
            g.paged.compareAndSet(chapter, null, metrics)
            keep(g, chapter, view)
        }
    }

    private fun keep(g: Gen, chapter: Int, view: ChapterView) {
        synchronized(g.views) {
            g.views[chapter] = view
            val it = g.views.entries.iterator()
            while (g.views.size > 4 && it.hasNext()) {
                val e = it.next()
                if (e.key in pinned || e.key == chapter) continue
                it.remove()
            }
        }
    }

    private fun build(g: Gen, chapter: Int): ChapterView {
        val setup = g.setup
        val plain = text.plainText(chapter)
        if (setup.vertical) return VerticalBuilder(setup, g.baseFont, images).build(book.chapters[chapter])
        val layout = TextLayoutBuilder(setup, g.baseFont, g.monoFont, colors, images, text.locale).build(book.chapters[chapter], plain)
        return HorizontalChapter(layout)
    }

    /** The drawable chapter, laid out again if it was dropped from memory. */
    fun view(chapter: Int): ChapterView? {
        val g = gen ?: return null
        if (chapter !in 0 until chapterCount) return null
        synchronized(g.views) { g.views[chapter] }?.let { return it }
        if (g.paged.get(chapter) == null) return null
        val v = build(g, chapter)
        if (gen === g) keep(g, chapter, v)
        return v
    }

    override fun pageCount(chapter: Int): Int = paged(chapter)?.pageCount ?: 0
    override fun pageOf(chapter: Int, offset: Int): Int = paged(chapter)?.pageOf(offset) ?: 0
    override fun offsetOf(pos: PagePos): Int = paged(pos.chapter)?.startOffset(pos.page) ?: 0
    override fun endOffsetOf(pos: PagePos): Int = paged(pos.chapter)?.endOffset(pos.page) ?: 0
    override fun progress(chapter: Int, offset: Int): Float = text.progress(chapter, offset)
    override fun locate(progress: Float): Pair<Int, Int> = text.locate(progress)

    override fun drawPage(g: Graphics2D, pos: PagePos, deco: Decorations) {
        val s = currentSetup ?: return
        val th = theme ?: return
        PageChrome.drawBackground(g, th, s.width, s.height)
        PageChrome.drawSpine(g, s)
        val view = view(pos.chapter) ?: return
        val ctx = DrawContext(th, colors, images, deco, pos.chapter, s)
        for (col in 0 until s.columns) {
            val page = pos.page + col
            if (page >= view.pageCount) break
            val left = s.columnLeft(col)
            view.draw(g, page, left, s.contentTop, ctx)
            val gp = globalPage(PagePos(pos.chapter, page))
            val total = totalPages()
            val label = if (gp != null && total != null) "$gp / $total" else "${page + 1} / ${view.pageCount}"
            val header = if (s.columns > 1 && col == 0) deco.bookTitle else text.sectionTitleAt(pos.chapter, view.startOffset(page)).ifBlank { deco.bookTitle }
            PageChrome.drawChrome(g, s, th, header, label, progress(pos.chapter, view.startOffset(page)), PagePos(pos.chapter, page) in deco.bookmarkedPages, col)
        }
    }

    override fun scrollHeight(pos: PagePos): Float = view(pos.chapter)?.scrollHeight(pos.page) ?: 0f

    override fun drawScrollSlice(g: Graphics2D, pos: PagePos, deco: Decorations) {
        val s = currentSetup ?: return
        val th = theme ?: return
        val view = view(pos.chapter) ?: return
        view.drawScroll(g, pos.page, s.contentLeft, DrawContext(th, colors, images, deco, pos.chapter, s))
    }

    private fun column(pos: PagePos, x: Float): Pair<Int, Float>? {
        val s = currentSetup ?: return null
        val view = view(pos.chapter) ?: return null
        val col = s.columnAt(x)
        val page = pos.page + col
        if (page >= view.pageCount) return null
        return page to s.columnLeft(col)
    }

    /** The chapter offset under a point of the page (window coordinates of the page area). */
    fun offsetAt(pos: PagePos, x: Float, y: Float): Int? {
        val s = currentSetup ?: return null
        val view = view(pos.chapter) ?: return null
        val (page, left) = column(pos, x) ?: return null
        return view.offsetAt(page, x - left, y - s.contentTop)
    }

    fun wordAt(pos: PagePos, x: Float, y: Float): IntRange? {
        val off = offsetAt(pos, x, y) ?: return null
        return text.wordAt(pos.chapter, off)
    }

    fun linkAt(pos: PagePos, x: Float, y: Float): String? {
        val s = currentSetup ?: return null
        val view = view(pos.chapter) ?: return null
        val (page, left) = column(pos, x) ?: return null
        return view.linkAt(page, x - left, y - s.contentTop, 6f)
    }

    /** The outline of [start]..[end] on the visible page(s), in page coordinates. */
    fun rangeShape(pos: PagePos, start: Int, end: Int): Shape? {
        val s = currentSetup ?: return null
        val view = view(pos.chapter) ?: return null
        val area = Area()
        for (col in 0 until s.columns) {
            val page = pos.page + col
            if (page >= view.pageCount) break
            val a = maxOf(start, view.startOffset(page))
            val b = minOf(end, view.endOffset(page))
            if (b <= a) continue
            val shape = view.rangeShape(page, a, b) ?: continue
            val t = java.awt.geom.AffineTransform.getTranslateInstance(s.columnLeft(col).toDouble(), s.contentTop.toDouble())
            area.add(Area(t.createTransformedShape(shape)))
        }
        return if (area.isEmpty) null else area
    }

    fun caretPoint(pos: PagePos, offset: Int, end: Boolean): java.awt.geom.Point2D.Float? {
        val s = currentSetup ?: return null
        val view = view(pos.chapter) ?: return null
        for (col in 0 until s.columns) {
            val page = pos.page + col
            if (page >= view.pageCount) break
            if (offset >= view.startOffset(page) && offset <= view.endOffset(page)) {
                val p = view.caretPoint(page, offset, end) ?: return null
                return java.awt.geom.Point2D.Float(p.x + s.columnLeft(col), p.y + s.contentTop)
            }
        }
        return null
    }

    override fun close() {
        images.clear()
    }
}
