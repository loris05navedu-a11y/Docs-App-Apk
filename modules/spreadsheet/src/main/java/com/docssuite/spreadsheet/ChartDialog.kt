package com.docssuite.spreadsheet

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.docssuite.core.ChartType
import com.docssuite.core.ChartView
import com.docssuite.core.buildChartEntries

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartDialog(
    cells: Map<String, String>,
    initial: EmbeddedChart?,
    defaultLabelsRange: String,
    defaultValuesRange: String,
    onConfirm: (ChartType, String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var type by remember { mutableStateOf(initial?.type ?: ChartType.COLUMN) }
    var labelsRange by remember { mutableStateOf(initial?.labelsRange ?: defaultLabelsRange) }
    var valuesRange by remember { mutableStateOf(initial?.valuesRange ?: defaultValuesRange) }
    var title by remember { mutableStateOf(initial?.title ?: "Mon graphique") }

    val entries = remember(labelsRange, valuesRange, cells.toMap()) {
        buildChartEntries(labelsRange, valuesRange, cells)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Graphique",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Fermer")
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ChartType.values().forEach { chartType ->
                        FilterChip(
                            selected = type == chartType,
                            onClick = { type = chartType },
                            label = { Text(chartType.label) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = labelsRange,
                        onValueChange = { labelsRange = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Libellés") },
                        placeholder = { Text("A1:A6") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = valuesRange,
                        onValueChange = { valuesRange = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Valeurs") },
                        placeholder = { Text("B1:B6") },
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    label = { Text("Titre du graphique") },
                    singleLine = true
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 1.dp
                ) {
                    ChartView(
                        type = type,
                        title = title,
                        entries = entries,
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    )
                }

                if (type == ChartType.BOXPLOT) {
                    Text(
                        "Répète le même libellé en colonne A pour comparer plusieurs " +
                            "groupes ; sinon toute la série forme une seule boîte.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }

                if (entries.isNotEmpty()) {
                    Text(
                        "${entries.size} valeur(s) lue(s) dans $valuesRange",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Annuler")
                    }
                    Button(
                        onClick = { onConfirm(type, title, labelsRange, valuesRange) },
                        modifier = Modifier.weight(1f),
                        enabled = entries.isNotEmpty()
                    ) {
                        Text(if (initial == null) "Insérer dans la feuille" else "Mettre à jour")
                    }
                }
            }
        }
    }
}
