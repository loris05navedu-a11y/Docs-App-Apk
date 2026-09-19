package com.docssuite.pdf

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.docssuite.core.rememberFileOpener
import com.docssuite.core.safeFileName
import com.docssuite.core.shareBytes
import com.docssuite.fileformats.FileFormat
import com.docssuite.fileformats.PdfText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Proportions d'une page A4 portrait, le temps de connaître les vraies. */
private const val DEFAULT_PAGE_RATIO = 1f / 1.414f

/** Au-delà, une page rendue coûterait plus de mémoire qu'elle n'apporte de netteté. */
private const val MAX_PAGE_WIDTH_PX = 2200

/** Le PDF déjà chargé qu'on demande au lecteur d'ouvrir à son arrivée. */
class PdfPayload(val name: String, val bytes: ByteArray)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    onBack: () -> Unit,
    initialFile: PdfPayload? = null,
    onOpenAsDocument: (name: String, text: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val snackbar = remember { SnackbarHostState() }

    var source by remember { mutableStateOf<PdfDocumentSource?>(null) }
    var rawBytes by remember { mutableStateOf<ByteArray?>(null) }
    var fileName by remember { mutableStateOf("") }
    var zoom by remember { mutableStateOf(1f) }
    var showMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }

    fun report(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun load(name: String, bytes: ByteArray) {
        scope.launch {
            busy = true
            val opened = withContext(Dispatchers.IO) {
                runCatching { PdfDocumentSource.open(context, bytes, name) }
            }
            busy = false
            opened
                .onSuccess {
                    source?.close()
                    source = it
                    rawBytes = bytes
                    fileName = name
                    zoom = 1f
                    listState.scrollToItem(0)
                }
                .onFailure { report(it.message ?: "Ouverture impossible") }
        }
    }

    val opener = rememberFileOpener(onError = ::report) { picked ->
        load(picked.name, picked.bytes)
    }

    LaunchedEffect(initialFile) { initialFile?.let { load(it.name, it.bytes) } }

    DisposableEffect(Unit) { onDispose { source?.close() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                title = {
                    Column {
                        Text(
                            fileName.ifBlank { "Lecteur PDF" },
                            maxLines = 1,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        source?.let {
                            Text(
                                "Page $currentPage sur ${it.pageCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    if (source != null) {
                        IconButton(
                            onClick = { zoom = (zoom - 0.25f).coerceAtLeast(1f) },
                            enabled = zoom > 1f
                        ) {
                            Icon(Icons.Filled.ZoomOut, contentDescription = "Dézoomer")
                        }
                        IconButton(
                            onClick = { zoom = (zoom + 0.25f).coerceAtMost(4f) },
                            enabled = zoom < 4f
                        ) {
                            Icon(Icons.Filled.ZoomIn, contentDescription = "Zoomer")
                        }
                    }
                    IconButton(onClick = { opener.open(arrayOf("application/pdf")) }) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "Ouvrir un PDF")
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Partager le PDF") },
                            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                            enabled = rawBytes != null,
                            onClick = {
                                showMenu = false
                                rawBytes?.let { bytes ->
                                    shareBytes(
                                        context,
                                        safeFileName(
                                            fileName.substringBeforeLast('.'),
                                            FileFormat.PDF
                                        ),
                                        FileFormat.PDF.mime,
                                        bytes,
                                        ::report
                                    )
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Convertir en document") },
                            leadingIcon = {
                                Icon(Icons.Filled.Description, contentDescription = null)
                            },
                            enabled = rawBytes != null && !busy,
                            onClick = {
                                showMenu = false
                                val bytes = rawBytes ?: return@DropdownMenuItem
                                scope.launch {
                                    busy = true
                                    val text = withContext(Dispatchers.IO) {
                                        runCatching { PdfText.extract(bytes) }.getOrDefault("")
                                    }
                                    busy = false
                                    if (text.isBlank()) {
                                        report(
                                            "Aucun texte à récupérer : ce PDF est " +
                                                "probablement un scan d'images"
                                        )
                                    } else {
                                        onOpenAsDocument(fileName.substringBeforeLast('.'), text)
                                    }
                                }
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        val document = source
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (document == null) {
                EmptyState(busy) { opener.open(arrayOf("application/pdf")) }
            } else {
                val horizontal = rememberScrollState()
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val pageWidth = maxWidth * zoom
                    val widthPx = with(density) {
                        pageWidth.toPx().toInt().coerceIn(1, MAX_PAGE_WIDTH_PX)
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxHeight()
                            .horizontalScroll(horizontal)
                            .width(pageWidth)
                            // L'appui double bascule entre pleine page et zoom
                            // de lecture, sans gêner le défilement.
                            .pointerInput(document) {
                                detectTapGestures(
                                    onDoubleTap = { zoom = if (zoom > 1f) 1f else 2f }
                                )
                            },
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(document.pageCount) { index ->
                            PdfPage(source = document, index = index, widthPx = widthPx)
                        }
                    }
                }
            }

            if (busy) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0x55000000)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun EmptyState(busy: Boolean, onOpen: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.PictureAsPdf,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(72.dp)
        )
        Text(
            "Aucun PDF ouvert",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            "Choisissez un fichier pour le lire page par page, le zoomer, " +
                "le partager ou en récupérer le texte.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )
        Button(onClick = onOpen, enabled = !busy) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null)
            Text("  Ouvrir un PDF")
        }
    }
}

@Composable
private fun PdfPage(source: PdfDocumentSource, index: Int, widthPx: Int) {
    var bitmap by remember(source, index) { mutableStateOf<Bitmap?>(null) }
    var ratio by remember(source, index) { mutableStateOf(DEFAULT_PAGE_RATIO) }

    // On ne libère pas les bitmaps à la main : Compose peut encore être en train
    // d'en dessiner un quand la page sort de l'écran, et `recycle()` ferait
    // planter le rendu. Le ramasse-miettes s'en charge.
    LaunchedEffect(source, index, widthPx) {
        ratio = source.aspectRatio(index)
        bitmap = withContext(Dispatchers.IO) { source.render(index, widthPx) }
    }

    Surface(
        shape = RoundedCornerShape(4.dp),
        shadowElevation = 2.dp,
        color = Color.White,
        modifier = Modifier.fillMaxWidth()
    ) {
        val image = bitmap
        if (image != null && !image.isRecycled) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
