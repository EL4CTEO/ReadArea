package com.readarea.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readarea.reader.ReadingTheme
import com.readarea.reader.ReadingThemes

object Accents {
    val colors = listOf(
        0xFF9A5B34.toInt(),
        0xFF3F6E8C.toInt(),
        0xFF4F7A5A.toInt(),
        0xFF8A4F7D.toInt(),
        0xFFB0463C.toInt(),
        0xFF5E5CA8.toInt(),
    )
    val names = listOf("Sienna", "Ink", "Sage", "Plum", "Brick", "Iris")
}

private fun lightScheme(accent: Color): ColorScheme = lightColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = lerp(accent, Color.White, 0.82f),
    onPrimaryContainer = lerp(accent, Color.Black, 0.55f),
    secondary = Color(0xFF6B6356),
    secondaryContainer = Color(0xFFEDE4D6),
    onSecondaryContainer = Color(0xFF2C261E),
    tertiary = Color(0xFF4F6D7A),
    tertiaryContainer = Color(0xFFD9E7EE),
    background = Color(0xFFFBF8F3),
    onBackground = Color(0xFF1E1B17),
    surface = Color(0xFFFBF8F3),
    onSurface = Color(0xFF1E1B17),
    surfaceVariant = Color(0xFFEFE8DE),
    onSurfaceVariant = Color(0xFF55504A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F2EA),
    surfaceContainer = Color(0xFFF2ECE3),
    surfaceContainerHigh = Color(0xFFECE5DB),
    surfaceContainerHighest = Color(0xFFE6DFD4),
    outline = Color(0xFF8A8278),
    outlineVariant = Color(0xFFD8CFC3),
)

private fun darkScheme(accent: Color): ColorScheme {
    val a = lerp(accent, Color.White, 0.38f)
    return darkColorScheme(
        primary = a,
        onPrimary = lerp(accent, Color.Black, 0.7f),
        primaryContainer = lerp(accent, Color.Black, 0.45f),
        onPrimaryContainer = lerp(accent, Color.White, 0.8f),
        secondary = Color(0xFFCFC5B6),
        secondaryContainer = Color(0xFF3A342C),
        onSecondaryContainer = Color(0xFFEDE4D6),
        tertiary = Color(0xFFA9C8D6),
        tertiaryContainer = Color(0xFF2E4650),
        background = Color(0xFF15130F),
        onBackground = Color(0xFFEAE3D8),
        surface = Color(0xFF15130F),
        onSurface = Color(0xFFEAE3D8),
        surfaceVariant = Color(0xFF3A362F),
        onSurfaceVariant = Color(0xFFCBC3B7),
        surfaceContainerLowest = Color(0xFF100E0B),
        surfaceContainerLow = Color(0xFF1C1915),
        surfaceContainer = Color(0xFF211E19),
        surfaceContainerHigh = Color(0xFF2B2722),
        surfaceContainerHighest = Color(0xFF36322C),
        outline = Color(0xFF958D82),
        outlineVariant = Color(0xFF4A453D),
    )
}

fun lerp(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

private val AppTypography = Typography().let { t ->
    val serif = FontFamily.Serif
    t.copy(
        displaySmall = t.displaySmall.copy(fontFamily = serif, fontWeight = FontWeight.SemiBold),
        headlineLarge = t.headlineLarge.copy(fontFamily = serif, fontWeight = FontWeight.SemiBold),
        headlineMedium = t.headlineMedium.copy(fontFamily = serif, fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.copy(fontFamily = serif, fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontFamily = serif, fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun ReadAreaTheme(
    themeMode: String = "system",
    dynamic: Boolean = false,
    accent: Int = 0,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val accentColor = Color(Accents.colors.getOrElse(accent) { Accents.colors[0] })
    val context = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkScheme(accentColor)
        else -> lightScheme(accentColor)
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}

@Composable
fun ReaderChromeTheme(theme: ReadingTheme, content: @Composable () -> Unit) {
    val bg = Color(theme.background)
    val fg = Color(theme.text)
    val accent = Color(theme.accent)
    val surface = if (theme.dark) lerp(bg, Color.White, 0.07f) else lerp(bg, Color.White, 0.55f)
    val scheme = if (theme.dark) {
        darkColorScheme(
            primary = accent,
            onPrimary = bg,
            primaryContainer = lerp(accent, bg, 0.6f),
            onPrimaryContainer = fg,
            secondaryContainer = lerp(surface, fg, 0.12f),
            onSecondaryContainer = fg,
            background = bg,
            onBackground = fg,
            surface = surface,
            onSurface = fg,
            surfaceVariant = lerp(surface, fg, 0.1f),
            onSurfaceVariant = Color(theme.secondary).let { lerp(it, fg, 0.35f) },
            surfaceContainerLow = lerp(surface, fg, 0.03f),
            surfaceContainer = lerp(surface, fg, 0.05f),
            surfaceContainerHigh = lerp(surface, fg, 0.08f),
            surfaceContainerHighest = lerp(surface, fg, 0.12f),
            outline = Color(theme.secondary),
            outlineVariant = lerp(surface, fg, 0.18f),
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            primaryContainer = lerp(accent, Color.White, 0.8f),
            onPrimaryContainer = lerp(accent, Color.Black, 0.5f),
            secondaryContainer = lerp(surface, fg, 0.08f),
            onSecondaryContainer = fg,
            background = bg,
            onBackground = fg,
            surface = surface,
            onSurface = fg,
            surfaceVariant = lerp(surface, fg, 0.07f),
            onSurfaceVariant = lerp(Color(theme.secondary), fg, 0.4f),
            surfaceContainerLow = lerp(surface, fg, 0.02f),
            surfaceContainer = lerp(surface, fg, 0.04f),
            surfaceContainerHigh = lerp(surface, fg, 0.06f),
            surfaceContainerHighest = lerp(surface, fg, 0.09f),
            outline = Color(theme.secondary),
            outlineVariant = lerp(surface, fg, 0.14f),
        )
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}

fun Int.asColor(): Color = Color(this)

fun Color.argb(): Int = toArgb()

val highlightPalette: List<Color> = ReadingThemes.highlightColors.map { Color(it) }
