package com.readarea.desktop.reader.engine

import com.readarea.desktop.platform.Os
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.font.TextAttribute
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class FontOption(val key: String, val label: String)

/**
 * Reading fonts. The built-in choices map to the nicest face each platform has installed; any installed
 * family can be picked too, and font files the reader imports live in the app's fonts folder.
 *
 * Java's physical fonts don't fall back for missing characters, so [Fallback] routes those (CJK, Arabic,
 * symbols…) to the platform's composite logical fonts.
 */
object ReaderFonts {
    private val SERIF = when (Os.current) {
        Os.MAC -> listOf("Iowan Old Style", "Charter", "Georgia", "Palatino", "Baskerville", "Times New Roman")
        Os.WINDOWS -> listOf("Georgia", "Cambria", "Palatino Linotype", "Book Antiqua", "Constantia", "Times New Roman")
        else -> listOf("Literata", "Noto Serif", "Source Serif 4", "Merriweather", "Charis SIL", "DejaVu Serif", "Liberation Serif", "FreeSerif")
    }
    private val SANS = when (Os.current) {
        Os.MAC -> listOf("Avenir Next", "Helvetica Neue", "Helvetica", "Arial")
        Os.WINDOWS -> listOf("Segoe UI", "Calibri", "Verdana", "Arial")
        else -> listOf("Inter", "Noto Sans", "Open Sans", "Cantarell", "Ubuntu", "DejaVu Sans", "Liberation Sans", "FreeSans")
    }
    private val MONO = when (Os.current) {
        Os.MAC -> listOf("SF Mono", "Menlo", "Monaco", "Courier New")
        Os.WINDOWS -> listOf("Cascadia Mono", "Consolas", "Lucida Console", "Courier New")
        else -> listOf("JetBrains Mono", "Noto Sans Mono", "DejaVu Sans Mono", "Liberation Mono", "FreeMono")
    }
    private val TYPEWRITER = listOf("American Typewriter", "Courier Prime", "Courier New", "Courier 10 Pitch", "Courier", "FreeMono")
    private val DYSLEXIC = listOf("OpenDyslexic", "Lexend", "Atkinson Hyperlegible", "Verdana", "Tahoma")

    val builtIn = listOf(
        FontOption("serif", "Serif"),
        FontOption("sans", "Sans"),
        FontOption("mono", "Mono"),
        FontOption("typewriter", "Typewriter"),
        FontOption("legible", "Legible"),
    )

    private val families: Set<String> by lazy {
        runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toHashSet() }.getOrDefault(hashSetOf())
    }

    private val customLoaded = ConcurrentHashMap<String, Font>()
    private val resolved = ConcurrentHashMap<String, Font>()

    /** Families installed on this computer, for the font picker. */
    fun installedFamilies(): List<String> = families.filter { !it.startsWith(".") && it !in LOGICAL }.sorted()

    private val LOGICAL = setOf("Dialog", "DialogInput", "Serif", "SansSerif", "Monospaced")

    private fun firstInstalled(candidates: List<String>, logical: String): String = candidates.firstOrNull { it in families } ?: logical

    fun familyFor(key: String): String = when {
        key == "serif" -> firstInstalled(SERIF, Font.SERIF)
        key == "sans" -> firstInstalled(SANS, Font.SANS_SERIF)
        key == "mono" -> firstInstalled(MONO, Font.MONOSPACED)
        key == "typewriter" -> firstInstalled(TYPEWRITER, Font.MONOSPACED)
        key == "legible" -> firstInstalled(DYSLEXIC, Font.SANS_SERIF)
        key.startsWith("family:") -> key.removePrefix("family:").takeIf { it in families } ?: Font.SERIF
        else -> Font.SERIF
    }

    fun label(key: String): String = builtIn.firstOrNull { it.key == key }?.label
        ?: key.removePrefix("family:").removePrefix("file:").substringBeforeLast('.')

    /** Whether the font is a logical one that already falls back per character. */
    fun isLogical(font: Font): Boolean = font.family in LOGICAL || font.name in LOGICAL

    /**
     * The base font for [key] at 1pt and regular weight; callers derive size, weight and style.
     * [fontsDir] holds fonts the reader imported (`file:name.ttf`).
     */
    fun base(key: String, fontsDir: File?): Font = resolved.getOrPut(key) {
        if (key.startsWith("file:") && fontsDir != null) {
            loadCustom(File(fontsDir, key.removePrefix("file:")))?.let { return@getOrPut it.deriveFont(1f) }
        }
        Font(familyFor(key), Font.PLAIN, 1).deriveFont(1f)
    }

    fun monoBase(): Font = resolved.getOrPut("mono") { Font(familyFor("mono"), Font.PLAIN, 1).deriveFont(1f) }

    /** Loads a TrueType or OpenType font file the reader imported. Only files inside the fonts folder are accepted. */
    fun loadCustom(file: File): Font? = customLoaded[file.path] ?: runCatching {
        if (!file.isFile || file.length() > 40L * 1024 * 1024) return null
        val font = Font.createFont(Font.TRUETYPE_FONT, file)
        customLoaded[file.path] = font
        font
    }.getOrNull()

    fun weight(w: Int): Float = when {
        w <= 150 -> TextAttribute.WEIGHT_EXTRA_LIGHT
        w <= 250 -> TextAttribute.WEIGHT_LIGHT - 0.1f
        w <= 350 -> TextAttribute.WEIGHT_LIGHT
        w <= 450 -> TextAttribute.WEIGHT_REGULAR
        w <= 550 -> TextAttribute.WEIGHT_SEMIBOLD
        w <= 650 -> TextAttribute.WEIGHT_DEMIBOLD
        w <= 750 -> TextAttribute.WEIGHT_BOLD
        w <= 850 -> TextAttribute.WEIGHT_EXTRABOLD
        else -> TextAttribute.WEIGHT_ULTRABOLD
    }

    fun clearCache() {
        resolved.clear()
    }
}

/**
 * Splits text into runs a font can draw and runs that need the fallback font, which on every platform is
 * a logical font that itself falls back across CJK, Arabic, Indic and symbol faces.
 */
class Fallback(private val primary: Font, private val fallback: Font) {
    private val cache = HashMap<Int, Boolean>()

    private fun canDisplay(cp: Int): Boolean {
        if (cp < 0x80) return cp >= 0x20 || cp == 0x09
        return cache.getOrPut(cp) { primary.canDisplay(cp) }
    }

    /** Calls [out] with (start, end, useFallback) for consecutive runs of [text][start, end). */
    inline fun runs(text: CharSequence, start: Int, end: Int, out: (Int, Int, Boolean) -> Unit) {
        var i = start
        var runStart = start
        var runFallback: Boolean? = null
        while (i < end) {
            val cp = Character.codePointAt(text, i)
            val n = Character.charCount(cp)
            // Marks, joiners and spaces follow their neighbours so shaping isn't split mid-word.
            val neutral = Character.getType(cp).let { it == Character.NON_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt() || it == Character.FORMAT.toInt() || it == Character.SPACE_SEPARATOR.toInt() || it == Character.CONTROL.toInt() }
            val fb = if (neutral) runFallback ?: false else !display(cp)
            if (runFallback == null) runFallback = fb
            else if (fb != runFallback) {
                out(runStart, i, runFallback)
                runStart = i
                runFallback = fb
            }
            i += n
        }
        if (end > runStart) out(runStart, end, runFallback ?: false)
    }

    fun display(cp: Int): Boolean = canDisplay(cp)

    val fallbackFont: Font get() = fallback
}
