package com.dskmusic.kompressall.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontFamily

/** Acentos disponibles. El primero ("blue") es el valor por defecto. */
val ACCENT_OPTIONS = listOf(
    "blue", "indigo", "green", "teal", "cyan", "purple", "pink", "red",
    "orange", "amber", "lime", "brown"
)

/** Par (variante pastel para tema oscuro, variante intensa para tema claro). */
fun accentPair(name: String): Pair<Color, Color> = when (name) {
    "indigo" -> Color(0xFFB0BEFF) to Color(0xFF3949AB)
    "green"  -> Color(0xFF7FD8BE) to Color(0xFF0E7A5F)
    "teal"   -> Color(0xFF6FD6C9) to Color(0xFF00796B)
    "cyan"   -> Color(0xFF80DEEA) to Color(0xFF00838D)
    "purple" -> Color(0xFFC5A8FF) to Color(0xFF6A3DE8)
    "pink"   -> Color(0xFFF48FB1) to Color(0xFFC2185B)
    "red"    -> Color(0xFFFF8A80) to Color(0xFFC62828)
    "orange" -> Color(0xFFFFB74D) to Color(0xFFE65100)
    "amber"  -> Color(0xFFFFD54F) to Color(0xFFF9A825)
    "lime"   -> Color(0xFFC6E074) to Color(0xFF808F00)
    "brown"  -> Color(0xFFC8A08A) to Color(0xFF6D4C41)
    else     -> Color(0xFF8AB4F8) to Color(0xFF1A73E8) // blue (por defecto)
}

private val DarkBackground = Color(0xFF12141A)

/** Fuentes disponibles. Todas son familias genéricas del sistema (sin
 *  descargar ni empaquetar ficheros de fuente): Android las resuelve a la
 *  tipografía real instalada (Roboto/Noto sans/serif/monospace…). */
val FONT_OPTIONS = listOf("default", "sans", "serif", "monospace", "cursive")

fun fontFamilyFor(name: String): FontFamily = when (name) {
    "sans"       -> FontFamily.SansSerif
    "serif"      -> FontFamily.Serif
    "monospace"  -> FontFamily.Monospace
    "cursive"    -> FontFamily.Cursive
    else         -> FontFamily.Default
}

private fun typographyWithFont(family: FontFamily): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family)
    )
}

@Composable
fun KompressAllTheme(theme: String, accent: String, font: String, content: @Composable () -> Unit) {
    val dark = when (theme) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true // oscuro por defecto
    }
    val (pastel, deep) = accentPair(accent)
    val scheme = if (dark) darkColorScheme(
        primary = pastel,
        onPrimary = Color(0xFF10141A),
        primaryContainer = pastel.copy(alpha = 0.22f).compositeOver(DarkBackground),
        onPrimaryContainer = pastel,
        secondary = Color(0xFF9DB8F5),
        onSecondary = Color(0xFF0F2455),
        background = DarkBackground,
        onBackground = Color(0xFFE3E5EA),
        surface = Color(0xFF1A1D25),
        onSurface = Color(0xFFE3E5EA),
        surfaceVariant = Color(0xFF252935),
        onSurfaceVariant = Color(0xFFA9B0BE),
        outline = Color(0xFF3A4050),
        error = Color(0xFFFF8A80)
    ) else lightColorScheme(
        primary = deep,
        onPrimary = Color.White,
        primaryContainer = deep.copy(alpha = 0.14f).compositeOver(Color.White),
        onPrimaryContainer = deep,
        secondary = Color(0xFF3D5FA8),
        onSecondary = Color.White,
        background = Color(0xFFF7F8FA),
        onBackground = Color(0xFF1B1D22),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1B1D22),
        surfaceVariant = Color(0xFFE8EAF0),
        onSurfaceVariant = Color(0xFF515766),
        outline = Color(0xFFC2C7D2),
        error = Color(0xFFB3261E)
    )
    val typography = remember(font) { typographyWithFont(fontFamilyFor(font)) }
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
