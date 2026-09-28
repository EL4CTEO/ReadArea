package readarea.tooling

/**
 * The machine the build runs on. jpackage only builds installers for its own platform, so each CI runner
 * packages for itself and native libraries are picked for this operating system and architecture.
 */
enum class HostOs { MAC, WINDOWS, LINUX }

data class Host(val os: HostOs, val arch: String) {
    /** Folder of this platform's SQLite library inside the sqlite-jdbc jar. */
    val sqliteNativeDir: String get() = "org/sqlite/native/" + when (os) {
        HostOs.MAC -> "Mac"
        HostOs.WINDOWS -> "Windows"
        HostOs.LINUX -> "Linux"
    } + "/" + arch

    val sqliteLibraryName: String get() = when (os) {
        HostOs.MAC -> "libsqlitejdbc.dylib"
        HostOs.WINDOWS -> "sqlitejdbc.dll"
        HostOs.LINUX -> "libsqlitejdbc.so"
    }

    /** File name of FlatLaf's native helper for this platform. */
    val flatlafLibraryName: String get() {
        val a = if (arch == "aarch64") "arm64" else arch
        return when (os) {
            HostOs.MAC -> "libflatlaf-macos-$a.dylib"
            HostOs.WINDOWS -> "flatlaf-windows-$a.dll"
            HostOs.LINUX -> "libflatlaf-linux-$a.so"
        }
    }

    /** The installers jpackage can make here. */
    val installerTypes: List<String> get() = when (os) {
        HostOs.MAC -> listOf("dmg")
        HostOs.WINDOWS -> listOf("msi")
        HostOs.LINUX -> listOf("deb", "rpm")
    }

    /** Short platform name for file names, e.g. `linux-x64`. */
    val classifier: String get() = when (os) {
        HostOs.MAC -> "macos"
        HostOs.WINDOWS -> "windows"
        HostOs.LINUX -> "linux"
    } + "-" + if (arch == "aarch64") "arm64" else "x64"

    companion object {
        fun current(): Host {
            val name = System.getProperty("os.name").lowercase()
            val os = when {
                name.contains("mac") || name.contains("darwin") -> HostOs.MAC
                name.contains("win") -> HostOs.WINDOWS
                else -> HostOs.LINUX
            }
            val arch = when (System.getProperty("os.arch").lowercase()) {
                "aarch64", "arm64" -> "aarch64"
                "amd64", "x86_64" -> "x86_64"
                else -> System.getProperty("os.arch").lowercase()
            }
            return Host(os, arch)
        }
    }
}
