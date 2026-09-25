package com.docssuite.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Les rôles « container » sont tous fixés : laissés par défaut, Material les
// tire de sa palette de référence lilas, et cartes, puces et barre de
// navigation détonnent sur le reste de l'app, ardoise et bleu.

private val LightColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBE7FF),
    onPrimaryContainer = Color(0xFF0B2A6B),
    secondary = Color(0xFF475569),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDE6F3),
    onSecondaryContainer = Color(0xFF142033),
    tertiary = Color(0xFF7C3AED),
    tertiaryContainer = Color(0xFFEDE4FF),
    onTertiaryContainer = Color(0xFF2E1065),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFF8FAFC),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFEEF2F7),
    onSurfaceVariant = Color(0xFF5B6472),
    surfaceTint = Color(0xFF2563EB),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE2E8F0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF1F4F9),
    surfaceContainerHigh = Color(0xFFEBEFF5),
    surfaceContainerHighest = Color(0xFFE5EAF1),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFD7DEE8),
    error = Color(0xFFDC2626),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB0FF),
    onPrimary = Color(0xFF00214D),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDBE7FF),
    secondary = Color(0xFFB4C0D3),
    onSecondary = Color(0xFF1E293B),
    secondaryContainer = Color(0xFF2B3953),
    onSecondaryContainer = Color(0xFFDDE6F3),
    tertiary = Color(0xFFC4B5FD),
    tertiaryContainer = Color(0xFF4C1D95),
    onTertiaryContainer = Color(0xFFEDE4FF),
    background = Color(0xFF0F172A),
    onBackground = Color(0xFFE8ECF4),
    surface = Color(0xFF0F172A),
    onSurface = Color(0xFFE8ECF4),
    surfaceVariant = Color(0xFF243049),
    onSurfaceVariant = Color(0xFFA8B3C5),
    surfaceTint = Color(0xFF8AB0FF),
    surfaceBright = Color(0xFF2A3650),
    surfaceDim = Color(0xFF0F172A),
    surfaceContainerLowest = Color(0xFF0B1222),
    surfaceContainerLow = Color(0xFF16203A),
    surfaceContainer = Color(0xFF1A2540),
    surfaceContainerHigh = Color(0xFF212D4A),
    surfaceContainerHighest = Color(0xFF283554),
    outline = Color(0xFF64748B),
    outlineVariant = Color(0xFF39455E),
    error = Color(0xFFFF8A80),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2)
)

@Composable
fun DocsSuiteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
