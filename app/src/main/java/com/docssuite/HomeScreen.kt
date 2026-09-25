package com.docssuite

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docssuite.core.ConfirmDialog
import com.docssuite.core.DocMeta
import com.docssuite.core.DocType
import com.docssuite.core.DocumentLibrary
import com.docssuite.core.DocumentSearch
import com.docssuite.core.DocumentStorage
import com.docssuite.core.readFile
import com.docssuite.core.rememberFileOpener
import com.docssuite.fileformats.FileFormats
import com.docssuite.fileformats.Imported
import com.docssuite.pdf.PdfPayload
import com.docssuite.presentation.saveImportedDeck
import com.docssuite.spreadsheet.saveImportedWorkbook
import com.docssuite.texteditor.saveImportedTextDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Les trois onglets de l'accueil. */
enum class HomeTab(val label: String, val selected: ImageVector, val idle: ImageVector) {
    HOME("Accueil", Icons.Filled.Home, Icons.Outlined.Home),
    TOOLS("Outils", Icons.Filled.Apps, Icons.Outlined.Apps),
    DOCUMENTS("Documents", Icons.Filled.Folder, Icons.Outlined.Folder)
}

/**
 * L'accueil, en trois onglets :
 * - **Accueil** : chercher, créer, reprendre — l'essentiel sans défiler ;
 * - **Outils** : les vingt outils rangés en cinq familles ;
 * - **Documents** : tout ce qui est enregistré, filtrable par sorte.
 *
 * [onNavigate] reçoit la route d'un outil ; les éditeurs et le lecteur PDF
 * s'ouvrent par leurs propres rappels, avec ou sans document.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    onOpenTextEditor: (String?) -> Unit,
    onOpenSpreadsheet: (String?) -> Unit,
    onOpenPresentation: (String?) -> Unit,
    onOpenPdf: (PdfPayload?) -> Unit,
    incomingFile: Uri? = null,
    onIncomingHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    val storage = remember { DocumentStorage(context) }
    val usage = remember { ToolUsage(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val focus = LocalFocusManager.current

    var tab by rememberSaveable { mutableStateOf(HomeTab.HOME) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<ToolCategory?>(null) }
    var docFilter by rememberSaveable { mutableStateOf<DocType?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var usageKey by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<DocMeta?>(null) }

    val documents = remember(refreshKey) { storage.list() }
    val shortcuts = remember(usageKey) { Tools.shortcuts(usage.all()) }
    val personal = remember(usageKey) { usage.any }

    fun report(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    // Un fichier importé est d'abord converti et enregistré, puis l'éditeur
    // correspondant s'ouvre dessus — comme n'importe quel document récent.
    // La conversion et l'écriture se font hors du fil principal : un gros
    // document figeait l'écran le temps de son enregistrement.
    fun save(imported: Imported): String? = when (imported) {
        is Imported.AsText -> saveImportedTextDocument(storage, imported.document, imported.suggestedName)
        is Imported.AsSheet -> saveImportedWorkbook(storage, imported.workbook, imported.suggestedName)
        is Imported.AsDeck -> saveImportedDeck(storage, imported.deck, imported.suggestedName)
        is Imported.AsPdf -> null
    }

    fun route(imported: Imported, savedId: String?, fileName: String) {
        when (imported) {
            is Imported.AsText -> onOpenTextEditor(savedId)
            is Imported.AsSheet -> onOpenSpreadsheet(savedId)
            is Imported.AsDeck -> onOpenPresentation(savedId)
            is Imported.AsPdf -> onOpenPdf(PdfPayload(fileName, imported.bytes))
        }
    }

    val opener = rememberFileOpener(onError = ::report) { picked ->
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val imported = FileFormats.import(picked.name, picked.bytes)
                    imported to save(imported)
                }
            }
                .onSuccess { (imported, id) -> route(imported, id, picked.name) }
                .onFailure { report(importError(it)) }
        }
    }

    // Fichier ouvert ou partagé depuis une autre application. On ne signale
    // qu'il est traité qu'à la toute fin : le faire plus tôt annulerait la
    // lecture en cours, puisque c'est ce qui relance l'effet.
    LaunchedEffect(incomingFile) {
        val uri = incomingFile ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                val picked = readFile(context, uri)
                val imported = FileFormats.import(picked.name, picked.bytes)
                Triple(imported, save(imported), picked.name)
            }
        }
            .onSuccess { (imported, id, name) -> route(imported, id, name) }
            .onFailure { report(importError(it)) }
        onIncomingHandled()
    }

    fun launch(tool: Tool) {
        focus.clearFocus()
        usage.record(tool.id)
        usageKey++
        when (tool.id) {
            Tools.NEW_DOCUMENT -> onOpenTextEditor(null)
            Tools.NEW_SHEET -> onOpenSpreadsheet(null)
            Tools.NEW_DECK -> onOpenPresentation(null)
            Tools.OPEN_PDF -> onOpenPdf(null)
            Tools.OPEN_FILE -> opener.open()
            else -> tool.route?.let(onNavigate)
        }
    }

    fun open(meta: DocMeta) {
        focus.clearFocus()
        when (meta.type) {
            DocType.TEXT -> onOpenTextEditor(meta.id)
            DocType.SHEET -> onOpenSpreadsheet(meta.id)
            DocType.DECK -> onOpenPresentation(meta.id)
        }
    }

    // Le retour efface d'abord la recherche, puis ramène à l'accueil, et
    // seulement ensuite quitte l'app.
    BackHandler(enabled = query.isNotEmpty() || tab != HomeTab.HOME) {
        if (query.isNotEmpty()) query = "" else tab = HomeTab.HOME
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                HomeTab.values().forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = {
                            focus.clearFocus()
                            tab = item
                        },
                        icon = { Icon(if (tab == item) item.selected else item.idle, contentDescription = null) },
                        label = { Text(item.label) },
                        modifier = Modifier.semantics { contentDescription = "Onglet ${item.label}" }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                HomeTab.HOME -> HomeTabContent(
                    query = query,
                    onQuery = { query = it },
                    documents = documents,
                    shortcuts = shortcuts,
                    personal = personal,
                    onTool = ::launch,
                    onDocument = ::open,
                    onAllDocuments = { tab = HomeTab.DOCUMENTS },
                    onCategory = {
                        category = it
                        tab = HomeTab.TOOLS
                    },
                    onSearchContent = { onNavigate("library") }
                )
                HomeTab.TOOLS -> ToolsTabContent(
                    category = category,
                    onCategory = { category = it },
                    onTool = ::launch
                )
                HomeTab.DOCUMENTS -> DocumentsTabContent(
                    documents = documents,
                    filter = docFilter,
                    onFilter = { docFilter = it },
                    onOpen = ::open,
                    onDelete = { deleting = it },
                    onOpenFile = { launch(Tools.byId(Tools.OPEN_FILE)!!) },
                    onNewDocument = { launch(Tools.byId(Tools.NEW_DOCUMENT)!!) },
                    onLibrary = { launch(Tools.byId("library")!!) }
                )
            }
        }
    }

    deleting?.let { meta ->
        ConfirmDialog(
            title = "Supprimer ce document ?",
            message = "« ${meta.name} » sera supprimé de l'appareil. On ne peut pas revenir en arrière.",
            onConfirm = {
                storage.delete(meta.id)
                DocumentLibrary(context).forget(meta.id)
                deleting = null
                refreshKey++
                report("« ${meta.name} » supprimé")
            },
            onDismiss = { deleting = null }
        )
    }
}

// ====================================================================== accueil

@Composable
private fun HomeTabContent(
    query: String,
    onQuery: (String) -> Unit,
    documents: List<DocMeta>,
    shortcuts: List<Tool>,
    personal: Boolean,
    onTool: (Tool) -> Unit,
    onDocument: (DocMeta) -> Unit,
    onAllDocuments: () -> Unit,
    onCategory: (ToolCategory) -> Unit,
    onSearchContent: () -> Unit
) {
    LazyColumn(
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item { Greeting() }
        item { SearchField(query, onQuery) }

        if (query.isNotBlank()) {
            searchResults(query, documents, onTool, onDocument, onSearchContent)
            return@LazyColumn
        }

        item { SectionTitle("Nouveau") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tools.quickCreate.mapNotNull(Tools::byId).forEach { tool ->
                    CreateTile(tool, Modifier.weight(1f)) { onTool(tool) }
                }
            }
        }

        item {
            SectionTitle(
                if (personal) "Tes outils du moment" else "Pour commencer",
                hint = if (personal) "selon ce que tu utilises" else null
            )
        }
        toolGrid(shortcuts, onTool, compact = true)

        item {
            SectionTitle(
                "Récents",
                action = if (documents.size > RECENT_COUNT) "Tout voir (${documents.size})" else null,
                onAction = onAllDocuments
            )
        }
        if (documents.isEmpty()) {
            item {
                Hint(
                    Icons.Filled.Description,
                    "Tes documents apparaîtront ici",
                    "Crée-en un ci-dessus, ou ouvre un fichier Word, Excel, PowerPoint ou PDF."
                )
            }
        } else {
            items(documents.take(RECENT_COUNT), key = { "recent-" + it.id }) { meta ->
                DocumentRow(meta, onOpen = { onDocument(meta) })
            }
        }

        item { SectionTitle("Tous les outils", hint = "${Tools.all.size} outils en ${ToolCategory.values().size} familles") }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolCategory.values().forEach { family ->
                    CategoryPill(family, count = Tools.of(family).size) { onCategory(family) }
                }
            }
        }
    }
}

private const val RECENT_COUNT = 4

private fun LazyListScope.searchResults(
    query: String,
    documents: List<DocMeta>,
    onTool: (Tool) -> Unit,
    onDocument: (DocMeta) -> Unit,
    onSearchContent: () -> Unit
) {
    val tools = Tools.search(query)
    val terms = DocumentSearch.terms(query)
    val docs = documents.filter { meta ->
        val name = DocumentSearch.fold(meta.name).text
        terms.all { name.contains(it) }
    }

    if (tools.isNotEmpty()) {
        item { SectionTitle("Outils", hint = "${tools.size}") }
        toolGrid(tools, onTool)
    }
    if (docs.isNotEmpty()) {
        item { SectionTitle("Documents", hint = "${docs.size}") }
        items(docs.take(30), key = { "found-" + it.id }) { meta ->
            DocumentRow(meta, onOpen = { onDocument(meta) })
        }
    }
    if (tools.isEmpty() && docs.isEmpty()) {
        item {
            Hint(
                Icons.Filled.Search,
                "Rien ne correspond à « ${query.trim()} »",
                "Essaie un autre mot : « fusionner », « signature », « facture », « enregistrer »…"
            )
        }
    }
    // Les noms ne disent pas tout : la recherche dans le texte même des
    // documents vit dans « Mes documents ».
    item {
        OutlinedButton(onClick = onSearchContent, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Filled.ManageSearch, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Chercher dans le contenu des documents")
        }
    }
}

@Composable
private fun Greeting() {
    val now = remember { Calendar.getInstance() }
    val hello = if (now.get(Calendar.HOUR_OF_DAY) >= 18) "Bonsoir" else "Bonjour"
    val day = remember {
        SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(now.time).replaceFirstChar { it.uppercase() }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                "DocsApp Suite",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "$hello · $day",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "App faite par\nLoris et Thao",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text("Rechercher partout", maxLines = 1) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Effacer la recherche")
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateTile(tool: Tool, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        modifier = modifier.semantics { contentDescription = "Nouveau : ${tool.title}" }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 14.dp, horizontal = 4.dp)
        ) {
            Box(
                Modifier.size(44.dp).background(tool.color, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(tool.icon, contentDescription = null, tint = Color.White)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                tool.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPill(family: ToolCategory, count: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = family.color.copy(alpha = 0.12f),
        modifier = Modifier.semantics { contentDescription = "Famille ${family.label}" }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            Box(Modifier.size(8.dp).background(family.color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(family.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ====================================================================== outils

@Composable
private fun ToolsTabContent(
    category: ToolCategory?,
    onCategory: (ToolCategory?) -> Unit,
    onTool: (Tool) -> Unit
) {
    val families = if (category == null) ToolCategory.values().toList() else listOf(category)
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            TabHeader("Outils", "${Tools.all.size} outils, rangés par ce qu'ils font")
        }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = category == null,
                    onClick = { onCategory(null) },
                    label = { Text("Tout") }
                )
                ToolCategory.values().forEach { family ->
                    FilterChip(
                        selected = category == family,
                        onClick = { onCategory(if (category == family) null else family) },
                        label = { Text(family.label) },
                        leadingIcon = { Box(Modifier.size(8.dp).background(family.color, CircleShape)) }
                    )
                }
            }
        }
        families.forEach { family ->
            item(key = "family-" + family.name) { FamilyTitle(family) }
            toolGrid(Tools.of(family), onTool, keyPrefix = family.name)
        }
    }
}

@Composable
private fun FamilyTitle(family: ToolCategory) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Box(Modifier.size(width = 4.dp, height = 18.dp).background(family.color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Text(family.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text(
            "${Tools.of(family).size}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Des tuiles d'outils, deux par rangée, de même hauteur. */
private fun LazyListScope.toolGrid(
    tools: List<Tool>,
    onTool: (Tool) -> Unit,
    keyPrefix: String = "",
    compact: Boolean = false
) {
    items(tools.chunked(2), key = { pair -> keyPrefix + pair.joinToString("+") { it.id } }) { pair ->
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            pair.forEach { tool ->
                if (compact) {
                    CompactToolTile(tool, Modifier.weight(1f).fillMaxHeight()) { onTool(tool) }
                } else {
                    ToolTile(tool, Modifier.weight(1f).fillMaxHeight()) { onTool(tool) }
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolTile(tool: Tool, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 1.dp,
        modifier = modifier.semantics { contentDescription = "Outil ${tool.title}" }
    ) {
        Column(Modifier.padding(14.dp)) {
            Box(
                Modifier.size(40.dp).background(tool.color.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(tool.icon, contentDescription = null, tint = tool.color, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                tool.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                tool.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Tuile basse pour les raccourcis : l'accueil doit tenir sans défiler. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactToolTile(tool: Tool, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 1.dp,
        modifier = modifier.semantics { contentDescription = "Outil ${tool.title}" }
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).background(tool.color.copy(alpha = 0.14f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(tool.icon, contentDescription = null, tint = tool.color, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                tool.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ====================================================================== documents

@Composable
private fun DocumentsTabContent(
    documents: List<DocMeta>,
    filter: DocType?,
    onFilter: (DocType?) -> Unit,
    onOpen: (DocMeta) -> Unit,
    onDelete: (DocMeta) -> Unit,
    onOpenFile: () -> Unit,
    onNewDocument: () -> Unit,
    onLibrary: () -> Unit
) {
    val shown = documents.filter { filter == null || it.type == filter }
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            TabHeader(
                "Documents",
                when (documents.size) {
                    0 -> "Aucun document enregistré"
                    1 -> "1 document enregistré"
                    else -> "${documents.size} documents enregistrés"
                }
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onOpenFile, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Ouvrir un fichier", maxLines = 1)
                }
                OutlinedButton(onClick = onLibrary, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.ManageSearch, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Chercher, ranger", maxLines = 1)
                }
            }
        }

        if (documents.isEmpty()) {
            item {
                Hint(
                    Icons.Filled.Folder,
                    "Rien pour l'instant",
                    "Les documents que tu crées ou que tu ouvres sont enregistrés ici, sur l'appareil."
                )
            }
            item {
                FilledTonalButton(onClick = onNewDocument, modifier = Modifier.fillMaxWidth()) {
                    Text("Créer un document")
                }
            }
            return@LazyColumn
        }

        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filter == null,
                    onClick = { onFilter(null) },
                    label = { Text("Tous · ${documents.size}") }
                )
                DocType.values().forEach { type ->
                    val count = documents.count { it.type == type }
                    if (count > 0) {
                        FilterChip(
                            selected = filter == type,
                            onClick = { onFilter(if (filter == type) null else type) },
                            label = { Text("${plural(type)} · $count") },
                            leadingIcon = {
                                Icon(typeIcon(type), contentDescription = null, tint = typeColor(type), modifier = Modifier.size(18.dp))
                            }
                        )
                    }
                }
            }
        }
        items(shown, key = { "doc-" + it.id }) { meta ->
            DocumentRow(meta, onOpen = { onOpen(meta) }, onDelete = { onDelete(meta) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentRow(meta: DocMeta, onOpen: () -> Unit, onDelete: (() -> Unit)? = null) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Ouvrir ${meta.name}" }
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(40.dp).background(typeColor(meta.type).copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(typeIcon(meta.type), contentDescription = null, tint = typeColor(meta.type), modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    meta.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${singular(meta.type)} · ${relativeTime(meta.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = "Supprimer ${meta.name}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Spacer(Modifier.width(10.dp))
            }
        }
    }
}

// ====================================================================== morceaux communs

@Composable
private fun TabHeader(title: String, subtitle: String) {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionTitle(
    title: String,
    hint: String? = null,
    action: String? = null,
    onAction: () -> Unit = {}
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (hint != null) {
            Spacer(Modifier.width(8.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        if (action != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun Hint(icon: ImageVector, title: String, body: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun typeIcon(type: DocType): ImageVector = when (type) {
    DocType.TEXT -> Icons.Filled.Description
    DocType.SHEET -> Icons.Filled.TableChart
    DocType.DECK -> Icons.Filled.Slideshow
}

private fun typeColor(type: DocType): Color = when (type) {
    DocType.TEXT -> Color(0xFF2563EB)
    DocType.SHEET -> Color(0xFF16A34A)
    DocType.DECK -> Color(0xFFEA580C)
}

private fun singular(type: DocType): String = when (type) {
    DocType.TEXT -> "Document"
    DocType.SHEET -> "Tableur"
    DocType.DECK -> "Présentation"
}

private fun plural(type: DocType): String = when (type) {
    DocType.TEXT -> "Documents"
    DocType.SHEET -> "Tableurs"
    DocType.DECK -> "Présentations"
}

/** Message lisible pour un import raté, y compris quand la mémoire manque. */
private fun importError(error: Throwable): String = when (error) {
    is OutOfMemoryError -> "Ce fichier est trop lourd pour la mémoire du téléphone."
    else -> error.message?.takeIf { it.isNotBlank() } ?: "Ce fichier n'a pas pu être ouvert"
}
