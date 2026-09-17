package com.docssuite.texteditor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.docssuite.core.AppFont
import com.docssuite.core.AppFonts
import com.docssuite.core.ColorPickerDialog
import com.docssuite.core.ConfirmDialog
import com.docssuite.core.DocMeta
import com.docssuite.core.DocType
import com.docssuite.core.DocumentStorage
import com.docssuite.core.EditorColors
import com.docssuite.core.FontSizes
import com.docssuite.core.HighlightColors
import com.docssuite.core.ListPickerDialog
import com.docssuite.core.TextInputDialog
import com.docssuite.core.ToolChip
import com.docssuite.core.ToolToggle
import com.docssuite.core.fontLabelAt
import com.docssuite.core.shareText

private data class Snapshot(
    val text: String,
    val styles: List<CharStyle>,
    val selection: TextRange
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(onBack: () -> Unit, initialDocId: String? = null) {
    val context = LocalContext.current
    val storage = remember { DocumentStorage(context) }

    var value by remember { mutableStateOf(TextFieldValue("")) }
    var styles by remember { mutableStateOf<List<CharStyle>>(emptyList()) }
    var pending by remember { mutableStateOf<CharStyle?>(null) }
    var align by remember { mutableStateOf(TextAlign.Start) }

    var docId by remember { mutableStateOf(storage.newId()) }
    var docName by remember { mutableStateOf("Document sans titre") }

    val undoStack = remember { mutableListOf<Snapshot>() }
    val redoStack = remember { mutableListOf<Snapshot>() }
    var historyVersion by remember { mutableStateOf(0) }

    var showFontPicker by remember { mutableStateOf(false) }
    var showSizePicker by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showHighlightPicker by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showOpen by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    val selection = value.selection
    val currentStyle = pending
        ?: styles.getOrNull(selection.start - 1)
        ?: styles.getOrNull(selection.start)
        ?: CharStyle()

    fun pushUndo() {
        undoStack.add(Snapshot(value.text, styles, selection))
        if (undoStack.size > 50) undoStack.removeAt(0)
        redoStack.clear()
        historyVersion++
    }

    fun applyStyle(transform: (CharStyle) -> CharStyle) {
        if (selection.collapsed) {
            pending = transform(currentStyle)
        } else {
            pushUndo()
            styles = applyToRange(styles, selection.min, selection.max, transform)
        }
    }

    fun loadDocument(meta: DocMeta) {
        val payload = storage.load(meta.id) ?: return
        val (text, loadedStyles) = stylesFromJson(payload)
        value = TextFieldValue(text, TextRange(text.length))
        styles = loadedStyles
        docId = meta.id
        docName = meta.name
        undoStack.clear()
        redoStack.clear()
        historyVersion++
    }

    LaunchedEffect(initialDocId) {
        if (initialDocId != null) {
            storage.list(DocType.TEXT).firstOrNull { it.id == initialDocId }?.let { loadDocument(it) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable { showRename = true }) {
                        Text(docName, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${wordCount(value.text)} mots",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        storage.save(docId, docName, DocType.TEXT, stylesToJson(value.text, styles))
                        savedMessage = "Enregistré"
                    }) {
                        Icon(Icons.Filled.Save, contentDescription = "Enregistrer")
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Nouveau document") },
                                onClick = {
                                    showMenu = false
                                    value = TextFieldValue("")
                                    styles = emptyList()
                                    docId = storage.newId()
                                    docName = "Document sans titre"
                                    undoStack.clear(); redoStack.clear(); historyVersion++
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Ouvrir…") },
                                onClick = { showMenu = false; showOpen = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Renommer") },
                                onClick = { showMenu = false; showRename = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Partager le texte") },
                                onClick = { showMenu = false; shareText(context, docName, value.text) }
                            )
                            DropdownMenuItem(
                                text = { Text("Statistiques") },
                                onClick = { showMenu = false; showStats = true }
                            )
                            Divider()
                            DropdownMenuItem(
                                text = { Text("Tout effacer") },
                                onClick = { showMenu = false; showClearConfirm = true }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${value.text.length} caractères · ${wordCount(value.text)} mots",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (selection.collapsed) "Aucune sélection"
                        else "${selection.max - selection.min} sélectionnés",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // --- Barre d'outils : styles de caractère ---
            Surface(tonalElevation = 2.dp) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        key(historyVersion) {
                            ToolToggle(
                                Icons.Filled.Undo, "Annuler",
                                enabled = undoStack.isNotEmpty()
                            ) {
                                if (undoStack.isNotEmpty()) {
                                    redoStack.add(Snapshot(value.text, styles, selection))
                                    val snap = undoStack.removeAt(undoStack.size - 1)
                                    value = TextFieldValue(snap.text, snap.selection)
                                    styles = snap.styles
                                    historyVersion++
                                }
                            }
                            ToolToggle(
                                Icons.Filled.Redo, "Rétablir",
                                enabled = redoStack.isNotEmpty()
                            ) {
                                if (redoStack.isNotEmpty()) {
                                    undoStack.add(Snapshot(value.text, styles, selection))
                                    val snap = redoStack.removeAt(redoStack.size - 1)
                                    value = TextFieldValue(snap.text, snap.selection)
                                    styles = snap.styles
                                    historyVersion++
                                }
                            }
                        }
                        VerticalSeparator()
                        ToolToggle(Icons.Filled.FormatBold, "Gras", currentStyle.bold) {
                            applyStyle { it.copy(bold = !currentStyle.bold) }
                        }
                        ToolToggle(Icons.Filled.FormatItalic, "Italique", currentStyle.italic) {
                            applyStyle { it.copy(italic = !currentStyle.italic) }
                        }
                        ToolToggle(Icons.Filled.FormatUnderlined, "Souligné", currentStyle.underline) {
                            applyStyle { it.copy(underline = !currentStyle.underline) }
                        }
                        ToolToggle(Icons.Filled.FormatStrikethrough, "Barré", currentStyle.strike) {
                            applyStyle { it.copy(strike = !currentStyle.strike) }
                        }
                        VerticalSeparator()
                        ToolToggle(Icons.Filled.FormatColorText, "Couleur du texte") {
                            showColorPicker = true
                        }
                        ToolToggle(Icons.Filled.FormatColorFill, "Surlignage") {
                            showHighlightPicker = true
                        }
                        ToolToggle(Icons.Filled.FormatClear, "Effacer la mise en forme") {
                            applyStyle { CharStyle() }
                        }
                    }

                    // --- Barre d'outils : police, taille, alignement ---
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ToolChip(fontLabelAt(currentStyle.font)) { showFontPicker = true }
                        ToolChip("A- ") {
                            val next = FontSizes.lastOrNull { it < currentStyle.size } ?: FontSizes.first()
                            applyStyle { it.copy(size = next) }
                        }
                        ToolChip("${currentStyle.size} pt") { showSizePicker = true }
                        ToolChip(" A+") {
                            val next = FontSizes.firstOrNull { it > currentStyle.size } ?: FontSizes.last()
                            applyStyle { it.copy(size = next) }
                        }
                        VerticalSeparator()
                        ToolToggle(Icons.Filled.FormatAlignLeft, "Aligner à gauche", align == TextAlign.Start) {
                            align = TextAlign.Start
                        }
                        ToolToggle(Icons.Filled.FormatAlignCenter, "Centrer", align == TextAlign.Center) {
                            align = TextAlign.Center
                        }
                        ToolToggle(Icons.Filled.FormatAlignRight, "Aligner à droite", align == TextAlign.End) {
                            align = TextAlign.End
                        }
                        ToolToggle(Icons.Filled.FormatAlignJustify, "Justifier", align == TextAlign.Justify) {
                            align = TextAlign.Justify
                        }
                    }
                }
            }

            // --- Zone d'édition ---
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp)
            ) {
                if (value.text.isEmpty()) {
                    Text(
                        "Commence à écrire… puis sélectionne un passage pour le mettre en forme.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = { newValue ->
                        if (newValue.text != value.text) {
                            pushUndo()
                            styles = adjustStyles(value.text, newValue.text, styles, currentStyle)
                            pending = null
                        } else if (newValue.selection != value.selection) {
                            pending = null
                        }
                        value = newValue
                    },
                    modifier = Modifier.fillMaxSize(),
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        textAlign = align
                    ),
                    visualTransformation = RichTextTransformation(styles),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }

    if (showFontPicker) {
        ListPickerDialog(
            title = "Police",
            items = AppFonts,
            label = { it.label },
            selected = AppFonts.getOrNull(currentStyle.font),
            itemContent = { font: AppFont ->
                Text(font.label, fontSize = 18.sp, style = TextStyle(fontFamily = font.family))
            },
            onPick = { font ->
                val index = AppFonts.indexOf(font)
                applyStyle { it.copy(font = index) }
                showFontPicker = false
            },
            onDismiss = { showFontPicker = false }
        )
    }

    if (showSizePicker) {
        ListPickerDialog(
            title = "Taille du texte",
            items = FontSizes,
            label = { "$it pt" },
            selected = currentStyle.size,
            onPick = { size ->
                applyStyle { it.copy(size = size) }
                showSizePicker = false
            },
            onDismiss = { showSizePicker = false }
        )
    }

    if (showColorPicker) {
        ColorPickerDialog(
            title = "Couleur du texte",
            colors = EditorColors,
            onPick = { color ->
                if (color != null) applyStyle { it.copy(color = colorToLong(color)) }
                showColorPicker = false
            },
            onDismiss = { showColorPicker = false }
        )
    }

    if (showHighlightPicker) {
        ColorPickerDialog(
            title = "Surlignage",
            colors = HighlightColors,
            allowNone = true,
            onPick = { color ->
                applyStyle { it.copy(highlight = if (color == null) 0L else colorToLong(color)) }
                showHighlightPicker = false
            },
            onDismiss = { showHighlightPicker = false }
        )
    }

    if (showRename) {
        TextInputDialog(
            title = "Renommer le document",
            initialValue = docName,
            onConfirm = { newName ->
                docName = newName
                showRename = false
            },
            onDismiss = { showRename = false }
        )
    }

    if (showOpen) {
        val documents = storage.list(DocType.TEXT)
        ListPickerDialog(
            title = if (documents.isEmpty()) "Aucun document enregistré" else "Ouvrir un document",
            items = documents,
            label = { it.name },
            onPick = { meta ->
                loadDocument(meta)
                showOpen = false
            },
            onDismiss = { showOpen = false }
        )
    }

    if (showStats) {
        val text = value.text
        ConfirmDialog(
            title = "Statistiques",
            message = buildString {
                appendLine("Caractères : ${text.length}")
                appendLine("Caractères sans espaces : ${text.count { !it.isWhitespace() }}")
                appendLine("Mots : ${wordCount(text)}")
                appendLine("Lignes : ${if (text.isEmpty()) 0 else text.lines().size}")
                append("Paragraphes : ${text.split("\n").count { it.isNotBlank() }}")
            },
            confirmLabel = "OK",
            onConfirm = { showStats = false },
            onDismiss = { showStats = false }
        )
    }

    if (showClearConfirm) {
        ConfirmDialog(
            title = "Tout effacer ?",
            message = "Le contenu du document sera vidé. Cette action peut être annulée avec le bouton Annuler.",
            confirmLabel = "Effacer",
            onConfirm = {
                pushUndo()
                value = TextFieldValue("")
                styles = emptyList()
                showClearConfirm = false
            },
            onDismiss = { showClearConfirm = false }
        )
    }

    savedMessage?.let { message ->
        ConfirmDialog(
            title = message,
            message = "« $docName » a bien été enregistré sur l'appareil.",
            confirmLabel = "OK",
            onConfirm = { savedMessage = null },
            onDismiss = { savedMessage = null }
        )
    }
}

@Composable
private fun VerticalSeparator() {
    Box(
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
            .size(width = 1.dp, height = 24.dp)
    )
}

private fun colorToLong(color: Color): Long {
    val argb = android.graphics.Color.argb(
        (color.alpha * 255).toInt(),
        (color.red * 255).toInt(),
        (color.green * 255).toInt(),
        (color.blue * 255).toInt()
    )
    return argb.toLong() and 0xFFFFFFFFL
}

private fun wordCount(text: String): Int =
    text.split(Regex("\\s+")).count { it.isNotBlank() }
