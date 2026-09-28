package com.readarea.desktop

import com.readarea.core.format.BookFormat
import com.readarea.desktop.data.Database
import com.readarea.desktop.data.Library
import com.readarea.desktop.data.SettingsStore
import com.readarea.desktop.i18n.I18n
import com.readarea.desktop.ui.components.Mirroring
import com.readarea.desktop.i18n.tr
import com.readarea.desktop.platform.AppDirs
import com.readarea.desktop.platform.Os
import com.readarea.desktop.platform.SingleInstance
import com.readarea.desktop.platform.SpeechEngine
import com.readarea.desktop.platform.SystemIntegration
import com.readarea.desktop.reader.ReaderWindow
import com.readarea.desktop.ui.MainWindow
import com.readarea.desktop.ui.theme.AppIcon
import com.readarea.desktop.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Window
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/** Everything the windows share: storage, settings, the open readers and the app-wide actions. */
class App(
    val dirs: AppDirs,
    val settings: SettingsStore,
    val db: Database,
    val library: Library,
    private val instance: SingleInstance?,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    lateinit var main: MainWindow
        private set
    private val readers = LinkedHashMap<Long, ReaderWindow>()

    @Volatile var systemDark = false
        private set

    val speech: SpeechEngine? by lazy { SpeechEngine.detect() }

    val version: String = App::class.java.`package`?.implementationVersion ?: "dev"

    val icons = listOf(16, 32, 48, 64, 128, 256).map { AppIcon.image(it) }

    val dark: Boolean get() = when (settings.app.value.themeMode) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    fun start(files: List<File>) {
        AppTheme.apply(dark, settings.app.value.accent)
        Mirroring.install()
        main = MainWindow(this)
        SystemIntegration.install(icons.last(), onOpenFiles = { openFiles(it) }, onAbout = { main.showSettings(about = true) }, onPreferences = { main.showSettings() }, onQuit = { shutdown(exit = false) })
        main.isVisible = true
        instance?.listen { received -> SwingUtilities.invokeLater { if (received.isEmpty()) main.bringToFront() else openFiles(received) } }
        watchSettings()
        if (files.isNotEmpty()) openFiles(files)
        else scope.launch { resume() }
        library.rescan(force = true)
        scope.launch {
            // Library folders change while the app runs; look again now and then.
            while (true) {
                delay(10 * 60_000L)
                if (settings.app.value.watchFolders) library.rescan(force = false)
            }
        }
    }

    private fun watchSettings() {
        scope.launch {
            settings.app.map { Triple(it.themeMode, it.accent, it.language) }.distinctUntilChanged().collect { applyTheme() }
        }
    }

    /** Re-reads the system's dark mode (cheaply, at most every few seconds) and restyles if needed. */
    fun refreshSystemDark() {
        if (settings.app.value.themeMode != "system") return
        scope.launch {
            val dark = withContext(Dispatchers.IO) { SystemIntegration.isSystemDark() }
            if (dark != systemDark) {
                systemDark = dark
                applyTheme()
            }
        }
    }

    private var lastThemeKey: Any? = null

    fun applyTheme() {
        val key = listOf(dark, settings.app.value.accent)
        if (key == lastThemeKey) return
        lastThemeKey = key
        AppTheme.apply(dark, settings.app.value.accent)
        for (w in Window.getWindows()) SwingUtilities.updateComponentTreeUI(w)
        readers.values.forEach { it.onSystemTheme(systemDark) }
    }

    fun detectDarkNow() {
        systemDark = runCatching { SystemIntegration.isSystemDark() }.getOrDefault(false)
    }

    private suspend fun resume() {
        val app = settings.app.value
        if (!app.reopenLastBook) return
        val ids = app.openBooks.ifEmpty { listOfNotNull(app.resumeBookId.takeIf { it > 0 }) }
        for (id in ids.take(3)) {
            val b = library.get(id) ?: continue
            if (!b.missing && File(b.path).isFile) openBook(id)
        }
    }

    /** Opens a book in its own window, or brings its window forward if it's already open. */
    fun openBook(id: Long, at: Pair<Int, Int>? = null) {
        readers[id]?.let { w ->
            w.bringToFront()
            at?.let { w.controller.goTo(it.first, it.second, rememberJump = true) }
            return
        }
        val w = ReaderWindow(this, id, at)
        readers[id] = w
        settings.updateApp { it.copy(resumeBookId = id, openBooks = (readers.keys.toList())) }
        w.isVisible = true
    }

    fun readerClosed(id: Long) {
        readers.remove(id)
        settings.updateApp { it.copy(openBooks = readers.keys.toList()) }
    }

    val openReaders: Collection<ReaderWindow> get() = readers.values

    /** Adds files (from the file manager, a drop or a second launch) and opens the first one. */
    fun openFiles(files: List<File>) {
        scope.launch {
            val ids = library.importFiles(files)
            if (ids.isEmpty()) {
                if (files.isNotEmpty()) main.toast(tr("toast_not_a_book"))
                return@launch
            }
            val first = ids.first()
            library.get(first)?.let { b -> if (!b.metaLoaded) launch { library.loadMetadata(b) } }
            if (ids.size == 1) openBook(first) else {
                main.toast(I18n.plural("toast_added_books", ids.size, ids.size))
                main.bringToFront()
            }
        }
    }

    fun chooseFiles(owner: Frame) {
        val dialog = FileDialog(owner, tr("open_files"), FileDialog.LOAD)
        dialog.isMultipleMode = true
        val exts = BookFormat.entries.flatMap { it.extensions }.toSet()
        dialog.setFilenameFilter { _, name -> exts.any { name.lowercase().endsWith(".$it") } }
        if (Os.current == Os.WINDOWS) dialog.file = exts.joinToString(";") { "*.$it" }
        dialog.isVisible = true
        val chosen = dialog.files?.toList().orEmpty()
        if (chosen.isNotEmpty()) openFiles(chosen)
    }

    fun chooseFolder(owner: Frame) {
        val dir: File? = if (Os.current == Os.MAC) {
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
            try {
                val d = FileDialog(owner, tr("add_folder"), FileDialog.LOAD)
                d.isVisible = true
                d.file?.let { File(d.directory, it) }
            } finally {
                System.setProperty("apple.awt.fileDialogForDirectories", "false")
            }
        } else {
            val c = JFileChooser(System.getProperty("user.home"))
            c.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            c.dialogTitle = tr("add_folder")
            if (c.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) c.selectedFile else null
        }
        if (dir != null && dir.isDirectory) {
            library.addFolder(dir)
            main.toast(tr("toast_scanning_folder", dir.name))
        }
    }

    fun shutdown(exit: Boolean = true) {
        readers.values.toList().forEach { runCatching { it.saveAndClose() } }
        runCatching { speech?.close() }
        settings.close()
        runCatching { instance?.close() }
        scope.cancel()
        library.close()
        db.close()
        if (exit) System.exit(0)
    }

    fun fatal(message: String) {
        JOptionPane.showMessageDialog(null, message, "ReadArea", JOptionPane.ERROR_MESSAGE)
    }
}
