package readarea.tooling

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Builds the trimmed Java runtime the installers ship: the modules jdeps finds the app's jars need, plus
 * ones it can't see (charsets and locale data looked up by name, the accessibility bridge), with only the
 * app's languages' locale data and a class-data archive for faster start-up.
 */
@CacheableTask
abstract class JlinkRuntime : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val appInput: DirectoryProperty

    @get:Input
    abstract val extraModules: ListProperty<String>

    @get:Input
    abstract val locales: ListProperty<String>

    @get:Input
    abstract val javaRelease: Property<Int>

    @get:Internal
    abstract val jdkHome: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun link() {
        val jdk = jdkHome.get().asFile
        val jars = appInput.get().asFile.listFiles { f -> f.name.endsWith(".jar") }.orEmpty().sortedBy { it.name }
        val found = run(
            listOf(tool(jdk, "jdeps"), "--ignore-missing-deps", "--multi-release", javaRelease.get().toString(), "--print-module-deps", "--class-path", jars.joinToString(File.pathSeparator)) + jars.map { it.path },
        ).trim().lines().last().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val modules = (found + extraModules.get()).toSortedSet()
        logger.lifecycle("Runtime modules: ${modules.joinToString()}")
        val out = outputDir.get().asFile
        out.deleteRecursively()
        run(
            listOf(
                tool(jdk, "jlink"),
                "--add-modules", modules.joinToString(","),
                "--include-locales", locales.get().joinToString(","),
                "--strip-debug",
                "--no-header-files",
                "--no-man-pages",
                "--compress", "zip-6",
                "--generate-cds-archive",
                "--output", out.path,
            ),
        )
        // The second class-data archive only serves heaps over 32 GB, which a reader never gets near.
        out.walkTopDown().filter { it.name == "classes_nocoops.jsa" }.forEach { it.delete() }
    }

    private fun tool(jdk: File, name: String): String {
        val exe = File(jdk, "bin/" + name + if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else "")
        check(exe.isFile) { "$exe not found: packaging needs a full JDK $javaRelease" }
        return exe.path
    }

    private fun run(command: List<String>): String {
        val p = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "${File(command.first()).name} failed:\n$output" }
        return output
    }
}
