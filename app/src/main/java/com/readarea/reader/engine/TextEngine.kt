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

class ChapterPages(val text: Spanned, val layout: StaticLayout, val starts: IntArray, private val justified: Boolean = false) : PagedText {
    private val justification = HashMap<Int, FloatArray?>()
    private val scratch = Path()

    private fun natural(a: Int, b: Int): Float {
        if (b <= a) return 0f
        var margin = 0
        for (sp in text.getSpans(a, b, LeadingMarginSpan::class.java)) margin += sp.getLeadingMargin(true)
        return Layout.getDesiredWidth(text, a, b, layout.paint) - margin
    }

    private fun spacesIn(a: Int, b: Int): Int {
        var n = 0
        for (i in a until b) if (text[i] == ' ') n++
        return n
    }

    private fun justificationOf(line: Int): FloatArray? = synchronized(justification) {
        if (justification.containsKey(line)) return justification[line]
        val v = computeJustification(line)
        justification[line] = v
        v
    }

    private fun computeJustification(line: Int): FloatArray? {
        if (!justified) return null
        val start = layout.getLineStart(line)
        val end = layout.getLineEnd(line)
        if (end >= text.length || text[end - 1] == '\n') return null
        var visible = end
        while (visible > start && (text[visible - 1] == ' ' || text[visible - 1] == '\t' || text[visible - 1] == '\u3000')) visible--
        val spaces = spacesIn(start, visible)
        if (spaces == 0) return null
        val dir = layout.getParagraphDirection(line).toFloat()
        val startX = layout.getPrimaryHorizontal(start)
        val last = text[end - 1]
        val hyphen = last == '\u00AD' || last.isLetterOrDigit() && text[end].isLetterOrDigit()
        val width = natural(start, visible) + if (hyphen) layout.paint.measureText("-") else 0f
        val available = if (dir > 0) layout.width - startX else startX
        return floatArrayOf(startX, (available - width) / spaces, dir, start.toFloat(), visible.toFloat())
    }

    fun x(line: Int, offset: Int): Float {
        val j = justificationOf(line)
        if (j == null) {
            if (offset >= layout.getLineEnd(line) && line < layout.lineCount - 1) {
                return if (layout.getParagraphDirection(line) < 0) layout.getLineLeft(line) else layout.getLineRight(line)
            }
            return layout.getPrimaryHorizontal(offset)
        }
        val start = j[3].toInt()
        val o = offset.coerceIn(start, j[4].toInt())
        return j[0] + j[2] * (natural(start, o) + j[1] * spacesIn(start, o))
    }

    fun offsetAt(line: Int, x: Float): Int {
        val j = justificationOf(line) ?: return layout.getOffsetForHorizontal(line, x)
        var lo = j[3].toInt()
        var hi = j[4].toInt()
        val dir = j[2]
        while (hi - lo > 1) {
            var mid = (lo + hi) / 2
            if (Character.isLowSurrogate(text[mid]) && mid - 1 > lo) mid--
            if (dir * (x(line, mid) - x) <= 0f) lo = mid else hi = mid
        }
        return if (kotlin.math.abs(x(line, lo) - x) <= kotlin.math.abs(x(line, hi) - x)) lo else hi
    }

    fun path(a: Int, b: Int, out: Path) {
        if (b <= a) return
        val first = layout.getLineForOffset(a)
        val last = layout.getLineForOffset((b - 1).coerceAtLeast(a))
        for (line in first..last) {
            val ls = layout.getLineStart(line)
            val le = layout.getLineEnd(line)
            val s = maxOf(a, ls)
            val e = minOf(b, le)
            if (e <= s) continue
            val j = justificationOf(line)
            if (j == null) {
                scratch.reset()
                layout.getSelectionPath(s, e, scratch)
                out.addPath(scratch)
                continue
            }
            val x0 = x(line, s)
            val x1 = if (e >= le && b > le) (if (j[2] > 0) layout.width.toFloat() else 0f) else x(line, e)
            out.addRect(minOf(x0, x1), layout.getLineTop(line).toFloat(), maxOf(x0, x1), layout.getLineBottom(line).toFloat(), Path.Direction.CW)
        }
    }

    override val pageCount: Int get() = starts.size
    override fun startOffset(page: Int): Int = layout.getLineStart(starts[page.coerceIn(0, starts.size - 1)])
    override fun endOffset(page: Int): Int = if (page + 1 < starts.size) layout.getLineStart(starts[page + 1]) else text.length
    fun top(page: Int): Int = layout.getLineTop(starts[page.coerceIn(0, starts.size - 1)])
    fun bottom(page: Int): Int = if (page + 1 < starts.size) layout.getLineTop(starts[page + 1]) else layout.height
    override fun pageOf(offset: Int): Int {
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

    private class Gen(val setup: PageSetup, val paint: TextPaint, val arr: AtomicReferenceArray<PagedText?>)

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

    private fun paged(chapter: Int): PagedText? = gen?.arr?.takeIf { chapter in 0 until chapterCount }?.get(chapter)

    fun pages(chapter: Int): ChapterPages? = paged(chapter) as? ChapterPages

    fun vpages(chapter: Int): VerticalPages? = paged(chapter) as? VerticalPages

    override fun ensure(chapter: Int) {
        val g = gen ?: return
        if (chapter !in 0 until chapterCount || g.arr.get(chapter) != null) return
        val cp = if (g.setup.vertical) buildVertical(g, chapter) else build(g, chapter)
        if (gen === g) g.arr.compareAndSet(chapter, null, cp)
    }

    private fun buildVertical(g: Gen, chapter: Int): VerticalPages {
        val s = g.setup
        val st = s.settings
        return VerticalBuilder(
            s.contentWidth.toFloat(), s.contentHeight.toFloat(), s.fontPx, s.density, st.lineSpacing, st.letterSpacing,
            st.paragraphSpacing, st.indent, st.justify, st.publisherStyles, g.paint.typeface ?: Typeface.DEFAULT, images,
        ).build(book.chapters[chapter].blocks)
    }

    override fun pageCount(chapter: Int): Int = paged(chapter)?.pageCount ?: 0

    override fun pageOf(chapter: Int, offset: Int): Int = paged(chapter)?.pageOf(offset) ?: 0

    override fun offsetOf(pos: PagePos): Int = paged(pos.chapter)?.startOffset(pos.page) ?: 0

    override fun endOffsetOf(pos: PagePos): Int = paged(pos.chapter)?.endOffset(pos.page) ?: 0

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
            val bodyEnd = sb.length
            for (rb in b.ruby) {
                var a = start + rb.start
                var z = (start + rb.end).coerceAtMost(bodyEnd)
                while (z > a && sb[z - 1].isWhitespace()) z--
                while (a < z && sb[a].isWhitespace()) a++
                if (z > a && (a until z).none { sb[it] == '\n' } && sb.getSpans(a, z, RubySpan::class.java).isEmpty()) sb.setSpan(RubySpan(rb.text), a, z, flags)
            }
            if (b.runs.any { it.style and RunStyle.EMPHASIS != 0 }) {
                var k = start
                for (r in b.runs) {
                    if (r.style and RunStyle.EMPHASIS != 0) {
                        for (q in k until k + r.text.length) {
                            if (!sb[q].isWhitespace() && !Character.isSurrogate(sb[q]) && sb.getSpans(q, q + 1, RubySpan::class.java).isEmpty()) sb.setSpan(RubySpan("・"), q, q + 1, flags)
                        }
                    }
                    k += r.text.length
                }
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
                    val lead = b.runs.firstOrNull()?.text?.firstOrNull()
                    val cjkOpen = lead != null && (lead == '\u3000' || Vertical.opening(lead))
                    if (!b.noIndent && indentPx > 0 && !centered && !cjkOpen) sb.setSpan(LeadingMarginSpan.Standard(indentPx, 0), start, end, flags)
                    if (paraAfter > 0) sb.setSpan(ParagraphSpacingSpan(0, paraAfter), start, end, flags)
                }
            }
        }
        if (sb.isEmpty()) sb.append(" \n")
        val justify = st.justify && st.letterSpacing == 0f
        val layout = StaticLayout.Builder.obtain(sb, 0, sb.length, g.paint, s.contentWidth)
            .setLineSpacing(0f, st.lineSpacing)
            .setIncludePad(false)
            .setBreakStrategy(if (justify || st.hyphenation) LineBreaker.BREAK_STRATEGY_HIGH_QUALITY else LineBreaker.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(if (st.hyphenation) Layout.HYPHENATION_FREQUENCY_NORMAL else Layout.HYPHENATION_FREQUENCY_NONE)
            .setJustificationMode(if (justify) LineBreaker.JUSTIFICATION_MODE_INTER_WORD else LineBreaker.JUSTIFICATION_MODE_NONE)
            .apply { if (Build.VERSION.SDK_INT >= 28) setUseLineSpacingFromFallbacks(true) }
            .build()
        return ChapterPages(sb, layout, paginate(layout, sb, s.contentHeight), justify)
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
        val pt = paged(pos.chapter) ?: return
        for (col in 0 until s.columns) {
            val page = pos.page + col
            if (page >= pt.pageCount) break
            val left = s.columnLeft(col)
            when (pt) {
                is ChapterPages -> {
                    val top = pt.top(page)
                    val bottom = pt.bottom(page)
                    canvas.save()
                    canvas.translate(left, s.contentTop - top)
                    canvas.clipRect(-s.marginH, top.toFloat(), s.contentWidth + s.marginH, bottom.toFloat())
                    drawHighlights(canvas, pos.chapter, pt.startOffset(page), pt.endOffset(page), deco, th) { a, b, out -> pt.path(a, b, out) }
                    pt.layout.draw(canvas)
                    canvas.restore()
                }
                is VerticalPages -> {
                    drawHighlights(canvas, pos.chapter, pt.startOffset(page), pt.endOffset(page), deco, th) { a, b, out -> pt.path(page, a, b, left, s.contentTop, out) }
                    pt.draw(canvas, page, left, s.contentTop, colors, images)
                }
            }
            val gp = globalPage(PagePos(pos.chapter, page))
            val label = if (gp != null) "$gp / ${totalPages()}" else "${page + 1} / ${pt.pageCount}"
            val header = if (s.columns > 1 && col == 0) deco.bookTitle else chapterTitle(pos.chapter).ifBlank { deco.bookTitle }
            PageChrome.drawChrome(canvas, s, th, header, label, progress(pos.chapter, pt.startOffset(page)), deco, PagePos(pos.chapter, page) in deco.bookmarkedPages, col)
        }
    }

    override fun scrollHeight(pos: PagePos): Float {
        val pt = paged(pos.chapter) ?: return 0f
        return when (pt) {
            is ChapterPages -> (pt.bottom(pos.page) - pt.top(pos.page)).toFloat()
            else -> currentSetup?.contentHeight?.toFloat() ?: 0f
        }
    }

    override fun drawScrollSlice(canvas: Canvas, pos: PagePos, deco: Decorations) {
        val s = currentSetup ?: return
        val th = theme ?: return
        when (val pt = paged(pos.chapter)) {
            is ChapterPages -> {
                val top = pt.top(pos.page)
                val bottom = pt.bottom(pos.page)
                canvas.save()
                canvas.translate(s.contentLeft, -top.toFloat())
                canvas.clipRect(-s.marginH, top.toFloat(), s.contentWidth + s.marginH, bottom.toFloat())
                drawHighlights(canvas, pos.chapter, pt.startOffset(pos.page), pt.endOffset(pos.page), deco, th) { a, b, out -> pt.path(a, b, out) }
                pt.layout.draw(canvas)
                canvas.restore()
            }
            is VerticalPages -> {
                drawHighlights(canvas, pos.chapter, pt.startOffset(pos.page), pt.endOffset(pos.page), deco, th) { a, b, out -> pt.path(pos.page, a, b, s.contentLeft, 0f, out) }
                pt.draw(canvas, pos.page, s.contentLeft, 0f, colors, images)
            }
            else -> {}
        }
    }

    private inline fun drawHighlights(canvas: Canvas, chapter: Int, start: Int, end: Int, deco: Decorations, th: ReadingTheme, shape: (Int, Int, Path) -> Unit) {
        val alpha = if (th.dark) 0x55 else 0x70
        for (h in deco.highlights) {
            if (h.chapter != chapter || h.end <= start || h.start >= end) continue
            fill(canvas, maxOf(h.start, start), minOf(h.end, end), (h.color and 0x00FFFFFF) or (alpha shl 24), shape)
        }
        deco.search?.let { h -> if (h.chapter == chapter && h.end > start && h.start < end) fill(canvas, maxOf(h.start, start), minOf(h.end, end), (th.accent and 0x00FFFFFF) or (0x66 shl 24), shape) }
        deco.speaking?.let { h -> if (h.chapter == chapter && h.end > start && h.start < end) fill(canvas, maxOf(h.start, start), minOf(h.end, end), (th.accent and 0x00FFFFFF) or (0x33 shl 24), shape) }
    }

    private inline fun fill(canvas: Canvas, s: Int, e: Int, color: Int, shape: (Int, Int, Path) -> Unit) {
        path.reset()
        shape(s, e, path)
        hlPaint.color = color
        hlPaint.style = Paint.Style.FILL
        canvas.drawPath(path, hlPaint)
    }

    private fun column(pos: PagePos, x: Float): Pair<Int, Float>? {
        val s = currentSetup ?: return null
        val pt = paged(pos.chapter) ?: return null
        val col = s.columnAt(x)
        val page = pos.page + col
        if (page >= pt.pageCount) return null
        return page to s.columnLeft(col)
    }

    fun offsetAt(pos: PagePos, x: Float, y: Float): Int? {
        val s = currentSetup ?: return null
        val pt = paged(pos.chapter) ?: return null
        val (page, left) = column(pos, x) ?: return null
        if (pt is VerticalPages) return pt.offsetAt(page, x - left, y - s.contentTop)
        val cp = pt as ChapterPages
        val top = cp.top(page)
        val ly = (y - s.contentTop + top).toInt().coerceIn(top, (cp.bottom(page) - 1).coerceAtLeast(top))
        val line = cp.layout.getLineForVertical(ly)
        return cp.offsetAt(line, x - left)
    }

    fun wordAt(pos: PagePos, x: Float, y: Float): IntRange? {
        val s = currentSetup ?: return null
        val vp = vpages(pos.chapter)
        val off = if (vp != null) column(pos, x)?.let { (page, left) -> vp.offsetAt(page, x - left, y - s.contentTop, floor = true) } ?: return null else offsetAt(pos, x, y) ?: return null
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
        val pt = paged(pos.chapter) ?: return null
        val (page, left) = column(pos, x) ?: return null
        if (pt is VerticalPages) return pt.linkAt(page, x - left, y - s.contentTop, 6 * s.density)
        val cp = pt as ChapterPages
        val top = cp.top(page)
        val ly = (y - s.contentTop + top).toInt()
        if (ly < top || ly >= cp.bottom(page)) return null
        val line = cp.layout.getLineForVertical(ly)
        val lx = x - left
        if (lx < cp.layout.getLineLeft(line) - 12 * s.density || lx > cp.layout.getLineRight(line) + 12 * s.density) return null
        val off = cp.offsetAt(line, lx)
        val tol = 2
        val spans = cp.text.getSpans((off - tol).coerceAtLeast(0), (off + tol).coerceAtMost(cp.text.length), LinkSpan::class.java)
        if (spans.isEmpty()) return null
        return spans.minByOrNull { kotlin.math.abs(cp.text.getSpanStart(it) - off) }?.href
    }

    fun selectionPath(pos: PagePos, start: Int, end: Int, out: Path): Boolean {
        val s = currentSetup ?: return false
        val pt = paged(pos.chapter) ?: return false
        out.reset()
        var any = false
        for (col in 0 until s.columns) {
            val page = pos.page + col
            if (page >= pt.pageCount) break
            val a = maxOf(start, pt.startOffset(page))
            val b = minOf(end, pt.endOffset(page))
            if (b <= a) continue
            path.reset()
            when (pt) {
                is ChapterPages -> {
                    pt.path(a, b, path)
                    path.offset(s.columnLeft(col), s.contentTop - pt.top(page))
                }
                is VerticalPages -> pt.path(page, a, b, s.columnLeft(col), s.contentTop, path)
            }
            out.addPath(path)
            any = true
        }
        return any
    }

    private fun pageColumnFor(pos: PagePos, offset: Int): Pair<Int, Float>? {
        val s = currentSetup ?: return null
        val pt = paged(pos.chapter) ?: return null
        for (col in 0 until s.columns) {
            val page = pos.page + col
            if (page >= pt.pageCount) break
            if (offset >= pt.startOffset(page) && offset <= pt.endOffset(page)) return page to s.columnLeft(col)
        }
        return null
    }

    fun handlePoint(pos: PagePos, offset: Int, isEnd: Boolean): PointF? {
        val s = currentSetup ?: return null
        val pt = paged(pos.chapter) ?: return null
        val (page, left) = pageColumnFor(pos, offset) ?: return null
        if (pt is VerticalPages) return pt.handle(page, offset, isEnd)?.apply { offset(left, s.contentTop) }
        val cp = pt as ChapterPages
        var line = cp.layout.getLineForOffset(offset)
        if (isEnd && offset > 0 && line > 0 && cp.layout.getLineStart(line) == offset) line--
        val x = cp.x(line, offset)
        val y = cp.layout.getLineBaseline(line) + cp.layout.getLineDescent(line).coerceAtMost((s.fontPx * 0.3f).toInt())
        return PointF(x + left, y + s.contentTop - cp.top(page))
    }

    fun lineBounds(pos: PagePos, offset: Int): RectF? {
        val s = currentSetup ?: return null
        val pt = paged(pos.chapter) ?: return null
        val (page, left) = pageColumnFor(pos, offset) ?: return null
        if (pt is VerticalPages) return pt.bounds(page, offset)?.apply { offset(left, s.contentTop) }
        val cp = pt as ChapterPages
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

    private class Folded(val text: String, val map: IntArray)

    private val folded = arrayOfNulls<Folded>(chapterCount)

    private fun fold(src: String): Folded {
        val sb = StringBuilder(src.length)
        val map = IntArray(src.length * 2 + 8)
        var n = 0
        fun put(c: Char, from: Int) {
            if (n == map.size) return
            sb.append(c)
            map[n++] = from
        }
        for (i in src.indices) {
            val c = src[i]
            when (c) {
                '\u00AD', '\u200B', '\u2060', '\uFEFF' -> continue
                '\u2018', '\u2019', '\u02BC', '\u2032' -> put('\'', i)
                '\u201C', '\u201D', '\u201E', '\u00AB', '\u00BB' -> put('"', i)
                '\u00A0', '\u2007', '\u202F' -> put(' ', i)
                '\u2010', '\u2011' -> put('-', i)
                else -> {
                    if (c.code < 0x80) {
                        put(c.lowercaseChar(), i)
                    } else {
                        val d = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD)
                        for (k in d) if (Character.getType(k) != Character.NON_SPACING_MARK.toInt()) put(k.lowercaseChar(), i)
                    }
                }
            }
        }
        return Folded(sb.toString(), map.copyOf(n))
    }

    private fun folded(chapter: Int): Folded = folded[chapter] ?: fold(plainText(chapter)).also { folded[chapter] = it }

    fun search(query: String, limit: Int = 500): List<SearchHit> {
        val q = fold(query.trim()).text
        if (q.length < 2) return emptyList()
        val out = ArrayList<SearchHit>()
        for (c in 0 until chapterCount) {
            val t = plainText(c)
            val f = folded(c)
            var k = f.text.indexOf(q)
            while (k >= 0) {
                val i = f.map[k]
                val e = f.map[k + q.length - 1] + 1
                val s = (i - 40).coerceAtLeast(0)
                val se = (e + 60).coerceAtMost(t.length)
                val clean: (String) -> String = { it.replace('\n', ' ').replace(OBJ.toString(), "").replace("\u00AD", "") }
                val prefix = clean(t.substring(s, i)).length + if (s > 0) 1 else 0
                val match = clean(t.substring(i, e)).length
                out.add(SearchHit(c, i, e, (if (s > 0) "…" else "") + clean(t.substring(s, se)) + (if (se < t.length) "…" else ""), prefix, prefix + match))
                if (out.size >= limit) return out
                k = f.text.indexOf(q, k + q.length)
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
