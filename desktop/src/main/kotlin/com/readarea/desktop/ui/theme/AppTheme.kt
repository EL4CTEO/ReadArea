package com.readarea.desktop.ui.theme

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf
import com.formdev.flatlaf.themes.FlatMacDarkLaf
import com.formdev.flatlaf.themes.FlatMacLightLaf
import com.readarea.desktop.platform.Os
import com.readarea.desktop.reader.engine.ReaderFonts
import java.awt.Color
import java.awt.Font
import javax.swing.UIManager

/** The app's colors, matching the Android app's warm Material scheme, in a light and a dark variant. */
data class Palette(
    val dark: Boolean,
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val background: Color,
    val sidebar: Color,
    val surface: Color,
    val surfaceLow: Color,
    val surfaceContainer: Color,
    val surfaceHigh: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val danger: Color,
    val success: Color,
)

object Accents {
    val colors = listOf(0x9A5B34, 0x3F6E8C, 0x4F7A5A, 0x8A4F7D, 0xB0463C, 0x5E5CA8)
    val names = listOf("Sienna", "Ink", "Sage", "Plum", "Brick", "Iris")
}

fun lerp(a: Color, b: Color, t: Float): Color = Color(
    (a.red + (b.red - a.red) * t).toInt().coerceIn(0, 255),
    (a.green + (b.green - a.green) * t).toInt().coerceIn(0, 255),
    (a.blue + (b.blue - a.blue) * t).toInt().coerceIn(0, 255),
)

fun Color.alpha(a: Int): Color = Color(red, green, blue, a.coerceIn(0, 255))

object AppTheme {
    @Volatile var palette: Palette = light(Color(Accents.colors[0]))
        private set

    val listeners = ArrayList<() -> Unit>()

    fun light(accent: Color) = Palette(
        dark = false,
        accent = accent,
        onAccent = Color.WHITE,
        accentContainer = lerp(accent, Color.WHITE, 0.82f),
        onAccentContainer = lerp(accent, Color.BLACK, 0.55f),
        background = Color(0xFBF8F3),
        sidebar = Color(0xF3EDE4),
        surface = Color(0xFFFFFF),
        surfaceLow = Color(0xF7F2EA),
        surfaceContainer = Color(0xF2ECE3),
        surfaceHigh = Color(0xECE5DB),
        onSurface = Color(0x1E1B17),
        onSurfaceVariant = Color(0x5F5850),
        outline = Color(0x8A8278),
        outlineVariant = Color(0xD8CFC3),
        danger = Color(0xB3261E),
        success = Color(0x2E7D5B),
    )

    fun dark(accent: Color) = Palette(
        dark = true,
        accent = lerp(accent, Color.WHITE, 0.38f),
        onAccent = lerp(accent, Color.BLACK, 0.7f),
        accentContainer = lerp(accent, Color.BLACK, 0.45f),
        onAccentContainer = lerp(accent, Color.WHITE, 0.8f),
        background = Color(0x15130F),
        sidebar = Color(0x1C1915),
        surface = Color(0x211E19),
        surfaceLow = Color(0x1C1915),
        surfaceContainer = Color(0x26221D),
        surfaceHigh = Color(0x2E2A24),
        onSurface = Color(0xEAE3D8),
        onSurfaceVariant = Color(0xB9B1A5),
        outline = Color(0x958D82),
        outlineVariant = Color(0x4A453D),
        danger = Color(0xF2B8B5),
        success = Color(0x8FD1AF),
    )

    /** A serif family for headings, like the book-ish titles of the Android app. */
    val headlineFamily: String by lazy { ReaderFonts.familyFor("serif") }

    fun headline(size: Float, bold: Boolean = true): Font = Font(headlineFamily, if (bold) Font.BOLD else Font.PLAIN, 1).deriveFont(size)

    fun ui(size: Float, style: Int = Font.PLAIN): Font = (UIManager.getFont("defaultFont") ?: Font(Font.SANS_SERIF, Font.PLAIN, 13)).deriveFont(style, size)

    /** Applies the look and feel for [dark] with [accent], then tells open windows to repaint. */
    fun apply(dark: Boolean, accentIndex: Int) {
        val accent = Color(Accents.colors.getOrElse(accentIndex) { Accents.colors[0] })
        val p = if (dark) dark(accent) else light(accent)
        palette = p
        FlatLaf.setGlobalExtraDefaults(
            mapOf(
                "@accentColor" to hex(p.accent),
                "@background" to hex(p.background),
                "@foreground" to hex(p.onSurface),
                "@selectionBackground" to hex(p.accent),
                "Component.arc" to "10",
                "Button.arc" to "999",
                "TextComponent.arc" to "10",
                "CheckBox.arc" to "6",
                "ScrollBar.thumbArc" to "999",
                "ScrollBar.thumbInsets" to "2,2,2,2",
                "ScrollBar.width" to "11",
                "ScrollBar.track" to hex(p.background),
                "Panel.background" to hex(p.background),
                "Popup.borderCornerRadius" to "12",
                "PopupMenu.borderCornerRadius" to "10",
                "MenuItem.selectionArc" to "8",
                "Component.focusWidth" to "1",
                "Component.innerFocusWidth" to "0",
                "TitlePane.unifiedBackground" to "true",
                "TitlePane.background" to hex(p.sidebar),
                "RootPane.background" to hex(p.background),
                "Slider.trackWidth" to "4",
                "Slider.thumbSize" to "16,16",
                "ToolTip.background" to hex(if (dark) Color(0x36322C) else Color(0x322D28)),
                "ToolTip.foreground" to hex(Color(0xF2ECE3)),
            ),
        )
        val laf = when {
            Os.current == Os.MAC && dark -> FlatMacDarkLaf()
            Os.current == Os.MAC -> FlatMacLightLaf()
            dark -> FlatDarkLaf()
            else -> FlatLightLaf()
        }
        FlatLaf.setup(laf)
        FlatLaf.updateUI()
        listeners.toList().forEach { it() }
    }

    private fun hex(c: Color) = "#%02x%02x%02x".format(c.red, c.green, c.blue)
}
