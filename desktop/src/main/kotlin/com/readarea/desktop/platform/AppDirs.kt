package com.readarea.desktop.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

enum class Os {
    MAC, WINDOWS, LINUX, OTHER;

    companion object {
        val current: Os = System.getProperty("os.name").orEmpty().lowercase().let {
            when {
                it.startsWith("mac") || it.startsWith("darwin") -> MAC
                it.startsWith("windows") -> WINDOWS
                it.contains("linux") || it.contains("bsd") -> LINUX
                else -> OTHER
            }
        }
    }
}

/**
 * Where ReadArea keeps its library, covers and caches, following each platform's conventions:
 * `~/Library/Application Support` on macOS, `%LOCALAPPDATA%` on Windows and the XDG directories on Linux.
 *
 * Everything lives in folders only the current user can open. Setting `READAREA_HOME` (or
 * `-Dreadarea.home`) keeps all data in one folder instead, for portable installs and tests.
 */
class AppDirs(val data: File, val cache: File) {
    val database: File get() = File(data, "library.db")
    val settings: File get() = File(data, "settings.json")
    val covers: File get() = File(data, "covers").also { ensurePrivate(it) }
    val fonts: File get() = File(data, "fonts").also { ensurePrivate(it) }
    val natives: File get() = File(cache, "natives").also { ensurePrivate(it) }

    fun init(): AppDirs {
        ensurePrivate(data)
        ensurePrivate(cache)
        return this
    }

    companion object {
        private const val NAME = "ReadArea"

        fun resolve(env: Map<String, String> = System.getenv(), home: String = System.getProperty("user.home"), os: Os = Os.current): AppDirs {
            val override = System.getProperty("readarea.home")?.takeIf { it.isNotBlank() } ?: env["READAREA_HOME"]?.takeIf { it.isNotBlank() }
            if (override != null) {
                val root = File(override).absoluteFile
                return AppDirs(root, File(root, "cache"))
            }
            return when (os) {
                Os.MAC -> AppDirs(File(home, "Library/Application Support/$NAME"), File(home, "Library/Caches/$NAME"))
                Os.WINDOWS -> {
                    val local = env["LOCALAPPDATA"]?.takeIf { it.isNotBlank() } ?: File(home, "AppData/Local").path
                    AppDirs(File(local, NAME), File(File(local, NAME), "Cache"))
                }
                else -> {
                    val dataHome = env["XDG_DATA_HOME"]?.takeIf { it.startsWith("/") } ?: File(home, ".local/share").path
                    val cacheHome = env["XDG_CACHE_HOME"]?.takeIf { it.startsWith("/") } ?: File(home, ".cache").path
                    AppDirs(File(dataHome, "readarea"), File(cacheHome, "readarea"))
                }
            }
        }

        private val posix = runCatching { java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix") }.getOrDefault(false)

        /** Creates [dir] if needed and makes it readable only by its owner where the file system allows. */
        fun ensurePrivate(dir: File) {
            val path: Path = dir.toPath()
            if (!Files.isDirectory(path)) {
                dir.parentFile?.mkdirs()
                if (posix) {
                    runCatching { Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))) }
                }
                dir.mkdirs()
            }
            if (posix) runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------")) }
        }

        /** Whether [file] resolves to a location inside [dir], after following links and `..`. */
        fun isInside(dir: File, file: File): Boolean = runCatching {
            file.canonicalPath.startsWith(dir.canonicalPath + File.separator)
        }.getOrDefault(false)

        /** Makes a single file readable and writable only by its owner. */
        fun makePrivate(file: File) {
            if (posix) runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
        }
    }
}
