package com.docssuite.converter.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.docssuite.converter.data.ThemeMode
import com.docssuite.converter.model.FileKind
import com.docssuite.converter.model.TargetFormat

/**
 * Système de design du convertisseur : un seul jeu de couleurs, d'espacements,
 * de rayons et de styles de texte, utilisé par tous les écrans.
 */

/** Échelle d'espacement. Toutes les marges de l'application en sont issues. */
object Spacing {
    val tiny = 4.dp
    val small = 8.dp
    val compact = 12.dp
    val medium = 16.dp
    val large = 20.dp
    val xlarge = 24.dp
    val section = 32.dp
    val screen = 20.dp
}

/** Rayons de bordure. */
object Radii {
    val small = 12.dp
    val medium = 16.dp
    val card = 20.dp
    val hero = 28.dp
    val pill = 999.dp
}

/** Couleurs propres au convertisseur, hors palette Material. */
data class ConverterTokens(
    val heroStart: Color,
    val heroEnd: Color,
    val imageAccent: Color,
    val audioAccent: Color,
    val videoAccent: Color,
    val documentAccent: Color,
    val archiveAccent: Color,
    val success: Color,
    val warning: Color,
    val dropZone: Color,
    val dropZoneBorder: Color,
    val elevatedSurface: Color
) {
    val heroBrush: Brush get() = Brush.linearGradient(listOf(heroStart, heroEnd))

    fun accentFor(kind: FileKind): Color = when (kind) {
        FileKind.IMAGE -> imageAccent
        FileKind.AUDIO -> audioAccent
        FileKind.VIDEO -> videoAccent
        FileKind.PDF, FileKind.TEXT -> documentAccent
        FileKind.ARCHIVE, FileKind.OTHER -> archiveAccent
    }

    fun accentFor(format: TargetFormat): Color = when (format) {
        TargetFormat.PNG, TargetFormat.JPG, TargetFormat.WEBP -> imageAccent
        TargetFormat.M4A, TargetFormat.WAV -> audioAccent
        TargetFormat.MP4 -> videoAccent
        TargetFormat.PDF, TargetFormat.TXT -> documentAccent
        TargetFormat.ZIP -> archiveAccent
    }
}

private val LightTokens = ConverterTokens(
    heroStart = Color(0xFF4F46E5),
    heroEnd = Color(0xFF7C3AED),
    imageAccent = Color(0xFF059669),
    audioAccent = Color(0xFFD97706),
    videoAccent = Color(0xFFE11D48),
    documentAccent = Color(0xFF2563EB),
    archiveAccent = Color(0xFF64748B),
    success = Color(0xFF059669),
    warning = Color(0xFFB45309),
    dropZone = Color(0xFFF1F3FC),
    dropZoneBorder = Color(0xFFC3C9E8),
    elevatedSurface = Color(0xFFFFFFFF)
)

private val DarkTokens = ConverterTokens(
    heroStart = Color(0xFF6366F1),
    heroEnd = Color(0xFF9333EA),
    imageAccent = Color(0xFF34D399),
    audioAccent = Color(0xFFFBBF24),
    videoAccent = Color(0xFFFB7185),
    documentAccent = Color(0xFF7FA8FF),
    archiveAccent = Color(0xFF94A3B8),
    success = Color(0xFF34D399),
    warning = Color(0xFFFBBF24),
    dropZone = Color(0xFF19203A),
    dropZoneBorder = Color(0xFF39436B),
    elevatedSurface = Color(0xFF18203A)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE5E7FB),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF7C3AED),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF0F1729),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F1729),
    surfaceVariant = Color(0xFFEEF1F8),
    onSurfaceVariant = Color(0xFF5A6478),
    outline = Color(0xFFB9C1D4),
    outlineVariant = Color(0xFFDDE3EE),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDE7E7),
    onErrorContainer = Color(0xFF7F1D1D)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8B9CFF),
    onPrimary = Color(0xFF0A1033),
    primaryContainer = Color(0xFF262F58),
    onPrimaryContainer = Color(0xFFDDE3FF),
    secondary = Color(0xFFC4B5FD),
    onSecondary = Color(0xFF2A1065),
    background = Color(0xFF0B1020),
    onBackground = Color(0xFFE9EDF7),
    surface = Color(0xFF131A2E),
    onSurface = Color(0xFFE9EDF7),
    surfaceVariant = Color(0xFF1D2540),
    onSurfaceVariant = Color(0xFFA3ADC4),
    outline = Color(0xFF4A5678),
    outlineVariant = Color(0xFF2C3652),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF4A0A05),
    errorContainer = Color(0xFF3A1210),
    onErrorContainer = Color(0xFFFFD5D1)
)

/** Typographie resserrée : titres marqués, corps très lisible. */
private val ConverterTypography = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp)
)

private val LocalConverterTokens = staticCompositionLocalOf { LightTokens }

object ConverterDesign {
    val tokens: ConverterTokens
        @Composable @ReadOnlyComposable get() = LocalConverterTokens.current
}

@Composable
fun ConverterTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalConverterTokens provides if (dark) DarkTokens else LightTokens) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = ConverterTypography,
            content = content
        )
    }
}
