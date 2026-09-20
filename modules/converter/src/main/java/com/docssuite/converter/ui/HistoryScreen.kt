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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docssuite.converter.model.HistoryEntry
import com.docssuite.converter.model.TargetFormat
import com.docssuite.converter.model.formatSize
import com.docssuite.converter.model.kindForExtension
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(state: ConverterState, actions: ConverterActions) {
    val entries = state.historyEntries
    var confirmClear by remember { mutableStateOf(false) }

    if (entries.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.History,
            title = "Aucune conversion pour l'instant",
            message = "Les fichiers que vous convertirez apparaîtront ici.",
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.screen,
            end = Spacing.screen,
            top = Spacing.small,
            bottom = Spacing.section
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.compact)
    ) {
        item {
            SectionHeader(
                "Conversions récentes",
                subtitle = "Seule la description est conservée, pas une copie des fichiers",
                trailing = {
                    TextButton(onClick = { confirmClear = true }) { Text("Tout effacer") }
                }
            )
        }
        items(entries) { entry ->
            HistoryRow(
                entry = entry,
                onOpen = { file ->
                    val format = TargetFormat.values()
                        .firstOrNull { it.extension == entry.targetExtension }
                    actions.open(file, format?.mime ?: "*/*")
                },
                onShare = { file ->
                    val format = TargetFormat.values()
                        .firstOrNull { it.extension == entry.targetExtension }
                    actions.share(file, format?.mime ?: "*/*")
                },
                onDelete = { state.deleteHistory(entry.id) }
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Effacer l'historique ?") },
            text = {
                Text(
                    "Les entrées et les fichiers convertis encore présents dans " +
                        "l'application seront supprimés. Les fichiers que vous avez " +
                        "déjà enregistrés ailleurs ne sont pas touchés."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.clearHistory()
                        confirmClear = false
                    }
                ) { Text("Effacer") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Annuler") }
            }
        )
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    onOpen: (java.io.File) -> Unit,
    onShare: (java.io.File) -> Unit,
    onDelete: () -> Unit
) {
    val tokens = ConverterDesign.tokens
    val kind = kindForExtension(entry.targetExtension.ifBlank { entry.sourceExtension })
    val file = entry.outputFile()

    SurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.compact),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (entry.succeeded) {
                IconBadge(iconFor(kind), tokens.accentFor(kind), size = 40.dp)
            } else {
                IconBadge(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error, size = 40.dp)
            }
            Spacer(Modifier.width(Spacing.compact))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.sourceName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (entry.succeeded) {
                        Icon(
                            Icons.Filled.ArrowForward,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp).padding(horizontal = 1.dp)
                        )
                        Text(
                            entry.targetExtension.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens.accentFor(kind)
                        )
                    }
                }
                Text(
                    buildString {
                        append(relativeDate(entry.timestamp))
                        if (entry.succeeded) {
                            append(" • ")
                            append(formatSize(entry.sourceSize))
                            append(" → ")
                            append(formatSize(entry.outputSize))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!entry.succeeded) {
                    Text(
                        entry.message ?: "Échec de la conversion",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else if (file == null) {
                    Text(
                        "Fichier plus disponible",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (file != null) {
                IconButton(onClick = { onOpen(file) }) {
                    Icon(
                        Icons.Outlined.OpenInNew,
                        contentDescription = "Ouvrir",
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = { onShare(file) }) {
                    Icon(
                        Icons.Outlined.Share,
                        contentDescription = "Partager",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Supprimer de l'historique",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** « Aujourd'hui 14:32 », « Hier 09:05 », sinon la date complète. */
private fun relativeDate(timestamp: Long): String {
    val time = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(timestamp))
    val entry = Calendar.getInstance().apply { timeInMillis = timestamp }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

    fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    return when {
        sameDay(entry, today) -> "Aujourd'hui $time"
        sameDay(entry, yesterday) -> "Hier $time"
        else -> SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(timestamp))
    }
}
