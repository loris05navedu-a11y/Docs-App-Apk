package com.docssuite.spreadsheet

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.TextButton
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.docssuite.core.ColorPickerDialog
import com.docssuite.core.buildChartEntries
import com.docssuite.core.ConfirmDialog
import com.docssuite.core.DocType
import com.docssuite.core.DocumentStorage
import com.docssuite.core.EditorColors
import com.docssuite.core.FormulaEngine
import com.docssuite.core.HighlightColors
import com.docssuite.core.ListPickerDialog
import com.docssuite.core.TextInputDialog
import com.docssuite.core.ToolToggle
import com.docssuite.core.ExportAction
import com.docssuite.core.ExportFormatDialog
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val DEFAULT_COLUMNS = 12
private const val DEFAULT_ROWS = 40
private val CELL_WIDTH = 96.dp
private val CELL_HEIGHT = 44.dp
private val HEADER_WIDTH = 48.dp

internal data class CellFormat(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val color: Long = 0xFF1A1A1AL,
    val background: Long = 0L,
    val align: Int = 0 // 0 = auto, 1 = gauche, 2 = centre, 3 = droite
)

private data class WorkbookSnapshot(val sheets: List<SheetState>, val active: Int, val selected: String)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SpreadsheetScreen(onBack: () -> Unit, initialDocId: String? = null) {
    val context = LocalContext.current
    val storage = remember { DocumentStorage(context) }

    // La feuille affichée vit dans ces états « à plat » ; les autres feuilles
    // attendent dans [sheets], resynchronisée à chaque changement d'onglet.
    val cells = remember { mutableStateMapOf<String, String>() }
    val formats = remember { mutableStateMapOf<String, CellFormat>() }
    var columns by remember { mutableStateOf(DEFAULT_COLUMNS) }
    var rows by remember { mutableStateOf(DEFAULT_ROWS) }
    var freezeRow by remember { mutableStateOf(false) }
    var freezeColumn by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf("A1") }
    var editing by remember { mutableStateOf("") }

    val sheets = remember { mutableStateListOf(SheetState("Feuille1")) }
    var active by remember { mutableStateOf(0) }

    var docId by remember { mutableStateOf(storage.newId()) }
    var docName by remember { mutableStateOf("Classeur sans titre") }

    var showMenu by remember { mutableStateOf(false) }
    var showFunctions by remember { mutableStateOf(false) }
    var showTextColor by remember { mutableStateOf(false) }
    var showFillColor by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showOpen by remember { mutableStateOf(false) }
    var showClearAll by remember { mutableStateOf(false) }
    var showChart by remember { mutableStateOf(false) }
    var showCalculator by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var sheetMenuFor by remember { mutableStateOf(-1) }
    var renameSheetIndex by remember { mutableStateOf(-1) }
    var deleteSheetIndex by remember { mutableStateOf(-1) }
    var findQuery by remember { mutableStateOf("") }
    var findReplacement by remember { mutableStateOf("") }

    // Format retenu le temps que l'utilisateur choisisse où ranger le fichier.
    var pendingFormat by remember { mutableStateOf(FileFormat.XLSX) }

    val charts = remember { mutableStateListOf<EmbeddedChart>() }
    var selectedChartId by remember { mutableStateOf<String?>(null) }
    var editingChart by remember { mutableStateOf<EmbeddedChart?>(null) }
    val gridState = rememberLazyListState()
    val density = LocalDensity.current
    val cellHeightPx = with(density) { CELL_HEIGHT.toPx() }

    val undoStack = remember { mutableListOf<WorkbookSnapshot>() }
    val redoStack = remember { mutableListOf<WorkbookSnapshot>() }
    var historyVersion by remember { mutableStateOf(0) }

    fun updateChart(id: String, transform: (EmbeddedChart) -> EmbeddedChart) {
        val index = charts.indexOfFirst { it.id == id }
        if (index >= 0) charts[index] = transform(charts[index])
    }
    var notice by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(selected) { editing = cells[selected] ?: "" }

    val hScroll = rememberScrollState()
    val format = formats[selected] ?: CellFormat()

    // ------------------------------------------------------------ classeur

    /** La feuille affichée, figée dans la liste des feuilles. */
    fun liveSheet() = SheetState(
        name = sheets.getOrNull(active)?.name ?: "Feuille1",
        cells = cells.toMap(),
        formats = formats.toMap(),
        columns = columns,
        rows = rows,
        charts = charts.toList(),
        freezeRow = freezeRow,
        freezeColumn = freezeColumn
    )

    fun allSheets(): List<SheetState> = sheets.toMutableList().also { list ->
        if (active in list.indices) list[active] = liveSheet()
    }

    fun syncActive() {
        if (active in sheets.indices) sheets[active] = liveSheet()
    }

    fun showSheet(index: Int) {
        val sheet = sheets.getOrNull(index) ?: return
        active = index
        cells.clear(); cells.putAll(sheet.cells)
        formats.clear(); formats.putAll(sheet.formats)
        charts.clear(); charts.addAll(sheet.charts)
        columns = sheet.columns
        rows = sheet.rows
        freezeRow = sheet.freezeRow
        freezeColumn = sheet.freezeColumn
        selectedChartId = null
        selected = "A1"
        editing = cells["A1"].orEmpty()
    }

    fun replaceWorkbook(newSheets: List<SheetState>, newActive: Int) {
        sheets.clear()
        sheets.addAll(newSheets.ifEmpty { listOf(SheetState("Feuille1")) })
        showSheet(newActive.coerceIn(0, sheets.lastIndex))
    }

    fun switchTo(index: Int) {
        if (index == active || index !in sheets.indices) return
        syncActive()
        showSheet(index)
    }

    // Le moteur voit la feuille affichée et toutes les autres ; les valeurs
    // calculées sont mises en cache jusqu'à la prochaine modification.
    val engine by remember {
        derivedStateOf {
            val live = cells.toMap()
            val list = sheets.toMutableList()
            if (active in list.indices) list[active] = list[active].copy(cells = live)
            WorkbookOps.engineCells(list, active)
        }
    }
    val shownCache = remember(engine) { HashMap<String, String>() }
    fun shown(key: String): String = shownCache.getOrPut(key) { FormulaEngine.displayValue(key, engine) }

    // ------------------------------------------------------------ historique

    fun snapshot() = WorkbookSnapshot(allSheets(), active, selected)

    fun pushUndo() {
        undoStack.add(snapshot())
        if (undoStack.size > 60) undoStack.removeAt(0)
        redoStack.clear()
        historyVersion++
    }

    fun restore(state: WorkbookSnapshot) {
        sheets.clear(); sheets.addAll(state.sheets)
        showSheet(state.active.coerceIn(0, sheets.lastIndex))
        selected = state.selected
        editing = cells[selected].orEmpty()
        historyVersion++
    }

    fun resetHistory() {
        undoStack.clear(); redoStack.clear(); historyVersion++
    }

    // ------------------------------------------------------------ cellules

    fun updateFormat(transform: (CellFormat) -> CellFormat) {
        pushUndo()
        formats[selected] = transform(formats[selected] ?: CellFormat())
    }

    fun selectedRow(): Int = (selected.dropWhile { it.isLetter() }.toIntOrNull() ?: 1) - 1

    fun selectedColumn(): Int = FormulaEngine.columnIndex(selected.takeWhile { it.isLetter() })

    fun replaceGrid(data: SheetData<CellFormat>) {
        cells.clear(); cells.putAll(data.cells)
        formats.clear(); formats.putAll(data.formats)
        editing = cells[selected].orEmpty()
    }

    fun applyGridEdit(
        operation: (Map<String, String>, Map<String, CellFormat>, Int) -> SheetData<CellFormat>,
        index: Int
    ) {
        pushUndo()
        replaceGrid(operation(cells.toMap(), formats.toMap(), index))
    }

    /**
     * Trie de la cellule choisie jusqu'à la dernière ligne remplie. Partir de
     * la sélection laisse l'en-tête en place quand on se place sous lui.
     */
    fun sortColumn(ascending: Boolean) {
        val column = selectedColumn()
        val lastFilled = cells.keys.mapNotNull { key -> key.dropWhile { it.isLetter() }.toIntOrNull()?.minus(1) }
            .maxOrNull() ?: return
        pushUndo()
        replaceGrid(
            SheetOps.sortByColumn(
                cells.toMap(), formats.toMap(),
                column = column,
                firstRow = selectedRow(),
                lastRow = lastFilled,
                columns = columns,
                ascending = ascending
            )
        )
    }

    fun commitEdit(moveDown: Boolean = false) {
        val newValue = editing.trim()
        if (newValue != cells[selected].orEmpty()) {
            pushUndo()
            if (newValue.isEmpty()) cells.remove(selected) else cells[selected] = newValue
        }
        if (moveDown) {
            val column = FormulaEngine.columnIndex(selected.takeWhile { it.isLetter() })
            val currentRow = selected.dropWhile { it.isLetter() }.toIntOrNull() ?: return
            if (currentRow < rows) {
                // cellKey est indexé à partir de 0 : currentRow désigne donc la ligne suivante.
                selected = FormulaEngine.cellKey(currentRow, column)
            } else {
                editing = ""
            }
        }
    }

    fun toCsv(): String {
        val lastRow = cells.keys.mapNotNull { CellRefRow(it) }.maxOrNull() ?: 0
        val lastColumn = cells.keys.maxOfOrNull { FormulaEngine.columnIndex(it.takeWhile { c -> c.isLetter() }) } ?: 0
        return (0..lastRow).joinToString("\n") { r ->
            (0..lastColumn).joinToString(";") { c -> shown(FormulaEngine.cellKey(r, c)) }
        }
    }

    fun openDocument(id: String, name: String) {
        val payload = storage.load(id) ?: return
        runCatching { WorkbookOps.decode(payload) }
            .onSuccess { (loaded, index) ->
                replaceWorkbook(loaded, index)
                docId = id
                docName = name
                resetHistory()
            }
            .onFailure { notice = "Ouverture impossible" to "« $name » est endommagé." }
    }

    LaunchedEffect(initialDocId) {
        if (initialDocId != null) {
            storage.list(DocType.SHEET).firstOrNull { it.id == initialDocId }
                ?.let { openDocument(it.id, it.name) }
        }
    }

    fun currentWorkbook() = buildWorkbook(docName, allSheets())

    val fileSaver = rememberFileSaver(
        onError = { notice = "Enregistrement impossible" to it },
        onSaved = { notice = "Fichier enregistré" to "« $it » est disponible à l'emplacement choisi." },
        content = {
            val all = allSheets()
            FileFormats.exportSheet(pendingFormat, buildWorkbook(docName, all), workbookDisplay(all))
        }
    )

    val fileOpener = rememberFileOpener(
        onError = { notice = "Import impossible" to it }
    ) { picked ->
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val imported = FileFormats.import(picked.name, picked.bytes)
                    imported to (imported as? Imported.AsSheet)?.let { decodeWorkbook(it.workbook) }
                }
            }
            result
                .onSuccess { (imported, decoded) ->
                    if (decoded != null) {
                        replaceWorkbook(decoded, 0)
                        // Un import devient un nouveau classeur : on ne veut pas
                        // écraser celui qui était ouvert.
                        docId = storage.newId()
                        docName = imported.suggestedName
                        resetHistory()
                    } else {
                        notice = "Ce n'est pas un classeur" to
                            "« ${picked.name} » est un document, une présentation ou un PDF. " +
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
                val all = allSheets()
                val produced = withContext(Dispatchers.IO) {
                    runCatching { FileFormats.exportSheet(format, buildWorkbook(docName, all), workbookDisplay(all)) }
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

    // ------------------------------------------------------------ recherche

    /** Parcourt la feuille ligne par ligne à partir de la cellule choisie. */
    fun findNext(): String? {
        if (findQuery.isEmpty()) return null
        val keys = cells.keys.filter { cells[it]!!.contains(findQuery, ignoreCase = true) }
            .sortedWith(compareBy({ CellRefRow(it) ?: 0 }, { FormulaEngine.columnIndex(it.takeWhile { c -> c.isLetter() }) }))
        if (keys.isEmpty()) return null
        val row = selectedRow()
        val column = selectedColumn()
        return keys.firstOrNull { key ->
            val r = CellRefRow(key) ?: 0
            val c = FormulaEngine.columnIndex(key.takeWhile { it.isLetter() })
            r > row || (r == row && c > column)
        } ?: keys.first()
    }

    fun goTo(key: String) {
        commitEdit()
        selected = key
        val row = CellRefRow(key) ?: 0
        scope.launch { gridState.animateScrollToItem((row - if (freezeRow) 1 else 0).coerceAtLeast(0)) }
        val column = FormulaEngine.columnIndex(key.takeWhile { it.isLetter() })
        scope.launch { hScroll.animateScrollTo(with(density) { (CELL_WIDTH * column).roundToPx() }) }
    }

    // ------------------------------------------------------------ interface

    @Composable
    fun Cell(r: Int, c: Int) {
        val key = FormulaEngine.cellKey(r, c)
        GridCell(
            value = shown(key),
            format = formats[key] ?: CellFormat(),
            isSelected = key == selected,
            onClick = {
                commitEdit()
                selected = key
                selectedChartId = null
            }
        )
    }

    @Composable
    fun GridRow(r: Int) {
        Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
            val rowActive = selected.dropWhile { it.isLetter() }.toIntOrNull() == r + 1
            HeaderCell("${r + 1}", HEADER_WIDTH, rowActive)
            if (freezeColumn) Cell(r, 0)
            Row(modifier = Modifier.horizontalScroll(hScroll)) {
                for (c in (if (freezeColumn) 1 else 0) until columns) Cell(r, c)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable { showRename = true }) {
                        Text(docName, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${sheets.size} feuille${if (sheets.size > 1) "s" else ""} · $columns colonnes · $rows lignes",
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
                        commitEdit()
                        syncActive()
                        storage.save(docId, docName, DocType.SHEET, WorkbookOps.encode(sheets.toList(), active))
                        notice = "Classeur enregistré" to
                            "« $docName » a bien été enregistré dans l'application."
                    }) {
                        Icon(Icons.Filled.Save, contentDescription = "Enregistrer")
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Nouveau classeur") },
                                onClick = {
                                    showMenu = false
                                    replaceWorkbook(listOf(SheetState("Feuille1")), 0)
                                    docId = storage.newId(); docName = "Classeur sans titre"
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
                                    fileOpener.open(importMimeTypes(DocKind.SHEET))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Exporter (Excel, PDF…)") },
                                leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                                onClick = { showMenu = false; showExport = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Partager en texte CSV") },
                                onClick = { showMenu = false; shareText(context, "$docName.csv", toCsv()) }
                            )
                            Divider()
                            DropdownMenuItem(
                                text = { Text("Rechercher et remplacer") },
                                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                onClick = { showMenu = false; commitEdit(); showFind = true }
                            )
                            DropdownMenuItem(
                                text = { Text(if (freezeRow) "✓ Ligne 1 figée" else "Figer la ligne 1") },
                                onClick = { showMenu = false; freezeRow = !freezeRow }
                            )
                            DropdownMenuItem(
                                text = { Text(if (freezeColumn) "✓ Colonne A figée" else "Figer la colonne A") },
                                onClick = { showMenu = false; freezeColumn = !freezeColumn }
                            )
                            Divider()
                            DropdownMenuItem(
                                text = { Text("Insérer une ligne ici") },
                                onClick = { showMenu = false; applyGridEdit(SheetOps::insertRow, selectedRow()); rows += 1 }
                            )
                            DropdownMenuItem(
                                text = { Text("Supprimer cette ligne") },
                                onClick = { showMenu = false; applyGridEdit(SheetOps::deleteRow, selectedRow()) }
                            )
                            DropdownMenuItem(
                                text = { Text("Insérer une colonne ici") },
                                onClick = { showMenu = false; applyGridEdit(SheetOps::insertColumn, selectedColumn()); columns += 1 }
                            )
                            DropdownMenuItem(
                                text = { Text("Supprimer cette colonne") },
                                onClick = { showMenu = false; applyGridEdit(SheetOps::deleteColumn, selectedColumn()) }
                            )
                            Divider()
                            DropdownMenuItem(
                                text = { Text("Trier à partir d'ici (croissant)") },
                                onClick = { showMenu = false; sortColumn(ascending = true) }
                            )
                            DropdownMenuItem(
                                text = { Text("Trier à partir d'ici (décroissant)") },
                                onClick = { showMenu = false; sortColumn(ascending = false) }
                            )
                            Divider()
                            DropdownMenuItem(text = { Text("Ajouter 10 lignes") }, onClick = { showMenu = false; rows += 10 })
                            DropdownMenuItem(text = { Text("Ajouter 3 colonnes") }, onClick = { showMenu = false; columns += 3 })
                            Divider()
                            DropdownMenuItem(text = { Text("Tout effacer") }, onClick = { showMenu = false; showClearAll = true })
                        }
                    }
                }
            )
        },
        bottomBar = {
            Column {
                SheetTabs(
                    names = sheets.map { it.name },
                    active = active,
                    onSelect = { commitEdit(); switchTo(it) },
                    onMenu = { sheetMenuFor = it },
                    onAdd = {
                        commitEdit()
                        pushUndo()
                        syncActive()
                        sheets.add(SheetState(WorkbookOps.uniqueName(sheets)))
                        showSheet(sheets.lastIndex)
                    }
                )
                val columnIndex = selectedColumn()
                // Les statistiques ne parcourent que la partie remplie de la colonne.
                val lastRow = cells.keys.mapNotNull { key ->
                    if (FormulaEngine.columnIndex(key.takeWhile { it.isLetter() }) == columnIndex) CellRefRow(key) else null
                }.maxOrNull() ?: -1
                val numbers = (0..lastRow).mapNotNull { r ->
                    val shownValue = shown(FormulaEngine.cellKey(r, columnIndex))
                    if (shownValue.isBlank() || shownValue.startsWith("#")) null
                    else shownValue.replace(" ", "").replace(',', '.').toDoubleOrNull()
                }
                val sum = numbers.sum()
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Text(
                            "Colonne ${FormulaEngine.columnLabel(columnIndex)} :",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text("Somme ${FormulaEngine.formatNumber(sum)}", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "Moyenne ${FormulaEngine.formatNumber(if (numbers.isEmpty()) 0.0 else sum / numbers.size)}",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text("Nombres ${numbers.size}", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // --- Barre de formule ---
            Surface(tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            selected,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    OutlinedTextField(
                        value = editing,
                        onValueChange = { editing = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("Valeur ou =SOMME(A1:A10)") },
                        textStyle = MaterialTheme.typography.bodyMedium,
                        // Sans action explicite, Entrée ne validait rien : la valeur
                        // n'arrivait dans la cellule qu'au clic sur une autre cellule.
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { commitEdit(moveDown = true) })
                    )
                    IconButton(onClick = { commitEdit() }) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Valider",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // --- Barre d'outils ---
            Surface(tonalElevation = 1.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    key(historyVersion) {
                        ToolToggle(Icons.Filled.Undo, "Annuler", enabled = undoStack.isNotEmpty()) {
                            commitEdit()
                            undoStack.removeLastOrNull()?.let { previous ->
                                redoStack.add(snapshot())
                                restore(previous)
                            }
                        }
                        ToolToggle(Icons.Filled.Redo, "Rétablir", enabled = redoStack.isNotEmpty()) {
                            redoStack.removeLastOrNull()?.let { next ->
                                undoStack.add(snapshot())
                                restore(next)
                            }
                        }
                    }
                    ToolToggle(Icons.Filled.Calculate, "Calculatrice") {
                        commitEdit()
                        showCalculator = true
                    }
                    ToolToggle(Icons.Filled.Functions, "Insérer une fonction") { showFunctions = true }
                    ToolToggle(Icons.Filled.PieChart, "Créer un graphique") {
                        commitEdit()
                        showChart = true
                    }
                    ToolToggle(Icons.Filled.Search, "Rechercher", showFind) {
                        commitEdit()
                        showFind = !showFind
                    }
                    ToolToggle(Icons.Filled.FormatBold, "Gras", format.bold) {
                        updateFormat { it.copy(bold = !it.bold) }
                    }
                    ToolToggle(Icons.Filled.FormatItalic, "Italique", format.italic) {
                        updateFormat { it.copy(italic = !it.italic) }
                    }
                    ToolToggle(Icons.Filled.FormatColorText, "Couleur du texte") { showTextColor = true }
                    ToolToggle(Icons.Filled.FormatColorFill, "Couleur de fond") { showFillColor = true }
                    ToolToggle(Icons.Filled.FormatAlignLeft, "Aligner à gauche", format.align == 1) {
                        updateFormat { it.copy(align = 1) }
                    }
                    ToolToggle(Icons.Filled.FormatAlignCenter, "Centrer", format.align == 2) {
                        updateFormat { it.copy(align = 2) }
                    }
                    ToolToggle(Icons.Filled.FormatAlignRight, "Aligner à droite", format.align == 3) {
                        updateFormat { it.copy(align = 3) }
                    }
                    ToolToggle(Icons.Filled.DeleteSweep, "Effacer la cellule") {
                        pushUndo()
                        cells.remove(selected)
                        formats.remove(selected)
                        editing = ""
                    }
                    ToolToggle(Icons.Filled.Add, "Ajouter 10 lignes") { rows += 10 }
                }
            }

            if (showFind) {
                SheetFindBar(
                    query = findQuery,
                    replacement = findReplacement,
                    occurrences = cells.values.count { it.contains(findQuery, ignoreCase = true) && findQuery.isNotEmpty() },
                    onQueryChange = { findQuery = it },
                    onReplacementChange = { findReplacement = it },
                    onFind = {
                        val next = findNext()
                        if (next == null) notice = "Recherche" to "« $findQuery » est introuvable dans cette feuille."
                        else goTo(next)
                    },
                    onReplace = {
                        val current = cells[selected]
                        if (findQuery.isNotEmpty() && current != null && current.contains(findQuery, ignoreCase = true)) {
                            pushUndo()
                            cells[selected] = current.replace(findQuery, findReplacement, ignoreCase = true)
                            editing = cells[selected].orEmpty()
                        }
                        findNext()?.let { goTo(it) }
                    },
                    onReplaceAll = {
                        val hits = cells.filterValues { findQuery.isNotEmpty() && it.contains(findQuery, ignoreCase = true) }
                        if (hits.isEmpty()) {
                            notice = "Remplacement" to "« $findQuery » est introuvable dans cette feuille."
                        } else {
                            pushUndo()
                            hits.forEach { (key, value) -> cells[key] = value.replace(findQuery, findReplacement, ignoreCase = true) }
                            editing = cells[selected].orEmpty()
                            notice = "Remplacement" to
                                if (hits.size == 1) "1 cellule modifiée." else "${hits.size} cellules modifiées."
                        }
                    },
                    onClose = { showFind = false }
                )
            }

            // --- En-tête des colonnes ---
            Row(modifier = Modifier.fillMaxWidth()) {
                HeaderCell("", HEADER_WIDTH)
                if (freezeColumn) HeaderCell("A", CELL_WIDTH, selectedColumn() == 0)
                Row(modifier = Modifier.horizontalScroll(hScroll)) {
                    for (c in (if (freezeColumn) 1 else 0) until columns) {
                        HeaderCell(FormulaEngine.columnLabel(c), CELL_WIDTH, selectedColumn() == c)
                    }
                }
            }

            // --- Grille, avec les graphiques posés par-dessus ---
            Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
                LazyColumn(state = gridState, modifier = Modifier.fillMaxSize()) {
                    // Une ligne figée reste en haut pendant que les autres défilent.
                    if (freezeRow) stickyHeader(key = "ligne-figee") { GridRow(0) }
                    items(if (freezeRow) (rows - 1).coerceAtLeast(0) else rows) { index ->
                        GridRow(if (freezeRow) index + 1 else index)
                    }
                }

                // Les graphiques suivent le défilement : leur position est ancrée
                // à la feuille, pas à l'écran.
                val scrollY = gridState.firstVisibleItemIndex * cellHeightPx +
                    gridState.firstVisibleItemScrollOffset
                charts.forEach { chart ->
                    FloatingChart(
                        chart = chart,
                        entries = buildChartEntries(chart.labelsRange, chart.valuesRange, engine),
                        selected = chart.id == selectedChartId,
                        screenX = with(density) { chart.x.dp.toPx() } - hScroll.value,
                        screenY = with(density) { chart.y.dp.toPx() } - scrollY,
                        onSelect = { selectedChartId = chart.id },
                        onMove = { dx, dy ->
                            updateChart(chart.id) {
                                it.copy(
                                    x = (it.x + dx).coerceAtLeast(0f),
                                    y = (it.y + dy).coerceAtLeast(0f)
                                )
                            }
                        },
                        onResize = { dw, dh ->
                            updateChart(chart.id) {
                                it.copy(
                                    width = (it.width + dw).coerceAtLeast(MIN_CHART_WIDTH),
                                    height = (it.height + dh).coerceAtLeast(MIN_CHART_HEIGHT)
                                )
                            }
                        },
                        onEdit = { editingChart = chart; showChart = true },
                        onDelete = {
                            pushUndo()
                            charts.removeAll { it.id == chart.id }
                            selectedChartId = null
                        }
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------ feuilles

    if (sheetMenuFor >= 0) {
        val index = sheetMenuFor
        val actions = buildList {
            add("Renommer…")
            add("Dupliquer")
            if (index > 0) add("Déplacer vers la gauche")
            if (index < sheets.lastIndex) add("Déplacer vers la droite")
            if (sheets.size > 1) add("Supprimer…")
        }
        ListPickerDialog(
            title = sheets.getOrNull(index)?.name ?: "Feuille",
            items = actions,
            label = { it },
            onPick = { action ->
                sheetMenuFor = -1
                commitEdit()
                when (action) {
                    "Renommer…" -> renameSheetIndex = index
                    "Dupliquer" -> {
                        pushUndo()
                        syncActive()
                        val source = sheets[index]
                        val copyName = WorkbookOps.uniqueName(sheets, source.name.take(24) + " copie ").trim()
                        sheets.add(index + 1, source.copy(name = copyName))
                        showSheet(index + 1)
                    }
                    "Déplacer vers la gauche", "Déplacer vers la droite" -> {
                        pushUndo()
                        syncActive()
                        val target = if (action.endsWith("gauche")) index - 1 else index + 1
                        val moved = sheets.removeAt(index)
                        sheets.add(target, moved)
                        showSheet(target)
                    }
                    "Supprimer…" -> deleteSheetIndex = index
                }
            },
            onDismiss = { sheetMenuFor = -1 }
        )
    }

    if (renameSheetIndex >= 0) {
        val index = renameSheetIndex
        TextInputDialog(
            title = "Renommer la feuille",
            initialValue = sheets.getOrNull(index)?.name.orEmpty(),
            onConfirm = { name ->
                syncActive()
                val problem = WorkbookOps.validName(sheets, index, name)
                if (problem != null) {
                    notice = "Nom refusé" to problem
                } else {
                    pushUndo()
                    // Les formules qui visaient l'ancien nom suivent le renommage.
                    val renamed = WorkbookOps.rename(sheets.toList(), index, name.trim())
                    val current = active
                    sheets.clear(); sheets.addAll(renamed)
                    showSheet(current)
                }
                renameSheetIndex = -1
            },
            onDismiss = { renameSheetIndex = -1 }
        )
    }

    if (deleteSheetIndex >= 0) {
        val index = deleteSheetIndex
        ConfirmDialog(
            title = "Supprimer « ${sheets.getOrNull(index)?.name.orEmpty()} » ?",
            message = "La feuille et son contenu seront supprimés. Le bouton Annuler permet de revenir en arrière.",
            onConfirm = {
                if (sheets.size > 1 && index in sheets.indices) {
                    pushUndo()
                    syncActive()
                    sheets.removeAt(index)
                    showSheet(index.coerceAtMost(sheets.lastIndex))
                }
                deleteSheetIndex = -1
            },
            onDismiss = { deleteSheetIndex = -1 }
        )
    }

    // ------------------------------------------------------------ dialogues

    if (showCalculator) {
        CalculatorDialog(
            cells = engine,
            selectedCell = selected,
            onInsert = { value ->
                pushUndo()
                editing = value
                cells[selected] = value
                showCalculator = false
            },
            onDismiss = { showCalculator = false }
        )
    }

    if (showChart) {
        val lastUsedRow = cells.keys.mapNotNull { CellRefRow(it) }.maxOrNull() ?: 5
        ChartDialog(
            cells = engine,
            initial = editingChart,
            defaultLabelsRange = "A1:A${lastUsedRow + 1}",
            defaultValuesRange = "B1:B${lastUsedRow + 1}",
            onConfirm = { type, title, labelsRange, valuesRange ->
                pushUndo()
                val existing = editingChart
                if (existing == null) {
                    val chart = EmbeddedChart(
                        id = storage.newId(),
                        type = type,
                        title = title,
                        labelsRange = labelsRange,
                        valuesRange = valuesRange,
                        // Décalé à chaque insertion pour ne pas empiler les graphiques.
                        x = 24f + charts.size * 16f,
                        y = 24f + charts.size * 16f
                    )
                    charts.add(chart)
                    selectedChartId = chart.id
                } else {
                    updateChart(existing.id) {
                        it.copy(
                            type = type,
                            title = title,
                            labelsRange = labelsRange,
                            valuesRange = valuesRange
                        )
                    }
                }
                editingChart = null
                showChart = false
            },
            onDismiss = { editingChart = null; showChart = false }
        )
    }

    if (showFunctions) {
        FunctionCatalogDialog(
            onPick = { entry ->
                editing = sampleFormula(entry)
                showFunctions = false
            },
            onDismiss = { showFunctions = false }
        )
    }

    if (showTextColor) {
        ColorPickerDialog(
            title = "Couleur du texte",
            colors = EditorColors,
            onPick = { color ->
                if (color != null) updateFormat { it.copy(color = colorToLong(color)) }
                showTextColor = false
            },
            onDismiss = { showTextColor = false }
        )
    }

    if (showFillColor) {
        ColorPickerDialog(
            title = "Couleur de fond",
            colors = HighlightColors,
            allowNone = true,
            onPick = { color ->
                updateFormat { it.copy(background = if (color == null) 0L else colorToLong(color)) }
                showFillColor = false
            },
            onDismiss = { showFillColor = false }
        )
    }

    if (showRename) {
        TextInputDialog(
            title = "Renommer le classeur",
            initialValue = docName,
            onConfirm = { docName = it; showRename = false },
            onDismiss = { showRename = false }
        )
    }

    if (showOpen) {
        val documents = storage.list(DocType.SHEET)
        ListPickerDialog(
            title = if (documents.isEmpty()) "Aucun classeur enregistré" else "Ouvrir un classeur",
            items = documents,
            label = { it.name },
            onPick = { meta ->
                openDocument(meta.id, meta.name)
                showOpen = false
            },
            onDismiss = { showOpen = false }
        )
    }

    if (showClearAll) {
        ConfirmDialog(
            title = "Tout effacer ?",
            message = "Toutes les cellules et leur mise en forme de cette feuille seront supprimées. " +
                "Le bouton Annuler permet de revenir en arrière.",
            confirmLabel = "Effacer",
            onConfirm = {
                pushUndo()
                cells.clear(); formats.clear(); editing = ""
                showClearAll = false
            },
            onDismiss = { showClearAll = false }
        )
    }

    if (showExport) {
        ExportFormatDialog(
            kind = DocKind.SHEET,
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

/** Numéro de ligne (à partir de 0) d'une référence `B12`, ou `null`. */
private fun CellRefRow(key: String): Int? = key.dropWhile { it.isLetter() }.toIntOrNull()?.minus(1)

/** Les onglets des feuilles, au bas de l'écran, comme dans Excel. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SheetTabs(
    names: List<String>,
    active: Int,
    onSelect: (Int) -> Unit,
    onMenu: (Int) -> Unit,
    onAdd: () -> Unit
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                names.forEachIndexed { index, name ->
                    val selected = index == active
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 2.dp, vertical = 4.dp)
                            .background(
                                if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                                MaterialTheme.shapes.small
                            )
                            .border(
                                if (selected) 1.dp else 0.dp,
                                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                MaterialTheme.shapes.small
                            )
                            .combinedClickable(
                                onClick = { if (selected) onMenu(index) else onSelect(index) },
                                onLongClick = { onMenu(index) }
                            )
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            name,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
            IconButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "Nouvelle feuille")
            }
        }
    }
}

@Composable
private fun SheetFindBar(
    query: String,
    replacement: String,
    occurrences: Int,
    onQueryChange: (String) -> Unit,
    onReplacementChange: (String) -> Unit,
    onFind: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Rechercher") },
                    supportingText = { Text(if (query.isEmpty()) " " else "$occurrences cellule(s)") }
                )
                TextButton(onClick = onFind) { Text("Suivant") }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Fermer la recherche") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = replacement,
                    onValueChange = onReplacementChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Remplacer par") }
                )
                TextButton(onClick = onReplace) { Text("Remplacer") }
                TextButton(onClick = onReplaceAll) { Text("Tout") }
            }
        }
    }
}

@Composable
private fun HeaderCell(label: String, width: androidx.compose.ui.unit.Dp, active: Boolean = false) {
    Box(
        modifier = Modifier
            .width(width)
            .height(CELL_HEIGHT)
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun GridCell(
    value: String,
    format: CellFormat,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val isNumber = value.replace(" ", "").replace(',', '.').toDoubleOrNull() != null
    val alignment = when (format.align) {
        1 -> TextAlign.Start
        2 -> TextAlign.Center
        3 -> TextAlign.End
        else -> if (isNumber) TextAlign.End else TextAlign.Start
    }
    Box(
        modifier = Modifier
            .width(CELL_WIDTH)
            .height(CELL_HEIGHT)
            .background(
                when {
                    isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    format.background != 0L -> Color(format.background)
                    else -> MaterialTheme.colorScheme.surface
                }
            )
            .border(
                if (isSelected) 2.dp else 0.5.dp,
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            value,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp,
            textAlign = alignment,
            color = if (value.startsWith("#")) MaterialTheme.colorScheme.error else Color(format.color),
            fontWeight = if (format.bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (format.italic) FontStyle.Italic else FontStyle.Normal
        )
    }
}

internal class DecodedSheet(
    val cells: Map<String, String>,
    val formats: Map<String, CellFormat>,
    val columns: Int,
    val rows: Int,
    val charts: List<EmbeddedChart>
)

internal fun encodeSheet(
    cells: Map<String, String>,
    formats: Map<String, CellFormat>,
    columns: Int,
    rows: Int,
    charts: List<EmbeddedChart>
): String {
    val cellsJson = JSONObject()
    cells.forEach { (key, value) -> cellsJson.put(key, value) }
    val formatsJson = JSONObject()
    formats.forEach { (key, f) ->
        formatsJson.put(key, JSONObject().apply {
            put("b", f.bold); put("i", f.italic)
            put("c", f.color); put("bg", f.background); put("a", f.align)
        })
    }
    return JSONObject().apply {
        put("columns", columns)
        put("rows", rows)
        put("cells", cellsJson)
        put("formats", formatsJson)
        put("charts", chartsToJson(charts))
    }.toString()
}

internal fun decodeSheet(payload: String): DecodedSheet {
    val root = JSONObject(payload)
    val cells = HashMap<String, String>()
    val cellsJson = root.optJSONObject("cells") ?: JSONObject()
    cellsJson.keys().forEach { key -> cells[key] = cellsJson.getString(key) }

    val formats = HashMap<String, CellFormat>()
    val formatsJson = root.optJSONObject("formats") ?: JSONObject()
    formatsJson.keys().forEach { key ->
        val o = formatsJson.getJSONObject(key)
        formats[key] = CellFormat(
            bold = o.optBoolean("b"),
            italic = o.optBoolean("i"),
            color = o.optLong("c", 0xFF1A1A1AL),
            background = o.optLong("bg", 0L),
            align = o.optInt("a", 0)
        )
    }
    return DecodedSheet(
        cells = cells,
        formats = formats,
        columns = root.optInt("columns", DEFAULT_COLUMNS),
        rows = root.optInt("rows", DEFAULT_ROWS),
        charts = chartsFromJson(root.optJSONArray("charts"))
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
