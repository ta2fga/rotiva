package com.ta2fga.obd2.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Masaüstü uygulamadaki 5 tema paleti (app/ui/theme.py). */
class Palette(
    val name: String, val dark: Boolean,
    val bg: Long, val panel: Long, val card: Long, val border: Long, val text: Long, val muted: Long,
    val accent: Long, val onAccent: Long, val ok: Long, val warn: Long, val crit: Long, val sel: Long,
    val series: List<Long>,
)

val PALETTES = listOf(
    Palette("Gece Mavisi", true, 0xFF0F1419, 0xFF161C24, 0xFF1B232D, 0xFF263140, 0xFFE6EBF2, 0xFF8B95A5,
        0xFF3B82F6, 0xFFFFFFFF, 0xFF22C55E, 0xFFF59E0B, 0xFFEF4444, 0xFF23406F,
        listOf(0xFF3B82F6, 0xFF22C55E, 0xFFF59E0B, 0xFFEF4444, 0xFFA855F7, 0xFF14B8A6, 0xFFEC4899, 0xFFEAB308)),
    Palette("Aydınlık", false, 0xFFF5F7FA, 0xFFEEF1F6, 0xFFFFFFFF, 0xFFD5DBE5, 0xFF0F172A, 0xFF526071,
        0xFF2563EB, 0xFFFFFFFF, 0xFF15803D, 0xFFB45309, 0xFFDC2626, 0xFFDBE7FF,
        listOf(0xFF2563EB, 0xFF15803D, 0xFFB45309, 0xFFDC2626, 0xFF7E22CE, 0xFF0F766E, 0xFFBE185D, 0xFFA16207)),
    Palette("Grafit Turuncu", true, 0xFF18181B, 0xFF202024, 0xFF27272A, 0xFF3F3F46, 0xFFFAFAFA, 0xFFA1A1AA,
        0xFFF97316, 0xFF18181B, 0xFF4ADE80, 0xFFFBBF24, 0xFFF87171, 0xFF5A3412,
        listOf(0xFFF97316, 0xFF4ADE80, 0xFFFBBF24, 0xFFF87171, 0xFFC084FC, 0xFF2DD4BF, 0xFFF472B6, 0xFF60A5FA)),
    Palette("Orman", true, 0xFF0F1A14, 0xFF14231B, 0xFF192B21, 0xFF264236, 0xFFE5F2EA, 0xFF8AA596,
        0xFF10B981, 0xFF06281C, 0xFF4ADE80, 0xFFFBBF24, 0xFFF87171, 0xFF1C5A43,
        listOf(0xFF10B981, 0xFFFBBF24, 0xFF60A5FA, 0xFFF87171, 0xFFC084FC, 0xFF2DD4BF, 0xFFF472B6, 0xFFFDE047)),
    Palette("Yüksek Kontrast", true, 0xFF000000, 0xFF000000, 0xFF0A0A0A, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFD4D4D4,
        0xFFFACC15, 0xFF000000, 0xFF4ADE80, 0xFFFB923C, 0xFFFF5C5C, 0xFF5C4A00,
        listOf(0xFFFACC15, 0xFF4ADE80, 0xFF38BDF8, 0xFFFF5C5C, 0xFFE879F9, 0xFF2DD4BF, 0xFFFB923C, 0xFFFFFFFF)),
)

val THEME_NAMES = PALETTES.map { it.name }

class Extra(
    val ok: Color, val warn: Color, val crit: Color, val card: Color, val border: Color, val muted: Color,
    val panel: Color, val series: List<Color>, val borderWidth: Int,
)

val LocalExtra = staticCompositionLocalOf {
    val p = PALETTES[0]
    Extra(Color(p.ok), Color(p.warn), Color(p.crit), Color(p.card), Color(p.border), Color(p.muted), Color(p.panel),
        p.series.map { Color(it) }, 1)
}

@Composable
fun AppTheme(name: String, content: @Composable () -> Unit) {
    val p = PALETTES.firstOrNull { it.name == name } ?: PALETTES[0]
    val c = { v: Long -> Color(v) }
    val scheme = if (p.dark) darkColorScheme(
        primary = c(p.accent), onPrimary = c(p.onAccent), background = c(p.bg), onBackground = c(p.text),
        surface = c(p.panel), onSurface = c(p.text), surfaceVariant = c(p.card), onSurfaceVariant = c(p.muted),
        outline = c(p.border), secondaryContainer = c(p.sel), onSecondaryContainer = c(p.text),
        error = c(p.crit), surfaceContainer = c(p.panel), surfaceContainerHigh = c(p.card),
        surfaceContainerHighest = c(p.card), surfaceContainerLow = c(p.panel), surfaceContainerLowest = c(p.bg),
    ) else lightColorScheme(
        primary = c(p.accent), onPrimary = c(p.onAccent), background = c(p.bg), onBackground = c(p.text),
        surface = c(p.panel), onSurface = c(p.text), surfaceVariant = c(p.card), onSurfaceVariant = c(p.muted),
        outline = c(p.border), secondaryContainer = c(p.sel), onSecondaryContainer = c(p.text),
        error = c(p.crit), surfaceContainer = c(p.panel), surfaceContainerHigh = c(p.card),
        surfaceContainerHighest = c(p.card), surfaceContainerLow = c(p.panel), surfaceContainerLowest = c(p.bg),
    )
    val extra = Extra(c(p.ok), c(p.warn), c(p.crit), c(p.card), c(p.border), c(p.muted), c(p.panel),
        p.series.map { Color(it) }, if (p.name == "Yüksek Kontrast") 2 else 1)
    CompositionLocalProvider(LocalExtra provides extra) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
