package com.docssuite.spreadsheet

import androidx.compose.foundation.background
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
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.docssuite.core.shareText
import org.json.JSONObject

private const val DEFAULT_COLUMNS = 12
private const val DEFAULT_ROWS = 40
private val CELL_WIDTH = 96.dp
private val CELL_HEIGHT = 44.dp
private val HEADER_WIDTH = 48.dp

private data class CellFormat(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val color: Long = 0xFF1A1A1AL,
    val background: Long = 0L,
    val align: Int = 0 // 0 = auto, 1 = gauche, 2 = centre, 3 = droite
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpreadsheetScreen(onBack: () -> Unit, initialDocId: String? = null) {
    val context = LocalContext.current
    val storage = remember { DocumentStorage(context) }

    val cells = remember { mutableStateMapOf<String, String>() }
    val formats = remember { mutableStateMapOf<String, CellFormat>() }
    var columns by remember { mutableStateOf(DEFAULT_COLUMNS) }
    var rows by remember { mutableStateOf(DEFAULT_ROWS) }
    var selected by remember { mutableStateOf("A1") }
    var editing by remember { mutableStateOf("") }

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

    val charts = remember { mutableStateListOf<EmbeddedChart>() }
    var selectedChartId by remember { mutableStateOf<String?>(null) }
    var editingChart by remember { mutableStateOf<EmbeddedChart?>(null) }
    val gridState = rememberLazyListState()
    val density = LocalDensity.current
    val cellHeightPx = with(density) { CELL_HEIGHT.toPx() }

    fun updateChart(id: String, transform: (EmbeddedChart) -> EmbeddedChart) {
        val index = charts.indexOfFirst { it.id == id }
        if (index >= 0) charts[index] = transform(charts[index])
    }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selected) { editing = cells[selected] ?: "" }

    val hScroll = rememberScrollState()
    val format = formats[selected] ?: CellFormat()

    fun updateFormat(transform: (CellFormat) -> CellFormat) {
        formats[selected] = transform(formats[selected] ?: CellFormat())
    }

    fun commitEdit(moveDown: Boolean = false) {
        if (editing.isBlank()) cells.remove(selected) else cells[selected] = editing.trim()
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

    fun toCsv(): String = (0 until rows).joinToString("\n") { r ->
        (0 until columns).joinToString(";") { c ->
            FormulaEngine.displayValue(FormulaEngine.cellKey(r, c), cells)
        }
    }

    fun openDocument(id: String, name: String) {
        val payload = storage.load(id) ?: return
        val decoded = decodeSheet(payload)
        cells.clear(); cells.putAll(decoded.cells)
        formats.clear(); formats.putAll(decoded.formats)
        charts.clear(); charts.addAll(decoded.charts)
        selectedChartId = null
        columns = decoded.columns
        rows = decoded.rows
        docId = id
        docName = name
        selected = "A1"
    }

    LaunchedEffect(initialDocId) {
        if (initialDocId != null) {
            storage.list(DocType.SHEET).firstOrNull { it.id == initialDocId }
                ?.let { openDocument(it.id, it.name) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable { showRename = true }) {
                        Text(docName, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "$columns colonnes · $rows lignes",
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
                        storage.save(
                            docId,
                            docName,
                            DocType.SHEET,
                            encodeSheet(cells, formats, columns, rows, charts)
                        )
                        savedMessage = "Classeur enregistré"
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
                                    cells.clear(); formats.clear(); charts.clear()
                                    selectedChartId = null
                                    columns = DEFAULT_COLUMNS; rows = DEFAULT_ROWS
                                    docId = storage.newId(); docName = "Classeur sans titre"
                                    selected = "A1"
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
                                text = { Text("Exporter en CSV") },
                                onClick = { showMenu = false; shareText(context, "$docName.csv", toCsv()) }
                            )
                            Divider()
                            DropdownMenuItem(
                                text = { Text("Ajouter 10 lignes") },
                                onClick = { showMenu = false; rows += 10 }
                            )
                            DropdownMenuItem(
                                text = { Text("Ajouter 3 colonnes") },
                                onClick = { showMenu = false; columns += 3 }
                            )
                            Divider()
                            DropdownMenuItem(
                                text = { Text("Tout effacer") },
                                onClick = { showMenu = false; showClearAll = true }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            val columnIndex = FormulaEngine.columnIndex(selected.takeWhile { it.isLetter() })
            val columnRefs = (0 until rows).map { FormulaEngine.cellKey(it, columnIndex) }
            val (sum, avg, count) = FormulaEngine.quickStats(columnRefs, cells)
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
                    Text("Moyenne ${FormulaEngine.formatNumber(avg)}", style = MaterialTheme.typography.labelMedium)
                    Text("Nombres $count", style = MaterialTheme.typography.labelMedium)
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
                    ToolToggle(Icons.Filled.Calculate, "Calculatrice") {
                        commitEdit()
                        showCalculator = true
                    }
                    ToolToggle(Icons.Filled.Functions, "Insérer une fonction") { showFunctions = true }
                    ToolToggle(Icons.Filled.PieChart, "Créer un graphique") {
                        commitEdit()
                        showChart = true
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
                        cells.remove(selected)
                        formats.remove(selected)
                        editing = ""
                    }
                    ToolToggle(Icons.Filled.Add, "Ajouter 10 lignes") { rows += 10 }
                }
            }

            // --- En-tête des colonnes ---
            Row(modifier = Modifier.fillMaxWidth()) {
                HeaderCell("", HEADER_WIDTH)
                Row(modifier = Modifier.horizontalScroll(hScroll)) {
                    for (c in 0 until columns) {
                        val isActive = FormulaEngine.columnIndex(selected.takeWhile { it.isLetter() }) == c
                        HeaderCell(FormulaEngine.columnLabel(c), CELL_WIDTH, isActive)
                    }
                }
            }

            // --- Grille, avec les graphiques posés par-dessus ---
            Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
                LazyColumn(state = gridState, modifier = Modifier.fillMaxSize()) {
                    items(rows) { r ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            val rowActive = selected.dropWhile { it.isLetter() }.toIntOrNull() == r + 1
                            HeaderCell("${r + 1}", HEADER_WIDTH, rowActive)
                            Row(modifier = Modifier.horizontalScroll(hScroll)) {
                                for (c in 0 until columns) {
                                    val key = FormulaEngine.cellKey(r, c)
                                    GridCell(
                                        value = FormulaEngine.displayValue(key, cells),
                                        format = formats[key] ?: CellFormat(),
                                        isSelected = key == selected,
                                        onClick = {
                                            commitEdit()
                                            selected = key
                                            selectedChartId = null
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Les graphiques suivent le défilement : leur position est ancrée
                // à la feuille, pas à l'écran.
                val scrollY = gridState.firstVisibleItemIndex * cellHeightPx +
                    gridState.firstVisibleItemScrollOffset
                charts.forEach { chart ->
                    FloatingChart(
                        chart = chart,
                        entries = buildChartEntries(chart.labelsRange, chart.valuesRange, cells),
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
                            charts.removeAll { it.id == chart.id }
                            selectedChartId = null
                        }
                    )
                }
            }
        }
    }

    if (showCalculator) {
        CalculatorDialog(
            cells = cells,
            selectedCell = selected,
            onInsert = { value ->
                editing = value
                cells[selected] = value
                showCalculator = false
            },
            onDismiss = { showCalculator = false }
        )
    }

    if (showChart) {
        val lastUsedRow = (0 until rows).lastOrNull { r ->
            (0 until columns).any { c -> cells[FormulaEngine.cellKey(r, c)]?.isNotBlank() == true }
        } ?: 5
        ChartDialog(
            cells = cells,
            initial = editingChart,
            defaultLabelsRange = "A1:A${lastUsedRow + 1}",
            defaultValuesRange = "B1:B${lastUsedRow + 1}",
            onConfirm = { type, title, labelsRange, valuesRange ->
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
        ListPickerDialog(
            title = "Insérer une fonction",
            items = FormulaEngine.FUNCTIONS,
            label = { it },
            onPick = { fn ->
                editing = if (fn == "SI") "=SI(A1>10;1;0)" else "=$fn(A1:A10)"
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
            message = "Toutes les cellules et leur mise en forme seront supprimées.",
            confirmLabel = "Effacer",
            onConfirm = {
                cells.clear(); formats.clear(); editing = ""
                showClearAll = false
            },
            onDismiss = { showClearAll = false }
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

private class DecodedSheet(
    val cells: Map<String, String>,
    val formats: Map<String, CellFormat>,
    val columns: Int,
    val rows: Int,
    val charts: List<EmbeddedChart>
)

private fun encodeSheet(
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

private fun decodeSheet(payload: String): DecodedSheet {
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
