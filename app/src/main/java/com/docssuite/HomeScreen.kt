package com.docssuite

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.docssuite.core.DocMeta
import com.docssuite.core.DocType
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
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenTextEditor: (String?) -> Unit,
    onOpenSpreadsheet: (String?) -> Unit,
    onOpenPresentation: (String?) -> Unit,
    onOpenPdf: (PdfPayload?) -> Unit,
    onOpenMedia: () -> Unit,
    onOpenConverter: () -> Unit,
    incomingFile: Uri? = null,
    onIncomingHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    val storage = remember { DocumentStorage(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var refreshKey by remember { mutableStateOf(0) }
    val documents = remember(refreshKey) { storage.list() }

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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("DocsApp Suite", fontWeight = FontWeight.Bold)
                            Text(
                                "Documents, tableurs et présentations",
                                style = MaterialTheme.typography.labelSmall,
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
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    "Créer",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            item {
                AppCard(
                    "Document",
                    "Écrire et mettre en forme du texte",
                    Icons.Filled.Description,
                    Color(0xFF2563EB)
                ) { onOpenTextEditor(null) }
            }
            item {
                AppCard(
                    "Tableur",
                    "Organiser des données et calculer",
                    Icons.Filled.TableChart,
                    Color(0xFF16A34A)
                ) { onOpenSpreadsheet(null) }
            }
            item {
                AppCard(
                    "Présentation",
                    "Créer des slides et lancer un diaporama",
                    Icons.Filled.Slideshow,
                    Color(0xFFEA580C)
                ) { onOpenPresentation(null) }
            }
            item {
                AppCard(
                    "PDF",
                    "Lire, zoomer et partager un PDF",
                    Icons.Filled.PictureAsPdf,
                    Color(0xFFDC2626)
                ) { onOpenPdf(null) }
            }
            item {
                AppCard(
                    "Lecteur vidéo et audio",
                    "MP4, MKV, WebM, MP3, FLAC…",
                    Icons.Filled.PlayCircle,
                    Color(0xFF7C3AED),
                    onClick = onOpenMedia
                )
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Ouvrir et convertir",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            item {
                AppCard(
                    "Ouvrir un fichier",
                    "Word, Excel, PowerPoint, OpenDocument, PDF, CSV, RTF…",
                    Icons.Filled.FolderOpen,
                    Color(0xFF0F766E)
                ) { opener.open() }
            }
            item {
                AppCard(
                    "File Converter",
                    "Images, PDF, audio et vidéo — hors ligne, sur l'appareil",
                    Icons.Filled.SwapHoriz,
                    Color(0xFF4F46E5),
                    onClick = onOpenConverter
                )
            }

            if (documents.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Documents récents",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(documents) { meta ->
                    RecentRow(
                        meta = meta,
                        onOpen = {
                            when (meta.type) {
                                DocType.TEXT -> onOpenTextEditor(meta.id)
                                DocType.SHEET -> onOpenSpreadsheet(meta.id)
                                DocType.DECK -> onOpenPresentation(meta.id)
                            }
                        },
                        onDelete = {
                            storage.delete(meta.id)
                            refreshKey++
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(56.dp).background(color, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = title, tint = Color.White)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RecentRow(meta: DocMeta, onOpen: () -> Unit, onDelete: () -> Unit) {
    val formatter = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE) }
    val (icon, color) = when (meta.type) {
        DocType.TEXT -> Icons.Filled.Description to Color(0xFF2563EB)
        DocType.SHEET -> Icons.Filled.TableChart to Color(0xFF16A34A)
        DocType.DECK -> Icons.Filled.Slideshow to Color(0xFFEA580C)
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(36.dp).background(color.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(meta.name, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                Text(
                    formatter.format(Date(meta.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Supprimer",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Message lisible pour un import raté, y compris quand la mémoire manque. */
private fun importError(error: Throwable): String = when (error) {
    is OutOfMemoryError -> "Ce fichier est trop lourd pour la mémoire du téléphone."
    else -> error.message?.takeIf { it.isNotBlank() } ?: "Ce fichier n'a pas pu être ouvert"
}
