package com.readarea.desktop.perf

import com.readarea.core.book.BookOpener
import com.readarea.core.format.BookFormat
import com.readarea.core.format.TestBooks
import com.readarea.core.text.BookText
import com.readarea.core.theme.ReadingThemes
import com.readarea.desktop.data.ReaderSettings
import com.readarea.desktop.reader.engine.Decorations
import com.readarea.desktop.reader.engine.PageChrome
import com.readarea.desktop.reader.engine.PagePos
import com.readarea.desktop.reader.engine.PageSetup
import com.readarea.desktop.reader.engine.TextEngine
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File

/**
 * Timings for what a reader feels: opening a long book, the first page, laying out the rest, drawing a
 * page, searching, and the memory it all takes. Run on demand:
 * `READAREA_BENCH=1 ./gradlew :desktop:test --tests '*PerfBench*' -i`
 */
class PerfBench {
    private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000

    private fun usedMb(): Long {
        repeat(3) { System.gc(); Thread.sleep(50) }
        val r = Runtime.getRuntime()
        return (r.totalMemory() - r.freeMemory()) / (1 shl 20)
    }

    /** A novel-length EPUB: [chapters] chapters of [paragraphs] paragraphs, about 1,000 pages. */
    private fun bigBook(chapters: Int = 60, paragraphs: Int = 120): File = TestBooks.tempFile(TestBooks.epub(chapters = chapters, paragraphs = paragraphs), "epub")

    @Test
    fun readingABigBook() {
        assumeTrue(System.getenv("READAREA_BENCH") == "1")
        val file = bigBook()
        val base = usedMb()
        repeat(2) { round ->
            var t = System.nanoTime()
            val opened = BookOpener.open(file, BookFormat.EPUB)
            val openMs = ms(t)
            t = System.nanoTime()
            val text = BookText(opened.book)
            val textMs = ms(t)
            val engine = TextEngine(opened.book, text, null, 32L shl 20)
            val s = ReaderSettings()
            engine.configure(PageSetup(900, 1100, s, scale = 2f), ReadingThemes.resolve(s.theme, s.nightTheme, false, false, s.customBg, s.customFg, s.texture))
            t = System.nanoTime()
            engine.ensure(0)
            val firstChapterMs = ms(t)
            t = System.nanoTime()
            for (c in 0 until engine.chapterCount) engine.ensure(c)
            val allMs = ms(t)
            val img = BufferedImage(1800, 2200, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.scale(2.0, 2.0)
            PageChrome.setupQuality(g)
            t = System.nanoTime()
            var pages = 0
            for (c in 0 until minOf(5, engine.chapterCount)) for (p in 0 until engine.pageCount(c)) {
                engine.drawPage(g, PagePos(c, p), Decorations())
                pages++
            }
            val drawMs = ms(t) / pages.coerceAtLeast(1).toDouble()
            g.dispose()
            t = System.nanoTime()
            val hits = text.search("candle")
            val searchMs = ms(t)
            val mem = usedMb() - base
            println(
                "BENCH round=$round chars=${(0 until text.chapterCount).sumOf { text.plainText(it).length }} pages=${engine.totalPages()} " +
                    "open=${openMs}ms text=${textMs}ms firstChapter=${firstChapterMs}ms allChapters=${allMs}ms draw=${"%.1f".format(drawMs)}ms/page " +
                    "search=${searchMs}ms(${hits.size}) heap=+${mem}MB",
            )
            engine.close()
            opened.close()
        }
    }

    @Test
    fun whereDrawingTimeGoes() {
        assumeTrue(System.getenv("READAREA_BENCH") == "1")
        val opened = BookOpener.open(bigBook(chapters = 4), BookFormat.EPUB)
        val text = BookText(opened.book)
        for ((label, settings) in listOf(
            "default" to ReaderSettings(),
            "no texture" to ReaderSettings(texture = false),
            "no justify" to ReaderSettings(texture = false, justify = false),
            "no header/footer" to ReaderSettings(texture = false, showHeader = false, showFooter = false),
        )) {
            val engine = TextEngine(opened.book, text, null, 32L shl 20)
            engine.configure(PageSetup(900, 1100, settings, scale = 2f), ReadingThemes.resolve(settings.theme, settings.nightTheme, false, false, settings.customBg, settings.customFg, settings.texture))
            engine.ensure(0)
            val img = BufferedImage(1800, 2200, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.scale(2.0, 2.0)
            PageChrome.setupQuality(g)
            repeat(3) { engine.drawPage(g, PagePos(0, 1), Decorations()) }
            var t = System.nanoTime()
            val n = 20
            repeat(n) { engine.drawPage(g, PagePos(0, 1 + it % 5), Decorations()) }
            val page = ms(t) / n.toDouble()
            t = System.nanoTime()
            repeat(n) { PageChrome.drawBackground(g, engine.theme!!, 900, 1100) }
            val bg = ms(t) / n.toDouble()
            println("BENCH draw[$label] page=${"%.1f".format(page)}ms background=${"%.1f".format(bg)}ms")
            g.dispose()
            engine.close()
        }
    }

    /** Samples [target]'s stack every few milliseconds while [block] runs and prints the hottest frames. */
    private fun profile(label: String, block: () -> Unit) {
        val target = Thread.currentThread()
        val self = HashMap<String, Int>()
        val ours = HashMap<String, Int>()
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = Thread {
            while (running.get()) {
                val st = target.stackTrace
                if (st.isNotEmpty()) {
                    val top = st[0]
                    self.merge("${top.className}.${top.methodName}", 1, Int::plus)
                    st.firstOrNull { it.className.startsWith("com.readarea") }?.let { ours.merge("${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}", 1, Int::plus) }
                }
                Thread.sleep(2)
            }
        }.apply { isDaemon = true; start() }
        block()
        running.set(false)
        sampler.join()
        val total = self.values.sum().coerceAtLeast(1)
        println("PROFILE $label: $total samples")
        self.entries.sortedByDescending { it.value }.take(15).forEach { println("PROFILE   self ${it.value * 100 / total}% ${it.key}") }
        ours.entries.sortedByDescending { it.value }.take(15).forEach { println("PROFILE   ours ${it.value * 100 / total}% ${it.key}") }
    }

    @Test
    fun whereLayoutTimeGoes() {
        assumeTrue(System.getenv("READAREA_BENCH") == "1")
        val opened = BookOpener.open(bigBook(chapters = 12), BookFormat.EPUB)
        val text = BookText(opened.book)
        val s = ReaderSettings()
        val engine = TextEngine(opened.book, text, null, 32L shl 20)
        engine.configure(PageSetup(900, 1100, s, scale = 2f), ReadingThemes.resolve(s.theme, s.nightTheme, false, false, s.customBg, s.customFg, s.texture))
        engine.ensure(0)
        engine.configure(PageSetup(901, 1100, s, scale = 2f), ReadingThemes.resolve(s.theme, s.nightTheme, false, false, s.customBg, s.customFg, s.texture))
        var t = System.nanoTime()
        profile("layout") { for (c in 0 until engine.chapterCount) engine.ensure(c) }
        println("BENCH layout ${engine.totalPages()} pages in ${ms(t)}ms on one thread")
        engine.configure(PageSetup(902, 1100, s, scale = 2f), ReadingThemes.resolve(s.theme, s.nightTheme, false, false, s.customBg, s.customFg, s.texture))
        t = System.nanoTime()
        engine.ensureAll((0 until engine.chapterCount).toList()) { false }
        println("BENCH layout ${engine.totalPages()} pages in ${ms(t)}ms with ensureAll on ${Runtime.getRuntime().availableProcessors()} cores")
    }

    @Test
    fun scanningALargeLibrary() {
        assumeTrue(System.getenv("READAREA_BENCH") == "1")
        val root = java.nio.file.Files.createTempDirectory("bench-lib").toFile()
        repeat(10) { i -> com.readarea.desktop.qa.SampleLibrary.create(File(root, "set$i")) }
        val home = java.nio.file.Files.createTempDirectory("bench-home").toFile()
        System.setProperty("readarea.home", home.path)
        val dirs = com.readarea.desktop.platform.AppDirs.resolve().init()
        com.readarea.desktop.configureRuntime(dirs)
        val settings = com.readarea.desktop.data.SettingsStore(dirs.settings)
        settings.updateApp { it.copy(folders = listOf(root.path)) }
        val db = com.readarea.desktop.data.Database(dirs.database)
        val library = com.readarea.desktop.data.Library(db, settings, dirs)
        val t = System.nanoTime()
        kotlinx.coroutines.runBlocking { library.scanAll() }
        val books = kotlinx.coroutines.runBlocking { library.read { books() } }
        println("BENCH library scan: ${books.size} books, ${books.count { it.metaLoaded }} with details, ${books.count { it.coverPath != null }} covers in ${ms(t)}ms")
        library.close()
        db.close()
        root.deleteRecursively()
        home.deleteRecursively()
    }
}
