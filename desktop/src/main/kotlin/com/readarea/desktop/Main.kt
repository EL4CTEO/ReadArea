package com.readarea.desktop

import com.readarea.desktop.data.Database
import com.readarea.desktop.data.Library
import com.readarea.desktop.data.SettingsStore
import com.readarea.desktop.i18n.I18n
import com.readarea.desktop.platform.AppDirs
import com.readarea.desktop.platform.Os
import com.readarea.desktop.platform.SingleInstance
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if ("--version" in args) {
        println("ReadArea " + (App::class.java.`package`?.implementationVersion ?: "dev"))
        return
    }
    val files = args.filter { !it.startsWith("-") }.map { File(it).absoluteFile }.filter { it.isFile }

    val dirs = AppDirs.resolve().init()
    configureRuntime(dirs)

    val instance = SingleInstance(dirs.data)
    if (instance.forward(files)) exitProcess(0)

    val settings = SettingsStore(dirs.settings)
    I18n.init(settings.app.value.language)
    val db = try {
        Database(dirs.database)
    } catch (e: Exception) {
        javax.swing.JOptionPane.showMessageDialog(null, "ReadArea couldn't open its library.\n\n${e.message}", "ReadArea", javax.swing.JOptionPane.ERROR_MESSAGE)
        exitProcess(1)
    }
    val library = Library(db, settings, dirs)
    val app = App(dirs, settings, db, library, instance)
    if (settings.app.value.themeMode == "system") app.detectDarkNow()
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { settings.flush() } })
    SwingUtilities.invokeLater { app.start(files) }
}

/**
 * Process-wide settings that must be in place before the first window or library access:
 * where native libraries and caches go, the macOS menu bar, and quiet third-party logging.
 */
fun configureRuntime(dirs: AppDirs) {
    // Native helpers (SQLite) unpack into the app's own folder instead of the shared temp folder.
    System.setProperty("org.sqlite.tmpdir", dirs.natives.path)
    // PDFBox caches the system font list; keep it with ReadArea's caches rather than in the home folder.
    System.setProperty("pdfbox.fontcache", dirs.cache.path)
    System.setProperty("flatlaf.menuBarEmbedded", "true")
    if (Os.current == Os.MAC) {
        System.setProperty("apple.laf.useScreenMenuBar", "true")
        System.setProperty("apple.awt.application.name", "ReadArea")
        System.setProperty("apple.awt.application.appearance", "system")
    }
    if (Os.current == Os.LINUX) System.setProperty("awt.useSystemAAFontSettings", System.getProperty("awt.useSystemAAFontSettings") ?: "on")
    // Libraries log document details (font names, broken objects); none of that belongs in a log.
    Logger.getLogger("").level = Level.WARNING
    for (name in listOf("org.apache.pdfbox", "org.apache.fontbox", "org.sqlite", "com.twelvemonkeys")) {
        // java.util.logging only holds loggers weakly; keep them so the level sticks.
        quietLoggers.add(Logger.getLogger(name).apply { level = Level.OFF })
    }
}

private val quietLoggers = ArrayList<Logger>()

/** Builds the app for tests and tools without showing windows. */
fun createHeadless(dirs: AppDirs): Triple<SettingsStore, Database, Library> {
    val settings = SettingsStore(dirs.settings)
    val db = Database(dirs.database)
    return Triple(settings, db, Library(db, settings, dirs))
}
