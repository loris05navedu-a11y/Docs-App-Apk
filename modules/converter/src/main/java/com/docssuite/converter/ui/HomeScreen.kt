package com.docssuite.converter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docssuite.converter.model.TargetFormat

/** Raccourci vers une conversion courante, réellement prise en charge par le moteur. */
private data class Shortcut(
    val from: String,
    val to: String,
    val mimeTypes: Array<String>,
    val target: TargetFormat
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

private val SHORTCUTS = listOf(
    Shortcut("JPG", "PNG", arrayOf("image/jpeg"), TargetFormat.PNG),
    Shortcut("PNG", "JPG", arrayOf("image/png"), TargetFormat.JPG),
    Shortcut("Image", "PDF", arrayOf("image/*"), TargetFormat.PDF),
    Shortcut("PDF", "Image", arrayOf("application/pdf"), TargetFormat.PNG),
    Shortcut("Vidéo", "M4A", arrayOf("video/*"), TargetFormat.M4A),
    Shortcut("Audio", "WAV", arrayOf("audio/*"), TargetFormat.WAV)
)

@Composable
fun HomeScreen(state: ConverterState, actions: ConverterActions, wide: Boolean) {
    val tokens = ConverterDesign.tokens
    val columns = if (wide) 3 else 2

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.screen,
            end = Spacing.screen,
            top = Spacing.small,
            bottom = Spacing.section
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.large)
    ) {
        item {
            Column {
                Text(
                    "Convertissez vos fichiers rapidement, directement sur votre appareil.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.compact))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Lock,
                        null,
                        tint = tokens.success,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(Spacing.tiny + 2.dp))
                    Text(
                        "Hors ligne, sans compte, rien n'est envoyé sur Internet",
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.success
                    )
                }
            }
        }

        item {
            DropZone(
                onPickSingle = { actions.pickSingle(arrayOf("*/*"), null) },
                onPickMultiple = actions.pickMultiple
            )
        }

        item {
            SectionHeader(
                "Conversions fréquentes",
                subtitle = "Choisissez un raccourci, le format de sortie est déjà prêt"
            )
        }

        items(SHORTCUTS.chunked(columns)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.compact)) {
                row.forEach { shortcut ->
                    ShortcutCard(
                        shortcut = shortcut,
                        modifier = Modifier.weight(1f),
                        onClick = { actions.pickSingle(shortcut.mimeTypes, shortcut.target) }
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        item {
            SupportedFormats()
        }
    }
}

@Composable
private fun ShortcutCard(
    shortcut: Shortcut,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tokens = ConverterDesign.tokens
    val accent = tokens.accentFor(shortcut.target)
    SurfaceCard(modifier = modifier, onClick = onClick) {
        Column(modifier = Modifier.padding(Spacing.medium)) {
            IconBadge(iconFor(shortcut.target), accent, size = 40.dp)
            Spacer(Modifier.height(Spacing.compact))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    shortcut.from,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    Icons.Filled.ArrowForward,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp).padding(horizontal = 1.dp)
                )
                Text(
                    shortcut.to,
                    style = MaterialTheme.typography.titleSmall,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SupportedFormats() {
    SurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.medium)) {
            Text("Formats pris en charge", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Spacing.compact))
            FormatLine("Images", "JPG, PNG, WEBP, GIF, BMP, HEIC → PNG, JPG, WEBP, PDF")
            FormatLine("Documents", "PDF → PNG, JPG, WEBP  •  TXT, CSV, MD → PDF")
            FormatLine("Audio", "MP3, M4A, AAC, OGG, FLAC, WAV → M4A, WAV")
            FormatLine("Vidéo", "MP4, MKV, WEBM, 3GP, MOV → MP4, M4A, WAV")
            FormatLine("Archives", "N'importe quel fichier → ZIP")
        }
    }
}

@Composable
private fun FormatLine(title: String, detail: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.tiny + 1.dp)) {
        Text(
            title,
            modifier = Modifier.width(88.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            detail,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
