package com.docssuite.converter.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docssuite.converter.model.FileKind
import com.docssuite.converter.model.TargetFormat

fun iconFor(kind: FileKind): ImageVector = when (kind) {
    FileKind.IMAGE -> Icons.Outlined.Image
    FileKind.PDF -> Icons.Outlined.PictureAsPdf
    FileKind.AUDIO -> Icons.Outlined.MusicNote
    FileKind.VIDEO -> Icons.Outlined.Movie
    FileKind.TEXT -> Icons.Outlined.Description
    FileKind.ARCHIVE -> Icons.Outlined.Archive
    FileKind.OTHER -> Icons.Outlined.InsertDriveFile
}

fun iconFor(format: TargetFormat): ImageVector = when (format) {
    TargetFormat.PNG, TargetFormat.JPG, TargetFormat.WEBP -> Icons.Outlined.Image
    TargetFormat.PDF -> Icons.Outlined.PictureAsPdf
    TargetFormat.TXT -> Icons.Outlined.Description
    TargetFormat.M4A, TargetFormat.WAV -> Icons.Outlined.MusicNote
    TargetFormat.MP4 -> Icons.Outlined.Movie
    TargetFormat.ZIP -> Icons.Outlined.Archive
}

/** Pastille d'icône colorée, présente sur toutes les cartes de l'application. */
@Composable
fun IconBadge(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    contentDescription: String? = null
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3.2f))
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size / 2))
    }
}

/** Pastille sur fond plein, réservée aux éléments mis en avant. */
@Composable
fun SolidBadge(
    icon: ImageVector,
    brush: Brush,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 56.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3.2f))
            .background(brush),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size / 2))
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.invoke()
    }
}

/** Carte de base : même rayon, même élévation, même fond partout. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    content: @Composable () -> Unit
) {
    val border by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant,
        tween(160),
        label = "border"
    )
    Card(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        shape = RoundedCornerShape(Radii.card),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else ConverterDesign.tokens.elevatedSurface
        ),
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 0.dp else 1.dp)
    ) {
        content()
    }
}

/** Grande zone de dépôt de l'accueil. */
@Composable
fun DropZone(
    modifier: Modifier = Modifier,
    onPickSingle: () -> Unit,
    onPickMultiple: () -> Unit
) {
    val tokens = ConverterDesign.tokens
    val borderColor = tokens.dropZoneBorder
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 208.dp)
            .clip(RoundedCornerShape(Radii.hero))
            .background(tokens.dropZone)
            .drawBehind {
                drawRoundRect(
                    color = borderColor,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(14.dp.toPx(), 10.dp.toPx())
                        )
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(Radii.hero.toPx())
                )
            }
            .clickable(onClick = onPickSingle)
            .padding(Spacing.xlarge),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(tokens.heroBrush),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "+",
                    color = Color.White,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Light
                )
            }
            Spacer(Modifier.height(Spacing.medium))
            Text("Sélectionner un fichier", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(Spacing.tiny))
            Text(
                "Images, PDF, audio, vidéo ou texte",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.medium))
            Text(
                "ou sélectionner plusieurs fichiers",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(Radii.pill))
                    .clickable(onClick = onPickMultiple)
                    .padding(horizontal = Spacing.compact, vertical = Spacing.small)
            )
        }
    }
}

/** Bouton principal, dimensionné pour le pouce comme pour la tablette. */
@Composable
fun PrimaryAction(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(Radii.medium),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.small))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Choix exclusif présenté comme un segment unique. */
@Composable
fun <T> SegmentedChoice(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.small))
        Surface(
            shape = RoundedCornerShape(Radii.small),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(Spacing.tiny)) {
                options.forEach { option ->
                    val isSelected = option == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(Radii.small - 4.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.surface
                                else Color.Transparent
                            )
                            .clickable { onSelect(option) }
                            .padding(vertical = Spacing.compact, horizontal = Spacing.tiny),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            labelOf(option),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

/** Choix exclusif en liste, pour les options aux libellés longs. */
@Composable
fun <T> ChoiceRows(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.small))
        options.forEach { option ->
            val isSelected = option == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.small))
                    .clickable { onSelect(option) }
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent
                    )
                    .padding(horizontal = Spacing.compact, vertical = Spacing.compact),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    labelOf(option),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface
                )
                if (isSelected) {
                    Icon(
                        Icons.Filled.Check,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.height(Spacing.tiny))
        }
    }
}

@Composable
fun QualitySlider(
    value: Int,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Qualité",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("$value %", style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 40f..100f,
            steps = 11
        )
        Text(
            when {
                value >= 95 -> "Qualité maximale, fichier plus lourd"
                value >= 80 -> "Bon équilibre entre qualité et taille"
                else -> "Fichier léger, compression visible"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Ligne « libellé — valeur » du récapitulatif. */
@Composable
fun SummaryRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.3f)
        )
    }
}

/** Anneau de progression du convertisseur. */
@Composable
fun ProgressRing(
    fraction: Float,
    modifier: Modifier = Modifier,
    diameter: androidx.compose.ui.unit.Dp = 168.dp
) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(320), label = "progress")
    val track = MaterialTheme.colorScheme.surfaceVariant
    val brush = ConverterDesign.tokens.heroBrush
    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val stroke = 12.dp.toPx()
                    val inset = stroke / 2
                    drawArc(
                        color = track,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size(
                            size.width - stroke, size.height - stroke
                        ),
                        style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    )
                    drawArc(
                        brush = brush,
                        startAngle = -90f,
                        sweepAngle = 360f * animated,
                        useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size(
                            size.width - stroke, size.height - stroke
                        ),
                        style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    )
                }
        )
        Text(
            "${(animated * 100).toInt()} %",
            style = MaterialTheme.typography.headlineMedium
        )
    }
}

/** Message d'information ou d'avertissement, sans jargon technique. */
@Composable
fun InfoBanner(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.small))
            .background(tone.copy(alpha = 0.10f))
            .padding(Spacing.compact),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, null, tint = tone, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.small))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        IconBadge(icon, MaterialTheme.colorScheme.onSurfaceVariant, size = 64.dp)
        Spacer(Modifier.height(Spacing.medium))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.tiny))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
