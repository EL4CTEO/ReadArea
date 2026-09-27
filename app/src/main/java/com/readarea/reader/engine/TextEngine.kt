package com.readarea.reader.engine

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.text.LineBreaker
import android.annotation.SuppressLint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import com.readarea.core.format.Align
import com.readarea.core.format.BlockKind
import com.readarea.core.format.ParsedBook
import com.readarea.core.format.RunStyle
import com.readarea.reader.ReaderFonts
import com.readarea.reader.ReadingTheme
import java.text.BreakIterator
import java.util.Locale
import java.util.concurrent.atomic.AtomicReferenceArray

class ChapterPages(val text: Spanned, val layout: StaticLayout, val starts: IntArray) {
    val pageCount: Int get() = starts.size
    fun startOffset(page: Int): Int = layout.getLineStart(starts[page.coerceIn(0, starts.size - 1)])
    fun endOffset(page: Int): Int = if (page + 1 < starts.size) layout.getLineStart(starts[page + 1]) else text.length
    fun top(page: Int): Int = layout.getLineTop(starts[page.coerceIn(0, starts.size - 1)])
    fun bottom(page: Int): Int = if (page + 1 < starts.size) layout.getLineTop(starts[page + 1]) else layout.height
    fun pageOf(offset: Int): Int {
        val line = layout.getLineForOffset(offset.coerceIn(0, text.length))
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= line) lo = mid else hi = mid - 1
        }
        return lo
    }
}

data class SearchHit(val chapter: Int, val start: Int, val end: Int, val snippet: String, val matchStart: Int, val matchEnd: Int)

class TextEngine(val book: ParsedBook, maxImageBytes: Int) : PageEngine() {
    override val fixed = false
    override val chapterCount: Int = book.chapters.size
    val colors = SpanColors()
    val images = ImageCache(book.resources, maxImageBytes)
    private val plain = arrayOfNulls<String>(chapterCount)
    private val anchorMaps = arrayOfNulls<Map<String, Int>>(chapterCount)
    private val lengths = IntArray(chapterCount) { book.chapters[it].textLength }
    private val prefix = LongArray(chapterCount + 1).also { p -> for (i in 0 until chapterCount) p[i + 1] = p[i] + lengths[i] }
    private val total = prefix[chapterCount].coerceAtLeast(1)
    private val locale: Locale = book.meta.language?.let { runCatching { Locale.forLanguageTag(it) }.getOrNull() }?.takeIf { it.language.isNotEmpty() } ?: Locale.getDefault()

    private class Gen(val setup: PageSetup, val paint: TextPaint, val arr: AtomicReferenceArray<ChapterPages?>)

    @Volatile private var gen: Gen? = null
    private val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun chapterTitle(chapter: Int): String {
        val t = book.chapters.getOrNull(chapter)?.title.orEmpty()
        if (t.isNotBlank()) return t
        return book.toc.lastOrNull { it.chapter <= chapter }?.title ?: book.meta.title
    }

    override fun configure(setup: PageSetup, theme: ReadingTheme) {
        applyTheme(theme)
        val old = gen
        if (old != null && setup.sameLayout(old.setup)) {
            currentSetup = setup
            return
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)
        paint.textSize = setup.fontPx
        paint.typeface = ReaderFonts.resolve(setup.settings.fontFamily, setup.settings.fontWeight)
        paint.letterSpacing = setup.settings.letterSpacing
        paint.textLocale = locale
        paint.color = theme.text
        gen = Gen(setup, paint, AtomicReferenceArray(chapterCount))
        currentSetup = setup
    }

    override fun applyTheme(theme: ReadingTheme) {
        this.theme = theme
        colors.text = theme.text
        colors.secondary = theme.secondary
        colors.link = theme.link
        colors.accent = theme.accent
        gen?.paint?.color = theme.text
    }

    override fun isReady(chapter: Int): Boolean = gen?.arr?.get(chapter) != null

    fun pages(chapter: Int): ChapterPages? = gen?.arr?.takeIf { chapter in 0 until chapterCount }?.get(chapter)

    override fun ensure(chapter: Int) {
        val g = gen ?: return
        if (chapter !in 0 until chapterCount || g.arr.get(chapter) != null) return
        val cp = build(g, chapter)
        if (gen === g) g.arr.compareAndSet(chapter, null, cp)
    }

    override fun pageCount(chapter: Int): Int = pages(chapter)?.pageCount ?: 0

    override fun pageOf(chapter: Int, offset: Int): Int = pages(chapter)?.pageOf(offset) ?: 0

    override fun offsetOf(pos: PagePos): Int = pages(pos.chapter)?.startOffset(pos.page) ?: 0

    override fun endOffsetOf(pos: PagePos): Int = pages(pos.chapter)?.endOffset(pos.page) ?: 0

    override fun progress(chapter: Int, offset: Int): Float {
        if (chapter !in 0 until chapterCount) return 0f
        return ((prefix[chapter] + offset.coerceIn(0, lengths[chapter])).toDouble() / total).toFloat().coerceIn(0f, 1f)
    }

    override fun locate(progress: Float): Pair<Int, Int> {
        val target = (progress.coerceIn(0f, 1f) * total).toLong()
        for (c in 0 until chapterCount) {
            if (target < prefix[c + 1] || c == chapterCount - 1) return c to (target - prefix[c]).toInt().coerceIn(0, lengths[c])
        }
        return 0 to 0
    }

    fun plainText(chapter: Int): String {
        plain[chapter]?.let { return it }
        val sb = StringBuilder()
        val anchors = HashMap<String, Int>()
        for (b in book.chapters[chapter].blocks) {
            val start = sb.length
            for (a in b.anchors) anchors.putIfAbsent(a.id, start + a.offset)
            if (b.kind == BlockKind.IMAGE || b.kind == BlockKind.RULE) sb.append(OBJ) else b.runs.forEach { sb.append(it.text) }
            sb.append('\n')
        }
        val s = sb.toString()
        plain[chapter] = s
        anchorMaps[chapter] = anchors
        return s
    }

    fun anchors(chapter: Int): Map<String, Int> {
        anchorMaps[chapter]?.let { return it }
        plainText(chapter)
        return anchorMaps[chapter] ?: emptyMap()
    }

    @SuppressLint("InlinedApi")
    private fun build(g: Gen, chapter: Int): ChapterPages {
        val s = g.setup
        val st = s.settings
        val fontPx = s.fontPx
        val sb = SpannableStringBuilder()
        val chap = book.chapters[chapter]
        val indentPx = (st.indent * fontPx).toInt()
        val paraAfter = (st.paragraphSpacing * fontPx).toInt()
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        for (b in chap.blocks) {
            val start = sb.length
            when (b.kind) {
                BlockKind.IMAGE -> {
                    val path = b.image
                    val size = path?.let { images.size(it) }
                    if (path != null && size != null) {
                        val (iw, ih) = size
                        var w = iw * s.density
                        if (w > s.contentWidth) w = s.contentWidth.toFloat()
                        if (iw >= 300 && w < s.contentWidth * 0.6f) w = s.contentWidth * 0.6f
                        var h = ih * (w / iw)
                        val maxH = s.contentHeight * 0.94f - paraAfter
                        if (h > maxH) {
                            h = maxH
                            w = iw * (h / ih)
                        }
                        sb.append(OBJ)
                        sb.setSpan(BlockImageSpan(path, w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), st.lineSpacing, images), start, start + 1, flags)
                    } else {
                        sb.append(OBJ)
                        sb.setSpan(RuleSpan((s.contentWidth * 0.1f).toInt(), (fontPx * 0.5f).toInt(), colors), start, start + 1, flags)
                    }
                    sb.append('\n')
                    sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, sb.length, flags)
                    sb.setSpan(ParagraphSpacingSpan((fontPx * 0.2f).toInt(), (fontPx * 0.4f).toInt()), start, sb.length, flags)
                    continue
                }
                BlockKind.RULE -> {
                    sb.append(OBJ)
                    sb.setSpan(RuleSpan((s.contentWidth * 0.36f).toInt(), (fontPx * 1.6f).toInt(), colors), start, start + 1, flags)
                    sb.append('\n')
                    sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, sb.length, flags)
                    continue
                }
                else -> {}
            }
            for (r in b.runs) {
                val rs = sb.length
                sb.append(r.text)
                val re = sb.length
                if (re == rs) continue
                val bold = r.style and RunStyle.BOLD != 0
                val italic = r.style and RunStyle.ITALIC != 0
                if (bold || italic) sb.setSpan(StyleSpan(if (bold && italic) Typeface.BOLD_ITALIC else if (bold) Typeface.BOLD else Typeface.ITALIC), rs, re, flags)
                if (r.style and RunStyle.UNDERLINE != 0) sb.setSpan(UnderlineSpan(), rs, re, flags)
                if (r.style and RunStyle.STRIKE != 0) sb.setSpan(StrikethroughSpan(), rs, re, flags)
                if (r.style and RunStyle.SUP != 0) {
                    sb.setSpan(SuperscriptSpan(), rs, re, flags)
                    sb.setSpan(RelativeSizeSpan(0.68f), rs, re, flags)
                }
                if (r.style and RunStyle.SUB != 0) {
                    sb.setSpan(SubscriptSpan(), rs, re, flags)
                    sb.setSpan(RelativeSizeSpan(0.68f), rs, re, flags)
                }
                if (r.style and RunStyle.MONO != 0) sb.setSpan(TypefaceSpan("monospace"), rs, re, flags)
                if (r.style and RunStyle.MARK != 0) sb.setSpan(BackgroundColorSpan(0x55FFD54F), rs, re, flags)
                if (st.publisherStyles && r.scale != 1f && b.kind != BlockKind.HEADING) sb.setSpan(RelativeSizeSpan(r.scale.coerceIn(0.7f, 1.6f)), rs, re, flags)
                r.link?.let { sb.setSpan(LinkSpan(it, colors), rs, re, flags) }
            }
            sb.append('\n')
            val end = sb.length
            val align = b.align
            val useAlign = st.publisherStyles || b.kind == BlockKind.HEADING || b.kind == BlockKind.CAPTION
            when {
                useAlign && align == Align.CENTER -> sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, end, flags)
                useAlign && align == Align.END -> sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE), start, end, flags)
                b.kind == BlockKind.HEADING && align == null -> sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, end, flags)
                b.kind == BlockKind.CAPTION -> sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, end, flags)
            }
            when (b.kind) {
                BlockKind.HEADING -> {
                    val scale = when (b.level) {
                        1 -> 1.5f
                        2 -> 1.35f
                        3 -> 1.2f
                        4 -> 1.1f
                        else -> 1.03f
                    }
                    sb.setSpan(RelativeSizeSpan(scale), start, end, flags)
                    sb.setSpan(ParagraphSpacingSpan((fontPx * 1.1f).toInt(), (fontPx * 0.7f).toInt()), start, end, flags)
                }
                BlockKind.QUOTE -> {
                    sb.setSpan(QuoteMarginSpan((fontPx * 1.3f * b.level.coerceIn(1, 3)).toInt(), 2f * s.density, colors), start, end, flags)
                    sb.setSpan(ParagraphSpacingSpan(0, paraAfter), start, end, flags)
                }
                BlockKind.LIST_ITEM -> {
                    val lvl = b.level.coerceIn(1, 5)
                    val first = (fontPx * 0.9f * (lvl - 1)).toInt()
                    sb.setSpan(LeadingMarginSpan.Standard(first, first + (fontPx * 1.1f).toInt()), start, end, flags)
                    sb.setSpan(ParagraphSpacingSpan(0, (paraAfter * 0.6f).toInt()), start, end, flags)
                }
                BlockKind.VERSE -> sb.setSpan(LeadingMarginSpan.Standard((fontPx * 1.6f).toInt(), (fontPx * 2.6f).toInt()), start, end, flags)
                BlockKind.PRE -> {
                    sb.setSpan(TypefaceSpan("monospace"), start, end, flags)
                    sb.setSpan(RelativeSizeSpan(0.85f), start, end, flags)
                    sb.setSpan(LeadingMarginSpan.Standard((fontPx * 0.6f).toInt()), start, end, flags)
                    sb.setSpan(ParagraphSpacingSpan(0, paraAfter), start, end, flags)
                }
                BlockKind.CAPTION -> {
                    sb.setSpan(RelativeSizeSpan(0.85f), start, end, flags)
                    sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, flags)
                    sb.setSpan(ParagraphSpacingSpan(0, paraAfter), start, end, flags)
                }
                else -> {
                    val centered = align == Align.CENTER || align == Align.END
                    if (!b.noIndent && indentPx > 0 && !centered) sb.setSpan(LeadingMarginSpan.Standard(indentPx, 0), start, end, flags)
                    if (paraAfter > 0) sb.setSpan(ParagraphSpacingSpan(0, paraAfter), start, end, flags)
                }
            }
        }
        if (sb.isEmpty()) sb.append(" \n")
        val layout = StaticLayout.Builder.obtain(sb, 0, sb.length, g.paint, s.contentWidth)
            .setLineSpacing(0f, st.lineSpacing)
            .setIncludePad(false)
            .setBreakStrategy(if (st.justify || st.hyphenation) LineBreaker.BREAK_STRATEGY_HIGH_QUALITY else LineBreaker.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(if (st.hyphenation) Layout.HYPHENATION_FREQUENCY_NORMAL else Layout.HYPHENATION_FREQUENCY_NONE)
            .setJustificationMode(if (st.justify) LineBreaker.JUSTIFICATION_MODE_INTER_WORD else LineBreaker.JUSTIFICATION_MODE_NONE)
            .apply { if (Build.VERSION.SDK_INT >= 28) setUseLineSpacingFromFallbacks(true) }
            .build()
        return ChapterPages(sb, layout, paginate(layout, sb, s.contentHeight))
    }

    private fun paginate(layout: StaticLayout, text: Spanned, height: Int): IntArray {
        val starts = ArrayList<Int>()
        starts.add(0)
        var pageTop = layout.getLineTop(0)
        val n = layout.lineCount
        var line = 0
        while (line < n) {
            val bottom = layout.getLineBottom(line)
            if (bottom - pageTop > height && line > starts.last()) {
                var brk = line
                val prev = line - 1
                if (prev > starts.last() && isHeadingLine(layout, text, prev) && !isHeadingLine(layout, text, line)) brk = prev
                starts.add(brk)
                pageTop = layout.getLineTop(brk)
                line = brk + 1
                continue
            }
            line++
        }
        val last = starts.last()
        if (starts.size > 1 && layout.getLineStart(last) >= text.length - 1) starts.removeAt(starts.size - 1)
        return starts.toIntArray()
    }

    private fun isHeadingLine(layout: StaticLayout, text: Spanned, line: Int): Boolean {
        val start = layout.getLineStart(line)
        val end = layout.getLineEnd(line)
        val spans = text.getSpans(start, end, RelativeSizeSpan::class.java)
        return spans.any { it.sizeChange > 1.05f && text.getSpanStart(it) <= start && text.getSpanEnd(it) >= end && text.getSpans(start, end, ParagraphSpacingSpan::class.java).isNotEmpty() }
    }

    override fun drawPage(canvas: Canvas, pos: PagePos, deco: Decorations) {
        val s = currentSetup ?: return
        val th = theme ?: return
        PageChrome.drawBackground(canvas, th, s.width, s.height)
        PageChrome.drawSpine(canvas, s)
        val cp = pages(pos.chapter) ?: return
        for (col in 0 until s.columns) {
            val page = pos.page + col
            if (page >= cp.pageCount) break
            val left = s.columnLeft(col)
            val top = cp.top(page)
            val bottom = cp.bottom(page)
            canvas.save()
            canvas.translate(left, s.contentTop - top)
            canvas.clipRect(-s.marginH, top.toFloat(), s.contentWidth + s.marginH, bottom.toFloat())
            drawHighlights(canvas, cp, pos.chapter, cp.startOffset(page), cp.endOffset(page), deco, th)
            cp.layout.draw(canvas)
            canvas.restore()
            val gp = globalPage(PagePos(pos.chapter, page))
            val label = if (gp != null) "$gp / ${totalPages()}" else "${page + 1} / ${cp.pageCount}"
            val header = if (s.columns > 1 && col == 0) deco.bookTitle else chapterTitle(pos.chapter).ifBlank { deco.bookTitle }
            PageChrome.drawChrome(canvas, s, th, header, label, progress(pos.chapter, cp.startOffset(page)), deco, PagePos(pos.chapter, page) in deco.bookmarkedPages, col)
        }
    }

    override fun scrollHeight(pos: PagePos): Float {
        val cp = pages(pos.chapter) ?: return 0f
        return (cp.bottom(pos.page) - cp.top(pos.page)).toFloat()
    }

    override fun drawScrollSlice(canvas: Canvas, pos: PagePos, deco: Decorations) {
        val s = currentSetup ?: return
        val th = theme ?: return
        val cp = pages(pos.chapter) ?: return
        val top = cp.top(pos.page)
        val bottom = cp.bottom(pos.page)
        canvas.save()
        canvas.translate(s.contentLeft, -top.toFloat())
        canvas.clipRect(-s.marginH, top.toFloat(), s.contentWidth + s.marginH, bottom.toFloat())
        drawHighlights(canvas, cp, pos.chapter, cp.startOffset(pos.page), cp.endOffset(pos.page), deco, th)
        cp.layout.draw(canvas)
        canvas.restore()
    }

    private fun drawHighlights(canvas: Canvas, cp: ChapterPages, chapter: Int, start: Int, end: Int, deco: Decorations, th: ReadingTheme) {
        val alpha = if (th.dark) 0x55 else 0x70
        for (h in deco.highlights) {
            if (h.chapter != chapter || h.end <= start || h.start >= end) continue
            fill(canvas, cp, maxOf(h.start, start), minOf(h.end, end), (h.color and 0x00FFFFFF) or (alpha shl 24))
        }
        deco.search?.let { h -> if (h.chapter == chapter && h.end > start && h.start < end) fill(canvas, cp, maxOf(h.start, start), minOf(h.end, end), (th.accent and 0x00FFFFFF) or (0x66 shl 24)) }
        deco.speaking?.let { h -> if (h.chapter == chapter && h.end > start && h.start < end) fill(canvas, cp, maxOf(h.start, start), minOf(h.end, end), (th.accent and 0x00FFFFFF) or (0x33 shl 24)) }
    }

    private fun fill(canvas: Canvas, cp: ChapterPages, s: Int, e: Int, color: Int) {
        path.reset()
        cp.layout.getSelectionPath(s, e, path)
        hlPaint.color = color
        hlPaint.style = Paint.Style.FILL
        canvas.drawPath(path, hlPaint)
    }

    private fun column(pos: PagePos, x: Float): Pair<Int, Float>? {
        val s = currentSetup ?: return null
        val cp = pages(pos.chapter) ?: return null
        val col = s.columnAt(x)
        val page = pos.page + col
        if (page >= cp.pageCount) return null
        return page to s.columnLeft(col)
    }

    fun offsetAt(pos: PagePos, x: Float, y: Float): Int? {
        val s = currentSetup ?: return null
        val cp = pages(pos.chapter) ?: return null
        val (page, left) = column(pos, x) ?: return null
        val top = cp.top(page)
        val ly = (y - s.contentTop + top).toInt().coerceIn(top, (cp.bottom(page) - 1).coerceAtLeast(top))
        val line = cp.layout.getLineForVertical(ly)
        return cp.layout.getOffsetForHorizontal(line, x - left)
    }

    fun wordAt(pos: PagePos, x: Float, y: Float): IntRange? {
        val off = offsetAt(pos, x, y) ?: return null
        val text = plainText(pos.chapter)
        if (text.isEmpty()) return null
        val o = off.coerceIn(0, text.length - 1)
        val bi = BreakIterator.getWordInstance(locale)
        bi.setText(text)
        var start = bi.preceding(o + 1).let { if (it == BreakIterator.DONE) 0 else it }
        var end = bi.following(o).let { if (it == BreakIterator.DONE) text.length else it }
        if (end > start && text.substring(start, end).isBlank()) {
            start = o
            end = (o + 1).coerceAtMost(text.length)
        }
        while (end > start && text[end - 1].isWhitespace()) end--
        if (end <= start) return null
        return start until end
    }

    fun linkAt(pos: PagePos, x: Float, y: Float): String? {
        val s = currentSetup ?: return null
        val cp = pages(pos.chapter) ?: return null
        val (page, left) = column(pos, x) ?: return null
        val top = cp.top(page)
        val ly = (y - s.contentTop + top).toInt()
        if (ly < top || ly >= cp.bottom(page)) return null
        val line = cp.layout.getLineForVertical(ly)
        val lx = x - left
        if (lx < cp.layout.getLineLeft(line) - 12 * s.density || lx > cp.layout.getLineRight(line) + 12 * s.density) return null
        val off = cp.layout.getOffsetForHorizontal(line, lx)
        val tol = 2
        val spans = cp.text.getSpans((off - tol).coerceAtLeast(0), (off + tol).coerceAtMost(cp.text.length), LinkSpan::class.java)
        if (spans.isEmpty()) return null
        return spans.minByOrNull { kotlin.math.abs(cp.text.getSpanStart(it) - off) }?.href
    }

    fun selectionPath(pos: PagePos, start: Int, end: Int, out: Path): Boolean {
        val s = currentSetup ?: return false
        val cp = pages(pos.chapter) ?: return false
        out.reset()
        var any = false
        for (col in 0 until s.columns) {
            val page = pos.page + col
            if (page >= cp.pageCount) break
            val a = maxOf(start, cp.startOffset(page))
            val b = minOf(end, cp.endOffset(page))
            if (b <= a) continue
            path.reset()
            cp.layout.getSelectionPath(a, b, path)
            path.offset(s.columnLeft(col), s.contentTop - cp.top(page))
            out.addPath(path)
            any = true
        }
        return any
    }

    private fun pageColumnFor(pos: PagePos, offset: Int): Pair<Int, Float>? {
        val s = currentSetup ?: return null
        val cp = pages(pos.chapter) ?: return null
        for (col in 0 until s.columns) {
            val page = pos.page + col
            if (page >= cp.pageCount) break
            if (offset >= cp.startOffset(page) && offset <= cp.endOffset(page)) return page to s.columnLeft(col)
        }
        return null
    }

    fun handlePoint(pos: PagePos, offset: Int, isEnd: Boolean): PointF? {
        val s = currentSetup ?: return null
        val cp = pages(pos.chapter) ?: return null
        val (page, left) = pageColumnFor(pos, offset) ?: return null
        var line = cp.layout.getLineForOffset(offset)
        if (isEnd && offset > 0 && line > 0 && cp.layout.getLineStart(line) == offset) line--
        val x = if (isEnd && cp.layout.getLineEnd(line) <= offset) cp.layout.getLineRight(line) else cp.layout.getPrimaryHorizontal(offset)
        val y = cp.layout.getLineBaseline(line) + cp.layout.getLineDescent(line).coerceAtMost((s.fontPx * 0.3f).toInt())
        return PointF(x + left, y + s.contentTop - cp.top(page))
    }

    fun lineBounds(pos: PagePos, offset: Int): RectF? {
        val s = currentSetup ?: return null
        val cp = pages(pos.chapter) ?: return null
        val (page, left) = pageColumnFor(pos, offset) ?: return null
        val line = cp.layout.getLineForOffset(offset)
        val dy = s.contentTop - cp.top(page)
        return RectF(cp.layout.getLineLeft(line) + left, cp.layout.getLineTop(line) + dy, cp.layout.getLineRight(line) + left, cp.layout.getLineBottom(line) + dy)
    }

    fun text(chapter: Int, start: Int, end: Int): String {
        val t = plainText(chapter)
        return t.substring(start.coerceIn(0, t.length), end.coerceIn(0, t.length)).replace(OBJ.toString(), "").trim()
    }

    fun resolveLink(href: String, fromChapter: Int): Pair<Int, Int>? {
        if (href.contains("://") || href.startsWith("mailto:")) return null
        val file = href.substringBefore('#')
        val frag = href.substringAfter('#', "")
        val chapter = book.chapters.indexOfFirst { it.href == file }.takeIf { it >= 0 }
        if (frag.isEmpty()) return chapter?.let { it to 0 }
        if (chapter != null) {
            var c = chapter
            while (c < chapterCount && (c == chapter || book.chapters[c].href.startsWith("$file~") || book.chapters[c].href.startsWith("$file#"))) {
                anchors(c)[frag]?.let { return c to it }
                c++
            }
        }
        anchors(fromChapter)[frag]?.let { return fromChapter to it }
        for (c in 0 until chapterCount) anchors(c)[frag]?.let { return c to it }
        return chapter?.let { it to 0 }
    }

    fun footnote(chapter: Int, offset: Int): String {
        val t = plainText(chapter)
        if (t.isEmpty()) return ""
        var start = t.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        if (offset < t.length && t[offset] == '\n') start = offset + 1
        val sb = StringBuilder()
        var p = start
        var paragraphs = 0
        val nextAnchor = anchors(chapter).values.filter { it > offset + 1 }.minOrNull() ?: Int.MAX_VALUE
        while (p < t.length && paragraphs < 6 && sb.length < 1500) {
            val e = t.indexOf('\n', p).let { if (it < 0) t.length else it }
            if (paragraphs > 0 && p >= nextAnchor) break
            val line = t.substring(p, e).replace(OBJ.toString(), "").trim()
            if (line.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(line)
            }
            paragraphs++
            p = e + 1
        }
        return sb.toString()
    }

    fun search(query: String, limit: Int = 500): List<SearchHit> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val out = ArrayList<SearchHit>()
        for (c in 0 until chapterCount) {
            val t = plainText(c)
            var i = t.indexOf(q, 0, ignoreCase = true)
            while (i >= 0) {
                val s = (i - 40).coerceAtLeast(0)
                val e = (i + q.length + 60).coerceAtMost(t.length)
                val snippet = t.substring(s, e).replace('\n', ' ').replace(OBJ.toString(), "")
                val prefixLen = t.substring(s, i).replace('\n', ' ').replace(OBJ.toString(), "").length
                out.add(SearchHit(c, i, i + q.length, (if (s > 0) "…" else "") + snippet + (if (e < t.length) "…" else ""), prefixLen + (if (s > 0) 1 else 0), prefixLen + (if (s > 0) 1 else 0) + q.length))
                if (out.size >= limit) return out
                i = t.indexOf(q, i + q.length, ignoreCase = true)
            }
        }
        return out
    }

    fun sentences(chapter: Int, from: Int): List<IntRange> {
        val t = plainText(chapter)
        val bi = BreakIterator.getSentenceInstance(locale)
        bi.setText(t)
        val out = ArrayList<IntRange>()
        var start = bi.preceding((from + 1).coerceAtMost(t.length)).let { if (it == BreakIterator.DONE) 0 else it }
        if (start < from && from - start > 0 && t.substring(start, from).isBlank()) start = from
        var end = bi.following(start)
        while (end != BreakIterator.DONE && out.size < 400) {
            var s = start
            var e = end
            while (s < e && (t[s].isWhitespace() || t[s] == OBJ)) s++
            while (e > s && (t[e - 1].isWhitespace() || t[e - 1] == OBJ)) e--
            if (e > s) {
                if (e - s > 600) {
                    var p = s
                    while (p < e) {
                        var q = (p + 400).coerceAtMost(e)
                        if (q < e) {
                            val sp = t.lastIndexOf(' ', q)
                            if (sp > p) q = sp
                        }
                        out.add(p until q)
                        p = q
                    }
                } else out.add(s until e)
            }
            start = end
            end = bi.next()
        }
        return out
    }

    fun locale(): Locale = locale

    override fun close() {
        images.clear()
    }

    companion object {
        const val OBJ = '￼'
    }
}
