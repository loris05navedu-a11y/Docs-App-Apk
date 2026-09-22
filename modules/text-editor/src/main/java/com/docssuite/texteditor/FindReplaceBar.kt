package com.docssuite.texteditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Barre Rechercher / Remplacer affichée au-dessus de la zone de saisie. */
@Composable
fun FindReplaceBar(
    query: String,
    replacement: String,
    matchCase: Boolean,
    occurrences: Int,
    onQueryChange: (String) -> Unit,
    onReplacementChange: (String) -> Unit,
    onMatchCaseChange: (Boolean) -> Unit,
    onFind: (forward: Boolean) -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text("Rechercher") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onFind(false) }, enabled = query.isNotEmpty()) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Occurrence précédente")
                }
                IconButton(onClick = { onFind(true) }, enabled = query.isNotEmpty()) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Occurrence suivante")
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Fermer la recherche")
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = replacement,
                    onValueChange = onReplacementChange,
                    label = { Text("Remplacer par") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onReplace, enabled = query.isNotEmpty()) {
                    Text("Remplacer")
                }
                TextButton(onClick = onReplaceAll, enabled = query.isNotEmpty()) {
                    Text("Tout")
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Checkbox(
                    checked = matchCase,
                    onCheckedChange = onMatchCaseChange,
                    modifier = Modifier.size(32.dp)
                )
                Text("Respecter la casse", style = MaterialTheme.typography.bodySmall)
                Text(
                    when {
                        query.isEmpty() -> ""
                        occurrences == 0 -> "  ·  aucun résultat"
                        occurrences == 1 -> "  ·  1 résultat"
                        else -> "  ·  $occurrences résultats"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
