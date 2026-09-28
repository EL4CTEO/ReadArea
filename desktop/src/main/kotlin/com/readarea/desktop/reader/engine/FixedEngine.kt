package com.readarea.desktop.reader.engine

import com.readarea.core.theme.ReadingTheme
import com.readarea.desktop.book.FixedSource
import com.readarea.desktop.book.FixedTocEntry
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * PDFs and comics: every page is one image, rendered in the background at the screen's resolution and
 * kept in a cache bounded by memory. PDF pages can take the reading theme's colors.
 */
class FixedEngine(val source: FixedSource, val title: String, private val maxCacheBytes: Long) : PageEngine() {
    override val fixed = true
    override val chapterCount = 1
    val pages: Int = source.pageCount
    private val cropCache = HashMap<Int, Rectangle2D.Float?>()
    private val executor = Executors.newSingleThreadExecutor(ThreadFactory { r -> Thread(r, "page-render").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } })
    private val cache = object : LinkedHashMap<String, BufferedImage>(16, 0.75f, true) {}
    private var cacheBytes = 0L
    private val pending = HashSet<String>()
    @Volatile private var tint: Pair<Int, Int>? = null
    @Volatile var onPageRendered: (() -> Unit)? = null

    val outline: List<FixedTocEntry> by lazy { runCatching { source.outline() }.getOrDefault(emptyList()) }

    override fun chapterTitle(chapter: Int): String = title

    override fun configure(setup: PageSetup, theme: ReadingTheme) {
        val old = currentSetup
        currentSetup = setup
        applyTheme(theme)
        if (old == null || old.width != setup.width || old.height != setup.height || old.columns != setup.columns || old.rtl != setup.rtl ||
            old.settings.pdfCrop != setup.settings.pdfCrop || old.settings.showFooter != setup.settings.showFooter || old.scale != setup.scale || old.zoom != setup.zoom
        ) {
            clearCache()
        }
    }

    override fun applyTheme(theme: ReadingTheme) {
        val old = tint
        this.theme = theme
        val s = currentSetup
        tint = if (source.tintable && theme.id != "day" && s?.settings?.pdfInvert != false) theme.background to theme.text else null
        if (old != tint) clearCache()
    }

    private fun clearCache() = synchronized(cache) {
        cache.clear()
        cacheBytes = 0
    }

    override fun isReady(chapter: Int) = true
    override fun ensure(chapter: Int) {}
    override fun pageCount(chapter: Int) = pages
    override fun pageOf(chapter: Int, offset: Int) = offset.coerceIn(0, (pages - 1).coerceAtLeast(0))
    override fun offsetOf(pos: PagePos) = pos.page
    override fun endOffsetOf(pos: PagePos) = pos.page + 1
    override fun progress(chapter: Int, offset: Int): Float = if (pages <= 1) 1f else offset.toFloat() / (pages - 1)
    override fun locate(progress: Float): Pair<Int, Int> = 0 to (progress * (pages - 1)).toInt().coerceIn(0, (pages - 1).coerceAtLeast(0))

    private fun crop(page: Int): Rectangle2D.Float? {
        val s = currentSetup ?: return null
        if (!s.settings.pdfCrop) return null
        synchronized(cropCache) { if (cropCache.containsKey(page)) return cropCache[page] }
        val r = runCatching { source.contentBounds(page) }.getOrNull()
        synchronized(cropCache) { cropCache[page] = r }
        return r
    }

    /** The box a page is drawn into, in logical pixels. */
    fun pageArea(s: PageSetup, col: Int = 0): Rectangle2D.Float {
        val m = 8f
        val top = m + (if (s.settings.showHeader) 18f else 0f)
        val bottom = s.height - (if (s.settings.showFooter) s.footerH + s.marginV * 0.5f else m)
        if (s.columns > 1) {
            val half = s.width / 2f
            val leftSide = (col == 0) != s.rtl
            return if (leftSide) Rectangle2D.Float(m, top, half - m, bottom - top) else Rectangle2D.Float(half, top, half - m, bottom - top)
        }
        return Rectangle2D.Float(m, top, s.width - 2 * m, bottom - top)
    }

    private fun key(page: Int, w: Int, h: Int) = "$page:${w}x$h"

    private fun render(page: Int, w: Int, h: Int): BufferedImage? {
        if (page !in 0 until pages || w <= 0 || h <= 0) return null
        val img = runCatching { source.render(page, w, h, crop(page)) }.getOrNull() ?: return null
        val t = tint ?: return img
        return tintImage(img, t.first, t.second)
    }

    private fun put(key: String, img: BufferedImage) = synchronized(cache) {
        cache[key]?.let { cacheBytes -= it.width.toLong() * it.height * 4 }
        cache[key] = img
        cacheBytes += img.width.toLong() * img.height * 4
        val it = cache.entries.iterator()
        while (cacheBytes > maxCacheBytes && cache.size > 2 && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue
            cacheBytes -= e.value.width.toLong() * e.value.height * 4
            it.remove()
        }
    }

    private fun cached(key: String): BufferedImage? = synchronized(cache) { cache[key] }

    /** The rendered page if ready; otherwise starts rendering it and returns null. */
    fun pageImage(page: Int, w: Int, h: Int, onReady: (() -> Unit)? = null): BufferedImage? {
        val key = key(page, w, h)
        cached(key)?.let { return it }
        schedule(page, w, h, onReady ?: onPageRendered)
        return null
    }

    private fun schedule(page: Int, w: Int, h: Int, done: (() -> Unit)?) {
        if (page !in 0 until pages) return
        val key = key(page, w, h)
        synchronized(pending) { if (!pending.add(key)) return }
        executor.execute {
            try {
                if (cached(key) == null) render(page, w, h)?.let { put(key, it) }
            } finally {
                synchronized(pending) { pending.remove(key) }
            }
            done?.invoke()
        }
    }

    /** Renders a page now, on the calling thread. For tests and thumbnails. */
    fun renderNow(page: Int, w: Int, h: Int): BufferedImage? = cached(key(page, w, h)) ?: render(page, w, h)?.also { put(key(page, w, h), it) }

    private fun deviceSize(s: PageSetup, area: Rectangle2D.Float): Pair<Int, Int> = (area.width * s.scale).toInt().coerceAtLeast(1) to (area.height * s.scale).toInt().coerceAtLeast(1)

    override fun prefetch(pos: PagePos) {
        val s = currentSetup ?: return
        val (w, h) = deviceSize(s, pageArea(s, 0))
        val st = s.columns
        for (p in listOf(pos.page + st, pos.page + st + 1, pos.page - st, pos.page - st + 1, pos.page + 1)) {
            if (p !in 0 until pages || cached(key(p, w, h)) != null) continue
            schedule(p, w, h, null)
        }
    }

    override fun drawPage(g: Graphics2D, pos: PagePos, deco: Decorations) {
        val s = currentSetup ?: return
        val th = theme ?: return
        g.color = if (tint != null) PageChrome.color(th.background, 255) else if (th.dark) Color(0x101010) else Color(0xF2F2F2)
        g.fillRect(0, 0, s.width, s.height)
        for (col in 0 until s.columns) {
            val page = pos.page + col
            if (page >= pages) break
            val area = pageArea(s, col)
            val (dw, dh) = deviceSize(s, area)
            val img = pageImage(page, dw, dh)
            // While a page renders, a blank sheet of the right shape stands in for it.
            val (pw, ph) = img?.let { it.width to it.height } ?: com.readarea.desktop.book.fitBox(scrollAspect(page), 1f, dw, dh)
            val lw = pw / s.scale
            val lh = ph / s.scale
            val leftSide = (col == 0) != s.rtl
            val dx = if (s.columns > 1) (if (leftSide) area.x + area.width - lw else area.x) else area.x + (area.width - lw) / 2f
            val dy = area.y + (area.height - lh) / 2f
            if (img != null) {
                g.drawImage(img, dx.toInt(), dy.toInt(), lw.toInt(), lh.toInt(), null)
            } else {
                g.color = if (th.dark) Color(0x202020) else Color.WHITE
                g.fill(Rectangle2D.Float(dx, dy, lw, lh))
            }
            PageChrome.drawChrome(g, s, th, null, "${page + 1} / $pages", progress(0, page), PagePos(0, page) in deco.bookmarkedPages, col)
        }
        PageChrome.drawSpine(g, s)
    }

    fun scrollAspect(page: Int): Float {
        val c = crop(page)
        return source.pageAspect(page) * (c?.let { it.width / it.height } ?: 1f)
    }

    override fun scrollHeight(pos: PagePos): Float {
        val s = currentSetup ?: return 0f
        return s.width * s.zoom.coerceAtMost(1f) / scrollAspect(pos.page) + 8f
    }

    override fun drawScrollSlice(g: Graphics2D, pos: PagePos, deco: Decorations) {
        val s = currentSetup ?: return
        val w = s.width * s.zoom.coerceAtMost(1f)
        val h = scrollHeight(pos) - 8f
        val x = (s.width - w) / 2f
        val img = pageImage(pos.page, (w * s.scale).toInt(), (h * s.scale).toInt())
        if (img != null) g.drawImage(img, x.toInt(), 0, w.toInt(), h.toInt(), null)
        else {
            g.color = if (theme?.dark == true) Color(0x202020) else Color.WHITE
            g.fill(Rectangle2D.Float(x, 0f, w, h))
        }
    }

    /** Page text for search, extracted in the background. */
    fun pageText(page: Int): String? = source.pageText(page)

    override fun close() {
        executor.shutdownNow()
        clearCache()
        runCatching { source.close() }
    }

    companion object {
        /** Maps white to the theme's page color and black to its text color, keeping the shades between. */
        fun tintImage(src: BufferedImage, bg: Int, fg: Int): BufferedImage {
            val w = src.width
            val h = src.height
            val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val row = IntArray(w)
            val br = (bg shr 16) and 0xFF
            val bgG = (bg shr 8) and 0xFF
            val bb = bg and 0xFF
            val fr = (fg shr 16) and 0xFF
            val fgG = (fg shr 8) and 0xFF
            val fb = fg and 0xFF
            val lut = IntArray(256) { l ->
                val k = l / 255f
                val r = (fr + (br - fr) * k).toInt()
                val gg = (fgG + (bgG - fgG) * k).toInt()
                val b = (fb + (bb - fb) * k).toInt()
                (r shl 16) or (gg shl 8) or b
            }
            for (y in 0 until h) {
                src.getRGB(0, y, w, 1, row, 0, w)
                for (x in 0 until w) {
                    val c = row[x]
                    val l = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
                    row[x] = lut[l]
                }
                out.setRGB(0, y, w, 1, row, 0, w)
            }
            return out
        }
    }
}
