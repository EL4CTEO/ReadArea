package com.readarea.reader.engine

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import com.readarea.data.ReaderSettings
import com.readarea.reader.ReadingTheme
import kotlin.random.Random

data class PagePos(val chapter: Int, val page: Int)

class PageSetup(
    val width: Int,
    val height: Int,
    val density: Float,
    val fontScale: Float,
    val settings: ReaderSettings,
    val topInset: Int,
    val bottomInset: Int,
    val columns: Int = 1,
    val hingeGap: Float = 0f,
    val scrollMode: Boolean = false,
) {
    val marginH: Float = settings.marginH * density
    val marginV: Float = settings.marginV * density
    val headerH: Float = if (settings.showHeader && !scrollMode) 22 * density else 0f
    val footerH: Float = if (settings.showFooter && !scrollMode) 22 * density else 0f
    val gutter: Float = if (columns > 1) maxOf(hingeGap + marginH, marginH * 2f) else 0f
    private val rawColumn: Float = (width - 2 * marginH - gutter * (columns - 1)) / columns
    private val maxColumn: Float = 700 * density
    val sideExtra: Float = if (rawColumn > maxColumn) (rawColumn - maxColumn) / 2f else 0f
    val contentWidth: Int = (rawColumn - 2 * sideExtra).toInt().coerceAtLeast(100)
    val contentLeft: Float = marginH + sideExtra
    val contentTop: Float = if (scrollMode) 0f else topInset + marginV + headerH
    val contentHeight: Int = if (scrollMode) height else (height - contentTop - bottomInset - marginV - footerH).toInt().coerceAtLeast(100)
    val fontPx: Float = settings.fontSize * density * fontScale

    fun columnLeft(col: Int): Float = contentLeft + col * (rawColumn + gutter)

    fun columnAt(x: Float): Int = if (columns > 1 && x > width / 2f) 1 else 0

    fun sameLayout(o: PageSetup?): Boolean {
        if (o == null) return false
        val a = settings
        val b = o.settings
        return width == o.width && height == o.height && topInset == o.topInset && bottomInset == o.bottomInset &&
            columns == o.columns && hingeGap == o.hingeGap && scrollMode == o.scrollMode &&
            a.fontFamily == b.fontFamily && a.fontSize == b.fontSize && a.fontWeight == b.fontWeight && a.lineSpacing == b.lineSpacing &&
            a.paragraphSpacing == b.paragraphSpacing && a.indent == b.indent && a.marginH == b.marginH && a.marginV == b.marginV &&
            a.justify == b.justify && a.hyphenation == b.hyphenation && a.letterSpacing == b.letterSpacing &&
            a.publisherStyles == b.publisherStyles && a.showHeader == b.showHeader && a.showFooter == b.showFooter &&
            a.pdfCrop == b.pdfCrop && fontScale == o.fontScale
    }
}

data class HighlightRange(val chapter: Int, val start: Int, val end: Int, val color: Int)

class Decorations {
    @Volatile var highlights: List<HighlightRange> = emptyList()
    @Volatile var search: HighlightRange? = null
    @Volatile var speaking: HighlightRange? = null
    @Volatile var clock: String = ""
    @Volatile var bookTitle: String = ""
    @Volatile var bookmarkedPages: Set<PagePos> = emptySet()
}

abstract class PageEngine {
    abstract val fixed: Boolean
    abstract val chapterCount: Int
    abstract fun chapterTitle(chapter: Int): String
    abstract fun configure(setup: PageSetup, theme: ReadingTheme)
    abstract fun applyTheme(theme: ReadingTheme)
    abstract fun isReady(chapter: Int): Boolean
    abstract fun ensure(chapter: Int)
    abstract fun pageCount(chapter: Int): Int
    abstract fun pageOf(chapter: Int, offset: Int): Int
    abstract fun offsetOf(pos: PagePos): Int
    abstract fun endOffsetOf(pos: PagePos): Int
    abstract fun progress(chapter: Int, offset: Int): Float
    abstract fun locate(progress: Float): Pair<Int, Int>
    abstract fun drawPage(canvas: Canvas, pos: PagePos, deco: Decorations)
    abstract fun scrollHeight(pos: PagePos): Float
    abstract fun drawScrollSlice(canvas: Canvas, pos: PagePos, deco: Decorations)
    open fun prefetch(pos: PagePos) {}
    open fun close() {}

    val setup: PageSetup? get() = currentSetup
    @Volatile protected var currentSetup: PageSetup? = null
    @Volatile var theme: ReadingTheme? = null
        protected set

    fun allReady(): Boolean = (0 until chapterCount).all { isReady(it) }

    fun totalPages(): Int? {
        if (!allReady()) return null
        var sum = 0
        for (c in 0 until chapterCount) sum += pageCount(c)
        return sum
    }

    fun globalPage(pos: PagePos): Int? {
        if (!allReady()) return null
        var sum = 0
        for (c in 0 until pos.chapter) sum += pageCount(c)
        return sum + pos.page + 1
    }

    val step: Int get() = if (currentSetup?.scrollMode == true) 1 else (currentSetup?.columns ?: 1)

    fun align(page: Int): Int = page - page % step

    fun next(pos: PagePos): PagePos? {
        if (!isReady(pos.chapter)) return null
        if (pos.page + step < pageCount(pos.chapter)) return PagePos(pos.chapter, pos.page + step)
        var c = pos.chapter + 1
        while (c < chapterCount) {
            if (!isReady(c)) return null
            if (pageCount(c) > 0) return PagePos(c, 0)
            c++
        }
        return null
    }

    fun prev(pos: PagePos): PagePos? {
        if (pos.page > 0) return PagePos(pos.chapter, align((pos.page - step).coerceAtLeast(0)))
        var c = pos.chapter - 1
        while (c >= 0) {
            if (!isReady(c)) return null
            if (pageCount(c) > 0) return PagePos(c, align(pageCount(c) - 1))
            c--
        }
        return null
    }

    fun neighborPending(pos: PagePos, forward: Boolean): Int? {
        if (forward) {
            if (isReady(pos.chapter) && pos.page + step < pageCount(pos.chapter)) return null
            val c = pos.chapter + 1
            return if (c < chapterCount && !isReady(c)) c else null
        }
        if (pos.page > 0) return null
        val c = pos.chapter - 1
        return if (c >= 0 && !isReady(c)) c else null
    }
}

object PageChrome {
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var textureBitmap: Bitmap? = null
    private var textureKey = 0
    private val texturePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun drawBackground(canvas: Canvas, theme: ReadingTheme, w: Int, h: Int) {
        canvas.drawColor(theme.background)
        if (theme.texture) {
            val tex = texture(theme)
            texturePaint.shader = BitmapShader(tex, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), texturePaint)
        }
    }

    @Synchronized
    private fun texture(theme: ReadingTheme): Bitmap {
        val key = theme.background
        textureBitmap?.let { if (textureKey == key) return it }
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rnd = Random(7)
        val dark = !theme.dark
        repeat(2600) {
            val x = rnd.nextFloat() * size
            val y = rnd.nextFloat() * size
            val a = rnd.nextInt(4, 14)
            p.color = if (dark) Color.argb(a, 70, 50, 20) else Color.argb(a, 255, 255, 255)
            c.drawCircle(x, y, rnd.nextFloat() * 0.9f + 0.3f, p)
        }
        p.strokeWidth = 0.6f
        repeat(140) {
            val x = rnd.nextFloat() * size
            val y = rnd.nextFloat() * size
            val len = rnd.nextFloat() * 9f + 3f
            val ang = rnd.nextFloat() * Math.PI.toFloat()
            p.color = if (dark) Color.argb(rnd.nextInt(5, 12), 90, 60, 30) else Color.argb(rnd.nextInt(4, 9), 255, 255, 255)
            c.drawLine(x, y, x + len * kotlin.math.cos(ang), y + len * kotlin.math.sin(ang), p)
        }
        textureBitmap = bmp
        textureKey = key
        return bmp
    }

    fun drawSpine(canvas: Canvas, s: PageSetup) {
        if (s.columns < 2) return
        val cx = s.width / 2f
        val w = 26 * s.density
        paint.style = Paint.Style.FILL
        paint.shader = android.graphics.LinearGradient(cx - w, 0f, cx + w, 0f, intArrayOf(0x00000000, 0x1A000000, 0x2A000000, 0x1A000000, 0x00000000), null, Shader.TileMode.CLAMP)
        canvas.drawRect(cx - w, 0f, cx + w, s.height.toFloat(), paint)
        paint.shader = null
    }

    fun drawChrome(
        canvas: Canvas,
        s: PageSetup,
        theme: ReadingTheme,
        header: String?,
        pageLabel: String,
        progress: Float,
        deco: Decorations,
        bookmarked: Boolean,
        col: Int = 0,
    ) {
        val d = s.density
        val left = if (s.columns > 1) s.columnLeft(col) else s.marginH
        val colRight = if (s.columns > 1) left + s.contentWidth else s.width - s.marginH
        val last = col == s.columns - 1
        textPaint.color = theme.secondary
        textPaint.textSize = 11.5f * d
        textPaint.typeface = null
        if (s.settings.showHeader && !header.isNullOrBlank()) {
            val y = s.topInset + s.marginV * 0.55f + 14 * d
            val avail = (colRight - left) - (if (bookmarked) 24 * d else 0f)
            val t = TextUtils.ellipsize(header, textPaint, avail, TextUtils.TruncateAt.END).toString()
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(t, (left + colRight) / 2f, y, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }
        if (bookmarked) drawRibbon(canvas, s, theme, colRight)
        if (!s.settings.showFooter) return
        val baseY = s.height - s.bottomInset - s.marginV * 0.55f - 5 * d
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(pageLabel, left, baseY, textPaint)
        if (!last) return
        var right = colRight
        textPaint.textAlign = Paint.Align.RIGHT
        val pct = "${(progress * 100).toInt().coerceIn(0, 100)}%"
        canvas.drawText(pct, right, baseY, textPaint)
        right -= textPaint.measureText(pct) + 10 * d
        if (s.settings.showClock && deco.clock.isNotEmpty()) {
            textPaint.textAlign = if (s.columns > 1) Paint.Align.RIGHT else Paint.Align.CENTER
            canvas.drawText(deco.clock, if (s.columns > 1) right else s.width / 2f, baseY, textPaint)
        }
        textPaint.textAlign = Paint.Align.LEFT
        if (s.settings.showProgressLine) {
            val y = s.height - s.bottomInset - s.marginV * 0.45f + 2 * d
            val x0 = s.marginH + s.sideExtra
            val x1 = s.width - s.marginH - s.sideExtra
            paint.style = Paint.Style.FILL
            paint.color = theme.secondary
            paint.alpha = 45
            canvas.drawRect(x0, y, x1, y + 1.2f * d, paint)
            paint.color = theme.accent
            paint.alpha = 190
            canvas.drawRect(x0, y, x0 + (x1 - x0) * progress.coerceIn(0f, 1f), y + 1.2f * d, paint)
            paint.alpha = 255
        }
    }

    private fun drawRibbon(canvas: Canvas, s: PageSetup, theme: ReadingTheme, right: Float) {
        val d = s.density
        val w = 14 * d
        val h = 26 * d
        val x = right - w
        val y = s.topInset.toFloat()
        val path = android.graphics.Path()
        path.moveTo(x, y)
        path.lineTo(x + w, y)
        path.lineTo(x + w, y + h)
        path.lineTo(x + w / 2, y + h - 6 * d)
        path.lineTo(x, y + h)
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = theme.accent
        paint.alpha = 230
        canvas.drawPath(path, paint)
        paint.alpha = 255
    }
}
