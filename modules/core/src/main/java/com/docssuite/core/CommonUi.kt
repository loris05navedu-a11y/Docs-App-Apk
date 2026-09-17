package com.docssuite.core

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatColorReset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

val EditorColors: List<Color> = listOf(
    Color(0xFF000000), Color(0xFF424242), Color(0xFF757575), Color(0xFFBDBDBD),
    Color(0xFFFFFFFF), Color(0xFFD32F2F), Color(0xFFE64A19), Color(0xFFF57C00),
    Color(0xFFFBC02D), Color(0xFF689F38), Color(0xFF388E3C), Color(0xFF00897B),
    Color(0xFF0097A7), Color(0xFF1976D2), Color(0xFF2563EB), Color(0xFF3F51B5),
    Color(0xFF7C3AED), Color(0xFF9C27B0), Color(0xFFC2185B), Color(0xFF795548)
)

val HighlightColors: List<Color> = listOf(
    Color(0xFFFFF59D), Color(0xFFFFCC80), Color(0xFFFFAB91), Color(0xFFF48FB1),
    Color(0xFFCE93D8), Color(0xFF9FA8DA), Color(0xFF90CAF9), Color(0xFF80DEEA),
    Color(0xFFA5D6A7), Color(0xFFE6EE9C), Color(0xFFE0E0E0), Color(0xFFFFFFFF)
)

@Composable
fun ColorPickerDialog(
    title: String,
    colors: List<Color> = EditorColors,
    allowNone: Boolean = false,
    onPick: (Color?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                colors.chunked(5).forEach { rowColors ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowColors.forEach { color ->
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(color, CircleShape)
                                    .border(1.dp, Color(0x33000000), CircleShape)
                                    .clickable { onPick(color) }
                            )
                        }
                    }
                }
                if (allowNone) {
                    TextButton(onClick = { onPick(null) }) {
                        Icon(Icons.Filled.FormatColorReset, contentDescription = null)
                        Text("  Aucune couleur")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fermer") }
        }
    )
}

@Composable
fun <T> ListPickerDialog(
    title: String,
    items: List<T>,
    label: (T) -> String,
    selected: T? = null,
    itemContent: (@Composable (T) -> Unit)? = null,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(items) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(item) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                            if (item == selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (itemContent != null) itemContent(item) else Text(label(item))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fermer") }
        }
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initialValue: String,
    label: String = "Nom",
    confirmLabel: String = "Valider",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Supprimer",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

/** Bouton d'outil compact avec état actif/inactif. */
@Composable
fun ToolToggle(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val background = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent
    Box(
        modifier = Modifier
            .size(42.dp)
            .background(background, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = when {
                    !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    active -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/** Petit bouton texte pour la barre d'outils (police, taille…). */
@Composable
fun ToolChip(text: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

fun shareText(context: Context, subject: String, body: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    context.startActivity(Intent.createChooser(intent, "Partager"))
}
