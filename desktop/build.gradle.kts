import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.xml.parsers.DocumentBuilderFactory

// ReadArea for macOS, Windows and Linux: a Kotlin/JVM Swing app sharing :core with the Android app.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
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

/**
 * Turns the Android string resources (the single source of truth for all 17 languages) into resource
 * bundles for the desktop app. Plurals become `name#one`, `name#few` and so on.
 */
abstract class GenerateStrings : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outDir.get().asFile.resolve("i18n")
        out.deleteRecursively()
        out.mkdirs()
        val folders = resDir.get().asFile.listFiles { f -> f.isDirectory && f.name.startsWith("values") && f.resolve("strings.xml").isFile }.orEmpty()
        for (folder in folders.sortedBy { it.name }) {
            val suffixes = bundleSuffixes(folder.name.removePrefix("values"))
            val entries = parse(folder.resolve("strings.xml"))
            val text = buildString {
                append("# Generated from app/src/main/res/").append(folder.name).append("/strings.xml. Do not edit.\n")
                for ((k, v) in entries.toSortedMap()) append(k).append('=').append(escape(v)).append('\n')
            }
            for (suffix in suffixes) out.resolve("strings$suffix.properties").writeText(text, Charsets.ISO_8859_1)
        }
    }

    private fun bundleSuffixes(qualifier: String): List<String> = when (qualifier) {
        "" -> listOf("")
        "-in" -> listOf("_in", "_id")
        "-pt-rBR" -> listOf("_pt_BR", "_pt")
        "-zh-rCN" -> listOf("_zh_CN", "_zh")
        else -> listOf("_" + qualifier.removePrefix("-").replace("-r", "_"))
    }

    private fun parse(file: File): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.isExpandEntityReferences = false
        val doc = factory.newDocumentBuilder().parse(file)
        val out = LinkedHashMap<String, String>()
        val strings = doc.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val e = strings.item(i) as org.w3c.dom.Element
            if (e.getAttribute("translatable") == "false") continue
            out[e.getAttribute("name")] = unescape(e.textContent)
        }
        val plurals = doc.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) {
            val p = plurals.item(i) as org.w3c.dom.Element
            val items = p.getElementsByTagName("item")
            for (k in 0 until items.length) {
                val item = items.item(k) as org.w3c.dom.Element
                out[p.getAttribute("name") + "#" + item.getAttribute("quantity")] = unescape(item.textContent)
            }
        }
        return out
    }

    private fun unescape(raw: String): String {
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        val s = raw.trim()
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> {
                    when (val n = s[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        else -> sb.append(n)
                    }
                    i += 2
                    continue
                }
                c == '"' -> quoted = !quoted
                !quoted && c.isWhitespace() -> if (sb.isEmpty() || !sb.last().isWhitespace()) sb.append(' ')
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun escape(v: String): String = buildString {
        v.forEachIndexed { i, c ->
            when {
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\t' -> append("\\t")
                c == ' ' && i == 0 -> append("\\ ")
                c.code < 0x20 || c.code > 0x7E -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
    }
}

val generateStrings by tasks.registering(GenerateStrings::class) {
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
