package com.docssuite.texteditor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatIndentDecrease
import androidx.compose.material.icons.filled.FormatIndentIncrease
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Subscript
import androidx.compose.material.icons.filled.Superscript
import androidx.compose.material.icons.filled.TableChart
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
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
import com.docssuite.core.ExportAction
import com.docssuite.core.ExportFormatDialog
import com.docssuite.core.FontSizes
import com.docssuite.core.HighlightColors
import com.docssuite.core.ListPickerDialog
import com.docssuite.core.TextInputDialog
import com.docssuite.core.ToolChip
import com.docssuite.core.ToolToggle
import com.docssuite.core.fontLabelAt
import com.docssuite.core.importMimeTypes
import com.docssuite.core.rememberFileOpener
import com.docssuite.core.rememberFileSaver
import com.docssuite.core.safeFileName
import com.docssuite.core.shareBytes
import com.docssuite.core.shareText
import com.docssuite.fileformats.DocKind
import com.docssuite.fileformats.FileFormat
import com.docssuite.fileformats.FileFormats
import com.docssuite.fileformats.Imported
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Snapshot(val doc: EditorDoc, val focus: Target)

private val HeadingNames = listOf("Normal", "Titre 1", "Titre 2", "Titre 3")
private val LineSpacings = listOf(100, 115, 150, 200)
private fun spacingLabel(value: Int) = when (value) {
    100 -> "Simple"
    115 -> "1,15"
    150 -> "1,5"
    200 -> "Double"
    else -> "$value %"
}
private val CaseModes = listOf("MAJUSCULES", "minuscules", "Première Lettre En Majuscule")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(onBack: () -> Unit, initialDocId: String? = null) {
    val context = LocalContext.current
    val storage = remember { DocumentStorage(context) }
    val scope = rememberCoroutineScope()

    var doc by remember { mutableStateOf(EditorModel.emptyDoc()) }
    var focus by remember { mutableStateOf(Target(0)) }
    var pending by remember { mutableStateOf<CharStyle?>(null) }
    var painter by remember { mutableStateOf<CharStyle?>(null) }

    var docId by remember { mutableStateOf(storage.newId()) }
    var docName by remember { mutableStateOf("Document sans titre") }

    val undoStack = remember { mutableListOf<Snapshot>() }
    val redoStack = remember { mutableListOf<Snapshot>() }
    var historyVersion by remember { mutableStateOf(0) }

    var showFontPicker by remember { mutableStateOf(false) }
    var showSizePicker by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showHighlightPicker by remember { mutableStateOf(false) }
    var showCellFill by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showOpen by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var showInsertTable by remember { mutableStateOf(false) }
    var showHeading by remember { mutableStateOf(false) }
    var showSpacing by remember { mutableStateOf(false) }
    var showCase by remember { mutableStateOf(false) }
    var showSymbols by remember { mutableStateOf(false) }
    var showOutline by remember { mutableStateOf(false) }
    var showDeleteTable by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findReplacement by remember { mutableStateOf("") }
    var findMatchCase by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingFocus by remember { mutableStateOf<Target?>(null) }

    // Format retenu le temps que l'utilisateur choisisse où ranger le fichier.
    var pendingFormat by remember { mutableStateOf(FileFormat.DOCX) }

    // Un demandeur de focus par champ, retrouvé par l'identifiant stable du bloc.
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requesterFor(blockId: Long, row: Int = -1, cell: Int = -1): FocusRequester =
        requesters.getOrPut("$blockId:$row:$cell") { FocusRequester() }

    fun requesterFor(target: Target): FocusRequester? {
        val block = doc.blocks.getOrNull(target.block) ?: return null
        return requesterFor(block.id, target.row, target.cell)
    }

    val focusedField = EditorModel.field(doc, focus)
        ?: EditorModel.field(doc, Target(0))
        ?: RichField()
    val selection = focusedField.value.selection
    val currentStyle = pending
        ?: focusedField.styles.getOrNull(selection.start - 1)
        ?: focusedField.styles.getOrNull(selection.start)
        ?: CharStyle()
    val currentPara = focusedField.paras.getOrElse(
        EditorModel.paragraphIndexAt(focusedField.text, selection.start)
    ) { ParaStyle() }
    val focusedTable = (doc.blocks.getOrNull(focus.block) as? EditorBlock.Table)?.takeIf { focus.inTable }
    val allText = EditorModel.allText(doc)

    fun pushUndo() {
        undoStack.add(Snapshot(doc, focus))
        if (undoStack.size > 80) undoStack.removeAt(0)
        redoStack.clear()
        historyVersion++
    }

    fun resetHistory() {
        undoStack.clear(); redoStack.clear(); historyVersion++
    }

    /** Le champ qui a le focus, ou le premier bloc de texte s'il a disparu. */
    fun activeTarget(): Target =
        if (EditorModel.field(doc, focus) != null) focus else Target(doc.blocks.indexOfFirst { it is EditorBlock.Text }.coerceAtLeast(0))

    fun updateField(transform: (RichField) -> RichField) {
        val target = activeTarget()
        val field = EditorModel.field(doc, target) ?: return
        val updated = transform(field).normalized()
        if (updated == field) return
        pushUndo()
        doc = EditorModel.withField(doc, target, updated)
    }

    fun applyStyle(transform: (CharStyle) -> CharStyle) {
        if (selection.collapsed) {
            pending = transform(currentStyle)
        } else {
            updateField { it.copy(styles = applyToRange(it.styles, selection.min, selection.max, transform)) }
        }
    }

    /** Frappe au clavier dans un champ : texte, styles, paragraphes et listes suivent. */
    fun onFieldChange(target: Target, newValue: TextFieldValue) {
        val old = EditorModel.field(doc, target) ?: return
        focus = target
        if (newValue.text == old.text) {
            if (newValue.selection != old.value.selection) pending = null
            doc = EditorModel.withField(doc, target, old.copy(value = newValue))
            return
        }
        pushUndo()
        val caretBefore = old.value.selection.start
        val style = pending
            ?: old.styles.getOrNull(caretBefore - 1)
            ?: old.styles.getOrNull(caretBefore)
            ?: CharStyle()
        var updated = RichField(
            value = newValue,
            styles = adjustStyles(old.text, newValue.text, old.styles, style),
            paras = EditorModel.adjustParas(old.text, newValue.text, old.paras)
        ).normalized()
        EditorModel.continueList(old, updated, style)?.let { updated = it }
        pending = null
        // Entrée au bout d'un titre : le paragraphe suivant repart en texte normal.
        val caret = updated.value.selection.start
        if (newValue.text.length == old.text.length + 1 && caret > 0 && updated.text.getOrNull(caret - 1) == '\n') {
            val previous = old.paras.getOrElse(EditorModel.paragraphIndexAt(old.text, caretBefore)) { ParaStyle() }
            if (previous.heading > 0) pending = style.copy(size = 16, bold = false)
        }
        doc = EditorModel.withField(doc, target, updated)
    }

    fun loadDocument(meta: DocMeta) {
        val payload = storage.load(meta.id) ?: return
        runCatching { EditorIO.fromJson(payload) }
            .onSuccess { loaded ->
                doc = loaded
                focus = Target(0)
                docId = meta.id
                docName = meta.name
                resetHistory()
            }
            .onFailure { notice = "Ouverture impossible" to "« ${meta.name} » est endommagé." }
    }

    LaunchedEffect(initialDocId) {
        if (initialDocId != null) {
            storage.list(DocType.TEXT).firstOrNull { it.id == initialDocId }?.let { loadDocument(it) }
        }
    }

    // Donne le focus à un champ créé ou désigné par une action (tableau
    // inséré, titre choisi dans le plan, occurrence trouvée).
    LaunchedEffect(pendingFocus) {
        val target = pendingFocus ?: return@LaunchedEffect
        delay(60)
        requesterFor(target)?.let { runCatching { it.requestFocus() } }
        pendingFocus = null
    }

    fun currentDocument() = EditorIO.toTextDocument(doc, docName)

    val fileSaver = rememberFileSaver(
        onError = { notice = "Enregistrement impossible" to it },
        onSaved = { notice = "Fichier enregistré" to "« $it » est disponible à l'emplacement choisi." },
        content = { FileFormats.exportText(pendingFormat, currentDocument()) }
    )

    val fileOpener = rememberFileOpener(
        onError = { notice = "Import impossible" to it }
    ) { picked ->
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val imported = FileFormats.import(picked.name, picked.bytes)
                    imported to (imported as? Imported.AsText)?.let { EditorIO.fromTextDocument(it.document) }
                }
            }
            result
                .onSuccess { (imported, converted) ->
                    if (converted != null) {
                        doc = converted
                        focus = Target(0)
                        // Un import devient un nouveau document : on ne veut pas
                        // écraser celui qui était ouvert.
                        docId = storage.newId()
                        docName = imported.suggestedName
                        resetHistory()
                    } else {
                        notice = "Ce n'est pas un document" to
                            "« ${picked.name} » est un tableur, une présentation ou un PDF. " +
                            "Ouvrez-le depuis l'accueil."
                    }
                }
                .onFailure {
                    notice = "Import impossible" to
                        if (it is OutOfMemoryError) "Ce fichier est trop lourd pour la mémoire du téléphone."
                        else (it.message ?: "Fichier illisible")
                }
        }
    }

    fun export(format: FileFormat, action: ExportAction) {
        showExport = false
        pendingFormat = format
        val fileName = safeFileName(docName, format)
        when (action) {
            ExportAction.SAVE -> fileSaver.save(fileName, format.mime)
            ExportAction.SHARE -> scope.launch {
                val document = currentDocument()
                val produced = withContext(Dispatchers.IO) {
                    runCatching { FileFormats.exportText(format, document) }
                }
                produced
                    .onSuccess {
                        shareBytes(context, fileName, format.mime, it) { message ->
                            notice = "Partage impossible" to message
                        }
                    }
                    .onFailure {
                        notice = "Export impossible" to (it.message ?: "Conversion échouée")
                    }
            }
        }
    }

    fun save() {
        val payload = EditorIO.toJson(doc)
        storage.save(docId, docName, DocType.TEXT, payload)
        notice = "Enregistré" to "« $docName » a bien été enregistré dans l'application."
    }

    // ------------------------------------------------------------ tableaux

    fun updateTable(transform: (TableData) -> TableData?) {
        val block = doc.blocks.getOrNull(focus.block) as? EditorBlock.Table ?: return
        val updated = transform(block.table)
        pushUndo()
        if (updated == null) {
            doc = EditorModel.deleteTable(doc, focus.block)
            focus = Target((focus.block).coerceAtMost(doc.blocks.lastIndex))
            return
        }
        val blocks = doc.blocks.toMutableList()
        blocks[focus.block] = block.copy(table = updated)
        doc = doc.copy(blocks = blocks)
        // La cellule sélectionnée peut avoir disparu : on se replie sur la plus proche.
        val row = focus.row.coerceIn(0, updated.rows.lastIndex)
        val cells = updated.rows[row]
        var cell = focus.cell.coerceIn(0, cells.lastIndex)
        while (cell > 0 && cells[cell].mergedAbove) cell--
        focus = Target(focus.block, row, cell)
    }

    // ------------------------------------------------------------ recherche

    fun findFrom(forward: Boolean) {
        if (findQuery.isEmpty()) return
        val targets = EditorModel.targets(doc)
        if (targets.isEmpty()) return
        val start = targets.indexOf(activeTarget()).coerceAtLeast(0)
        val ignoreCase = !findMatchCase
        for (step in 0..targets.size) {
            val index = if (forward) (start + step) % targets.size else ((start - step) % targets.size + targets.size) % targets.size
            val target = targets[index]
            val field = EditorModel.field(doc, target) ?: continue
            val text = field.text
            val found = if (forward) {
                val from = if (step == 0) field.value.selection.max else 0
                text.indexOf(findQuery, from, ignoreCase).takeIf { it >= 0 }
            } else {
                val before = if (step == 0) field.value.selection.min - 1 else text.length
                if (before < 0) null else text.lastIndexOf(findQuery, before, ignoreCase).takeIf { it >= 0 }
            }
            if (found != null) {
                doc = EditorModel.withField(doc, target, field.copy(value = field.value.copy(selection = TextRange(found, found + findQuery.length))))
                focus = target
                pendingFocus = target
                return
            }
        }
        notice = "Recherche" to "« $findQuery » est introuvable."
    }

    fun openCase() {
        if (selection.collapsed) {
            notice = "Changer la casse" to "Sélectionnez d'abord le texte à mettre en majuscules ou en minuscules."
        } else {
            showCase = true
        }
    }

    // ------------------------------------------------------------ interface

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable { showRename = true }) {
                        Text(docName, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${wordCount(allText)} mots",
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
                    IconButton(onClick = ::save) {
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
                                    doc = EditorModel.emptyDoc()
                                    focus = Target(0)
                                    docId = storage.newId()
                                    docName = "Document sans titre"
                                    resetHistory()
                                }
                            )
                            DropdownMenuItem(text = { Text("Ouvrir…") }, onClick = { showMenu = false; showOpen = true })
                            DropdownMenuItem(text = { Text("Renommer") }, onClick = { showMenu = false; showRename = true })
                            DropdownMenuItem(
                                text = { Text("Importer un fichier…") },
                                leadingIcon = { Icon(Icons.Filled.FileOpen, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    fileOpener.open(importMimeTypes(DocKind.TEXT))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Exporter (Word, PDF…)") },
                                leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                                onClick = { showMenu = false; showExport = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Partager le texte") },
                                onClick = { showMenu = false; shareText(context, docName, allText) }
                            )
                            Divider()
                            DropdownMenuItem(
                                text = { Text("Insérer un tableau…") },
                                leadingIcon = { Icon(Icons.Filled.TableChart, contentDescription = null) },
                                onClick = { showMenu = false; showInsertTable = true }
                            )
                            DropdownMenuItem(text = { Text("Plan du document") }, onClick = { showMenu = false; showOutline = true })
                            DropdownMenuItem(text = { Text("Caractères spéciaux…") }, onClick = { showMenu = false; showSymbols = true })
                            DropdownMenuItem(text = { Text("Changer la casse…") }, onClick = { showMenu = false; openCase() })
                            DropdownMenuItem(
                                text = { Text("Interligne : ${spacingLabel(doc.lineSpacing)}") },
                                onClick = { showMenu = false; showSpacing = true }
                            )
                            DropdownMenuItem(text = { Text("Rechercher et remplacer") }, onClick = { showMenu = false; showFind = true })
                            DropdownMenuItem(text = { Text("Statistiques") }, onClick = { showMenu = false; showStats = true })
                            Divider()
                            DropdownMenuItem(text = { Text("Tout effacer") }, onClick = { showMenu = false; showClearConfirm = true })
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
                        "${allText.length} caractères · ${wordCount(allText)} mots",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        when {
                            focusedTable != null -> "Tableau · ligne ${focus.row + 1}"
                            selection.collapsed -> "Aucune sélection"
                            else -> "${selection.max - selection.min} sélectionnés"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            Surface(tonalElevation = 2.dp) {
                Column {
                    // --- Caractères ---
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        key(historyVersion) {
                            ToolToggle(Icons.Filled.Undo, "Annuler", enabled = undoStack.isNotEmpty()) {
                                if (undoStack.isNotEmpty()) {
                                    redoStack.add(Snapshot(doc, focus))
                                    val snap = undoStack.removeAt(undoStack.size - 1)
                                    doc = snap.doc; focus = snap.focus
                                    historyVersion++
                                }
                            }
                            ToolToggle(Icons.Filled.Redo, "Rétablir", enabled = redoStack.isNotEmpty()) {
                                if (redoStack.isNotEmpty()) {
                                    undoStack.add(Snapshot(doc, focus))
                                    val snap = redoStack.removeAt(redoStack.size - 1)
                                    doc = snap.doc; focus = snap.focus
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
                        ToolToggle(Icons.Filled.Superscript, "Exposant", currentStyle.baseline == 1) {
                            applyStyle { it.copy(baseline = if (currentStyle.baseline == 1) 0 else 1) }
                        }
                        ToolToggle(Icons.Filled.Subscript, "Indice", currentStyle.baseline == -1) {
                            applyStyle { it.copy(baseline = if (currentStyle.baseline == -1) 0 else -1) }
                        }
                        VerticalSeparator()
                        ToolToggle(Icons.Filled.FormatColorText, "Couleur du texte") { showColorPicker = true }
                        ToolToggle(Icons.Filled.FormatColorFill, "Surlignage") { showHighlightPicker = true }
                        ToolToggle(Icons.Filled.FormatPaint, "Reproduire la mise en forme", painter != null) {
                            val copied = painter
                            if (copied == null) {
                                painter = currentStyle
                            } else {
                                if (!selection.collapsed) {
                                    updateField { it.copy(styles = applyToRange(it.styles, selection.min, selection.max) { copied }) }
                                }
                                painter = null
                            }
                        }
                        ToolToggle(Icons.Filled.FormatClear, "Effacer la mise en forme") { applyStyle { CharStyle() } }
                    }

                    // --- Paragraphes ---
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ToolChip(HeadingNames[currentPara.heading.coerceIn(0, 3)]) { showHeading = true }
                        ToolChip(fontLabelAt(currentStyle.font)) { showFontPicker = true }
                        ToolChip("A−") {
                            val next = FontSizes.lastOrNull { it < currentStyle.size } ?: FontSizes.first()
                            applyStyle { it.copy(size = next) }
                        }
                        ToolChip("${currentStyle.size} pt") { showSizePicker = true }
                        ToolChip("A+") {
                            val next = FontSizes.firstOrNull { it > currentStyle.size } ?: FontSizes.last()
                            applyStyle { it.copy(size = next) }
                        }
                        VerticalSeparator()
                        ToolToggle(Icons.Filled.FormatAlignLeft, "Aligner à gauche", currentPara.align == 0) {
                            updateField { EditorModel.setAlign(it, 0) }
                        }
                        ToolToggle(Icons.Filled.FormatAlignCenter, "Centrer", currentPara.align == 1) {
                            updateField { EditorModel.setAlign(it, 1) }
                        }
                        ToolToggle(Icons.Filled.FormatAlignRight, "Aligner à droite", currentPara.align == 2) {
                            updateField { EditorModel.setAlign(it, 2) }
                        }
                        ToolToggle(Icons.Filled.FormatAlignJustify, "Justifier", currentPara.align == 3) {
                            updateField { EditorModel.setAlign(it, 3) }
                        }
                        VerticalSeparator()
                        ToolToggle(Icons.Filled.FormatListBulleted, "Liste à puces") {
                            updateField(EditorModel::toggleBullets)
                        }
                        ToolToggle(Icons.Filled.FormatListNumbered, "Liste numérotée") {
                            updateField(EditorModel::toggleNumbering)
                        }
                        ToolToggle(Icons.Filled.FormatIndentDecrease, "Diminuer le retrait", enabled = currentPara.indent > 0) {
                            updateField { EditorModel.changeIndent(it, -1) }
                        }
                        ToolToggle(Icons.Filled.FormatIndentIncrease, "Augmenter le retrait") {
                            updateField { EditorModel.changeIndent(it, 1) }
                        }
                        VerticalSeparator()
                        ToolToggle(Icons.Filled.TableChart, "Insérer un tableau") { showInsertTable = true }
                        ToolChip("Ω") { showSymbols = true }
                        ToolChip("Aa") { openCase() }
                    }

                    // --- Tableau (seulement quand le curseur y est) ---
                    if (focusedTable != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "Tableau",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            ToolChip("+ Ligne") { updateTable { EditorModel.insertRow(it, focus.row, below = true) } }
                            ToolChip("− Ligne") { updateTable { EditorModel.deleteRow(it, focus.row) } }
                            ToolChip("+ Colonne") { updateTable { EditorModel.insertColumn(it, focus.row, focus.cell, after = true) } }
                            ToolChip("− Colonne") { updateTable { EditorModel.deleteColumn(it, focus.row, focus.cell) } }
                            ToolChip("Fusionner →") { updateTable { EditorModel.mergeRight(it, focus.row, focus.cell) } }
                            ToolChip("Scinder") { updateTable { EditorModel.splitCell(it, focus.row, focus.cell) } }
                            ToolChip("Fond") { showCellFill = true }
                            ToolChip(if (focusedTable.table.headerRow) "En-tête ✓" else "En-tête") {
                                updateTable { it.copy(headerRow = !it.headerRow) }
                            }
                            ToolChip("Supprimer le tableau") { showDeleteTable = true }
                        }
                    }

                    if (painter != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Mise en forme copiée : sélectionnez un texte puis touchez de nouveau le pinceau.",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { painter = null }) { Text("Annuler") }
                        }
                    }
                }
            }

            if (showFind) {
                val occurrences = EditorModel.targets(doc).sumOf { target ->
                    TextSearch.count(EditorModel.field(doc, target)?.text.orEmpty(), findQuery, findMatchCase)
                }
                FindReplaceBar(
                    query = findQuery,
                    replacement = findReplacement,
                    matchCase = findMatchCase,
                    occurrences = occurrences,
                    onQueryChange = { findQuery = it },
                    onReplacementChange = { findReplacement = it },
                    onMatchCaseChange = { findMatchCase = it },
                    onFind = ::findFrom,
                    onReplace = {
                        val target = activeTarget()
                        val field = EditorModel.field(doc, target)
                        val range = field?.value?.selection
                        val selected = if (field != null && range != null) field.text.substring(range.min, range.max) else ""
                        // On ne remplace que si la sélection est bien l'occurrence
                        // en cours ; sinon on s'y rend d'abord.
                        if (field != null && range != null && findQuery.isNotEmpty() &&
                            selected.equals(findQuery, ignoreCase = !findMatchCase)
                        ) {
                            pushUndo()
                            val result = TextSearch.replaceRange(field.text, field.styles, range.min until range.max, findReplacement)
                            val updated = RichField(
                                value = TextFieldValue(result.text, TextRange(result.caret)),
                                styles = result.styles,
                                paras = EditorModel.adjustParas(field.text, result.text, field.paras)
                            ).normalized()
                            doc = EditorModel.withField(doc, target, updated)
                        }
                        findFrom(true)
                    },
                    onReplaceAll = {
                        var total = 0
                        var updated = doc
                        EditorModel.targets(doc).forEach { target ->
                            val field = EditorModel.field(updated, target) ?: return@forEach
                            val result = TextSearch.replaceAll(field.text, field.styles, findQuery, findReplacement, findMatchCase)
                            if (result.count > 0) {
                                total += result.count
                                updated = EditorModel.withField(
                                    updated, target,
                                    RichField(
                                        value = TextFieldValue(result.text, TextRange(result.caret)),
                                        styles = result.styles,
                                        paras = EditorModel.adjustParas(field.text, result.text, field.paras)
                                    ).normalized()
                                )
                            }
                        }
                        if (total == 0) {
                            notice = "Remplacement" to "« $findQuery » est introuvable."
                        } else {
                            pushUndo()
                            doc = updated
                            notice = "Remplacement" to
                                if (total == 1) "1 occurrence remplacée." else "$total occurrences remplacées."
                        }
                    },
                    onClose = { showFind = false }
                )
            }

            // --- Zone d'édition ---
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                val onlyEmptyText = doc.blocks.size == 1 && (doc.blocks[0] as? EditorBlock.Text)?.field?.text?.isEmpty() == true
                doc.blocks.forEachIndexed { index, block ->
                    key(block.id) {
                        when (block) {
                            is EditorBlock.Text -> RichTextField(
                                field = block.field,
                                lineSpacing = doc.lineSpacing,
                                placeholder = if (onlyEmptyText) {
                                    "Commence à écrire… puis sélectionne un passage pour le mettre en forme."
                                } else null,
                                focusRequester = requesterFor(block.id),
                                onFocused = { focus = Target(index) },
                                onValueChange = { onFieldChange(Target(index), it) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            is EditorBlock.Table -> TableView(
                                table = block.table,
                                lineSpacing = doc.lineSpacing,
                                selected = if (focus.block == index && focus.inTable) focus.row to focus.cell else null,
                                requesterFor = { r, c -> requesterFor(block.id, r, c) },
                                onCellFocused = { r, c -> focus = Target(index, r, c) },
                                onCellChange = { r, c, value -> onFieldChange(Target(index, r, c), value) }
                            )
                        }
                    }
                }
                // De la place sous le texte pour qu'on puisse le faire remonter
                // au-dessus du clavier.
                Spacer(modifier = Modifier.height(160.dp))
            }
        }
    }

    // ------------------------------------------------------------ dialogues

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

    if (showHeading) {
        ListPickerDialog(
            title = "Style de paragraphe",
            items = HeadingNames,
            label = { it },
            selected = HeadingNames[currentPara.heading.coerceIn(0, 3)],
            itemContent = { name: String ->
                val level = HeadingNames.indexOf(name)
                Text(
                    name,
                    fontSize = EditorModel.headingSize(level).coerceAtMost(22).sp,
                    fontWeight = if (level > 0) FontWeight.Bold else FontWeight.Normal
                )
            },
            onPick = { name ->
                updateField { EditorModel.setHeading(it, HeadingNames.indexOf(name)) }
                pending = null
                showHeading = false
            },
            onDismiss = { showHeading = false }
        )
    }

    if (showSpacing) {
        ListPickerDialog(
            title = "Interligne",
            items = LineSpacings,
            label = { spacingLabel(it) },
            selected = doc.lineSpacing,
            onPick = { value ->
                pushUndo()
                doc = doc.copy(lineSpacing = value)
                showSpacing = false
            },
            onDismiss = { showSpacing = false }
        )
    }

    if (showCase) {
        ListPickerDialog(
            title = "Changer la casse",
            items = CaseModes,
            label = { it },
            onPick = { mode ->
                updateField { EditorModel.changeCase(it, CaseModes.indexOf(mode)) }
                showCase = false
            },
            onDismiss = { showCase = false }
        )
    }

    if (showSymbols) {
        SpecialCharactersDialog(
            onPick = { symbol ->
                val target = activeTarget()
                val field = EditorModel.field(doc, target)
                if (field != null) {
                    pushUndo()
                    val range = field.value.selection
                    doc = EditorModel.withField(
                        doc, target,
                        EditorModel.replace(field, range.min, range.max, symbol, currentStyle)
                    )
                    pendingFocus = target
                }
                showSymbols = false
            },
            onDismiss = { showSymbols = false }
        )
    }

    if (showOutline) {
        OutlineDialog(
            entries = outlineOf(doc),
            onPick = { entry ->
                val field = EditorModel.field(doc, entry.target)
                if (field != null) {
                    doc = EditorModel.withField(doc, entry.target, field.copy(value = field.value.copy(selection = TextRange(entry.offset))))
                    focus = entry.target
                    pendingFocus = entry.target
                }
                showOutline = false
            },
            onDismiss = { showOutline = false }
        )
    }

    if (showInsertTable) {
        InsertTableDialog(
            onConfirm = { rows, columns, header ->
                pushUndo()
                val (updated, target) = EditorModel.insertTable(doc, activeTarget(), EditorModel.newTable(rows, columns, header))
                doc = updated
                focus = target
                pendingFocus = target
                showInsertTable = false
            },
            onDismiss = { showInsertTable = false }
        )
    }

    if (showDeleteTable) {
        ConfirmDialog(
            title = "Supprimer le tableau ?",
            message = "Le tableau et son contenu seront retirés du document. Le bouton Annuler permet de revenir en arrière.",
            onConfirm = {
                updateTable { null }
                showDeleteTable = false
            },
            onDismiss = { showDeleteTable = false }
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

    if (showCellFill) {
        ColorPickerDialog(
            title = "Fond de la cellule",
            colors = HighlightColors,
            allowNone = true,
            onPick = { color ->
                updateTable { EditorModel.setFill(it, focus.row, focus.cell, if (color == null) 0L else colorToLong(color)) }
                showCellFill = false
            },
            onDismiss = { showCellFill = false }
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
        val tables = doc.blocks.count { it is EditorBlock.Table }
        val headings = outlineOf(doc).size
        ConfirmDialog(
            title = "Statistiques",
            message = buildString {
                appendLine("Caractères : ${allText.length}")
                appendLine("Caractères sans espaces : ${allText.count { !it.isWhitespace() }}")
                appendLine("Mots : ${wordCount(allText)}")
                appendLine("Paragraphes : ${allText.split("\n").count { it.isNotBlank() }}")
                appendLine("Titres : $headings")
                append("Tableaux : $tables")
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
                doc = EditorModel.emptyDoc()
                focus = Target(0)
                showClearConfirm = false
            },
            onDismiss = { showClearConfirm = false }
        )
    }

    if (showExport) {
        ExportFormatDialog(
            kind = DocKind.TEXT,
            onPick = ::export,
            onDismiss = { showExport = false }
        )
    }

    notice?.let { (title, body) ->
        ConfirmDialog(
            title = title,
            message = body,
            confirmLabel = "OK",
            onConfirm = { notice = null },
            onDismiss = { notice = null }
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

internal fun wordCount(text: String): Int =
    text.split(Regex("\\s+")).count { it.isNotBlank() }
