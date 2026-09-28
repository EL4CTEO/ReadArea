package readarea.tooling

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.Serializable

/** A file type the installed app registers to open. */
data class FileAssociation(val extension: String, val mimeType: String, val description: String, val macRank: String = "Default") : Serializable

/**
 * Runs jpackage: `app-image` builds the self-contained app from the jars and runtime; any other type
 * (dmg, msi, deb, rpm...) wraps an app image in that installer with menu entries and file associations.
 */
abstract class Jpackage : DefaultTask() {
    @get:Input
    abstract val type: Property<String>

    @get:Input
    abstract val appName: Property<String>

    @get:Input
    abstract val appVersion: Property<String>

    @get:Input
    abstract val vendor: Property<String>

    @get:Input
    abstract val appDescription: Property<String>

    @get:Input
    abstract val copyright: Property<String>

    @get:Input
    @get:Optional
    abstract val aboutUrl: Property<String>

    /** For `app-image`: the jars and native libraries, and the main jar and class in there. */
    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appInput: DirectoryProperty

    @get:Input
    @get:Optional
    abstract val mainJar: Property<String>

    @get:Input
    @get:Optional
    abstract val mainClass: Property<String>

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeImage: DirectoryProperty

    @get:Input
    abstract val javaOptions: ListProperty<String>

    /** For installers: the app image to wrap. */
    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appImage: DirectoryProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val icon: RegularFileProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val licenseFile: RegularFileProperty

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resourceDir: DirectoryProperty

    @get:Input
    abstract val fileAssociations: ListProperty<FileAssociation>

    /** Platform options passed as they are, e.g. `--win-menu` or `--linux-shortcut`. */
    @get:Input
    abstract val extraArgs: ListProperty<String>

    /**
     * For `app-image`: start the app once to record the classes start-up loads into a class-data archive
     * the launcher then uses, for noticeably faster launches. Skipped (with a warning) where the app can't
     * start, such as without a display.
     */
    @get:Input
    abstract val trainClassArchive: Property<Boolean>

    /** Environment for jpackage, e.g. signing settings that shouldn't end up in the build cache key. */
    @get:Internal
    abstract val environment: MapProperty<String, String>

    @get:Internal
    abstract val jdkHome: DirectoryProperty

    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    @TaskAction
    fun run() {
        val dest = destination.get().asFile
        dest.deleteRecursively()
        dest.mkdirs()
        val args = ArrayList<String>()
        args += listOf(
            "--type", type.get(),
            "--name", appName.get(),
            "--app-version", appVersion.get(),
            "--vendor", vendor.get(),
            "--description", appDescription.get(),
            "--copyright", copyright.get(),
            "--dest", dest.path,
        )
        icon.orNull?.let { args += listOf("--icon", it.asFile.path) }
        if (type.get() == "app-image") {
            args += listOf("--input", appInput.get().asFile.path, "--main-jar", mainJar.get(), "--main-class", mainClass.get(), "--runtime-image", runtimeImage.get().asFile.path)
            for (o in javaOptions.get()) args += listOf("--java-options", o)
        } else {
            args += listOf("--app-image", appImage.get().asFile.listFiles()!!.single { !it.name.startsWith(".") }.path)
            licenseFile.orNull?.let { args += listOf("--license-file", it.asFile.path) }
            aboutUrl.orNull?.let { args += listOf("--about-url", it) }
            resourceDir.orNull?.let { args += listOf("--resource-dir", it.asFile.path) }
            val assocDir = File(temporaryDir, "associations").apply { deleteRecursively(); mkdirs() }
            for (a in fileAssociations.get()) {
                val f = File(assocDir, a.extension + ".properties")
                f.writeText(
                    buildString {
                        append("extension=").append(a.extension).append('\n')
                        append("mime-type=").append(a.mimeType).append('\n')
                        append("description=").append(a.description).append('\n')
                        append("mac.CFBundleTypeRole=Viewer\n")
                        append("mac.LSHandlerRank=").append(a.macRank).append('\n')
                    },
                )
                args += listOf("--file-associations", f.path)
            }
        }
        args += extraArgs.get()
        val exe = File(jdkHome.get().asFile, "bin/jpackage" + if (Host.current().os == HostOs.WINDOWS) ".exe" else "")
        check(exe.isFile) { "$exe not found: packaging needs a full JDK" }
        logger.lifecycle("jpackage --type ${type.get()}")
        val pb = ProcessBuilder(listOf(exe.path) + args).redirectErrorStream(true)
        pb.environment().putAll(environment.get())
        val p = pb.start()
        val output = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0) error("jpackage failed:\n$output")
        logger.info(output)
        if (type.get() == "app-image" && trainClassArchive.getOrElse(false)) ClassArchiveTraining(dest, appName.get(), temporaryDir, logger).run()
    }
}
