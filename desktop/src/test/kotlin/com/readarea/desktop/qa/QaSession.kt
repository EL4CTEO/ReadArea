package com.readarea.desktop.qa

import com.readarea.desktop.App
import com.readarea.desktop.configureRuntime
import com.readarea.desktop.data.Database
import com.readarea.desktop.data.Library
import com.readarea.desktop.data.SettingsStore
import com.readarea.desktop.i18n.I18n
import com.readarea.desktop.platform.AppDirs
import java.awt.Component
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities

/** The real app running on a sample library in a throwaway data folder, in the given language. */
class QaSession private constructor(val home: File, val app: App, val library: Library) {
    fun stop() {
        onEdt { app.shutdown(exit = false) }
        home.deleteRecursively()
    }

    companion object {
        fun start(language: String): QaSession {
            val home = Files.createTempDirectory("readarea-qa").toFile()
            System.setProperty("readarea.home", File(home, "data").path)
            val samples = SampleLibrary.create(File(home, "Books"))
            val dirs = AppDirs.resolve().init()
            configureRuntime(dirs)
            val settings = SettingsStore(dirs.settings)
            settings.updateApp { it.copy(folders = listOf(samples.path), onboardingDone = true, reopenLastBook = false, language = language) }
            I18n.init(language)
            val db = Database(dirs.database)
            val library = Library(db, settings, dirs)
            val app = App(dirs, settings, db, library, null)
            onEdt { app.start(emptyList()) }
            waitUntil(60_000, "the library scan") {
                val s = library.scan.value
                !s.running && library.books.value.orEmpty().size >= 15 && library.books.value.orEmpty().all { it.metaLoaded }
            }
            return QaSession(home, app, library)
        }
    }
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

fun waitUntil(ms: Long, what: String = "condition", cond: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) {
        if (edt(cond)) return
        Thread.sleep(100)
    }
    throw AssertionError("Timed out waiting for $what")
}

/** Paints [window] into an image and saves it as `dir/name.png`. */
fun shot(window: Window, dir: File, name: String): BufferedImage {
    Thread.sleep(250)
    val img = edt {
        val c: Component = (window as? JFrame)?.rootPane ?: window
        val i = BufferedImage(c.width.coerceAtLeast(1), c.height.coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
        val g = i.createGraphics()
        c.paint(g)
        g.dispose()
        i
    }
    ImageIO.write(img, "png", File(dir, "$name.png"))
    return img
}

/** Roughly how many colors an image has: a blank or broken screen has very few. */
fun distinctColors(img: BufferedImage): Int {
    val set = HashSet<Int>()
    for (y in 0 until img.height step 7) for (x in 0 until img.width step 7) set.add(img.getRGB(x, y) and 0xF0F0F0)
    return set.size
}
