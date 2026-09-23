package com.docssuite.texteditor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Taille du tableau à insérer, avec un aperçu de la grille. */
@Composable
internal fun InsertTableDialog(onConfirm: (Int, Int, Boolean) -> Unit, onDismiss: () -> Unit) {
    var rows by remember { mutableStateOf(3) }
    var columns by remember { mutableStateOf(3) }
    var header by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insérer un tableau") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Stepper("Lignes", rows, 1..60) { rows = it }
                Stepper("Colonnes", columns, 1..12) { columns = it }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { header = !header }
                ) {
                    Checkbox(checked = header, onCheckedChange = { header = it })
                    Text("Première ligne en en-tête")
                }
                // Aperçu : au plus huit lignes, pour rester lisible.
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(minOf(rows, 8)) { r ->
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            repeat(columns) {
                                Box(
                                    modifier = Modifier
                                        .size(width = (200 / columns).coerceIn(12, 40).dp, height = 12.dp)
                                        .padding(0.dp)
                                ) {
                                    Surface(
                                        color = if (header && r == 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(2.dp),
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 12.dp)
                                    ) {}
                                }
                            }
                        }
                    }
                    if (rows > 8) Text("…", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(rows, columns, header) }) { Text("Insérer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(96.dp))
        FilledTonalIconButton(onClick = { onChange((value - 1).coerceIn(range)) }, enabled = value > range.first) {
            Icon(Icons.Filled.Remove, contentDescription = "Moins de $label")
        }
        Text(
            "$value",
            modifier = Modifier.width(44.dp),
            fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        FilledTonalIconButton(onClick = { onChange((value + 1).coerceIn(range)) }, enabled = value < range.last) {
            Icon(Icons.Filled.Add, contentDescription = "Plus de $label")
        }
    }
}

internal val SpecialCharacters = listOf(
    "«", "»", "“", "”", "‘", "’", "…", "—", "–", "•", "·", "§",
    "©", "®", "™", "€", "£", "$", "¥", "¢", "°", "±", "×", "÷",
    "≠", "≤", "≥", "≈", "∞", "√", "∑", "π", "µ", "Ω", "α", "β",
    "γ", "δ", "λ", "σ", "→", "←", "↑", "↓", "⇒", "⇔", "✓", "✗",
    "★", "☆", "♥", "♦", "½", "¼", "¾", "²", "³", "‰", "¶", "†",
    "É", "È", "Ê", "À", "Ç", "Œ", "œ", "Æ", "æ", "ß", "¿", "¡"
)

@Composable
internal fun SpecialCharactersDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Caractères spéciaux") },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(44.dp),
                modifier = Modifier.heightIn(max = 360.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(SpecialCharacters) { symbol ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(44.dp).clickable { onPick(symbol) }
                    ) {
                        Box(contentAlignment = Alignment.Center) { Text(symbol, fontSize = 20.sp) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )
}

/** Une entrée du plan : un titre et l'endroit où il se trouve. */
internal data class OutlineEntry(val target: Target, val offset: Int, val level: Int, val text: String)

internal fun outlineOf(doc: EditorDoc): List<OutlineEntry> = doc.blocks.flatMapIndexed { index, block ->
    if (block !is EditorBlock.Text) return@flatMapIndexed emptyList()
    val text = block.field.text
    val starts = EditorModel.paragraphStarts(text)
    block.field.paras.mapIndexedNotNull { p, para ->
        if (para.heading == 0) return@mapIndexedNotNull null
        val bounds = EditorModel.paragraphBounds(text, p)
        val line = text.substring(bounds.first, bounds.last + 1).trim()
        if (line.isEmpty()) null else OutlineEntry(Target(index), starts.getOrElse(p) { 0 }, para.heading, line)
    }
}

@Composable
internal fun OutlineDialog(entries: List<OutlineEntry>, onPick: (OutlineEntry) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Plan du document") },
        text = {
            if (entries.isEmpty()) {
                Text(
                    "Aucun titre pour l'instant. Placez le curseur sur une ligne et choisissez " +
                        "« Titre 1 », « Titre 2 » ou « Titre 3 » dans le sélecteur de style : " +
                        "elle apparaîtra ici, et sera un vrai titre dans Word."
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(entries) { entry ->
                        Text(
                            entry.text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(entry) }
                                .padding(start = ((entry.level - 1) * 18).dp, top = 10.dp, bottom = 10.dp),
                            fontWeight = if (entry.level == 1) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )
}
