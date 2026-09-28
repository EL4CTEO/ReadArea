import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ReadArea for macOS, Windows and Linux: a Kotlin/JVM Swing app sharing :core with the Android app.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    id("readarea.desktop-packaging")
}

val appVersion: String = providers.environmentVariable("READAREA_VERSION_NAME").orNull?.takeIf { it.isNotBlank() } ?: "1.0.0"
version = appVersion

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.flatlaf)
    implementation(libs.pdfbox)
    implementation(libs.sqlite.jdbc)
    runtimeOnly(libs.twelvemonkeys.jpeg)
    runtimeOnly(libs.twelvemonkeys.webp)
    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":core")))
}

/** JVM flags shared by `gradle run`, tests and the packaged launchers. */
val desktopJvmArgs = listOf(
    "-Dfile.encoding=UTF-8",
    "-Dawt.useSystemAAFontSettings=on",
    "-Dsun.java2d.metal=true",
    "-Dapple.awt.application.name=ReadArea",
    "-Dapple.laf.useScreenMenuBar=true",
    "-Xss4m",
    "-XX:+UseG1GC",
    "-XX:MaxRAMPercentage=40",
)

application {
    mainClass.set("com.readarea.desktop.MainKt")
    applicationName = "ReadArea"
    applicationDefaultJvmArgs = desktopJvmArgs
}

tasks.jar {
    archiveBaseName.set("readarea-desktop")
    manifest {
        attributes("Implementation-Title" to "ReadArea", "Implementation-Version" to appVersion)
    }
}

// Installers for macOS, Windows and Linux: `./gradlew :desktop:packageDesktop` on each platform.
desktopPackaging {
    appName.set("ReadArea")
    appVersion.set(project.version.toString())
    vendor.set("ReadArea")
    appDescription.set("A calm, private e-book reader")
    copyright.set("Copyright © 2026 EL4CTEO. MIT License.")
    homepage.set("https://github.com/EL4CTEO/ReadArea")
    identifier.set("com.readarea.desktop")
    mainClass.set(application.mainClass)
    iconExporter.set("com.readarea.desktop.tools.IconExport")
    jvmArgs.set(desktopJvmArgs)
    // Used by name, so jdeps can't see them: legacy text encodings, locale data for dates and numbers,
    // and the bridge screen readers use on Windows.
    extraModules.set(listOf("jdk.charsets", "jdk.localedata", "jdk.accessibility"))
    locales.set(listOf("en", "ar", "de", "es", "fr", "hi", "id", "in", "it", "ja", "ko", "nl", "pl", "pt", "ru", "tr", "uk", "zh"))
    licenseFile.set(rootProject.layout.projectDirectory.file("LICENSE"))
    resourceDir.set(layout.projectDirectory.dir("packaging"))
    windowsUpgradeUuid.set("d47633b5-2e65-482b-bd2a-d0083ba4778f")
    // E-book formats only: ReadArea shouldn't take over PDFs or documents other apps usually open.
    fileAssociations.set(
        listOf(
            readarea.build.FileAssociation("epub", "application/epub+zip", "EPUB e-book"),
            readarea.build.FileAssociation("mobi", "application/x-mobipocket-ebook", "Mobipocket e-book"),
            readarea.build.FileAssociation("azw3", "application/vnd.amazon.ebook", "Kindle e-book"),
            readarea.build.FileAssociation("fb2", "application/x-fictionbook+xml", "FictionBook e-book"),
            readarea.build.FileAssociation("cbz", "application/vnd.comicbook+zip", "Comic book archive"),
        ),
    )
}

val generateStrings = tasks.register<readarea.build.GenerateStrings>("generateStrings") {
    resDir.set(rootProject.layout.projectDirectory.dir("app/src/main/res"))
    outDir.set(layout.buildDirectory.dir("generated/strings"))
}

sourceSets.main {
    resources.srcDir(generateStrings)
}

tasks.test {
    maxHeapSize = "2g"
    jvmArgs(desktopJvmArgs.filter { it.startsWith("-D") })
    systemProperty("readarea.test", "true")
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        events("failed")
    }
}

tasks.named<JavaExec>("run") {
    // `./gradlew :desktop:run --args="book.epub"`; READAREA_HOME keeps a development library apart.
    environment("READAREA_HOME", providers.environmentVariable("READAREA_HOME").orElse(layout.buildDirectory.dir("dev-home").get().asFile.path).get())
}
