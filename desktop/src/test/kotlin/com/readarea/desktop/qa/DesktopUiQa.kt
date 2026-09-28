package com.readarea.desktop.qa

import com.readarea.core.format.BookFormat
import com.readarea.desktop.App
import com.readarea.desktop.configureRuntime
import com.readarea.desktop.data.Book
import com.readarea.desktop.data.Bookmark
import com.readarea.desktop.data.Database
import com.readarea.desktop.data.Highlight
import com.readarea.desktop.data.Library
import com.readarea.desktop.data.SettingsStore
import com.readarea.desktop.i18n.I18n
import com.readarea.desktop.platform.AppDirs
import com.readarea.desktop.reader.Panel
import com.readarea.desktop.reader.PageView
import com.readarea.desktop.reader.ReaderWindow
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.awt.Component
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.Collections
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * Runs the real app against a sample library on a (virtual) display, walks through every screen and the
 * reader, and saves screenshots to build/qa-screens. Fails on any exception thrown on the UI thread.
 * Run with a display, e.g. `xvfb-run ./gradlew :desktop:test`.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DesktopUiQa {
    companion object {
        private val out = File("build/qa-screens").apply { deleteRecursively(); mkdirs() }
        private lateinit var home: File
        private lateinit var app: App
        private lateinit var library: Library
        private val errors: MutableList<Throwable> = Collections.synchronizedList(ArrayList())

        @BeforeClass
        @JvmStatic
        fun start() {
            assumeFalse("needs a display", GraphicsEnvironment.isHeadless())
            home = Files.createTempDirectory("readarea-qa").toFile()
            System.setProperty("readarea.home", File(home, "data").path)
            Thread.setDefaultUncaughtExceptionHandler { _, e -> errors.add(e) }
            val samples = SampleLibrary.create(File(home, "Books"))
            val dirs = AppDirs.resolve().init()
            configureRuntime(dirs)
            val settings = SettingsStore(dirs.settings)
            settings.updateApp { it.copy(folders = listOf(samples.path), onboardingDone = true, reopenLastBook = false) }
            I18n.init("en")
            val db = Database(dirs.database)
            library = Library(db, settings, dirs)
            app = App(dirs, settings, db, library, null)
            onEdt { app.start(emptyList()) }
            waitUntil(60_000) { val s = library.scan.value; !s.running && library.books.value.orEmpty().size >= 15 && library.books.value.orEmpty().all { it.metaLoaded } }
            seed()
        }

        @AfterClass
        @JvmStatic
        fun stop() {
            if (!::app.isInitialized) return
            onEdt { app.shutdown(exit = false) }
            home.deleteRecursively()
        }

        private fun seed() = runBlocking {
            val books = library.books.value.orEmpty()
            val epubs = books.filter { it.bookFormat == BookFormat.EPUB }
            library.createShelf("Summer reading", epubs.take(3).map { it.id })
            library.createShelf("Classics", epubs.drop(2).take(2).map { it.id })
            val first = epubs.first { it.title == "The Lighthouse Keeper" }
            library.updatePosition(first.id, 1, 400, 0.34f)
            epubs.drop(1).take(2).forEachIndexed { i, b -> library.updatePosition(b.id, 0, 200, 0.12f + i * 0.3f) }
            library.setFavorite(listOf(first.id), true)
            library.addHighlight(Highlight(bookId = first.id, chapter = 0, start = 30, end = 140, text = "The quick brown fox jumps over a lazy dog while reading an old book", color = 0, note = "Lovely opening.", chapterTitle = "Chapter 1", progress = 0.02f))
            library.addHighlight(Highlight(bookId = epubs[1].id, chapter = 1, start = 10, end = 90, text = "Thinking about distant seas by candle light", color = 2, chapterTitle = "Chapter 2", progress = 0.3f))
            library.addBookmark(Bookmark(bookId = first.id, chapter = 1, offset = 400, progress = 0.34f, snippet = "Over by fox an an thinking thinking reading while the book", chapterTitle = "Chapter 2"))
            val today = com.readarea.core.library.ReadingStats.today()
            val db = library
            db.read {
                for (d in 0 until 60) {
                    if (d % 7 == 3) continue
                    insertSession(first.id, System.currentTimeMillis() - d * 86_400_000L, ((10 + (d * 37) % 50) * 60_000).toLong(), 12, today - d)
                }
            }
            library.setStatus(listOf(epubs.last().id), com.readarea.core.library.BookStatus.FINISHED)
            Thread.sleep(600)
        }

        fun onEdt(block: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
        }

        fun <T> edt(block: () -> T): T {
            var r: T? = null
            onEdt { r = block() }
            @Suppress("UNCHECKED_CAST")
            return r as T
        }

        fun waitUntil(ms: Long, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) {
                if (edt(cond)) return
                Thread.sleep(100)
            }
            throw AssertionError("Timed out waiting")
        }

        fun shot(window: Window, name: String): BufferedImage {
            Thread.sleep(250)
            val img = edt {
                val c: Component = (window as? JFrame)?.rootPane ?: window
                val i = BufferedImage(c.width.coerceAtLeast(1), c.height.coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
                val g = i.createGraphics()
                c.paint(g)
                g.dispose()
                i
            }
            ImageIO.write(img, "png", File(out, "$name.png"))
            return img
        }

        fun distinctColors(img: BufferedImage): Int {
            val set = HashSet<Int>()
            for (y in 0 until img.height step 7) for (x in 0 until img.width step 7) set.add(img.getRGB(x, y) and 0xF0F0F0)
            return set.size
        }

        fun reader(id: Long): ReaderWindow = edt { Window.getWindows().filterIsInstance<ReaderWindow>().first { it.controller.bookId == id && it.isShowing } }

        fun book(title: String): Book = library.books.value.orEmpty().first { it.title == title }
    }

    @Test
    fun a_mainScreens() {
        edt { app.main.setSize(1360, 860) }
        for (id in listOf("home", "library", "shelves", "notes", "stats", "settings")) {
            onEdt { app.main.navigate(id) }
            Thread.sleep(700)
            val img = shot(app.main, "main_$id")
            assertTrue("$id looks blank", distinctColors(img) > 12)
        }
        onEdt { app.settings.updateApp { it.copy(libraryGrid = false) }; app.main.navigate("library") }
        Thread.sleep(600)
        shot(app.main, "main_library_list")
        onEdt { app.settings.updateApp { it.copy(libraryGrid = true) } }
    }

    @Test
    fun b_detailsDialog() {
        val b = book("The Lighthouse Keeper")
        onEdt { app.main.showBook(b.id) }
        Thread.sleep(900)
        val dialog = edt { Window.getWindows().first { it is javax.swing.JDialog && it.isShowing } }
        shot(dialog, "details_dialog")
        onEdt { dialog.dispose() }
    }

    @Test
    fun c_readerEpub() {
        val b = book("The Lighthouse Keeper")
        onEdt { app.openBook(b.id) }
        waitUntil(20_000) { runCatching { reader(b.id).controller.ui.value.laidOut }.getOrDefault(false) }
        val w = reader(b.id)
        onEdt { w.setSize(1240, 860) }
        Thread.sleep(900)
        shot(w, "reader_epub_spread")
        onEdt { w.setSize(820, 900) }
        Thread.sleep(900)
        val page = shot(w, "reader_epub_single")
        assertTrue(distinctColors(page) > 8)
        for (p in listOf(Panel.CONTENTS, Panel.TEXT, Panel.THEME)) {
            onEdt { w.controller.openPanel(p) }
            Thread.sleep(600)
            shot(w, "reader_panel_${p.name.lowercase()}")
        }
        onEdt { w.controller.openPanel(Panel.SEARCH); w.controller.search("candle") }
        Thread.sleep(1200)
        shot(w, "reader_panel_search")
        onEdt { w.controller.closePanel() }
        // Select a word in the middle of the page and show the highlight toolbar.
        onEdt { w.controller.selectWord(300f, 300f) }
        Thread.sleep(500)
        shot(w, "reader_selection")
        onEdt { w.controller.clearSelection() }
        // Catch a page curl half way.
        val view = edt { findPageView(w) }
        onEdt { view.flip(true) }
        Thread.sleep(170)
        shot(w, "reader_curl")
        Thread.sleep(700)
        onEdt { w.controller.toggleMenu(true) }
        Thread.sleep(300)
        shot(w, "reader_menu")
        onEdt { w.controller.toggleMenu(false) }
        onEdt { w.controller.updateSettings { it.copy(theme = "night") } }
        Thread.sleep(700)
        shot(w, "reader_night")
        onEdt { w.controller.updateSettings { it.copy(pageAnim = "scroll", theme = "sepia") } }
        Thread.sleep(1500)
        shot(w, "reader_scroll")
        onEdt { w.controller.updateSettings { it.copy(pageAnim = "curl", theme = "paper") } }
        Thread.sleep(800)
        onEdt { w.saveAndClose() }
    }

    @Test
    fun d_readerOtherFormats() {
        for ((title, name) in listOf("吾輩は猫である" to "vertical_japanese", "الرحلة" to "arabic", "Atlas of Clouds" to "pdf", "Moonlit Harbor" to "comic", "Field Notes" to "markdown", "Folk Tales of the North" to "fb2", "Quarterly Garden Report" to "docx")) {
            val b = library.books.value.orEmpty().firstOrNull { it.title == title } ?: throw AssertionError("missing $title: " + library.books.value.orEmpty().map { it.title })
            onEdt { app.openBook(b.id) }
            waitUntil(20_000) { runCatching { reader(b.id).controller.ui.value.let { it.laidOut || it.error != null } }.getOrDefault(false) }
            val w = reader(b.id)
            onEdt { w.setSize(900, 860) }
            Thread.sleep(1500)
            val err = edt { w.controller.ui.value.error }
            shot(w, "reader_$name")
            assertTrue("$title failed to open: $err", err == null)
            onEdt { w.saveAndClose() }
        }
    }

    @Test
    fun e_darkApp() {
        onEdt { app.settings.updateApp { it.copy(themeMode = "dark", accent = 1) } }
        Thread.sleep(1200)
        for (id in listOf("home", "library", "stats")) {
            onEdt { app.main.navigate(id) }
            Thread.sleep(600)
            shot(app.main, "dark_$id")
        }
        onEdt { app.settings.updateApp { it.copy(themeMode = "light", accent = 0) } }
        Thread.sleep(800)
    }

    @Test
    fun z_noUiErrors() {
        val list = synchronized(errors) { errors.toList() }
        list.forEach { it.printStackTrace() }
        assertTrue("UI thread errors: ${list.map { it.toString() }}", list.isEmpty())
    }

    private fun findPageView(c: Component): PageView {
        if (c is PageView) return c
        if (c is java.awt.Container) for (child in c.components) runCatching { return findPageView(child) }
        throw NoSuchElementException()
    }
}
