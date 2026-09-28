package readarea.tooling

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/** What the desktop installers are made of; set in the desktop module's build script. */
abstract class DesktopPackagingExtension {
    abstract val appName: Property<String>
    abstract val appVersion: Property<String>
    abstract val vendor: Property<String>
    abstract val appDescription: Property<String>
    abstract val copyright: Property<String>
    abstract val homepage: Property<String>

    /** Reverse-DNS identifier, used for the macOS bundle. */
    abstract val identifier: Property<String>
    abstract val mainClass: Property<String>

    /** Main class that writes the icons: `main(outputDir)` producing `<appName>.png`, `.ico` and `.icns`. */
    abstract val iconExporter: Property<String>
    abstract val jvmArgs: ListProperty<String>

    /** Runtime modules jdeps can't find because they're used by name (charsets, locale data...). */
    abstract val extraModules: ListProperty<String>

    /** Languages whose locale data the runtime keeps. */
    abstract val locales: ListProperty<String>
    abstract val fileAssociations: ListProperty<FileAssociation>
    abstract val licenseFile: RegularFileProperty

    /** jpackage resource overrides, e.g. the Linux menu entry; files for other platforms are ignored. */
    abstract val resourceDir: org.gradle.api.file.DirectoryProperty

    /** Stays the same across versions so Windows upgrades an installed copy instead of adding another. */
    abstract val windowsUpgradeUuid: Property<String>
}
