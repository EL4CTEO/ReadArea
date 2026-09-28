package readarea.tooling

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

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
