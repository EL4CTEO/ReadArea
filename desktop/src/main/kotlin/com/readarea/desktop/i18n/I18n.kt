package com.readarea.desktop.i18n

import java.text.MessageFormat
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

/**
 * UI text. Strings shared with the Android app come from its resources (generated at build time); the few
 * desktop-only ones live in `i18n/desktop*.properties`. Both are looked up for the chosen language, then
 * English.
 */
object I18n {
    /** Language tag to its own name, in the order the settings list shows them. */
    val languages: List<Pair<String, String>> = listOf(
        "en" to "English", "ar" to "العربية", "de" to "Deutsch", "es" to "Español", "fr" to "Français", "hi" to "हिन्दी",
        "id" to "Bahasa Indonesia", "it" to "Italiano", "ja" to "日本語", "ko" to "한국어", "nl" to "Nederlands", "pl" to "Polski",
        "pt-BR" to "Português (Brasil)", "ru" to "Русский", "tr" to "Türkçe", "uk" to "Українська", "zh-CN" to "简体中文",
    )

    @Volatile var locale: Locale = Locale.getDefault()
        private set
    private var shared: ResourceBundle? = null
    private var desktop: ResourceBundle? = null

    val rtl: Boolean get() = locale.language in setOf("ar", "he", "iw", "fa", "ur")

    fun init(tag: String) {
        locale = if (tag.isBlank() || tag == "system") Locale.getDefault() else Locale.forLanguageTag(tag)
        val control = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)
        shared = runCatching { ResourceBundle.getBundle("i18n/strings", locale, I18n::class.java.classLoader, control) }.getOrNull()
        desktop = runCatching { ResourceBundle.getBundle("i18n/desktop", locale, I18n::class.java.classLoader, control) }.getOrNull()
        Locale.setDefault(Locale.Category.FORMAT, locale)
    }

    private fun lookup(key: String): String? {
        for (b in listOf(desktop, shared)) {
            if (b == null) continue
            try {
                return b.getString(key)
            } catch (_: MissingResourceException) {
            }
        }
        return null
    }

    operator fun get(key: String): String = lookup(key) ?: key

    fun has(key: String): Boolean = lookup(key) != null

    /** A string with `%s`/`%1$d` placeholders, as in the Android resources. */
    fun format(key: String, vararg args: Any?): String = runCatching { String.format(locale, get(key), *args) }.getOrElse { get(key) }

    /** A plural string for [count], picking the language's plural category. */
    fun plural(key: String, count: Int, vararg args: Any?): String {
        val cat = category(locale.language, count)
        val pattern = lookup("$key#$cat") ?: lookup("$key#other") ?: lookup("$key#one") ?: return key
        val all = if (args.isEmpty()) arrayOf<Any?>(count) else args
        return runCatching { String.format(locale, pattern, *all) }.getOrElse { pattern }
    }

    fun message(pattern: String, vararg args: Any?): String = MessageFormat(pattern, locale).format(args)

    /** CLDR cardinal plural categories for the languages the app ships. */
    fun category(lang: String, n: Int): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when (lang) {
            "ja", "ko", "zh", "id", "in", "tr" -> if (lang == "tr" && n == 1) "one" else "other"
            "fr", "hi", "pt" -> if (n == 0 || n == 1) "one" else "other"
            "ru", "uk" -> when {
                mod10 == 1 && mod100 != 11 -> "one"
                mod10 in 2..4 && mod100 !in 12..14 -> "few"
                else -> "many"
            }
            "pl" -> when {
                n == 1 -> "one"
                mod10 in 2..4 && mod100 !in 12..14 -> "few"
                else -> "many"
            }
            "ar" -> when {
                n == 0 -> "zero"
                n == 1 -> "one"
                n == 2 -> "two"
                mod100 in 3..10 -> "few"
                mod100 in 11..99 -> "many"
                else -> "other"
            }
            else -> if (n == 1) "one" else "other"
        }
    }
}

/** Shorthand for [I18n.get]. */
fun tr(key: String): String = I18n[key]

fun tr(key: String, vararg args: Any?): String = I18n.format(key, *args)
