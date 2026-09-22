package com.docssuite.spreadsheet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.docssuite.core.FormulaEngine
import com.docssuite.core.FunctionHelp

/**
 * Catalogue des fonctions, groupé et cherchable. Chaque entrée montre sa
 * signature et ce qu'elle fait : sans cela, une liste de quatre-vingts noms
 * n'apprend rien à qui ne les connaît pas déjà.
 */
@Composable
fun FunctionCatalogDialog(
    onPick: (FunctionHelp) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val needle = query.trim().uppercase()

    val groups = remember(needle) {
        FormulaEngine.CATALOG
            .map { group ->
                group.title to group.entries.filter { entry ->
                    needle.isEmpty() ||
                        entry.name.contains(needle) ||
                        entry.description.uppercase().contains(needle)
                }
            }
            .filter { it.second.isNotEmpty() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insérer une fonction") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Rechercher") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (groups.isEmpty()) {
                    Text(
                        "Aucune fonction ne correspond.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    groups.forEach { (title, entries) ->
                        item(key = "titre-$title") {
                            Text(
                                title,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                            )
                        }
                        items(entries, key = { it.signature }) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(entry) }
                                    .padding(vertical = 6.dp)
                            ) {
                                Column {
                                    Text(
                                        entry.signature,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        entry.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Divider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )
}

/**
 * Formule pré-remplie pour une fonction : les arguments de la signature sont
 * remplacés par des exemples plausibles autour de la cellule choisie.
 */
fun sampleFormula(entry: FunctionHelp): String {
    val parameters = entry.signature.substringAfter('(').removeSuffix(")")
    if (parameters.isBlank()) return "=${entry.name}()"
    val filled = parameters.split(';').joinToString(";") { raw ->
        when (val p = raw.trim().lowercase()) {
            "plage", "p1", "p2", "somme", "moy" -> "A1:A10"
            "critère" -> "\">0\""
            "test", "t1", "t2" -> "A1>10"
            "texte", "a", "b", "cherché", "ancien", "nouveau", "sép" -> "\"texte\""
            "format" -> "\"0.00\""
            "date", "fin", "début" -> "AUJOURDHUI()"
            else -> if (p.contains("colonne") || p.contains("ligne")) "2" else "A1"
        }
    }
    return "=${entry.name}($filled)"
}
