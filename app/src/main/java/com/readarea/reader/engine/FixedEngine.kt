package com.readarea.reader.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import com.readarea.core.FixedSource
import com.readarea.reader.ReadingTheme
import java.util.concurrent.Executors

class FixedEngine(val source: FixedSource, private val tintable: Boolean, val title: String) : PageEngine() {
    override val fixed = true
    override val chapterCount = 1
    val pages: Int = source.pageCount
    private val cropCache = HashMap<Int, RectF?>()
    private val executor = Executors.newSingleThreadExecutor()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val cache = object : LruCache<String, Bitmap>(3) {}
    private val scrollCache = object : LruCache<String, Bitmap>(6) {}
    private val pending = HashSet<String>()
    @Volatile var onPageRendered: (() -> Unit)? = null

    override fun chapterTitle(chapter: Int): String = title

    override fun configure(setup: PageSetup, theme: ReadingTheme) {
        val old = currentSetup
        currentSetup = setup
        cache.resize(if (setup.columns > 1) 7 else 4)
        applyTheme(theme)
        if (old == null || old.width != setup.width || old.height != setup.height || old.columns != setup.columns || old.settings.pdfCrop != setup.settings.pdfCrop || old.settings.showFooter != setup.settings.showFooter) {
            cache.evictAll()
            scrollCache.evictAll()
        }
    }

    override fun applyTheme(theme: ReadingTheme) {
        this.theme = theme
        val s = currentSetup
        paint.colorFilter = if (tintable && theme.id != "day" && s?.settings?.pdfInvert != false) tintFilter(theme) else null
    }

    private fun tintFilter(theme: ReadingTheme): ColorMatrixColorFilter {
        val bg = theme.background
        val fg = theme.text
        fun ch(c: Int, shift: Int) = ((c shr shift) and 0xFF).toFloat()
        val m = FloatArray(20)
        val lum = floatArrayOf(0.299f, 0.587f, 0.114f)
        for ((row, shift) in listOf(0 to 16, 1 to 8, 2 to 0)) {
            val b = ch(bg, shift)
            val f = ch(fg, shift)
            val k = (b - f) / 255f
            m[row * 5 + 0] = lum[0] * k
            m[row * 5 + 1] = lum[1] * k
            m[row * 5 + 2] = lum[2] * k
            m[row * 5 + 4] = f
        }
        m[18] = 1f
        return ColorMatrixColorFilter(ColorMatrix(m))
    }

    override fun isReady(chapter: Int) = true
    override fun ensure(chapter: Int) {}
    override fun pageCount(chapter: Int) = pages
    override fun pageOf(chapter: Int, offset: Int) = offset.coerceIn(0, (pages - 1).coerceAtLeast(0))
    override fun offsetOf(pos: PagePos) = pos.page
    override fun endOffsetOf(pos: PagePos) = pos.page + 1
    override fun progress(chapter: Int, offset: Int): Float = if (pages <= 1) 1f else offset.toFloat() / (pages - 1)
    override fun locate(progress: Float): Pair<Int, Int> = 0 to (progress * (pages - 1)).toInt().coerceIn(0, (pages - 1).coerceAtLeast(0))

    private fun crop(page: Int): RectF? {
        val s = currentSetup ?: return null
        if (!s.settings.pdfCrop) return null
        synchronized(cropCache) { if (cropCache.containsKey(page)) return cropCache[page] }
        val r = runCatching { source.contentBounds(page) }.getOrNull()
        synchronized(cropCache) { cropCache[page] = r }
        return r
    }

    private fun pageArea(s: PageSetup, col: Int = 0): RectF {
        val m = 4 * s.density
        val bottom = s.height - s.bottomInset - (if (s.settings.showFooter) s.footerH + s.marginV * 0.6f else m)
        if (s.columns > 1) {
            val half = s.width / 2f
            val gap = maxOf(s.hingeGap / 2f, m)
            return if (col == 0) RectF(m, s.topInset + m, half - gap, bottom) else RectF(half + gap, s.topInset + m, s.width - m, bottom)
        }
        return RectF(m, s.topInset + m, s.width - m, bottom)
    }

    private fun render(page: Int, w: Int, h: Int): Bitmap? {
        if (page !in 0 until pages || w <= 0 || h <= 0) return null
        val c = crop(page)
        val aspect = source.pageAspect(page) * (c?.let { it.width() / it.height() } ?: 1f)
        var bw = w
        var bh = (w / aspect).toInt()
        if (bh > h) {
            bh = h
            bw = (h * aspect).toInt()
        }
        val bmp = Bitmap.createBitmap(bw.coerceAtLeast(1), bh.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        runCatching { source.render(page, bmp, c) }
        return bmp
    }

    fun pageBitmap(page: Int): Bitmap? {
        val s = currentSetup ?: return null
        val area = pageArea(s, 0)
        val key = "$page:${area.width().toInt()}x${area.height().toInt()}"
        cache.get(key)?.let { return it }
        val bmp = synchronized(this) { cache.get(key) ?: render(page, area.width().toInt(), area.height().toInt())?.also { cache.put(key, it) } }
        return bmp
    }

    override fun prefetch(pos: PagePos) {
        val s = currentSetup ?: return
        val area = pageArea(s, 0)
        val st = s.columns
        for (p in listOf(pos.page + st, pos.page + st + 1, pos.page - st, pos.page - st + 1, pos.page + 1)) {
            if (p !in 0 until pages) continue
            val key = "$p:${area.width().toInt()}x${area.height().toInt()}"
            if (cache.get(key) != null) continue
            synchronized(pending) { if (!pending.add(key)) continue }
            executor.execute {
                try {
                    synchronized(this) {
                        if (cache.get(key) == null) render(p, area.width().toInt(), area.height().toInt())?.let { cache.put(key, it) }
                    }
                } finally {
                    synchronized(pending) { pending.remove(key) }
                }
            }
        }
    }

    override fun drawPage(canvas: Canvas, pos: PagePos, deco: Decorations) {
        val s = currentSetup ?: return
        val th = theme ?: return
        canvas.drawColor(if (paint.colorFilter != null) th.background else if (th.dark) 0xFF101010.toInt() else 0xFFF2F2F2.toInt())
        for (col in 0 until s.columns) {
            val page = pos.page + col
            if (page >= pages) break
            val area = pageArea(s, col)
            val bmp = pageBitmap(page)
            if (bmp != null) {
                val dx = if (s.columns > 1) (if (col == 0) area.right - bmp.width else area.left) else area.left + (area.width() - bmp.width) / 2f
                val dy = area.top + (area.height() - bmp.height) / 2f
                dst.set(dx, dy, dx + bmp.width, dy + bmp.height)
                canvas.drawBitmap(bmp, null, dst, paint)
            }
            PageChrome.drawChrome(canvas, s, th, null, "${page + 1} / $pages", progress(0, page), deco, PagePos(0, page) in deco.bookmarkedPages, col)
        }
        PageChrome.drawSpine(canvas, s)
    }

    fun scrollAspect(page: Int): Float {
        val c = crop(page)
        return source.pageAspect(page) * (c?.let { it.width() / it.height() } ?: 1f)
    }

    override fun scrollHeight(pos: PagePos): Float {
        val s = currentSetup ?: return 0f
        return s.width / scrollAspect(pos.page) + 6 * s.density
    }

    fun scrollBitmap(page: Int, width: Int, onReady: () -> Unit): Bitmap? {
        val key = "$page@$width"
        scrollCache.get(key)?.let { return it }
        synchronized(pending) { if (!pending.add(key)) return null }
        executor.execute {
            try {
                val bmp = synchronized(this) { render(page, width, Int.MAX_VALUE / 4) }
                if (bmp != null) scrollCache.put(key, bmp)
            } finally {
                synchronized(pending) { pending.remove(key) }
            }
            onReady()
        }
        return null
    }

    fun renderRegion(page: Int, region: RectF, w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val c = crop(page) ?: RectF(0f, 0f, 1f, 1f)
        val sub = RectF(c.left + region.left * c.width(), c.top + region.top * c.height(), c.left + region.right * c.width(), c.top + region.bottom * c.height())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        synchronized(this) { runCatching { source.render(page, bmp, sub) } }
        return bmp
    }

    fun submit(task: Runnable) = executor.execute(task)

    val drawPaint: Paint get() = paint

    override fun drawScrollSlice(canvas: Canvas, pos: PagePos, deco: Decorations) {
        val s = currentSetup ?: return
        val bmp = scrollBitmap(pos.page, s.width) { onPageRendered?.invoke() }
        val h = scrollHeight(pos) - 6 * s.density
        dst.set(0f, 0f, s.width.toFloat(), h)
        if (bmp != null) canvas.drawBitmap(bmp, null, dst, paint) else {
            val p = Paint()
            p.color = if (theme?.dark == true) 0xFF202020.toInt() else 0xFFFFFFFF.toInt()
            canvas.drawRect(dst, p)
        }
    }

    override fun close() {
        executor.shutdownNow()
        cache.evictAll()
        scrollCache.evictAll()
        source.close()
    }
}
