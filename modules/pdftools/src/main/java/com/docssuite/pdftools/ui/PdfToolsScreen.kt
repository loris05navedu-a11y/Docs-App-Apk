package com.docssuite.pdftools.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docssuite.core.shareBytes

private val documentColors = listOf(
    Color(0xFF2563EB), Color(0xFF16A34A), Color(0xFFEA580C),
    Color(0xFF9333EA), Color(0xFFDB2777), Color(0xFF0891B2)
)

/**
 * Outils PDF : ouvrir un ou plusieurs PDF, et en faire un nouveau en
 * réordonnant, tournant, supprimant ou extrayant des pages. Tout se fait
 * sans perte : le texte reste sélectionnable, les images intactes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfToolsScreen(
    onBack: () -> Unit,
    onOpenPdf: ((name: String, bytes: ByteArray) -> Unit)? = null,
    state: PdfToolsState = rememberPdfToolsState()
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var exportSelection by remember { mutableStateOf<Boolean?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var pendingPdf by remember { mutableStateOf<ByteArray?>(null) }

    DisposableEffect(state) { onDispose { state.dispose() } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        state.import(uris)
    }
    fun pick() {
        runCatching { picker.launch(arrayOf("application/pdf")) }
            .onFailure { state.report("Aucune application de fichiers n'est disponible") }
    }

    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val bytes = pendingPdf
        pendingPdf = null
        if (uri != null && bytes != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Emplacement inaccessible")
            }
                .onSuccess { state.report("PDF enregistré") }
                .onFailure { state.report(it.message ?: "Enregistrement impossible") }
        }
    }

    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        state.dismissMessage()
    }

    // Une suppression se rattrape d'un geste, sans boîte de confirmation.
    LaunchedEffect(state.deletedCount) {
        val count = state.deletedCount
        if (count == 0) return@LaunchedEffect
        val result = snackbar.showSnackbar(
            "$count page(s) supprimée(s)",
            actionLabel = "Annuler",
            duration = SnackbarDuration.Long
        )
        if (result == SnackbarResult.ActionPerformed) state.undoDelete() else state.forgetUndo()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            state.hasSelection -> "${state.selectedCount} sélectionnée(s)"
                            state.pages.isEmpty() -> "Outils PDF"
                            else -> "Outils PDF — ${state.pages.size} page(s)"
                        },
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (state.hasSelection) state.selectAll(false) else onBack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    if (state.pages.isNotEmpty()) {
                        IconButton(onClick = { state.selectAll(!state.hasSelection || state.selectedCount < state.pages.size) }) {
                            Icon(Icons.Filled.SelectAll, contentDescription = "Tout sélectionner")
                        }
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "Tout fermer")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.pages.isEmpty()) {
                EmptyContent(onPick = ::pick)
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (state.documents.size > 1) DocumentLegend(state)
                    PageGrid(state, modifier = Modifier.weight(1f))
                    ActionBar(
                        state = state,
                        onAdd = ::pick,
                        onExportAll = { exportSelection = false },
                        onExportSelection = { exportSelection = true }
                    )
                }
            }
            BusyOverlay(state.busyMessage)
        }
    }

    exportSelection?.let { onlySelection ->
        ExportDialog(
            heading = if (onlySelection) "Extraire ${state.selectedCount} page(s)" else "Enregistrer ${state.pages.size} page(s)",
            defaultTitle = remember(onlySelection) { state.defaultTitle(onlySelection) },
            canOpen = onOpenPdf != null,
            onDismiss = { exportSelection = null },
            onSave = { title ->
                exportSelection = null
                state.withPdf(onlySelection, title) { bytes ->
                    pendingPdf = bytes
                    runCatching { savePdf.launch(pdfName(title)) }
                        .onFailure { state.report("Aucune application de fichiers n'est disponible") }
                }
            },
            onShare = { title ->
                exportSelection = null
                state.withPdf(onlySelection, title) { bytes ->
                    shareBytes(context, pdfName(title), "application/pdf", bytes) { state.report(it) }
                }
            },
            onOpen = { title ->
                exportSelection = null
                state.withPdf(onlySelection, title) { bytes -> onOpenPdf?.invoke(pdfName(title), bytes) }
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Tout fermer ?") },
            text = { Text("Les documents ouverts ici seront retirés. Les fichiers d'origine ne sont pas modifiés.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; state.clear() }) { Text("Tout fermer") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annuler") } }
        )
    }
}

private fun pdfName(title: String): String {
    val base = title.trim()
        .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "")
        .trim('.', ' ')
        .take(80)
        .ifBlank { "Document" }
    return "$base.pdf"
}

@Composable
private fun EmptyContent(onPick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(16.dp))
        Text("Réorganise tes PDF", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Fusionne plusieurs PDF, change l'ordre des pages, tourne celles qui sont de travers, supprime ou extrais-en quelques-unes. Le texte reste sélectionnable et la qualité intacte.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.NoteAdd, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Choisir des PDF")
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Les fichiers d'origine ne sont jamais modifiés : le résultat est un nouveau PDF.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** Avec plusieurs fichiers, chaque page porte la couleur de son document d'origine. */
@Composable
private fun DocumentLegend(state: PdfToolsState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        state.documents.take(4).forEach { document ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                Box(Modifier.size(10.dp).background(documentColors[document.colorIndex], CircleShape))
                Spacer(Modifier.width(4.dp))
                Text(document.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PageGrid(state: PdfToolsState, modifier: Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(state.pages, key = { _, page -> page.id }) { index, page ->
            PageTile(state, page, index + 1, showOrigin = state.documents.size > 1)
        }
    }
}

@Composable
private fun PageTile(state: PdfToolsState, page: WorkPage, position: Int, showOrigin: Boolean) {
    val widthPx = with(LocalDensity.current) { 180.dp.roundToPx() }
    val thumbnail by produceState<ImageBitmap?>(null, page.document.id, page.pageIndex, page.quarterTurns) {
        value = state.thumbnail(page, widthPx)?.asImageBitmap()
    }
    val accent = documentColors[page.document.colorIndex]
    val border = if (page.selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null
    Card(
        shape = RoundedCornerShape(10.dp),
        border = border,
        elevation = CardDefaults.cardElevation(defaultElevation = if (page.selected) 6.dp else 1.dp),
        modifier = Modifier
            .clickable { state.toggle(page) }
            .semantics {
                contentDescription = "Page $position"
                selected = page.selected
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            val image = thumbnail
            if (image != null) {
                Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
            } else {
                Text(
                    "Page ${page.pageIndex + 1}",
                    color = Color.Gray,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            if (page.selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color.White, CircleShape)
                )
            }
            Surface(
                shape = RoundedCornerShape(topEnd = 8.dp),
                color = if (showOrigin) accent else MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.BottomStart)
            ) {
                Text(
                    "$position",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun ActionBar(
    state: PdfToolsState,
    onAdd: () -> Unit,
    onExportAll: () -> Unit,
    onExportSelection: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val any = state.hasSelection
                ToolButton(Icons.Filled.RotateLeft, "Tourner ←") { state.rotateSelected(-1) }
                ToolButton(Icons.Filled.RotateRight, "Tourner →") { state.rotateSelected(1) }
                ToolButton(Icons.Filled.KeyboardArrowLeft, "Avancer", enabled = any) { state.moveSelected(-1) }
                ToolButton(Icons.Filled.KeyboardArrowRight, "Reculer", enabled = any) { state.moveSelected(1) }
                ToolButton(Icons.Filled.Delete, "Supprimer", enabled = any) { state.deleteSelected() }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onAdd) { Icon(Icons.Filled.NoteAdd, contentDescription = "Ajouter un PDF") }
                Spacer(Modifier.weight(1f))
                if (state.hasSelection) {
                    OutlinedButton(onClick = onExportSelection) { Text("Extraire") }
                }
                Button(onClick = onExportAll) { Text("Enregistrer") }
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    Column(
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@Composable
private fun ExportDialog(
    heading: String,
    defaultTitle: String,
    canOpen: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onShare: (String) -> Unit,
    onOpen: (String) -> Unit
) {
    var title by rememberSaveable { mutableStateOf(defaultTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(heading) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Nom du nouveau PDF") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onSave(title) }, modifier = Modifier.fillMaxWidth()) { Text("Enregistrer") }
                OutlinedButton(onClick = { onShare(title) }, modifier = Modifier.fillMaxWidth()) { Text("Partager") }
                if (canOpen) {
                    OutlinedButton(onClick = { onOpen(title) }, modifier = Modifier.fillMaxWidth()) { Text("Ouvrir dans le lecteur PDF") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )
}

@Composable
private fun BusyOverlay(message: String?) {
    if (message == null) return
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(onClick = {}),
        contentAlignment = Alignment.Center
    ) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(message)
            }
        }
    }
}

