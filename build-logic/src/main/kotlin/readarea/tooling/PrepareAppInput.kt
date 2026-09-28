package readarea.tooling

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Lays out the jars for jpackage's `--input` folder. Native libraries bundled inside jars for every
 * platform (SQLite, FlatLaf) are taken out: this platform's go into `natives/`, where the launcher points
 * the libraries at them, and the others are dropped. That keeps them out of temp folders at run time,
 * lets macOS code signing reach them, and saves about 10 MB.
 */
@CacheableTask
abstract class PrepareAppInput : DefaultTask() {
    @get:Classpath
    abstract val jars: ConfigurableFileCollection

    @get:Input
    abstract val hostOs: Property<String>

    @get:Input
    abstract val hostArch: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        val host = Host(HostOs.valueOf(hostOs.get()), hostArch.get())
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val natives = File(out, "natives").apply { mkdirs() }
        val wanted = setOf(host.sqliteNativeDir + "/" + host.sqliteLibraryName, "com/formdev/flatlaf/natives/" + host.flatlafLibraryName)
        val found = HashSet<String>()
        for (jar in jars.files.filter { it.isFile && it.name.endsWith(".jar") }.sortedBy { it.name }) {
            val target = File(out, jar.name)
            val hasNatives = ZipFile(jar).use { z -> z.entries().asSequence().any { isNative(it.name) } }
            if (!hasNatives) {
                jar.copyTo(target, overwrite = true)
                continue
            }
            ZipFile(jar).use { z ->
                ZipOutputStream(target.outputStream().buffered()).use { zos ->
                    for (e in z.entries()) {
                        if (isNative(e.name)) {
                            if (e.name in wanted) {
                                z.getInputStream(e).use { input -> File(natives, e.name.substringAfterLast('/')).outputStream().use { input.copyTo(it) } }
                                found.add(e.name)
                            }
                            continue
                        }
                        // A rewritten jar can't keep a signature over its old contents.
                        if (e.name.startsWith("META-INF/") && (e.name.endsWith(".SF") || e.name.endsWith(".RSA") || e.name.endsWith(".DSA") || e.name.endsWith(".EC"))) continue
                        zos.putNextEntry(ZipEntry(e.name).apply { time = e.time })
                        if (!e.isDirectory) z.getInputStream(e).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
        }
        val missing = wanted - found
        check(missing.isEmpty()) { "No native library for ${host.os} ${host.arch} in the dependencies: $missing" }
    }

    private fun isNative(name: String) = (name.startsWith("org/sqlite/native/") || name.startsWith("com/formdev/flatlaf/natives/")) && !name.endsWith("/")
}
