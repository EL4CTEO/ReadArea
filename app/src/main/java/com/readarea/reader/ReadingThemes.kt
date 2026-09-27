package com.readarea.reader

import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import com.readarea.data.ReaderSettings
import java.io.File

data class ReadingTheme(
    val id: String,
    val name: String,
    val background: Int,
    val text: Int,
    val secondary: Int,
    val link: Int,
    val accent: Int,
    val dark: Boolean,
    val texture: Boolean = false,
)

object ReadingThemes {
    val all = listOf(
        ReadingTheme("day", "Day", 0xFFFFFFFF.toInt(), 0xFF1F1F1F.toInt(), 0xFF8A8A8A.toInt(), 0xFF2F6FDB.toInt(), 0xFF2F6FDB.toInt(), false),
        ReadingTheme("paper", "Paper", 0xFFF7F1E3.toInt(), 0xFF2E2A24.toInt(), 0xFF968C7C.toInt(), 0xFF8C5A2B.toInt(), 0xFFB5733A.toInt(), false, true),
        ReadingTheme("sepia", "Sepia", 0xFFEEDFC2.toInt(), 0xFF4A3928.toInt(), 0xFF9A8466.toInt(), 0xFF8A4B1E.toInt(), 0xFFA65E2E.toInt(), false, true),
        ReadingTheme("mint", "Mint", 0xFFE6F0E8.toInt(), 0xFF22332A.toInt(), 0xFF7F9486.toInt(), 0xFF2E7D5B.toInt(), 0xFF2E7D5B.toInt(), false),
        ReadingTheme("sky", "Sky", 0xFFE8EEF6.toInt(), 0xFF1E2A3A.toInt(), 0xFF7D8A9C.toInt(), 0xFF3565B0.toInt(), 0xFF3565B0.toInt(), false),
        ReadingTheme("dusk", "Dusk", 0xFF2E3440.toInt(), 0xFFD8DEE9.toInt(), 0xFF7F889A.toInt(), 0xFF88C0D0.toInt(), 0xFF88C0D0.toInt(), true),
        ReadingTheme("night", "Night", 0xFF1A1A1C.toInt(), 0xFFC4BDB2.toInt(), 0xFF6F6A63.toInt(), 0xFFD6A55C.toInt(), 0xFFD6A55C.toInt(), true),
        ReadingTheme("amoled", "AMOLED", 0xFF000000.toInt(), 0xFFA8A8A8.toInt(), 0xFF5A5A5A.toInt(), 0xFF7FA7D9.toInt(), 0xFF7FA7D9.toInt(), true),
    )

    fun resolve(s: ReaderSettings, night: Boolean = false): ReadingTheme {
        val id = if (night && s.autoNight) s.nightTheme else s.theme
        if (id == "custom") {
            val dark = luminance(s.customBg) < 0.4
            val secondary = blend(s.customFg, s.customBg, 0.45f)
            return ReadingTheme("custom", "Custom", s.customBg, s.customFg, secondary, if (dark) 0xFF88B4E7.toInt() else 0xFF2F6FDB.toInt(), if (dark) 0xFF88B4E7.toInt() else 0xFF2F6FDB.toInt(), dark, s.texture)
        }
        val t = all.firstOrNull { it.id == id } ?: all[1]
        return t.copy(texture = t.texture && s.texture)
    }

    fun luminance(c: Int): Double = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0

    fun blend(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) * (1 - t) + Color.red(b) * t).toInt()
        val g = (Color.green(a) * (1 - t) + Color.green(b) * t).toInt()
        val bl = (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt()
        return Color.rgb(r, g, bl)
    }

    val highlightColors = intArrayOf(0xFFFFD54F.toInt(), 0xFF81C784.toInt(), 0xFF64B5F6.toInt(), 0xFFF48FB1.toInt(), 0xFFFFB74D.toInt())
    val highlightNames = listOf("Yellow", "Green", "Blue", "Pink", "Orange")
}

data class FontOption(val key: String, val label: String)

object ReaderFonts {
    val builtIn = listOf(
        FontOption("serif", "Serif"),
        FontOption("sans", "Sans"),
        FontOption("light", "Light"),
        FontOption("condensed", "Condensed"),
        FontOption("medium", "Medium"),
        FontOption("serif-mono", "Typewriter"),
        FontOption("mono", "Mono"),
        FontOption("casual", "Casual"),
        FontOption("cursive", "Cursive"),
    )

    private val cache = HashMap<String, Typeface>()

    fun label(key: String): String = builtIn.firstOrNull { it.key == key }?.label ?: File(key.removePrefix("file:")).nameWithoutExtension

    fun base(key: String): Typeface = synchronized(cache) {
        cache.getOrPut(key) {
            when (key) {
                "serif" -> Typeface.SERIF
                "sans" -> Typeface.SANS_SERIF
                "light" -> Typeface.create("sans-serif-light", Typeface.NORMAL)
                "condensed" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                "medium" -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
                "serif-mono" -> Typeface.create("serif-monospace", Typeface.NORMAL)
                "mono" -> Typeface.MONOSPACE
                "casual" -> Typeface.create("casual", Typeface.NORMAL)
                "cursive" -> Typeface.create("cursive", Typeface.NORMAL)
                else -> runCatching { Typeface.createFromFile(key.removePrefix("file:")) }.getOrDefault(Typeface.SERIF)
            }
        }
    }

    fun resolve(key: String, weight: Int): Typeface {
        val base = base(key)
        if (weight == 400 || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return base
        return Typeface.create(base, weight.coerceIn(100, 900), false)
    }
}
