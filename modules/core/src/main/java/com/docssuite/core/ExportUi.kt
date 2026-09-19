package com.docssuite.core

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.docssuite.fileformats.DocKind
import com.docssuite.fileformats.FileFormat

/** Ce que l'utilisateur veut faire du fichier produit. */
enum class ExportAction { SAVE, SHARE }

/**
 * Choix du format d'enregistrement. Chaque ligne propose les deux gestes
 * utiles : ranger le fichier sur l'appareil, ou l'envoyer tout de suite.
 */
@Composable
fun ExportFormatDialog(
    kind: DocKind,
    onPick: (FileFormat, ExportAction) -> Unit,
    onDismiss: () -> Unit
) {
    val formats = FileFormat.exportTargets(kind)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exporter") },
        text = {
            Column {
                Text(
                    "Le fichier produit s'ouvre dans Word, Excel, PowerPoint, " +
                        "LibreOffice ou Google Docs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(formats) { format ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(format, ExportAction.SAVE) }
                                .padding(start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                format.label,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            IconButton(onClick = { onPick(format, ExportAction.SAVE) }) {
                                Icon(
                                    Icons.Filled.Download,
                                    contentDescription = "Enregistrer en ${format.label}",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(onClick = { onPick(format, ExportAction.SHARE) }) {
                                Icon(
                                    Icons.Filled.Share,
                                    contentDescription = "Partager en ${format.label}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )
}

/** Types MIME acceptés à l'import pour une famille de documents. */
fun importMimeTypes(kind: DocKind): Array<String> {
    val formats = FileFormat.values().filter { it.kind == kind }
    // On ajoute `application/octet-stream` : beaucoup de fichiers reçus par
    // messagerie arrivent sans type déclaré et seraient sinon grisés.
    return (formats.map { it.mime } + "application/octet-stream").distinct().toTypedArray()
}
