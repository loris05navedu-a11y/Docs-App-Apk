package com.docssuite.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF7C3AED),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEF2F7),
    onSurface = Color(0xFF111827),
    onSurfaceVariant = Color(0xFF5B6472),
    outlineVariant = Color(0xFFD7DEE8),
    error = Color(0xFFDC2626)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FA8FF),
    onPrimary = Color(0xFF00214D),
    secondary = Color(0xFFC4B5FD),
    background = Color(0xFF0F172A),
    surface = Color(0xFF16203A),
    surfaceVariant = Color(0xFF243049),
    onSurface = Color(0xFFE8ECF4),
    onSurfaceVariant = Color(0xFFA8B3C5),
    outlineVariant = Color(0xFF39455E),
    error = Color(0xFFFF8A80)
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
