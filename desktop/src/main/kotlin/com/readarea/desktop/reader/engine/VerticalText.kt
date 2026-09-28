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
import java.awt.Shape
import java.awt.font.TextAttribute
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Rules of vertical Japanese and Chinese typesetting: which characters stay upright, line-start rules, forms. */
object Vertical {
    const val UPRIGHT: Byte = 0
    const val ROTATED: Byte = 1
    const val TCY: Byte = 2
    const val IMAGE: Byte = 3
    const val RULE: Byte = 4
    const val SPACE: Byte = 5
    const val BREAK: Byte = 6

    const val SESAME = "﹅"

    private const val NO_START = "、。，．・：；？！゛゜ヽヾゝゞ々〻ー」』）〕］｝〉》】〙〗〟’”»゠–〜～‼⁇⁈⁉ぁぃぅぇぉっゃゅょゎゕゖァィゥェォッャュョヮヵヶㇰㇱㇲㇳㇴㇵㇶㇷㇸㇹㇺㇻㇼㇽㇾㇿ…‥,.:;!?)]}"
    private const val NO_END = "「『（〔［｛〈《【〘〖〝‘“«([{"
    private const val HANG = "、。，．,."
    private const val SMALL = "ぁぃぅぇぉっゃゅょゎゕゖァィゥェォッャュョヮヵヶㇰㇱㇲㇳㇴㇵㇶㇷㇸㇹㇺㇻㇼㇽㇾㇿ"
    private const val OPENING = "「『（〔［｛〈《【〘〖〝"

    /** Punctuation drawn with its vertical presentation form, since Java2D can't apply the font's 'vert' feature. */
    private val FORMS = mapOf(
        '、' to '︑', '。' to '︒', '，' to '︐', '：' to '︓', '；' to '︔', '！' to '︕', '？' to '︖',
        '「' to '﹁', '」' to '﹂', '『' to '﹃', '』' to '﹄', '（' to '︵', '）' to '︶', '｛' to '︷',
        '｝' to '︸', '〔' to '︹', '〕' to '︺', '【' to '︻', '】' to '︼', '《' to '︽', '》' to '︾',
        '〈' to '︿', '〉' to '﹀', '［' to '﹇', '］' to '﹈', '…' to '︙', '‥' to '︰', '—' to '︱',
        '―' to '︱', '–' to '︲', '〖' to '︗', '〗' to '︘',
    )

    /** Upright characters whose shape still has to turn a quarter: the long vowel mark and wave dashes. */
    private const val TURN = "ー〜～―─"

    fun upright(cp: Int): Boolean = when {
        cp < 0x1100 -> false
        cp <= 0x11FF -> true
        cp == 0x2013 || cp == 0x2014 || cp == 0x2015 || cp == 0x2016 || cp == 0x2025 || cp == 0x2026 || cp == 0x203B || cp == 0x2E3A || cp == 0x2E3B -> true
        cp in 0x2460..0x24FF -> true
        cp in 0x25A0..0x27BF -> true
        cp in 0x2B50..0x2B59 -> true
        cp in 0x2E80..0xA4CF -> true
        cp in 0xA960..0xA97F -> true
        cp in 0xAC00..0xD7FF -> true
        cp in 0xF900..0xFAFF -> true
        cp in 0xFE10..0xFE1F -> true
        cp in 0xFE30..0xFE6F -> true
        cp in 0xFF00..0xFFEF -> true
        cp in 0x1F000..0x1FAFF -> true
        cp in 0x20000..0x3FFFF -> true
        else -> false
    }

    /** The character to draw for [text][start, end) set vertically, and whether it must be turned. */
    fun glyph(text: String, start: Int, end: Int, font: Font): Pair<String, Boolean> {
        if (end - start == 1) {
            val c = text[start]
            if (c == '⸺') return "︱︱" to false
            FORMS[c]?.let { f -> if (font.canDisplay(f)) return f.toString() to false }
            if (TURN.indexOf(c) >= 0) return c.toString() to true
        }
        return text.substring(start, end) to false
    }

    fun isSmallKana(c: Char): Boolean = SMALL.indexOf(c) >= 0
    fun noStart(c: Char): Boolean = NO_START.indexOf(c) >= 0
    fun noEnd(c: Char): Boolean = NO_END.indexOf(c) >= 0
    fun hangs(c: Char): Boolean = HANG.indexOf(c) >= 0
    fun opening(c: Char): Boolean = OPENING.indexOf(c) >= 0

    fun isTcy(text: String, start: Int, end: Int): Boolean {
        val n = end - start
        if (n == 1) return text[start] in "!?0123456789"
        if (n != 2) return false
        val a = text[start]
        val b = text[start + 1]
        if (a in '0'..'9' && b in '0'..'9') return true
        return a in "!?" && b in "!?"
    }
}

class VImage(val path: String, val width: Float, val height: Float)

class VRuby(val col: Int, val y0: Float, val y1: Float, val text: String, val size: Float, val baseSize: Float)

/** Font lookups shared by layout and drawing, so both measure the same way. */
private class VFonts(private val base: Font, private val letterSpacing: Float) {
    private val cache = HashMap<Long, Font>()

    fun get(size: Float, style: Int, rotated: Boolean): Font {
        val mono = style and RunStyle.MONO != 0
        val bold = style and RunStyle.BOLD != 0
        val italic = style and RunStyle.ITALIC != 0 && style and RunStyle.EMPHASIS == 0
        val key = ((size * 64).toLong() shl 4) or (if (mono) 1L else 0) or (if (bold) 2L else 0) or (if (italic) 4L else 0) or (if (rotated) 8L else 0)
        return cache.getOrPut(key) {
            val attrs = HashMap<TextAttribute, Any>()
            attrs[TextAttribute.SIZE] = size
            if (bold) attrs[TextAttribute.WEIGHT] = TextAttribute.WEIGHT_BOLD
            if (italic) attrs[TextAttribute.POSTURE] = TextAttribute.POSTURE_OBLIQUE
            if (rotated && letterSpacing != 0f) attrs[TextAttribute.TRACKING] = letterSpacing
            (if (mono) Font(Font.MONOSPACED, Font.PLAIN, 1) else base).deriveFont(attrs)
        }
    }

    fun width(font: Font, text: String, start: Int, end: Int): Float =
        if (end <= start) 0f else font.getStringBounds(text, start, end, TextLayoutBuilder.FRC).width.toFloat()
}

class VerticalPages(
    val text: String,
    private val pageStarts: IntArray,
    private val pageFirstCol: IntArray,
    private val colPage: IntArray,
    private val colX: FloatArray,
    private val colSize: FloatArray,
    private val colFirst: IntArray,
    private val colEnd: IntArray,
    private val itStart: IntArray,
    private val itEnd: IntArray,
    private val itY: FloatArray,
    private val itAdv: FloatArray,
    private val itPad: FloatArray,
    private val itKind: ByteArray,
    private val itSize: FloatArray,
    private val itStyle: IntArray,
    private val itRef: IntArray,
    private val refs: List<Any>,
    private val rubies: List<VRuby>,
    baseFont: Font,
    letterSpacing: Float,
    private val contentHeight: Float,
) : ChapterView {
    private val fonts = VFonts(baseFont, letterSpacing)
    override val pageCount: Int get() = pageStarts.size
    private val rubyByPage: Array<List<VRuby>> = rubies.groupBy { colPage[it.col] }.let { byPage -> Array(pageStarts.size) { p -> byPage[p].orEmpty() } }

    override fun startOffset(page: Int): Int = pageStarts[page.coerceIn(0, pageStarts.size - 1)]
    override fun endOffset(page: Int): Int = if (page + 1 < pageStarts.size) pageStarts[page + 1] else text.length

    override fun pageOf(offset: Int): Int {
        var lo = 0
        var hi = pageStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (pageStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    override fun scrollHeight(page: Int): Float = contentHeight

    private fun cols(page: Int): IntRange {
        if (page !in pageFirstCol.indices) return IntRange.EMPTY
        val first = pageFirstCol[page]
        val end = if (page + 1 < pageFirstCol.size) pageFirstCol[page + 1] else colX.size
        return first until end
    }

    override fun drawScroll(g: Graphics2D, page: Int, left: Float, ctx: DrawContext) = draw(g, page, left, 0f, ctx)

    override fun draw(g: Graphics2D, page: Int, left: Float, top: Float, ctx: DrawContext) {
        val start = startOffset(page)
        val end = endOffset(page)
        val deco = ctx.deco
        val theme = ctx.theme
        fun fill(a: Int, b: Int, color: Color) {
            val s = shape(page, a, b, left, top) ?: return
            g.color = color
            g.fill(s)
        }
        val alpha = if (theme.dark) 0x55 else 0x70
        for (h in deco.highlights) if (h.chapter == ctx.chapter && h.end > start && h.start < end) fill(maxOf(h.start, start), minOf(h.end, end), PageChrome.color(h.color, alpha))
        deco.search?.let { h -> if (h.chapter == ctx.chapter && h.end > start && h.start < end) fill(maxOf(h.start, start), minOf(h.end, end), PageChrome.color(theme.accent, 0x66)) }
        deco.speaking?.let { h -> if (h.chapter == ctx.chapter && h.end > start && h.start < end) fill(maxOf(h.start, start), minOf(h.end, end), PageChrome.color(theme.accent, 0x33)) }
        deco.selection?.let { h -> if (h.chapter == ctx.chapter && h.end > start && h.start < end) fill(maxOf(h.start, start), minOf(h.end, end), PageChrome.color(theme.accent, 0x4C)) }

        val textColor = PageChrome.color(theme.text, 255)
        val linkColor = ctx.colors.link.color
        for (c in cols(page)) {
            val cx = left + colX[c]
            for (i in colFirst[c] until colEnd[c]) {
                if (itStyle[i] and RunStyle.MARK != 0) {
                    g.color = Color(0xFF, 0xD5, 0x4F, 0x55)
                    val w = itSize[i] * 0.62f
                    g.fill(Rectangle2D.Float(cx - w, top + itY[i], w * 2, itAdv[i]))
                }
            }
            for (i in colFirst[c] until colEnd[c]) {
                val kind = itKind[i]
                val size = itSize[i]
                val st = itStyle[i]
                val y = top + itY[i]
                val linked = itRef[i] >= 0 && refs[itRef[i]] is String
                val color = if (linked) linkColor else textColor
                val shift = if (st and RunStyle.SUP != 0) size * 0.35f else if (st and RunStyle.SUB != 0) -size * 0.35f else 0f
                when (kind) {
                    Vertical.UPRIGHT -> {
                        val font = fonts.get(size, st, false)
                        g.font = font
                        g.color = color
                        val (glyph, turn) = Vertical.glyph(text, itStart[i], itEnd[i], font)
                        val w = fonts.width(font, glyph, 0, glyph.length)
                        if (turn) {
                            drawTurned(g, glyph, font, cx + shift, y + itPad[i] + (size - w) / 2f)
                        } else {
                            var dx = 0f
                            var dy = 0f
                            if (glyph.length == 1 && Vertical.isSmallKana(glyph[0])) {
                                dx = size * 0.1f
                                dy = -size * 0.12f
                            }
                            g.drawString(glyph, cx - w / 2f + dx + shift, y + itPad[i] + size * 0.88f + dy)
                        }
                        if (st and RunStyle.EMPHASIS != 0) {
                            val es = size * 0.5f
                            val ef = fonts.get(es, 0, false)
                            g.font = ef
                            val ew = fonts.width(ef, Vertical.SESAME, 0, 1)
                            g.drawString(Vertical.SESAME, cx + size * 0.5f + es * 0.5f - ew / 2f, y + itPad[i] + size * 0.5f + es * 0.38f)
                        }
                    }
                    Vertical.ROTATED -> {
                        val font = fonts.get(size, st, true)
                        g.color = color
                        drawTurned(g, text.substring(itStart[i], itEnd[i]), font, cx + shift, y + itPad[i])
                    }
                    Vertical.TCY -> {
                        val font = fonts.get(size, st, false)
                        g.font = font
                        g.color = color
                        val w = fonts.width(font, text, itStart[i], itEnd[i])
                        val maxW = size * 0.96f
                        val sx = if (w > maxW) maxW / w else 1f
                        val lm = font.getLineMetrics(text, itStart[i], itEnd[i], TextLayoutBuilder.FRC)
                        val saved = g.transform
                        g.translate((cx - min(w, maxW) / 2f).toDouble(), (y + itPad[i] + size / 2f + (lm.ascent - lm.descent) / 2f).toDouble())
                        g.scale(sx.toDouble(), 1.0)
                        g.drawString(text.substring(itStart[i], itEnd[i]), 0f, 0f)
                        g.transform = saved
                    }
                    Vertical.IMAGE -> {
                        val img = refs[itRef[i]] as VImage
                        val scale = ctx.setup.scale
                        val bmp = ctx.images.get(img.path, (img.width * scale).toInt().coerceAtLeast(1), (img.height * scale).toInt().coerceAtLeast(1))
                        if (bmp != null) g.drawImage(bmp, (cx - img.width / 2f).toInt(), y.toInt(), img.width.toInt(), img.height.toInt(), null)
                    }
                    Vertical.RULE -> {
                        val r = size * 0.1f
                        val cy = y + itAdv[i] / 2f
                        g.color = PageChrome.color(theme.secondary, 150)
                        g.fill(Ellipse2D.Float(cx - r * 1.3f, cy - r * 1.3f, r * 2.6f, r * 2.6f))
                        g.fill(Ellipse2D.Float(cx - r, cy - itAdv[i] * 0.16f - r, r * 2, r * 2))
                        g.fill(Ellipse2D.Float(cx - r, cy + itAdv[i] * 0.16f - r, r * 2, r * 2))
                        g.color = PageChrome.color(theme.secondary, 70)
                        g.fill(Rectangle2D.Float(cx - r * 0.25f, y, r * 0.5f, itAdv[i] / 2 - itAdv[i] * 0.22f))
                        g.fill(Rectangle2D.Float(cx - r * 0.25f, cy + itAdv[i] * 0.22f, r * 0.5f, itAdv[i] / 2 - itAdv[i] * 0.22f))
                    }
                }
                if (kind != Vertical.IMAGE && kind != Vertical.RULE && (linked || st and (RunStyle.UNDERLINE or RunStyle.STRIKE) != 0)) {
                    g.color = if (linked) Color(color.red, color.green, color.blue, 150) else color
                    val t = max(1f, size * 0.055f)
                    val lx = if (st and RunStyle.STRIKE != 0) cx else cx - size * 0.6f
                    g.fill(Rectangle2D.Float(lx - t / 2f, y, t, itAdv[i]))
                }
            }
        }
        g.color = textColor
        for (r in rubyByPage.getOrNull(page).orEmpty()) {
            val font = fonts.get(r.size, 0, false)
            g.font = font
            val cx = left + colX[r.col] + r.baseSize * 0.5f + r.size * 0.62f
            val n = r.text.codePointCount(0, r.text.length).coerceAtLeast(1)
            val step = (r.y1 - r.y0) / n
            var k = 0
            var idx = 0
            while (k < r.text.length) {
                val cp = r.text.codePointAt(k)
                val len = Character.charCount(cp)
                val (glyph, turn) = Vertical.glyph(r.text, k, k + len, font)
                val w = fonts.width(font, glyph, 0, glyph.length)
                val cellTop = top + r.y0 + step * idx + (step - r.size) / 2f
                if (Vertical.upright(cp) && !turn) {
                    var dx = 0f
                    var dy = 0f
                    if (glyph.length == 1 && Vertical.isSmallKana(glyph[0])) {
                        dx = r.size * 0.1f
                        dy = -r.size * 0.12f
                    }
                    g.drawString(glyph, cx - w / 2f + dx, cellTop + r.size * 0.88f + dy)
                } else {
                    drawTurned(g, glyph, font, cx, cellTop + (r.size - w) / 2f)
                }
                k += len
                idx++
            }
        }
    }

    /** Draws [s] turned a quarter clockwise, centered on the column at [cx], starting at [y]. */
    private fun drawTurned(g: Graphics2D, s: String, font: Font, cx: Float, y: Float) {
        val lm = font.getLineMetrics(s, TextLayoutBuilder.FRC)
        val saved = g.transform
        g.font = font
        g.translate(cx.toDouble(), y.toDouble())
        g.rotate(Math.PI / 2)
        g.drawString(s, 0f, (lm.ascent - lm.descent) / 2f)
        g.transform = saved
    }

    private fun yAt(i: Int, offset: Int): Float {
        val s = itStart[i]
        val e = itEnd[i]
        if (offset <= s) return itY[i]
        if (offset >= e) return itY[i] + itAdv[i]
        if (itKind[i] == Vertical.ROTATED) return itY[i] + itPad[i] + fonts.width(fonts.get(itSize[i], itStyle[i], true), text, s, offset)
        return itY[i] + itAdv[i] * (offset - s) / (e - s)
    }

    private fun columnAt(page: Int, x: Float): Int? {
        var best: Int? = null
        var bestD = Float.MAX_VALUE
        for (c in cols(page)) {
            if (colEnd[c] <= colFirst[c]) continue
            val d = abs(colX[c] - x)
            if (d < bestD) {
                bestD = d
                best = c
            }
        }
        return best
    }

    override fun offsetAt(page: Int, x: Float, y: Float): Int? {
        val c = columnAt(page, x) ?: return null
        val first = colFirst[c]
        val last = colEnd[c] - 1
        if (y <= itY[first]) return itStart[first]
        for (i in first..last) {
            if (y < itY[i] + itAdv[i]) {
                if (itKind[i] == Vertical.ROTATED) {
                    val s = itStart[i]
                    val e = itEnd[i]
                    var o = s
                    while (o < e && yAt(i, o + 1) <= y) o++
                    return o
                }
                return if (y - itY[i] < itAdv[i] / 2f) itStart[i] else itEnd[i]
            }
        }
        return itEnd[last]
    }

    private fun itemAt(page: Int, x: Float, y: Float, slop: Float): Int? {
        val c = columnAt(page, x) ?: return null
        if (abs(colX[c] - x) > colSize[c] * 0.8f + slop) return null
        for (i in colFirst[c] until colEnd[c]) if (y >= itY[i] - slop && y < itY[i] + itAdv[i] + slop) return i
        return null
    }

    override fun linkAt(page: Int, x: Float, y: Float, slop: Float): String? {
        val i = itemAt(page, x, y, slop) ?: return null
        return refs.getOrNull(itRef[i]) as? String
    }

    private fun shape(page: Int, start: Int, end: Int, dx: Float, dy: Float): Shape? {
        val area = Area()
        for (c in cols(page)) {
            val first = colFirst[c]
            val last = colEnd[c] - 1
            if (last < first || itEnd[last] <= start || itStart[first] >= end) continue
            var y0 = Float.MAX_VALUE
            var y1 = -Float.MAX_VALUE
            for (i in first..last) {
                if (itEnd[i] <= start || itStart[i] >= end) continue
                y0 = min(y0, yAt(i, start))
                y1 = max(y1, yAt(i, end))
            }
            if (y1 <= y0) continue
            val w = colSize[c] * 0.62f
            area.add(Area(Rectangle2D.Float(dx + colX[c] - w, dy + y0, w * 2, y1 - y0)))
        }
        return if (area.isEmpty) null else area
    }

    override fun rangeShape(page: Int, start: Int, end: Int): Shape? = shape(page, start, end, 0f, 0f)

    override fun caretPoint(page: Int, offset: Int, end: Boolean): Point2D.Float? {
        for (c in cols(page)) {
            val first = colFirst[c]
            val last = colEnd[c] - 1
            if (last < first) continue
            val s = itStart[first]
            val e = itEnd[last]
            val inside = if (end) offset > s && offset <= e else offset >= s && offset < e
            if (!inside) continue
            for (i in first..last) {
                val hit = if (end) offset > itStart[i] && offset <= itEnd[i] else offset >= itStart[i] && offset < itEnd[i]
                if (hit) {
                    val w = colSize[c] * 0.62f
                    return Point2D.Float(if (end) colX[c] + w else colX[c] - w, yAt(i, offset))
                }
            }
        }
        return null
    }
}

/**
 * Sets a chapter in vertical columns read right to left, with furigana to the right of the base text,
 * line-start and line-end rules (kinsoku), hanging punctuation and horizontal numbers (tate-chū-yoko).
 */
class VerticalBuilder(setup: PageSetup, baseFont: Font, private val images: ImageCache) {
    private val width = setup.contentWidth.toFloat()
    private val height = setup.contentHeight.toFloat()
    private val fontPx = setup.fontPx
    private val st = setup.settings
    private val lineSpacing = st.lineSpacing
    private val letterSpacing = st.letterSpacing
    private val paragraphSpacing = st.paragraphSpacing
    private val indent = st.indent
    private val justify = st.justify
    private val publisherStyles = st.publisherStyles
    private val font: Font = if (baseFont.canDisplay('中') && baseFont.canDisplay('あ')) baseFont else Font(Font.SERIF, Font.PLAIN, 1)
    private val fonts = VFonts(font, letterSpacing)

    private class FList {
        var a = FloatArray(256)
        var n = 0
        fun add(v: Float) {
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }
        fun out(): FloatArray = a.copyOf(n)
    }

    private class IList {
        var a = IntArray(256)
        var n = 0
        fun add(v: Int) {
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }
        fun out(): IntArray = a.copyOf(n)
    }

    private class BList {
        var a = ByteArray(256)
        var n = 0
        fun add(v: Byte) {
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }
        fun out(): ByteArray = a.copyOf(n)
    }

    private val sb = StringBuilder()
    private val pageFirstCol = IList()
    private val colPage = IList()
    private val colX = FList()
    private val colSize = FList()
    private val colFirst = IList()
    private val colEnd = IList()
    private val itStart = IList()
    private val itEnd = IList()
    private val itY = FList()
    private val itAdv = FList()
    private val itPad = FList()
    private val itKind = BList()
    private val itSize = FList()
    private val itStyle = IList()
    private val itRef = IList()
    private val refs = ArrayList<Any>()
    private val linkIds = HashMap<String, Int>()
    private val rubies = ArrayList<VRuby>()
    private var page = 0
    private var xRight = width
    private var pageHasContent = false

    fun build(chapter: Chapter): VerticalPages {
        pageFirstCol.add(0)
        for (b in chapter.blocks) {
            when (b.kind) {
                BlockKind.IMAGE -> image(b)
                BlockKind.RULE -> rule()
                else -> textBlock(b)
            }
        }
        if (!pageHasContent && page > 0) {
            pageFirstCol.n--
            page--
        }
        val firstCols = pageFirstCol.out()
        val colFirstArr = colFirst.out()
        val starts = IntArray(firstCols.size) { p ->
            val c = firstCols[p]
            if (c < colFirstArr.size && colFirstArr[c] < itStart.n) itStart.a[colFirstArr[c]] else if (p == 0) 0 else sb.length
        }
        starts[0] = 0
        for (p in 1 until starts.size) if (starts[p] < starts[p - 1]) starts[p] = starts[p - 1]
        return VerticalPages(
            sb.toString(), starts, firstCols, colPage.out(), colX.out(), colSize.out(), colFirstArr, colEnd.out(),
            itStart.out(), itEnd.out(), itY.out(), itAdv.out(), itPad.out(), itKind.out(), itSize.out(), itStyle.out(), itRef.out(),
            refs, rubies, font, letterSpacing, height,
        )
    }

    private fun newPage() {
        page++
        xRight = width
        pageFirstCol.add(colX.n)
        pageHasContent = false
    }

    private fun newColumn(pitch: Float, size: Float): Int {
        if (xRight - pitch < -0.5f && pageHasContent) newPage()
        colPage.add(page)
        colX.add(xRight - pitch / 2f)
        colSize.add(size)
        colFirst.add(itStart.n)
        colEnd.add(itStart.n)
        xRight -= pitch
        pageHasContent = true
        return colX.n - 1
    }

    private fun gap(px: Float) {
        if (pageHasContent) xRight -= px
    }

    private fun addItem(col: Int, start: Int, end: Int, y: Float, adv: Float, pad: Float, kind: Byte, size: Float, style: Int, ref: Int) {
        itStart.add(start)
        itEnd.add(end)
        itY.add(y)
        itAdv.add(adv)
        itPad.add(pad)
        itKind.add(kind)
        itSize.add(size)
        itStyle.add(style)
        itRef.add(ref)
        colEnd.a[col] = itStart.n
    }

    private fun image(b: Block) {
        val start = sb.length
        sb.append(BookText.OBJ).append('\n')
        val path = b.image
        val size = path?.let { images.size(it) }
        if (path == null || size == null) {
            val col = newColumn(fontPx * 2f, fontPx)
            addItem(col, start, start + 1, height * 0.44f, height * 0.12f, 0f, Vertical.RULE, fontPx, 0, -1)
            return
        }
        val iw = size.width.toFloat()
        val ih = size.height.toFloat()
        var w = iw
        if (w > width) w = width
        if (iw >= 300 && w < width * 0.6f) w = width * 0.6f
        var h = ih * (w / iw)
        if (h > height * 0.96f) {
            h = height * 0.96f
            w = iw * (h / ih)
        }
        val pitch = w + fontPx * 0.8f
        if (xRight - pitch < 0f && pageHasContent) newPage()
        val col = newColumn(pitch, w / 1.24f)
        refs.add(VImage(path, w, h))
        addItem(col, start, start + 1, (height - h) / 2f, h, 0f, Vertical.IMAGE, w, 0, refs.size - 1)
    }

    private fun rule() {
        val start = sb.length
        sb.append(BookText.OBJ).append('\n')
        gap(fontPx * 0.5f)
        val col = newColumn(fontPx * 1.6f, fontPx)
        addItem(col, start, start + 1, height * 0.32f, height * 0.36f, 0f, Vertical.RULE, fontPx, 0, -1)
        gap(fontPx * 0.5f)
    }

    private fun textBlock(b: Block) {
        val base = sb.length
        val heading = b.kind == BlockKind.HEADING
        val scale = when {
            heading -> when (b.level) {
                1 -> 1.45f
                2 -> 1.3f
                3 -> 1.15f
                else -> 1.05f
            }
            b.kind == BlockKind.CAPTION || b.kind == BlockKind.PRE -> 0.85f
            else -> 1f
        }
        val size = fontPx * scale
        val pitch = max(size * 1.4f, size * lineSpacing * 1.12f)
        val n = b.length
        val style = IntArray(n)
        val sizes = FloatArray(n)
        val ref = IntArray(n) { -1 }
        var k = 0
        for (r in b.runs) {
            sb.append(r.text)
            var stl = r.style
            if (b.kind == BlockKind.CAPTION) stl = stl or RunStyle.ITALIC
            var cs = size
            if (publisherStyles && r.scale != 1f && !heading) cs *= r.scale.coerceIn(0.7f, 1.6f)
            if (stl and (RunStyle.SUP or RunStyle.SUB) != 0) cs *= 0.68f
            val id = r.link?.let { l -> linkIds.getOrPut(l) { refs.add(l); refs.size - 1 } } ?: -1
            for (j in r.text.indices) {
                style[k] = stl
                sizes[k] = cs
                ref[k] = id
                k++
            }
        }
        sb.append('\n')
        val t = sb.substring(base, base + n)

        val uStart = IList()
        val uEnd = IList()
        val uKind = BList()
        val uAdv = FList()
        val uPad = FList()
        var i = 0
        while (i < n) {
            val c = t[i]
            if (c == '\n') {
                uStart.add(i); uEnd.add(i + 1); uKind.add(Vertical.BREAK); uAdv.add(0f); uPad.add(0f)
                i++
                continue
            }
            val cp = t.codePointAt(i)
            val len = Character.charCount(cp)
            if (Vertical.upright(cp)) {
                uStart.add(i); uEnd.add(i + len); uKind.add(Vertical.UPRIGHT); uAdv.add(sizes[i] * (1f + letterSpacing)); uPad.add(sizes[i] * letterSpacing / 2f)
                i += len
                continue
            }
            var j = i
            while (j < n && t[j] != '\n' && style[j] == style[i] && ref[j] == ref[i] && sizes[j] == sizes[i]) {
                val q = t.codePointAt(j)
                if (Vertical.upright(q)) break
                j += Character.charCount(q)
            }
            if (Vertical.isTcy(t, i, j)) {
                uStart.add(i); uEnd.add(j); uKind.add(Vertical.TCY); uAdv.add(sizes[i] * (1f + letterSpacing)); uPad.add(sizes[i] * letterSpacing / 2f)
                i = j
                continue
            }
            val measure = fonts.get(sizes[i], style[i], true)
            var w = i
            while (w < j) {
                var e = w
                while (e < j && t[e] != ' ' && t[e] != ' ') e++
                while (e < j && (t[e] == ' ' || t[e] == ' ')) e++
                if (e == w) e = w + 1
                var adv = fonts.width(measure, t, w, e)
                if (adv > height * 0.98f) {
                    var p = w
                    while (p < e) {
                        var q = p + 1
                        while (q < e && fonts.width(measure, t, p, q + 1) <= height * 0.95f) q++
                        adv = fonts.width(measure, t, p, q)
                        uStart.add(p); uEnd.add(q); uKind.add(Vertical.ROTATED); uAdv.add(adv); uPad.add(0f)
                        p = q
                    }
                } else {
                    val blank = t.substring(w, e).isBlank()
                    uStart.add(w); uEnd.add(e); uKind.add(if (blank) Vertical.SPACE else Vertical.ROTATED); uAdv.add(adv); uPad.add(0f)
                }
                w = e
            }
            i = j
        }
        val units = uStart.n
        val group = IntArray(units) { -1 }
        val rubyText = ArrayList<String>()
        for (r in b.ruby) {
            var rs = r.start.coerceIn(0, n)
            var re = r.end.coerceIn(0, n)
            while (re > rs && t[re - 1].isWhitespace()) re--
            while (rs < re && t[rs].isWhitespace()) rs++
            if (re <= rs) continue
            // Units run in order through the text, so the base's are found from the first one at its start
            // instead of by looking at every unit for every annotation (quadratic in dense ruby).
            var lo = 0
            var hi = units
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (uStart.a[mid] < rs) lo = mid + 1 else hi = mid
            }
            val members = ArrayList<Int>()
            var u = lo
            while (u < units && uStart.a[u] <= re) {
                if (uEnd.a[u] <= re && group[u] < 0 && uKind.a[u] != Vertical.BREAK) members.add(u)
                u++
            }
            if (members.isEmpty()) continue
            val gid = rubyText.size
            rubyText.add(r.text)
            members.forEach { group[it] = gid }
            val baseAdv = members.sumOf { uAdv.a[it].toDouble() }.toFloat()
            val rubyAdv = r.text.codePointCount(0, r.text.length) * size * 0.5f
            if (rubyAdv > baseAdv) {
                val extra = (rubyAdv - baseAdv) / members.size
                members.forEach { u ->
                    uAdv.a[u] += extra
                    uPad.a[u] += extra / 2f
                }
            }
        }

        fun pieceEnd(u: Int): Int {
            val g = group[u]
            if (g < 0) return u + 1
            var e = u + 1
            while (e < units && group[e] == g) e++
            return e
        }

        fun pieceStart(u: Int): Int {
            val g = group[u]
            if (g < 0) return u
            var s = u
            while (s > 0 && group[s - 1] == g) s--
            return s
        }

        fun firstChar(u: Int): Char = t[uStart.a[u]]
        fun lastChar(u: Int): Char = t[uEnd.a[u] - 1]

        val em = size
        val trimmed = t.trimStart()
        val startsOpen = trimmed.isNotEmpty() && (Vertical.opening(trimmed[0]) || t.isNotEmpty() && (t[0] == '　' || t[0] == ' '))
        val align = if (heading || b.kind == BlockKind.CAPTION || publisherStyles) b.align else null
        val (firstIndent, restIndent) = when (b.kind) {
            BlockKind.HEADING -> em * 2f to em * 2f
            BlockKind.QUOTE -> em * 2f * b.level.coerceIn(1, 3) to em * 2f * b.level.coerceIn(1, 3)
            BlockKind.LIST_ITEM -> em * (b.level.coerceIn(1, 5) - 1) to em * b.level.coerceIn(1, 5)
            BlockKind.VERSE -> em to em * 2f
            BlockKind.PARAGRAPH -> (if (!b.noIndent && indent > 0f && !startsOpen && align != Align.CENTER && align != Align.END) em else 0f) to 0f
            else -> 0f to 0f
        }

        if (heading) {
            if (pageHasContent && xRight - pitch - fontPx * lineSpacing * 3f < 0f) newPage()
            gap(em * 0.8f)
        }
        val limit = height
        var u = 0
        var first = true
        if (units == 0) newColumn(pitch, size)
        while (u < units) {
            val col = newColumn(pitch, size)
            val top0 = if (first) firstIndent else restIndent
            var y = top0
            var j = u
            var forced = false
            var hung = false
            while (j < units) {
                if (uKind.a[j] == Vertical.BREAK) {
                    forced = true
                    break
                }
                val pe = pieceEnd(j)
                var adv = 0f
                for (q in j until pe) adv += uAdv.a[q]
                if (y + adv <= limit + 0.5f || j == u) {
                    y += adv
                    j = pe
                    continue
                }
                if (!hung && pe == j + 1 && uKind.a[j] == Vertical.UPRIGHT && Vertical.hangs(firstChar(j))) {
                    y += adv
                    j = pe
                    hung = true
                    continue
                }
                break
            }
            if (!forced && j < units && j > u) {
                var br = j
                var guard = 0
                while (br > u && guard < 5 && uKind.a[br] != Vertical.BREAK && (Vertical.noStart(firstChar(br)) || Vertical.noEnd(lastChar(br - 1)))) {
                    if (hung && br == j) break
                    br = pieceStart(br - 1)
                    guard++
                }
                if (br > u) j = br
            }
            var used = 0f
            var pieces = 0
            run {
                var q = u
                while (q < j) {
                    val pe = pieceEnd(q)
                    for (x in q until pe) used += uAdv.a[x]
                    pieces++
                    q = pe
                }
            }
            val single = first && (j >= units || forced && j + 1 >= units)
            var startY = top0
            if (single && align == Align.CENTER) startY = max(0f, (limit - used) / 2f)
            else if (single && align == Align.END) startY = max(0f, limit - used)
            val slack = limit - top0 - used
            val spread = if (justify && !forced && j < units && pieces > 1 && slack > 0f && slack < em * 4f && !hung) slack / (pieces - 1) else 0f
            y = startY
            var q = u
            while (q < j) {
                val pe = pieceEnd(q)
                val gStart = y
                for (x in q until pe) {
                    if (uKind.a[x] != Vertical.BREAK) {
                        val s = uStart.a[x]
                        addItem(col, base + s, base + uEnd.a[x], y, uAdv.a[x], uPad.a[x], uKind.a[x], sizes[s], style[s], ref[s])
                    }
                    y += uAdv.a[x]
                }
                val g = group[q]
                if (g >= 0) rubies.add(VRuby(col, gStart, y, rubyText[g], size * 0.5f, size))
                y += spread
                q = pe
            }
            if (forced) j++
            if (j == u) j++
            u = j
            first = false
        }
        when {
            heading -> gap(em * 0.8f)
            b.kind == BlockKind.PARAGRAPH || b.kind == BlockKind.QUOTE || b.kind == BlockKind.PRE || b.kind == BlockKind.CAPTION -> if (paragraphSpacing > 0f) gap(paragraphSpacing * fontPx)
            b.kind == BlockKind.LIST_ITEM -> if (paragraphSpacing > 0f) gap(paragraphSpacing * fontPx * 0.6f)
            else -> {}
        }
    }
}
