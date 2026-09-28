package com.readarea.desktop.qa

import com.readarea.desktop.reader.Panel
import com.readarea.desktop.reader.ReaderWindow
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.BeforeClass
import org.junit.Test
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.io.File

/** The app in Arabic: every screen should lay out right to left. Screenshots go to build/qa-screens-rtl. */
class RtlUiQa {
    companion object {
        private val out = File("build/qa-screens-rtl").apply { deleteRecursively(); mkdirs() }
        private lateinit var session: QaSession

        @BeforeClass
        @JvmStatic
        fun start() {
            assumeFalse("needs a display", GraphicsEnvironment.isHeadless())
            session = QaSession.start("ar")
        }

        @AfterClass
        @JvmStatic
        fun stop() {
            if (::session.isInitialized) session.stop()
            com.readarea.desktop.i18n.I18n.init("en")
        }
    }

    @Test
    fun screensMirror() {
        val app = session.app
        edt { app.main.setSize(1360, 860) }
        for (id in listOf("home", "library", "shelves", "notes", "stats", "settings")) {
            onEdt { app.main.navigate(id) }
            Thread.sleep(700)
            val img = shot(app.main, out, "main_$id")
            assertTrue("$id looks blank", distinctColors(img) > 12)
        }
        // Screens built after start-up are mirrored too.
        assertTrue(edt { !app.main.contentPane.componentOrientation.isLeftToRight })
        val b = session.library.books.value.orEmpty().first { it.title == "The Lighthouse Keeper" }
        onEdt { app.main.showBook(b.id) }
        Thread.sleep(900)
        val dialog = edt { Window.getWindows().first { it is javax.swing.JDialog && it.isShowing } }
        shot(dialog, out, "details_dialog")
        assertTrue(edt { !(dialog as javax.swing.JDialog).rootPane.componentOrientation.isLeftToRight })
        onEdt { dialog.dispose() }
    }

    @Test
    fun readerMirrors() {
        val app = session.app
        val b = session.library.books.value.orEmpty().first { it.title == "الرحلة" }
        onEdt { app.openBook(b.id) }
        waitUntil(20_000, "the Arabic book") { runCatching { reader(b.id).controller.ui.value.laidOut }.getOrDefault(false) }
        val w = reader(b.id)
        onEdt { w.setSize(900, 860) }
        Thread.sleep(900)
        onEdt { w.controller.toggleMenu(true) }
        Thread.sleep(400)
        shot(w, out, "reader_menu")
        onEdt { w.controller.toggleMenu(false); w.controller.openPanel(Panel.CONTENTS) }
        Thread.sleep(600)
        shot(w, out, "reader_contents")
        onEdt { w.controller.openPanel(Panel.TEXT) }
        Thread.sleep(600)
        shot(w, out, "reader_text")
        onEdt { w.saveAndClose() }
    }

    private fun reader(id: Long): ReaderWindow = edt { Window.getWindows().filterIsInstance<ReaderWindow>().first { it.controller.bookId == id && it.isShowing } }
}
