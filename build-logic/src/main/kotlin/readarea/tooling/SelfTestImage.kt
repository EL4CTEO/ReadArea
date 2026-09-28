package readarea.tooling

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Starts the packaged app with `--self-test` through its own native launcher and fails the build unless
 * every check passes, so an installer never ships a runtime that's missing a module or native library.
 */
abstract class SelfTestImage : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appImage: DirectoryProperty

    @get:Input
    abstract val appName: Property<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val image = appImage.get().asFile
        val name = appName.get()
        val launcher = when (Host.current().os) {
            HostOs.MAC -> File(image, "$name.app/Contents/MacOS/$name")
            HostOs.WINDOWS -> File(image, "$name/$name.exe")
            HostOs.LINUX -> File(image, "$name/bin/$name")
        }
        check(launcher.isFile) { "No launcher at $launcher" }
        val out = report.get().asFile
        out.delete()
        val p = ProcessBuilder(launcher.path, "--self-test=" + out.absolutePath).redirectErrorStream(true).start()
        val console = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(3, TimeUnit.MINUTES)) {
            p.destroyForcibly()
            error("The self-test didn't finish")
        }
        val text = if (out.isFile) out.readText() else console
        logger.lifecycle(text.trimEnd())
        check(p.exitValue() == 0) { "The packaged app failed its self-test (exit ${p.exitValue()}):\n$text$console" }
    }
}
