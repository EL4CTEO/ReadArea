package com.readarea.desktop.reader.engine

import com.readarea.core.format.Align
import com.readarea.core.format.Block
import com.readarea.core.format.BlockKind
import com.readarea.core.format.Chapter
import com.readarea.core.format.RunStyle
import com.readarea.core.text.BookText
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Paint
import java.awt.PaintContext
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Transparency
import java.awt.font.FontRenderContext
import java.awt.font.GraphicAttribute
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.awt.image.ColorModel
import java.text.AttributedString
import java.text.BreakIterator
import java.text.CharacterIterator
import java.util.Locale

/** A paint whose color can change after text was laid out, so a new theme doesn't mean a new layout. */
class ThemePaint(@Volatile var color: Color) : Paint {
    override fun createContext(cm: ColorModel?, deviceBounds: Rectangle?, userBounds: Rectangle2D?, xform: AffineTransform?, hints: RenderingHints?): PaintContext =
        color.createContext(cm, deviceBounds, userBounds, xform, hints)

    override fun getTransparency(): Int = color.transparency
}

/** Link and ornament colors shared by every chapter's layout. */
class SpanColors {
    val link = ThemePaint(Color(0x2F6FDB))
    @Volatile var text: Color = Color.BLACK
    @Volatile var secondary: Color = Color.GRAY
    @Volatile var accent: Color = Color(0x2F6FDB)
}

data class LinkRange(val start: Int, val end: Int, val href: String)

/** One line of a laid-out chapter: text, an image or an ornament. Offsets are chapter offsets. */
class Line(
    val kind: Int,
    val start: Int,
    val end: Int,
    val x: Float,
    val top: Float,
    val baseline: Float,
    val bottom: Float,
    val layout: TextLayout? = null,
    val hyphen: Boolean = false,
    val heading: Boolean = false,
    val block: Int = 0,
    val firstInBlock: Boolean = false,
    val lastInBlock: Boolean = false,
    val image: String? = null,
    val imageW: Float = 0f,
    val imageH: Float = 0f,
    val quoteX: Float = Float.NaN,
    val hyphenFont: Font? = null,
) {
    companion object {
        const val TEXT = 0
        const val IMAGE = 1
        const val RULE = 2
        const val BLANK = 3
        const val ORNAMENT = 4
    }
}

/** Where each line and page of a chapter starts: small enough to keep for every chapter of a book. */
class ChapterMetrics(val textLength: Int, val lineStarts: IntArray, val lineTops: FloatArray, val lineBottoms: FloatArray, val pageStarts: IntArray, val height: Float) : PagedText {
    override val pageCount: Int get() = pageStarts.size
    override fun startOffset(page: Int): Int = if (lineStarts.isEmpty()) 0 else lineStarts[pageStarts[page.coerceIn(0, pageStarts.size - 1)]]
    override fun endOffset(page: Int): Int = if (page + 1 < pageStarts.size) lineStarts[pageStarts[page + 1]] else textLength

    fun top(page: Int): Float = if (lineTops.isEmpty()) 0f else lineTops[pageStarts[page.coerceIn(0, pageStarts.size - 1)]]
    fun bottom(page: Int): Float = if (page + 1 < pageStarts.size) lineTops[pageStarts[page + 1]] else height

    fun lineOf(offset: Int): Int {
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    override fun pageOf(offset: Int): Int {
        if (lineStarts.isEmpty()) return 0
        val line = lineOf(offset.coerceIn(0, textLength))
        var lo = 0
        var hi = pageStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (pageStarts[mid] <= line) lo = mid else hi = mid - 1
        }
        return lo
    }
}

class ChapterLayout(val metrics: ChapterMetrics, val lines: Array<Line>, val links: List<LinkRange>, val width: Float) {
    /** Lines on [page]. */
    fun lineRange(page: Int): IntRange {
        val ps = metrics.pageStarts
        if (lines.isEmpty() || page !in ps.indices) return IntRange.EMPTY
        val first = ps[page]
        val last = if (page + 1 < ps.size) ps[page + 1] - 1 else lines.size - 1
        return first..last
    }

    fun lineAtY(page: Int, y: Float): Int? {
        val r = lineRange(page)
        if (r.isEmpty()) return null
        var best = r.first
        for (i in r) {
            val l = lines[i]
            if (y < l.top) break
            best = i
            if (y < l.bottom) break
        }
        return best
    }
}

/** A graphic standing in for characters, drawn by [draw]. Used for ruby, tabs and invisible characters. */
private abstract class Glyphic(align: Int) : GraphicAttribute(align)

private class EmptyGraphic : Glyphic(ROMAN_BASELINE) {
    override fun getAscent() = 0f
    override fun getDescent() = 0f
    override fun getAdvance() = 0f
    override fun draw(g: Graphics2D, x: Float, y: Float) {}
}

private class SpaceGraphic(private val width: Float) : Glyphic(ROMAN_BASELINE) {
    override fun getAscent() = 0f
    override fun getDescent() = 0f
    override fun getAdvance() = width
    override fun draw(g: Graphics2D, x: Float, y: Float) {}
}

/** Base text with small annotation text centered above it (furigana, or emphasis dots). */
private class RubyGraphic(base: String, ruby: String, baseFont: Font, rubyFont: Font, frc: FontRenderContext) : Glyphic(ROMAN_BASELINE) {
    private val baseLayout = TextLayout(base, baseFont, frc)
    private val rubyLayout = TextLayout(ruby, rubyFont, frc)
    private val width = maxOf(baseLayout.advance, rubyLayout.advance)
    private val gap = baseFont.size2D * 0.05f

    override fun getAscent() = baseLayout.ascent + rubyLayout.ascent + rubyLayout.descent + gap
    override fun getDescent() = baseLayout.descent
    override fun getAdvance() = width

    override fun draw(g: Graphics2D, x: Float, y: Float) {
        baseLayout.draw(g, x + (width - baseLayout.advance) / 2f, y)
        rubyLayout.draw(g, x + (width - rubyLayout.advance) / 2f, y - baseLayout.ascent - rubyLayout.descent - gap)
    }
}

/**
 * Line breaking that ignores the opportunities soft hyphens give, for when hyphenation is off.
 * Everything else is the locale's normal line breaking.
 */
private class NoSoftHyphenBreaks(private val inner: BreakIterator) : BreakIterator() {
    private var text: CharacterIterator? = null

    private fun ok(b: Int): Boolean {
        val t = text ?: return true
        if (b == DONE || b <= t.beginIndex || b >= t.endIndex) return true
        return t.setIndex(b - 1) != '­'
    }

    override fun first(): Int = inner.first()
    override fun last(): Int = inner.last()
    override fun current(): Int = inner.current()
    override fun getText(): CharacterIterator = inner.text
    override fun setText(newText: CharacterIterator) {
        text = newText.clone() as CharacterIterator
        inner.setText(newText)
    }

    override fun next(n: Int): Int {
        var r = current()
        repeat(n) { r = next() }
        return r
    }

    override fun next(): Int {
        var b = inner.next()
        while (!ok(b)) b = inner.next()
        return b
    }

    override fun previous(): Int {
        var b = inner.previous()
        while (!ok(b)) b = inner.previous()
        return b
    }

    override fun following(offset: Int): Int {
        var b = inner.following(offset)
        while (!ok(b)) b = inner.next()
        return b
    }

    override fun preceding(offset: Int): Int {
        var b = inner.preceding(offset)
        while (!ok(b)) b = inner.previous()
        return b
    }

    override fun isBoundary(offset: Int): Boolean = inner.isBoundary(offset) && ok(offset)
}

/**
 * Lays out one chapter's blocks into lines for a page width, then splits the lines into pages.
 * The character offsets match [BookText.plainText] exactly.
 */
class TextLayoutBuilder(
    private val setup: PageSetup,
    private val baseFont: Font,
    private val monoFont: Font,
    private val colors: SpanColors,
    private val images: ImageCache,
    private val locale: Locale,
) {
    private val st = setup.settings
    private val frc = FRC
    private val fontPx = setup.fontPx
    private val width = setup.contentWidth.toFloat()
    private val fonts = HashMap<Long, Font>()
    private val fallbackSerif = !ReaderFonts.isLogical(baseFont) && baseFont.family.let { f -> listOf("Sans", "Helvetica", "Arial", "Segoe", "Inter", "Verdana", "Calibri", "Cantarell", "Ubuntu", "Avenir").none { f.contains(it, true) } }
    private val weight = ReaderFonts.weight(st.fontWeight)
    private val justify = st.justify
    private val paraAfter = st.paragraphSpacing * fontPx
    private val indentPx = st.indent * fontPx

    private fun font(mono: Boolean, fallback: Boolean, size: Float, bold: Boolean, italic: Boolean, sup: Boolean, sub: Boolean, underline: Boolean, strike: Boolean): Font {
        val sizeKey = (size * 64).toLong()
        val key = (sizeKey shl 8) or (if (mono) 1L else 0) or (if (fallback) 2L else 0) or (if (bold) 4L else 0) or (if (italic) 8L else 0) or
            (if (sup) 16L else 0) or (if (sub) 32L else 0) or (if (underline) 64L else 0) or (if (strike) 128L else 0)
        return fonts.getOrPut(key) {
            val base = when {
                fallback -> Font(if (mono) Font.MONOSPACED else if (fallbackSerif) Font.SERIF else Font.SANS_SERIF, Font.PLAIN, 1)
                mono -> monoFont
                else -> baseFont
            }
            val attrs = HashMap<TextAttribute, Any>()
            attrs[TextAttribute.SIZE] = size
            attrs[TextAttribute.WEIGHT] = if (bold) maxOf(TextAttribute.WEIGHT_BOLD, weight + 0.75f) else weight
            attrs[TextAttribute.POSTURE] = if (italic) TextAttribute.POSTURE_OBLIQUE else TextAttribute.POSTURE_REGULAR
            attrs[TextAttribute.KERNING] = TextAttribute.KERNING_ON
            attrs[TextAttribute.LIGATURES] = TextAttribute.LIGATURES_ON
            if (st.letterSpacing != 0f && !mono) attrs[TextAttribute.TRACKING] = st.letterSpacing
            if (sup) attrs[TextAttribute.SUPERSCRIPT] = TextAttribute.SUPERSCRIPT_SUPER
            if (sub) attrs[TextAttribute.SUPERSCRIPT] = TextAttribute.SUPERSCRIPT_SUB
            if (underline) attrs[TextAttribute.UNDERLINE] = TextAttribute.UNDERLINE_ON
            if (strike) attrs[TextAttribute.STRIKETHROUGH] = TextAttribute.STRIKETHROUGH_ON
            base.deriveFont(attrs)
        }
    }

    private class Style(val scale: Float, val first: Float, val rest: Float, val align: Align, val before: Float, val after: Float, val heading: Boolean, val quote: Int, val mono: Boolean, val italic: Boolean, val bold: Boolean)

    private fun styleFor(b: Block): Style {
        val useAlign = st.publisherStyles || b.kind == BlockKind.HEADING || b.kind == BlockKind.CAPTION
        val bodyAlign = if (justify) Align.JUSTIFY else Align.START
        var align = when {
            useAlign && b.align == Align.CENTER -> Align.CENTER
            useAlign && b.align == Align.END -> Align.END
            b.kind == BlockKind.HEADING && b.align == null -> Align.CENTER
            b.kind == BlockKind.CAPTION -> Align.CENTER
            b.align == Align.JUSTIFY && justify -> Align.JUSTIFY
            else -> bodyAlign
        }
        return when (b.kind) {
            BlockKind.HEADING -> {
                val scale = when (b.level) { 1 -> 1.5f; 2 -> 1.35f; 3 -> 1.2f; 4 -> 1.1f; else -> 1.03f }
                if (align == Align.JUSTIFY) align = Align.START
                Style(scale, 0f, 0f, align, fontPx * 1.1f, fontPx * 0.7f, true, 0, false, false, true)
            }
            BlockKind.QUOTE -> {
                val m = fontPx * 1.3f * b.level.coerceIn(1, 3)
                Style(1f, m, m, align, 0f, paraAfter, false, b.level.coerceIn(1, 3), false, false, false)
            }
            BlockKind.LIST_ITEM -> {
                val first = fontPx * 0.9f * (b.level.coerceIn(1, 5) - 1)
                Style(1f, first, first + fontPx * 1.1f, align, 0f, paraAfter * 0.6f, false, 0, false, false, false)
            }
            BlockKind.VERSE -> Style(1f, fontPx * 1.6f, fontPx * 2.6f, if (align == Align.JUSTIFY) Align.START else align, 0f, 0f, false, 0, false, false, false)
            BlockKind.PRE -> Style(0.85f, fontPx * 0.6f, fontPx * 0.6f, Align.START, 0f, paraAfter, false, 0, true, false, false)
            BlockKind.CAPTION -> Style(0.85f, 0f, 0f, Align.CENTER, 0f, paraAfter, false, 0, false, true, false)
            else -> {
                val centered = align == Align.CENTER || align == Align.END
                val lead = b.runs.firstOrNull()?.text?.firstOrNull()
                val cjkOpen = lead != null && (lead == '　' || OPENING.indexOf(lead) >= 0)
                val indent = if (!b.noIndent && indentPx > 0 && !centered && !cjkOpen) indentPx else 0f
                Style(1f, indent, 0f, align, 0f, paraAfter, false, 0, false, false, false)
            }
        }
    }

    fun build(chapter: Chapter, plain: String): ChapterLayout {
        val lines = ArrayList<Line>()
        val links = ArrayList<LinkRange>()
        var y = 0f
        var pendingGap = 0f
        var offset = 0
        chapter.blocks.forEachIndexed { index, b ->
            val blockStart = offset
            when (b.kind) {
                BlockKind.IMAGE -> {
                    val gapBefore = maxOf(pendingGap, fontPx * 0.2f)
                    y += if (lines.isEmpty()) 0f else gapBefore
                    val path = b.image
                    val size = path?.let { images.size(it) }
                    if (path != null && size != null) {
                        var w = size.width.toFloat()
                        val iw = size.width.toFloat()
                        val ih = size.height.toFloat()
                        if (w > width) w = width
                        if (iw >= 300 && w < width * 0.6f) w = width * 0.6f
                        var h = ih * (w / iw)
                        val maxH = setup.contentHeight * 0.94f - paraAfter
                        if (h > maxH) {
                            h = maxH
                            w = iw * (h / ih)
                        }
                        lines.add(Line(Line.IMAGE, blockStart, blockStart + 2, (width - w) / 2f, y, y + h, y + h, image = path, imageW = w, imageH = h, block = index, firstInBlock = true, lastInBlock = true))
                        y += h
                    } else {
                        val h = fontPx * 0.8f
                        lines.add(Line(Line.ORNAMENT, blockStart, blockStart + 2, 0f, y, y + h / 2, y + h, block = index, firstInBlock = true, lastInBlock = true))
                        y += h
                    }
                    pendingGap = fontPx * 0.4f
                    offset += 2
                    return@forEachIndexed
                }
                BlockKind.RULE -> {
                    y += if (lines.isEmpty()) 0f else pendingGap
                    val h = fontPx * 1.6f
                    lines.add(Line(Line.RULE, blockStart, blockStart + 2, 0f, y, y + h / 2, y + h, block = index, firstInBlock = true, lastInBlock = true))
                    y += h
                    pendingGap = 0f
                    offset += 2
                    return@forEachIndexed
                }
                else -> {}
            }
            val style = styleFor(b)
            val text = b.text
            val size = fontPx * style.scale
            val metricsFont = font(style.mono, false, size, style.bold, style.italic, false, false, false, false)
            val lm = metricsFont.getLineMetrics("Hg", frc)
            val baseAscent = lm.ascent
            val baseDescent = lm.descent
            val lineH = (baseAscent + baseDescent) * st.lineSpacing
            val gap = if (lines.isEmpty()) 0f else maxOf(pendingGap, style.before)
            y += gap
            val quoteX = if (style.quote > 0) style.first * 0.35f else Float.NaN
            if (text.isEmpty()) {
                lines.add(Line(Line.BLANK, blockStart, blockStart + 1, 0f, y, y + lineH * 0.8f, y + lineH, heading = style.heading, block = index, firstInBlock = true, lastInBlock = true, quoteX = quoteX))
                y += lineH
                pendingGap = style.after
                offset += 1
                return@forEachIndexed
            }
            val attributed = attributed(b, text, style, size, blockStart, links)
            val first = lines.size
            var segStart = 0
            while (segStart <= text.length) {
                var segEnd = text.indexOf('\n', segStart).let { if (it < 0) text.length else it }
                if (segEnd == segStart) {
                    // An empty line from a line break: keep its height.
                    val end = blockStart + segEnd + 1
                    lines.add(Line(Line.BLANK, blockStart + segStart, end, 0f, y, y + lineH * 0.8f, y + lineH, heading = style.heading, block = index, quoteX = quoteX))
                    y += lineH
                    segStart = segEnd + 1
                    if (segEnd >= text.length) break
                    continue
                }
                val iter = attributed.getIterator(null, segStart, segEnd)
                val breaker = BreakIterator.getLineInstance(locale).let { if (st.hyphenation) it else NoSoftHyphenBreaks(it) }
                val lbm = LineBreakMeasurer(iter, breaker, frc)
                var firstLine = true
                while (lbm.position < segEnd) {
                    val lineStart = lbm.position
                    val left = if (firstLine) style.first else style.rest
                    val wrap = (width - left).coerceAtLeast(fontPx * 2)
                    var layout = lbm.nextLayout(wrap, segEnd, false) ?: break
                    var end = lbm.position
                    var hyphen = st.hyphenation && end < segEnd && text[end - 1] == '­'
                    val hyphenW = if (hyphen) hyphenWidth(size) else 0f
                    if (hyphen && layout.visibleAdvance + hyphenW > wrap) {
                        lbm.position = lineStart
                        layout = lbm.nextLayout(wrap - hyphenW, segEnd, false) ?: break
                        end = lbm.position
                        hyphen = end < segEnd && text[end - 1] == '­'
                    }
                    val last = end >= segEnd
                    val target = wrap - if (hyphen) hyphenWidth(size) else 0f
                    if (style.align == Align.JUSTIFY && !last && layout.visibleAdvance >= target * 0.72f) {
                        layout = if (hasSpace(text, lineStart, end)) runCatching { layout.getJustifiedLayout(target) }.getOrDefault(layout)
                        else justifyByTracking(attributed, lineStart, end, target - layout.visibleAdvance, size) ?: layout
                    }
                    val ltr = layout.isLeftToRight
                    val visible = layout.visibleAdvance + (if (hyphen) hyphenWidth(size) else 0f)
                    // Trailing spaces hang past the line end: on the right in left-to-right text, the left otherwise.
                    val trailing = layout.advance - layout.visibleAdvance
                    val boxL = if (ltr) left else 0f
                    val boxR = if (ltr) width else width - left
                    val x = when (style.align) {
                        Align.CENTER -> boxL + (boxR - boxL - visible) / 2f - (if (ltr) 0f else trailing)
                        Align.END -> if (ltr) boxR - visible else boxL - trailing
                        else -> if (ltr) boxL else boxR - layout.advance
                    }
                    val ascent = maxOf(baseAscent, layout.ascent)
                    val descent = maxOf(baseDescent, layout.descent)
                    val h = maxOf(lineH, ascent + descent + 1f)
                    val baseline = y + (h - ascent - descent) / 2f + ascent
                    // A line's range includes the line break that ends its segment.
                    val rangeEnd = if (last) blockStart + segEnd + 1 else blockStart + end
                    lines.add(
                        Line(
                            Line.TEXT, blockStart + lineStart, rangeEnd, x, y, baseline, y + h, layout, hyphen, style.heading, index,
                            quoteX = quoteX, hyphenFont = if (hyphen) font(style.mono, false, size, style.bold, style.italic, false, false, false, false) else null,
                        ),
                    )
                    y += h
                    firstLine = false
                }
                segStart = segEnd + 1
                if (segEnd >= text.length) break
            }
            if (lines.size > first) {
                lines[first] = lines[first].copyFlags(firstInBlock = true, lastInBlock = lines.size - 1 == first)
                if (lines.size - 1 > first) lines[lines.size - 1] = lines[lines.size - 1].copyFlags(firstInBlock = false, lastInBlock = true)
            }
            pendingGap = style.after
            offset += text.length + 1
        }
        if (lines.isEmpty()) lines.add(Line(Line.BLANK, 0, plain.length.coerceAtLeast(1), 0f, 0f, fontPx, fontPx * 1.4f))
        val arr = lines.toTypedArray()
        val pageStarts = paginate(arr, setup.contentHeight.toFloat(), plain.length)
        val metrics = ChapterMetrics(
            plain.length,
            IntArray(arr.size) { arr[it].start },
            FloatArray(arr.size) { arr[it].top },
            FloatArray(arr.size) { arr[it].bottom },
            pageStarts,
            y - (lines.last().bottom - arr.last().bottom),
        )
        return ChapterLayout(metrics, arr, links, width)
    }

    private fun Line.copyFlags(firstInBlock: Boolean, lastInBlock: Boolean) =
        Line(kind, start, end, x, top, baseline, bottom, layout, hyphen, heading, block, firstInBlock, lastInBlock, image, imageW, imageH, quoteX, hyphenFont)

    private fun Line.shifted(dy: Float) =
        Line(kind, start, end, x, top + dy, baseline + dy, bottom + dy, layout, hyphen, heading, block, firstInBlock, lastInBlock, image, imageW, imageH, quoteX, hyphenFont)

    private fun Line.withImageHeight(h: Float): Line {
        val w = imageW * h / imageH
        return Line(kind, start, end, x + (imageW - w) / 2f, top, top + h, top + h, layout, hyphen, heading, block, firstInBlock, lastInBlock, image, w, h, quoteX, hyphenFont)
    }

    private fun hasSpace(text: String, a: Int, b: Int): Boolean {
        for (i in a until minOf(b, text.length) - 1) if (text[i] == ' ' || text[i] == '　') return true
        return false
    }

    /**
     * Justifies a line without spaces (Chinese, Japanese) by spreading the extra room between characters,
     * which Java's own justification only does at spaces.
     */
    private fun justifyByTracking(attributed: AttributedString, start: Int, end: Int, extra: Float, size: Float): TextLayout? {
        val chars = end - start
        if (chars < 2 || extra <= 0.5f || extra > size * 3) return null
        val iter = attributed.getIterator(null, start, end)
        val line = AttributedString(iter)
        val sub = line.iterator
        val perChar = extra / chars
        var i = sub.beginIndex
        while (i < sub.endIndex) {
            sub.index = i
            val runEnd = sub.getRunLimit(TextAttribute.FONT)
            val font = sub.getAttribute(TextAttribute.FONT) as? Font
            if (font != null) {
                val tracking = (font.attributes[TextAttribute.TRACKING] as? Float ?: 0f) + perChar / font.size2D
                line.addAttribute(TextAttribute.FONT, font.deriveFont(mapOf(TextAttribute.TRACKING to tracking)), i - sub.beginIndex, runEnd - sub.beginIndex)
            }
            i = runEnd
        }
        return runCatching { TextLayout(line.iterator, frc) }.getOrNull()
    }

    private val hyphenWidths = HashMap<Float, Float>()
    private val fallbacks = HashMap<Boolean, Fallback>()

    private fun hyphenWidth(size: Float): Float = hyphenWidths.getOrPut(size) { TextLayout("-", font(false, false, size, false, false, false, false, false, false), frc).advance }

    private fun attributed(b: Block, text: String, style: Style, size: Float, blockStart: Int, links: MutableList<LinkRange>): AttributedString {
        val a = AttributedString(text)
        val fb = fallbacks.getOrPut(style.mono) {
            val probe = font(style.mono, false, fontPx, false, false, false, false, false, false)
            Fallback(probe, probe)
        }
        var pos = 0
        for (r in b.runs) {
            val rs = pos
            val re = pos + r.text.length
            pos = re
            if (re == rs) continue
            val bold = style.bold || r.style and RunStyle.BOLD != 0
            val italic = style.italic || (r.style and RunStyle.ITALIC != 0 && r.style and RunStyle.EMPHASIS == 0)
            val mono = style.mono || r.style and RunStyle.MONO != 0
            val sup = r.style and RunStyle.SUP != 0
            val sub = r.style and RunStyle.SUB != 0
            val runScale = if (st.publisherStyles && r.scale != 1f && b.kind != BlockKind.HEADING) r.scale.coerceIn(0.7f, 1.6f) else 1f
            val runSize = size * runScale
            fb.runs(text, rs, re) { s, e, useFallback ->
                a.addAttribute(TextAttribute.FONT, font(mono, useFallback, runSize, bold, italic, sup, sub, r.style and RunStyle.UNDERLINE != 0, r.style and RunStyle.STRIKE != 0), s, e)
            }
            if (r.style and RunStyle.MARK != 0) a.addAttribute(TextAttribute.BACKGROUND, MARK, rs, re)
            if (r.link != null) {
                a.addAttribute(TextAttribute.FOREGROUND, colors.link, rs, re)
                links.add(LinkRange(blockStart + rs, blockStart + re, r.link!!))
            }
        }
        // Tabs in preformatted text take four spaces' width instead of none.
        if (style.mono) {
            var t = text.indexOf('\t')
            while (t >= 0) {
                a.addAttribute(TextAttribute.CHAR_REPLACEMENT, SpaceGraphic(size * 0.6f * 4), t, t + 1)
                t = text.indexOf('\t', t + 1)
            }
        }
        applyRuby(a, b, text, size, style)
        return a
    }

    private fun applyRuby(a: AttributedString, b: Block, text: String, size: Float, style: Style) {
        val taken = BooleanArray(text.length)
        for (rb in b.ruby) {
            var s = rb.start.coerceIn(0, text.length)
            var e = rb.end.coerceIn(s, text.length)
            while (e > s && text[e - 1].isWhitespace()) e--
            while (s < e && text[s].isWhitespace()) s++
            if (e <= s || (s until e).any { text[it] == '\n' || taken[it] } || rb.text.isBlank()) continue
            placeRuby(a, text, s, e, rb.text, size, style)
            for (i in s until e) taken[i] = true
        }
        var k = 0
        for (r in b.runs) {
            if (r.style and RunStyle.EMPHASIS != 0) {
                for (q in k until k + r.text.length) {
                    if (q < text.length && !taken[q] && !text[q].isWhitespace() && !Character.isSurrogate(text[q])) {
                        placeRuby(a, text, q, q + 1, "・", size, style)
                        taken[q] = true
                    }
                }
            }
            k += r.text.length
        }
    }

    private fun placeRuby(a: AttributedString, text: String, s: Int, e: Int, ruby: String, size: Float, style: Style) {
        val base = text.substring(s, e)
        val primary = font(style.mono, false, size, style.bold, style.italic, false, false, false, false)
        val baseFont = if (primary.canDisplayUpTo(base) == -1) primary else font(style.mono, true, size, style.bold, style.italic, false, false, false, false)
        val rubySize = size * 0.5f
        val rubyPrimary = font(false, false, rubySize, false, false, false, false, false, false)
        val rubyFont = if (rubyPrimary.canDisplayUpTo(ruby) == -1) rubyPrimary else font(false, true, rubySize, false, false, false, false, false, false)
        a.addAttribute(TextAttribute.CHAR_REPLACEMENT, RubyGraphic(base, ruby, baseFont, rubyFont, frc), s, s + 1)
        for (i in s + 1 until e) a.addAttribute(TextAttribute.CHAR_REPLACEMENT, EmptyGraphic(), i, i + 1)
    }

    /**
     * Splits lines into pages. A heading is never left alone at the bottom of a page, and a paragraph
     * doesn't leave just its first line at the bottom or just its last line at the top.
     */
    private fun paginate(lines: Array<Line>, height: Float, textLength: Int): IntArray {
        val starts = ArrayList<Int>()
        starts.add(0)
        var pageTop = lines[0].top
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.bottom - pageTop > height && i > starts.last()) {
                // An image that nearly fits shrinks into the room left instead of leaving a gap.
                val room = pageTop + height - l.top
                if (l.kind == Line.IMAGE && l.imageH > 0f && room >= maxOf(l.imageH * 0.6f, height * 0.3f)) {
                    val dy = room - l.imageH
                    lines[i] = l.withImageHeight(room)
                    for (j in i + 1 until lines.size) lines[j] = lines[j].shifted(dy)
                    i++
                    continue
                }
                var brk = i
                val prev = i - 1
                if (prev > starts.last() && lines[prev].heading && !l.heading) brk = prev
                else if (prev > starts.last()) {
                    val p = lines[prev]
                    val sameBlock = p.block == l.block && l.kind == Line.TEXT && p.kind == Line.TEXT
                    // Orphan: only the first line of a paragraph would stay behind.
                    if (sameBlock && p.firstInBlock && prev - 1 > starts.last()) brk = prev
                    // Widow: only the last line of a paragraph would move on.
                    else if (sameBlock && l.lastInBlock && !p.firstInBlock && prev - 1 > starts.last() && lines[prev - 1].block == l.block) brk = prev
                }
                starts.add(brk)
                pageTop = lines[brk].top
                i = brk + 1
                continue
            }
            i++
        }
        if (starts.size > 1 && lines[starts.last()].start >= textLength - 1 && lines[starts.last()].kind == Line.BLANK) starts.removeAt(starts.size - 1)
        return starts.toIntArray()
    }

    companion object {
        /** Fractional metrics and antialiasing, matching how pages are drawn at any scale. */
        val FRC = FontRenderContext(null, true, true)
        private val MARK = Color(0xFF, 0xD5, 0x4F, 0x55)
        private const val OPENING = "「『（〔［｛〈《【〘〖〝"
    }
}
