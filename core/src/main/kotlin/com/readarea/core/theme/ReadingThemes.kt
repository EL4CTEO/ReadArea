package com.readarea.core.theme

/** Colors of a reading theme as ARGB ints, the same on every platform. */
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

    val highlightColors = intArrayOf(0xFFFFD54F.toInt(), 0xFF81C784.toInt(), 0xFF64B5F6.toInt(), 0xFFF48FB1.toInt(), 0xFFFFB74D.toInt())
    val highlightNames = listOf("Yellow", "Green", "Blue", "Pink", "Orange")

    fun highlightColor(index: Int): Int = highlightColors.getOrElse(index) { highlightColors[0] }

    /**
     * The theme to draw with. With [autoNight] on, the system being dark swaps in [nightTheme]; "custom"
     * builds a theme from the reader's own page and text colors.
     */
    fun resolve(theme: String, nightTheme: String, autoNight: Boolean, systemDark: Boolean, customBg: Int, customFg: Int, texture: Boolean): ReadingTheme {
        val id = if (systemDark && autoNight) nightTheme else theme
        if (id == "custom") return custom(customBg, customFg, texture)
        val t = all.firstOrNull { it.id == id } ?: all[1]
        return t.copy(texture = t.texture && texture)
    }

    fun custom(bg: Int, fg: Int, texture: Boolean): ReadingTheme {
        val dark = luminance(bg) < 0.4
        val secondary = blend(fg, bg, 0.45f)
        val link = if (dark) 0xFF88B4E7.toInt() else 0xFF2F6FDB.toInt()
        return ReadingTheme("custom", "Custom", bg or 0xFF000000.toInt(), fg or 0xFF000000.toInt(), secondary, link, link, dark, texture)
    }

    fun luminance(c: Int): Double = (0.299 * red(c) + 0.587 * green(c) + 0.114 * blue(c)) / 255.0

    fun blend(a: Int, b: Int, t: Float): Int {
        val r = (red(a) * (1 - t) + red(b) * t).toInt()
        val g = (green(a) * (1 - t) + green(b) * t).toInt()
        val bl = (blue(a) * (1 - t) + blue(b) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF

    /** WCAG contrast ratio between two opaque colors, 1..21. */
    fun contrast(a: Int, b: Int): Double {
        fun lin(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        fun rel(c: Int) = 0.2126 * lin(red(c)) + 0.7152 * lin(green(c)) + 0.0722 * lin(blue(c))
        val la = rel(a)
        val lb = rel(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
