package com.readarea.desktop.reader.engine

import com.readarea.core.theme.ReadingTheme
import com.readarea.desktop.data.ReaderSettings
import java.awt.Graphics2D

/** Threads for laying out chapters in the background: most of the cores, leaving one for the window. */
private val LAYOUT_THREADS = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 6)

private val layoutPool = java.util.concurrent.Executors.newFixedThreadPool(LAYOUT_THREADS) { r ->
    Thread(r, "chapter-layout").apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY - 1
    }
}

data class PagePos(val chapter: Int, val page: Int)

/**
 * The geometry of a page, in logical pixels. One setup is one layout: any change here (other than colors)
 * means paginating again.
 */
class PageSetup(
    val width: Int,
    val height: Int,
    val settings: ReaderSettings,
    val columns: Int = 1,
    val scrollMode: Boolean = false,
    val rtl: Boolean = false,
    val vertical: Boolean = false,
    /** Device pixels per logical pixel, so images are decoded sharp enough for HiDPI screens. */
    val scale: Float = 1f,
    val zoom: Float = 1f,
) {
    val marginH: Float = settings.marginH * zoom
    val marginV: Float = settings.marginV * zoom
    val headerH: Float = if (settings.showHeader && !scrollMode) 26f else 0f
    val footerH: Float = if (settings.showFooter && !scrollMode) 26f else 0f
    val gutter: Float = if (columns > 1) maxOf(marginH * 1.6f, 48f) else 0f
    private val rawColumn: Float = (width - 2 * marginH - gutter * (columns - 1)) / columns
    private val maxColumn: Float = settings.columnWidth * zoom
    val sideExtra: Float = if (rawColumn > maxColumn) (rawColumn - maxColumn) / 2f else 0f
    val contentWidth: Int = (rawColumn - 2 * sideExtra).toInt().coerceAtLeast(120)
    val contentLeft: Float = marginH + sideExtra
    val contentTop: Float = if (scrollMode) 0f else marginV + headerH
    val contentHeight: Int = if (scrollMode) height else (height - contentTop - marginV - footerH).toInt().coerceAtLeast(120)
    val fontPx: Float = settings.fontSize * zoom

    fun columnLeft(col: Int): Float = contentLeft + (if (rtl) columns - 1 - col else col) * (rawColumn + gutter)

    /** Which column of a spread [x] falls in, counting in reading order. */
    fun columnAt(x: Float): Int {
        if (columns < 2) return 0
        val right = x > width / 2f
        return if (right != rtl) 1 else 0
    }

    fun sameLayout(o: PageSetup?): Boolean {
        if (o == null) return false
        return width == o.width && height == o.height && columns == o.columns && scrollMode == o.scrollMode && rtl == o.rtl &&
            vertical == o.vertical && zoom == o.zoom && settings.layoutKey() == o.settings.layoutKey()
    }
}

data class HighlightRange(val chapter: Int, val start: Int, val end: Int, val color: Int)

/** What's drawn over the text: highlights, the current search hit, the sentence being read aloud. */
class Decorations {
    @Volatile var highlights: List<HighlightRange> = emptyList()
    @Volatile var search: HighlightRange? = null
    @Volatile var speaking: HighlightRange? = null
    @Volatile var selection: HighlightRange? = null
    @Volatile var bookTitle: String = ""
    @Volatile var bookmarkedPages: Set<PagePos> = emptySet()
}

/** One laid-out chapter: pages over a range of chapter offsets. */
interface PagedText {
    val pageCount: Int
    fun startOffset(page: Int): Int
    fun endOffset(page: Int): Int
    fun pageOf(offset: Int): Int
}

/**
 * Turns a book into pages. Text books are laid out chapter by chapter in the background ([ensure]);
 * PDFs and comics already are pages. Drawing happens on the UI thread from finished layouts.
 */
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
    abstract fun drawPage(g: Graphics2D, pos: PagePos, deco: Decorations)
    abstract fun scrollHeight(pos: PagePos): Float
    abstract fun drawScrollSlice(g: Graphics2D, pos: PagePos, deco: Decorations)
    open fun prefetch(pos: PagePos) {}
    open fun close() {}

    val setup: PageSetup? get() = currentSetup
    @Volatile protected var currentSetup: PageSetup? = null
    @Volatile var theme: ReadingTheme? = null
        protected set

    fun allReady(): Boolean = (0 until chapterCount).all { isReady(it) }

    /**
     * Lays out [chapters] in the given order, spread over a few background threads: chapters are
     * independent, and the engine drops any result laid out for settings that have since changed.
     * Returns early once [cancelled] says so.
     */
    fun ensureAll(chapters: List<Int>, cancelled: () -> Boolean) {
        if (chapters.isEmpty()) return
        val next = java.util.concurrent.atomic.AtomicInteger()
        val work = Runnable {
            while (!cancelled()) {
                val i = next.getAndIncrement()
                if (i >= chapters.size) break
                ensure(chapters[i])
            }
        }
        val helpers = (1 until minOf(LAYOUT_THREADS, chapters.size)).map { layoutPool.submit(work) }
        work.run()
        helpers.forEach { runCatching { it.get() } }
    }

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

    /** The page a global page number (1-based) is on, once every chapter is laid out. */
    fun fromGlobal(page: Int): PagePos? {
        if (!allReady()) return null
        var rest = page - 1
        for (c in 0 until chapterCount) {
            val n = pageCount(c)
            if (rest < n) return PagePos(c, align(rest.coerceAtLeast(0)))
            rest -= n
        }
        val last = (chapterCount - 1).coerceAtLeast(0)
        return PagePos(last, align((pageCount(last) - 1).coerceAtLeast(0)))
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

    /** The neighbouring chapter that still needs laying out before the reader can turn to it. */
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
