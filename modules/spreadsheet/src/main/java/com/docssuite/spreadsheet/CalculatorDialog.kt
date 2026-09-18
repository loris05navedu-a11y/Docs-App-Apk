package com.docssuite.spreadsheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.docssuite.core.FormulaEngine

/**
 * Calculatrice adossée au moteur de formules : les références de cellules
 * (A1, B2…) sont donc évaluées comme dans le tableur.
 */
@Composable
fun CalculatorDialog(
    cells: Map<String, String>,
    selectedCell: String,
    onInsert: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var expression by remember { mutableStateOf("") }

    val result = remember(expression, cells) {
        FormulaEngine.evaluateExpression(expression, cells)
    }
    val resultText = result?.let { FormulaEngine.formatNumber(it) } ?: "—"

    fun append(text: String) { expression += text }
    fun backspace() { if (expression.isNotEmpty()) expression = expression.dropLast(1) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 4.dp) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Calculatrice",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            expression.ifEmpty { "0" }
                                .replace('*', '×')
                                .replace('/', '÷'),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            maxLines = 3
                        )
                        Text(
                            resultText,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (result == null && expression.isNotBlank()) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }

                KeypadRow {
                    CalcKey("C", Modifier.weight(1f), accent = true) { expression = "" }
                    CalcKey("⌫", Modifier.weight(1f), accent = true) { backspace() }
                    CalcKey("(", Modifier.weight(1f)) { append("(") }
                    CalcKey(")", Modifier.weight(1f)) { append(")") }
                }
                KeypadRow {
                    CalcKey("7", Modifier.weight(1f)) { append("7") }
                    CalcKey("8", Modifier.weight(1f)) { append("8") }
                    CalcKey("9", Modifier.weight(1f)) { append("9") }
                    CalcKey("÷", Modifier.weight(1f), accent = true) { append("/") }
                }
                KeypadRow {
                    CalcKey("4", Modifier.weight(1f)) { append("4") }
                    CalcKey("5", Modifier.weight(1f)) { append("5") }
                    CalcKey("6", Modifier.weight(1f)) { append("6") }
                    CalcKey("×", Modifier.weight(1f), accent = true) { append("*") }
                }
                KeypadRow {
                    CalcKey("1", Modifier.weight(1f)) { append("1") }
                    CalcKey("2", Modifier.weight(1f)) { append("2") }
                    CalcKey("3", Modifier.weight(1f)) { append("3") }
                    CalcKey("−", Modifier.weight(1f), accent = true) { append("-") }
                }
                KeypadRow {
                    CalcKey("0", Modifier.weight(1f)) { append("0") }
                    CalcKey(",", Modifier.weight(1f)) { append(".") }
                    CalcKey("^", Modifier.weight(1f)) { append("^") }
                    CalcKey("+", Modifier.weight(1f), accent = true) { append("+") }
                }
                KeypadRow {
                    CalcKey("√", Modifier.weight(1f)) { append("RACINE(") }
                    CalcKey("Σ", Modifier.weight(1f)) { append("SOMME(") }
                    CalcKey("%", Modifier.weight(1f)) { append("/100") }
                    CalcKey(selectedCell, Modifier.weight(1f)) { append(selectedCell) }
                }

                Text(
                    "Astuce : tape une référence comme A1 pour réutiliser une cellule.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onInsert("=$expression") },
                        modifier = Modifier.weight(1f),
                        enabled = result != null
                    ) {
                        Text("Formule")
                    }
                    Button(
                        onClick = { onInsert(resultText) },
                        modifier = Modifier.weight(1f),
                        enabled = result != null
                    ) {
                        Text("→ $selectedCell")
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Fermer")
                }
            }
        }
    }
}

@Composable
private fun KeypadRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

@Composable
private fun CalcKey(
    label: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp)
    ) {
        Text(
            label,
            fontSize = if (label.length > 2) 13.sp else 18.sp,
            fontWeight = if (accent) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}
