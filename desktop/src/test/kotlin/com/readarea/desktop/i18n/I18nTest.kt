package com.readarea.desktop.i18n

import com.readarea.core.theme.ReadingThemes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.Properties

class I18nTest {
    private val languages = listOf("ar", "de", "es", "fr", "hi", "id", "it", "ja", "ko", "nl", "pl", "pt-BR", "ru", "tr", "uk", "zh-CN")

    @After
    fun reset() = I18n.init("en")

    private fun desktopKeys(suffix: String): Properties = Properties().apply {
        val name = "/i18n/desktop$suffix.properties"
        I18nTest::class.java.getResourceAsStream(name)!!.reader(Charsets.UTF_8).use { load(it) }
    }

    /** Every string the desktop sources ask for exists in English, in either bundle. */
    @Test
    fun everyKeyUsedInTheSourcesExists() {
        I18n.init("en")
        val call = Regex("""(?:\btr|I18n\.format|I18n\.plural)\(\s*"([a-z0-9_]+)"""")
        val ternary = Regex("""tr\(if \([^)]*\) "([a-z0-9_]+)" else "([a-z0-9_]+)"\)""")
        val used = HashSet<String>()
        File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val text = f.readText()
            call.findAll(text).forEach { used.add(it.groupValues[1]) }
            ternary.findAll(text).forEach { used.add(it.groupValues[1]); used.add(it.groupValues[2]) }
        }
        // Keys built at run time.
        ReadingThemes.all.forEach { used.add("theme_" + it.id) }
        ReadingThemes.highlightNames.forEach { used.add("highlight_" + it.lowercase()) }
        assertTrue("found only ${used.size} keys", used.size > 150)
        // A key ending in "_" is the fixed part of one built at run time, covered above.
        val missing = used.filter { !it.endsWith("_") && !I18n.has(it) && !I18n.has("$it#other") }
        assertTrue("missing strings: $missing", missing.isEmpty())
    }

    /** Each language translates every desktop string, with the plural forms its language needs. */
    @Test
    fun everyLanguageHasEveryDesktopString() {
        val english = desktopKeys("").stringPropertyNames().map { it.substringBefore('#') }.toSet()
        for (tag in languages) {
            val lang = Locale.forLanguageTag(tag).language
            val keys = desktopKeys("_$lang").stringPropertyNames()
            val plain = keys.map { it.substringBefore('#') }.toSet()
            assertEquals("$tag keys", english, plain)
            for (plural in keys.filter { '#' in it }.map { it.substringBefore('#') }.toSet()) {
                for (n in listOf(0, 1, 2, 3, 5, 11, 21, 22, 25, 101, 111)) {
                    val cat = I18n.category(lang, n)
                    assertTrue("$tag $plural#$cat for $n", "$plural#$cat" in keys || "$plural#other" in keys)
                }
            }
        }
    }

    /** Every pattern formats with the arguments the code passes, in every language. */
    @Test
    fun everyPatternFormats() {
        for (tag in listOf("en") + languages) {
            I18n.init(tag)
            val props = desktopKeys(if (tag == "en") "" else "_" + Locale.forLanguageTag(tag).language)
            for (key in props.stringPropertyNames()) {
                val pattern = props.getProperty(key)
                val args: Array<Any?> = Regex("%([sd])").findAll(pattern).map { if (it.groupValues[1] == "d") 3 else "x" }.toList().toTypedArray()
                val out = String.format(I18n.locale, pattern, *args)
                assertTrue("$tag $key", out.isNotBlank() && !out.contains('%'))
            }
            assertTrue("$tag plural", I18n.plural("toast_removed", 3, 3).isNotBlank())
        }
    }

    @Test
    fun pluralCategoriesFollowCldr() {
        assertEquals(listOf("one", "few", "many", "many", "one", "few"), listOf(1, 2, 5, 11, 21, 22).map { I18n.category("ru", it) })
        assertEquals(listOf("one", "few", "many", "many", "few"), listOf(1, 3, 5, 12, 23).map { I18n.category("pl", it) })
        assertEquals(listOf("zero", "one", "two", "few", "many", "other"), listOf(0, 1, 2, 3, 11, 100).map { I18n.category("ar", it) })
        assertEquals(listOf("one", "one", "other"), listOf(0, 1, 2).map { I18n.category("fr", it) })
        assertEquals("other", I18n.category("ja", 1))
    }

    @Test
    fun languagesResolveToTheirBundles() {
        I18n.init("de")
        assertEquals("Tastenkürzel", I18n["keyboard_shortcuts"])
        I18n.init("pt-BR")
        assertEquals("Atalhos de teclado", I18n["keyboard_shortcuts"])
        I18n.init("zh-CN")
        assertEquals("键盘快捷键", I18n["keyboard_shortcuts"])
        I18n.init("id")
        assertEquals("Pintasan keyboard", I18n["keyboard_shortcuts"])
        I18n.init("ru")
        assertEquals("Удалено 5 книг", I18n.plural("toast_removed", 5, 5))
    }
}
