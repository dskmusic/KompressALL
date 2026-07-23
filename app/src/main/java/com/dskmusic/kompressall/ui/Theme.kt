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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** Acentos disponibles. El primero ("blue") es el valor por defecto. */
val ACCENT_OPTIONS = listOf(
    "blue", "indigo", "green", "teal", "cyan", "purple", "pink", "red",
    "orange", "amber", "lime", "gray"
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
    "gray"   -> Color(0xFFD5D5D5) to Color(0xFF5A5A5A) // blanco y negro
    else     -> Color(0xFF8AB4F8) to Color(0xFF1A73E8) // blue (por defecto)
}

private val DarkBackground = Color(0xFF12141A)
private val AmoledBackground = Color(0xFF000000)
private val AmoledSurface = Color(0xFF0A0A0A)

/**
 * Fuentes disponibles. Las 5 primeras son familias genéricas del sistema (sin
 * descargar ni empaquetar ficheros de fuente): Android las resuelve a la tipografía
 * real instalada. Las 3 últimas son variantes de peso de esas mismas familias
 * genéricas (no hay más familias "genéricas" en Android más allá de estas 5, y
 * empaquetar tipografías reales requeriría archivos .ttf que no están disponibles
 * en este entorno) — dan un resultado visualmente distinto sin añadir binarios.
 */
val FONT_OPTIONS = listOf(
    "default", "sans", "serif", "monospace", "cursive",
    "sans_bold", "serif_light", "monospace_medium"
)

fun fontFamilyFor(name: String): FontFamily = when (name) {
    "sans", "sans_bold"       -> FontFamily.SansSerif
    "serif", "serif_light"    -> FontFamily.Serif
    "monospace", "monospace_medium" -> FontFamily.Monospace
    "cursive"                 -> FontFamily.Cursive
    else                       -> FontFamily.Default
}

fun fontWeightFor(name: String): FontWeight? = when (name) {
    "sans_bold" -> FontWeight.Bold
    "serif_light" -> FontWeight.Light
    "monospace_medium" -> FontWeight.Medium
    else -> null
}

private fun typographyWithFont(family: FontFamily, weight: FontWeight?): Typography {
    val base = Typography()
    fun copy(style: androidx.compose.ui.text.TextStyle) =
        style.copy(fontFamily = family, fontWeight = weight ?: style.fontWeight)
    return Typography(
        displayLarge = copy(base.displayLarge),
        displayMedium = copy(base.displayMedium),
        displaySmall = copy(base.displaySmall),
        headlineLarge = copy(base.headlineLarge),
        headlineMedium = copy(base.headlineMedium),
        headlineSmall = copy(base.headlineSmall),
        titleLarge = copy(base.titleLarge),
        titleMedium = copy(base.titleMedium),
        titleSmall = copy(base.titleSmall),
        bodyLarge = copy(base.bodyLarge),
        bodyMedium = copy(base.bodyMedium),
        bodySmall = copy(base.bodySmall),
        labelLarge = copy(base.labelLarge),
        labelMedium = copy(base.labelMedium),
        labelSmall = copy(base.labelSmall)
    )
}

/** "dark" | "light" | "system" | "amoled" | "custom" (usa [customColor] como fondo). */
@Composable
fun KompressAllTheme(
    theme: String,
    accent: String,
    font: String,
    customColor: Int = 0xFF2A2A2A.toInt(),
    content: @Composable () -> Unit
) {
    val (pastel, deep) = accentPair(accent)
    val customBg = Color(customColor)
    val dark = when (theme) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        "amoled" -> true
        "custom" -> customBg.luminance() < 0.5f
        else -> true // oscuro por defecto
    }
    val background = when (theme) {
        "amoled" -> AmoledBackground
        "custom" -> customBg
        else -> if (dark) DarkBackground else Color(0xFFF7F8FA)
    }
    val surface = when (theme) {
        "amoled" -> AmoledSurface
        "custom" -> customBg
        else -> if (dark) Color(0xFF1A1D25) else Color.White
    }
    val scheme = if (dark) darkColorScheme(
        primary = pastel,
        onPrimary = Color(0xFF10141A),
        primaryContainer = pastel.copy(alpha = 0.22f).compositeOver(background),
        onPrimaryContainer = pastel,
        secondary = Color(0xFF9DB8F5),
        onSecondary = Color(0xFF0F2455),
        background = background,
        onBackground = Color(0xFFE3E5EA),
        surface = surface,
        onSurface = Color(0xFFE3E5EA),
        surfaceVariant = Color(0xFF252935),
        onSurfaceVariant = Color(0xFFA9B0BE),
        outline = Color(0xFF3A4050),
        error = Color(0xFFFF8A80)
    ) else lightColorScheme(
        primary = deep,
        onPrimary = Color.White,
        primaryContainer = deep.copy(alpha = 0.14f).compositeOver(background),
        onPrimaryContainer = deep,
        secondary = Color(0xFF3D5FA8),
        onSecondary = Color.White,
        background = background,
        onBackground = Color(0xFF1B1D22),
        surface = surface,
        onSurface = Color(0xFF1B1D22),
        surfaceVariant = Color(0xFFE8EAF0),
        onSurfaceVariant = Color(0xFF515766),
        outline = Color(0xFFC2C7D2),
        error = Color(0xFFB3261E)
    )
    val typography = remember(font) { typographyWithFont(fontFamilyFor(font), fontWeightFor(font)) }
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
