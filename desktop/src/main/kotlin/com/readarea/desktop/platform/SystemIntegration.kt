package com.readarea.desktop.platform

import java.awt.Desktop
import java.awt.Taskbar
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * The bits of each operating system ReadArea talks to: dark mode, file managers, the browser for links.
 *
 * Helper programs run by absolute path where the OS has a fixed location for them, with arguments as a
 * list (never through a shell) and a short timeout, so a program planted earlier on the PATH or odd
 * characters in a file name can't change what runs.
 */
object SystemIntegration {
    private val SAFE_SCHEMES = setOf("http", "https", "mailto")
    private const val MAX_LINK = 2000

    /** Runs [cmd] and returns its standard output, or null if it failed or took too long. */
    fun run(cmd: List<String>, timeoutMs: Long = 2000): String? = runCatching {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(nullFile())).start()
        val sb = StringBuffer()
        // Read on another thread so a helper that hangs can't block past the timeout.
        val reader = Thread {
            runCatching {
                p.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(4096)
                    while (sb.length < 64 * 1024) {
                        val n = r.read(buf)
                        if (n < 0) break
                        sb.append(buf, 0, n)
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            return null
        }
        reader.join(500)
        if (p.exitValue() != 0) null else sb.toString()
    }.getOrNull()

    private fun nullFile() = File(if (Os.current == Os.WINDOWS) "NUL" else "/dev/null")

    /** A system binary at a known path, or null; never a lookup through the PATH. */
    fun systemBinary(vararg candidates: String): String? = candidates.firstOrNull { File(it).canExecute() }

    fun isSystemDark(): Boolean = when (Os.current) {
        Os.MAC -> run(listOf("/usr/bin/defaults", "read", "-g", "AppleInterfaceStyle"))?.trim()?.equals("Dark", true) == true
        Os.WINDOWS -> {
            val reg = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\reg.exe").path
            run(listOf(reg, "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme"))
                ?.let { Regex("AppsUseLightTheme\\s+REG_DWORD\\s+0x(\\d+)").find(it)?.groupValues?.get(1) == "0" } ?: false
        }
        else -> linuxDark()
    }

    private fun linuxDark(): Boolean {
        systemBinary("/usr/bin/gdbus", "/bin/gdbus")?.let { gdbus ->
            val out = run(listOf(gdbus, "call", "--session", "--timeout", "1", "--dest", "org.freedesktop.portal.Desktop", "--object-path", "/org/freedesktop/portal/desktop", "--method", "org.freedesktop.portal.Settings.Read", "org.freedesktop.appearance", "color-scheme"))
            val v = out?.let { Regex("uint32 (\\d)").find(it)?.groupValues?.get(1) }
            if (v != null) return v == "1"
        }
        systemBinary("/usr/bin/gsettings", "/bin/gsettings")?.let { gs ->
            run(listOf(gs, "get", "org.gnome.desktop.interface", "color-scheme"))?.let { if (it.contains("dark")) return true }
            run(listOf(gs, "get", "org.gnome.desktop.interface", "gtk-theme"))?.let { if (it.contains("dark", true)) return true }
        }
        return System.getenv("GTK_THEME")?.contains("dark", true) == true
    }

    /**
     * A link from a book in the form it may be shown and opened, or null if it may not be opened at all:
     * only web pages (with a plain ASCII host) and email, at most 2,000 characters, percent-encoded so
     * every character is visible for what it is.
     */
    fun safeLink(href: String): String? {
        val text = href.trim()
        if (text.length > MAX_LINK) return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in SAFE_SCHEMES) return null
        if (scheme != "mailto" && uri.host.isNullOrBlank()) return null
        return uri.toASCIIString().takeIf { it.length <= MAX_LINK }
    }

    fun isOpenableLink(href: String): Boolean = safeLink(href) != null

    /** Opens a web or email link in the default app. Anything else is refused. */
    fun openLink(href: String): Boolean {
        val uri = URI(safeLink(href) ?: return false)
        return runCatching {
            val d = Desktop.getDesktop()
            if (uri.scheme.equals("mailto", true) && d.isSupported(Desktop.Action.MAIL)) d.mail(uri) else d.browse(uri)
            true
        }.getOrElse {
            // Some Linux desktops lack AWT's integration; xdg-open does the same job.
            if (Os.current == Os.LINUX) systemBinary("/usr/bin/xdg-open")?.let { run(listOf(it, uri.toASCIIString()), 4000) != null } ?: false else false
        }
    }

    /** Shows [file] in Finder, Explorer or the Linux file manager. */
    fun reveal(file: File) {
        val f = file.absoluteFile
        runCatching {
            when (Os.current) {
                Os.MAC -> ProcessBuilder("/usr/bin/open", "-R", f.path).start()
                Os.WINDOWS -> {
                    val explorer = File(System.getenv("SystemRoot") ?: "C:\\Windows", "explorer.exe").path
                    ProcessBuilder(explorer, "/select,", f.path).start()
                }
                else -> {
                    val d = Desktop.getDesktop()
                    if (d.isSupported(Desktop.Action.BROWSE_FILE_DIR)) d.browseFileDirectory(f)
                    else systemBinary("/usr/bin/xdg-open")?.let { ProcessBuilder(it, (f.parentFile ?: f).path).start() }
                }
            }
        }
    }

    fun canTrash(): Boolean = runCatching { Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH) }.getOrDefault(false)

    /**
     * Hooks into the OS: files opened from Finder, the macOS app menu's About and Settings items, and the
     * dock icon when running from a development build.
     */
    fun install(icon: BufferedImage, onOpenFiles: (List<File>) -> Unit, onAbout: () -> Unit, onPreferences: () -> Unit, onQuit: () -> Unit) {
        if (!Desktop.isDesktopSupported()) return
        val d = Desktop.getDesktop()
        runCatching { if (d.isSupported(Desktop.Action.APP_OPEN_FILE)) d.setOpenFileHandler { e -> onOpenFiles(e.files) } }
        runCatching { if (d.isSupported(Desktop.Action.APP_ABOUT)) d.setAboutHandler { onAbout() } }
        runCatching { if (d.isSupported(Desktop.Action.APP_PREFERENCES)) d.setPreferencesHandler { onPreferences() } }
        runCatching {
            if (d.isSupported(Desktop.Action.APP_QUIT_HANDLER)) d.setQuitHandler { _, response ->
                onQuit()
                response.performQuit()
            }
        }
        runCatching { if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) Taskbar.getTaskbar().iconImage = icon }
    }
}
