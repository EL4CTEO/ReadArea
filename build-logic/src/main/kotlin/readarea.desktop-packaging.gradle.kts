import readarea.tooling.DesktopPackagingExtension
import readarea.tooling.Host
import readarea.tooling.HostOs
import readarea.tooling.JlinkRuntime
import readarea.tooling.Jpackage
import readarea.tooling.PrepareAppInput
import readarea.tooling.SelfTestImage

/*
 * Native installers for the desktop app, built with the JDK's own tools on each platform:
 *
 *   exportIcons      the app icon as .png, .ico and .icns
 *   prepareAppInput  the jars, with this platform's native libraries taken out into natives/
 *   jlinkRuntime     a trimmed Java runtime
 *   appImage         the self-contained app (jpackage app-image)
 *   selfTestImage    runs the packaged app's --self-test through its native launcher
 *   package<Type>    installers: dmg on macOS, msi on Windows, deb and rpm on Linux
 *   portableArchive  the app image as a zip (Windows) or tar.gz (Linux)
 *   packageDesktop   all of the above; results in build/package/dist
 */
plugins {
    java
}

val packaging = extensions.create<DesktopPackagingExtension>("desktopPackaging")
val mainSourceSet = extensions.getByType<JavaPluginExtension>().sourceSets.named("main")
val host = Host.current()
val packageDir = layout.buildDirectory.dir("package")
val jdk = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }.map { it.metadata.installationPath }

/** jpackage wants a plain numeric version; Windows caps its parts at 255.255.65535. */
fun numericVersion(v: String): String {
    val parts = v.substringBefore('-').substringBefore('+').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    val (major, minor, patch) = parts
    return if (host.os == HostOs.WINDOWS) "${major.coerceIn(0, 255)}.${minor.coerceIn(0, 255)}.${patch.coerceIn(0, 65535)}" else "${major.coerceAtLeast(1)}.$minor.$patch"
}

val exportIcons = tasks.register<JavaExec>("exportIcons") {
    group = "distribution"
    description = "Writes the app icon in the formats the installers need."
    val out = packageDir.map { it.dir("icons") }
    classpath = mainSourceSet.get().runtimeClasspath
    mainClass.set(packaging.iconExporter)
    argumentProviders.add(CommandLineArgumentProvider { listOf(out.get().asFile.path) })
    jvmArgs("-Djava.awt.headless=true")
    outputs.dir(out)
}

val prepareAppInput = tasks.register<PrepareAppInput>("prepareAppInput") {
    group = "distribution"
    description = "Collects the app's jars for jpackage and takes out this platform's native libraries."
    jars.from(tasks.named("jar"), mainSourceSet.get().runtimeClasspath.filter { it.name.endsWith(".jar") })
    hostOs.set(host.os.name)
    hostArch.set(host.arch)
    outputDir.set(packageDir.map { it.dir("input") })
}

val jlinkRuntime = tasks.register<JlinkRuntime>("jlinkRuntime") {
    group = "distribution"
    description = "Builds the trimmed Java runtime the app ships with."
    appInput.set(prepareAppInput.flatMap { it.outputDir })
    extraModules.set(packaging.extraModules)
    locales.set(packaging.locales)
    javaRelease.set(21)
    jdkHome.set(layout.dir(jdk.map { it.asFile }))
    outputDir.set(packageDir.map { it.dir("runtime") })
}

/** macOS signing, when a Developer ID identity is configured (see the release workflow). */
val macSigning: List<String> = System.getenv("MACOS_SIGNING_IDENTITY")?.takeIf { it.isNotBlank() && host.os == HostOs.MAC }?.let { id ->
    listOf("--mac-sign", "--mac-signing-key-user-name", id) + (System.getenv("MACOS_KEYCHAIN")?.takeIf { it.isNotBlank() }?.let { listOf("--mac-signing-keychain", it) } ?: emptyList())
} ?: emptyList()

fun Jpackage.common() {
    group = "distribution"
    appName.set(packaging.appName)
    appVersion.set(packaging.appVersion.map { numericVersion(it) })
    vendor.set(packaging.vendor)
    appDescription.set(packaging.appDescription)
    copyright.set(packaging.copyright)
    aboutUrl.set(packaging.homepage)
    jdkHome.set(layout.dir(jdk.map { it.asFile }))
    icon.set(exportIcons.map { packageDir.get().file("icons/" + packaging.appName.get() + iconExtension()) })
}

fun iconExtension() = when (host.os) {
    HostOs.MAC -> ".icns"
    HostOs.WINDOWS -> ".ico"
    HostOs.LINUX -> ".png"
}

val appImageTask = tasks.register<Jpackage>("appImage") {
    common()
    description = "Builds the self-contained app for this platform."
    dependsOn(exportIcons)
    type.set("app-image")
    appInput.set(prepareAppInput.flatMap { it.outputDir })
    mainJar.set(tasks.named<Jar>("jar").flatMap { it.archiveFileName })
    mainClass.set(packaging.mainClass)
    runtimeImage.set(jlinkRuntime.flatMap { it.outputDir })
    // Native libraries load from the app's own folder, never unpacked into a temp folder.
    javaOptions.set(
        packaging.jvmArgs.map {
            it + listOf(
                "-Dorg.sqlite.lib.path=\$APPDIR/natives",
                "-Dorg.sqlite.lib.name=${host.sqliteLibraryName}",
                "-Dflatlaf.nativeLibraryPath=\$APPDIR/natives",
                "-Dreadarea.packaged=true",
            )
        },
    )
    extraArgs.set(
        when (host.os) {
            HostOs.MAC -> packaging.identifier.map { listOf("--mac-package-identifier", it, "--mac-package-name", packaging.appName.get(), "--mac-app-category", "public.app-category.books") + macSigning }
            else -> provider { emptyList() }
        },
    )
    destination.set(packageDir.map { it.dir("image") })
}

val selfTestImage = tasks.register<SelfTestImage>("selfTestImage") {
    group = "verification"
    description = "Runs the packaged app's self-test through its native launcher."
    appImage.set(appImageTask.flatMap { it.destination })
    appName.set(packaging.appName)
    report.set(packageDir.map { it.file("self-test.txt") })
}

val installers = host.installerTypes.map { installerType ->
    tasks.register<Jpackage>("package" + installerType.replaceFirstChar { it.uppercase() }) {
        common()
        description = "Builds the $installerType installer."
        type.set(installerType)
        appImage.set(appImageTask.flatMap { it.destination })
        licenseFile.set(packaging.licenseFile)
        resourceDir.set(packaging.resourceDir.map { it.dir(host.os.name.lowercase()) }.filter { it.asFile.isDirectory })
        fileAssociations.set(packaging.fileAssociations)
        extraArgs.set(
            provider {
                val name = packaging.appName.get()
                when (host.os) {
                    HostOs.WINDOWS -> listOf(
                        "--win-menu", "--win-menu-group", name, "--win-shortcut", "--win-shortcut-prompt", "--win-dir-chooser",
                        // Installs for the current user, without asking for administrator rights.
                        "--win-per-user-install", "--win-upgrade-uuid", packaging.windowsUpgradeUuid.get(), "--win-help-url", packaging.homepage.get(),
                    )
                    HostOs.LINUX -> listOf(
                        "--linux-package-name", name.lowercase(), "--linux-shortcut", "--linux-menu-group", "Office;Viewer;",
                        "--linux-app-category", if (installerType == "deb") "text" else "Applications/Text", "--linux-rpm-license-type", "MIT",
                    )
                    HostOs.MAC -> listOf("--mac-package-identifier", packaging.identifier.get(), "--mac-package-name", name) + macSigning
                }
            },
        )
        destination.set(packageDir.map { it.dir("installer-$installerType") })
    }
}

val portableArchive = if (host.os == HostOs.MAC) null else if (host.os == HostOs.WINDOWS) {
    tasks.register<Zip>("portableArchive") {
        group = "distribution"
        description = "Packs the app image as a zip that runs without installing."
        from(appImageTask.flatMap { it.destination })
        archiveFileName.set(packaging.appName.zip(packaging.appVersion) { n, v -> "$n-$v-${host.classifier}-portable.zip" })
        destinationDirectory.set(packageDir.map { it.dir("portable") })
    }
} else {
    tasks.register<Tar>("portableArchive") {
        group = "distribution"
        description = "Packs the app image as a tar.gz that runs without installing."
        from(appImageTask.flatMap { it.destination })
        compression = Compression.GZIP
        archiveFileName.set(packaging.appName.zip(packaging.appVersion) { n, v -> "$n-$v-${host.classifier}.tar.gz" })
        destinationDirectory.set(packageDir.map { it.dir("portable") })
    }
}

tasks.register<Sync>("packageDesktop") {
    group = "distribution"
    description = "Builds, checks and collects every installer for this platform into build/package/dist."
    dependsOn(selfTestImage)
    for (t in installers) from(t.flatMap { it.destination }) { rename { n -> installerName(n) } }
    portableArchive?.let { from(it) }
    into(packageDir.map { it.dir("dist") })
}

/** Gives installers names that say which platform and processor they're for. */
fun installerName(original: String): String {
    val ext = listOf(".tar.gz", ".dmg", ".msi", ".deb", ".rpm", ".exe", ".pkg").firstOrNull { original.endsWith(it) } ?: return original
    return "${packaging.appName.get()}-${packaging.appVersion.get()}-${host.classifier}$ext"
}
