package com.docssuite.scanner.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.docssuite.core.shareBytes
import com.docssuite.scanner.PageFormat
import com.docssuite.scanner.Pt
import com.docssuite.scanner.ScanFilter
import com.docssuite.scanner.ScanImaging
import com.docssuite.scanner.ScanPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Scanner de documents : photographier une feuille (ou importer une photo),
 * la recadrer aux quatre coins, la rendre lisible, et exporter toutes les
 * pages en un PDF. [onOpenPdf] ouvre le résultat dans le lecteur de l'app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(onBack: () -> Unit, onOpenPdf: ((name: String, bytes: ByteArray) -> Unit)? = null) {
    val state = rememberScannerState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // Le chemin survit à une fermeture de l'app pendant la prise de vue.
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    var captureAgain by rememberSaveable { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var pendingPdf by remember { mutableStateOf<ByteArray?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        pendingCapture?.let { state.onPhotoCaptured(File(it), ok) }
        pendingCapture = null
    }

    fun startCapture() {
        val file = state.newCaptureFile()
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            pendingCapture = file.absolutePath
            camera.launch(uri)
        }.onFailure {
            pendingCapture = null
            file.delete()
            state.report("Aucune application d'appareil photo n'est disponible")
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        state.importImages(uris)
    }

    fun startImport() {
        runCatching { gallery.launch("image/*") }
            .onFailure { state.report("Aucune galerie n'est disponible") }
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

    // « Valider et photo suivante » : l'appareil photo se rouvre une fois
    // la page enregistrée, pour enchaîner un document de plusieurs pages.
    LaunchedEffect(captureAgain, state.stage) {
        if (captureAgain && state.stage == ScannerStage.PAGES && state.busyMessage == null) {
            captureAgain = false
            startCapture()
        }
    }

    BackHandler(enabled = state.stage == ScannerStage.EDIT) { state.closeEditor() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            state.stage == ScannerStage.EDIT -> "Recadrer la page"
                            state.pages.isEmpty() -> "Scanner"
                            else -> "Scanner — ${state.pages.size} page(s)"
                        },
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (state.stage == ScannerStage.EDIT) state.closeEditor() else onBack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    if (state.stage == ScannerStage.PAGES && state.pages.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "Nouveau scan")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val editor = state.editor
            when {
                state.stage == ScannerStage.EDIT && editor != null -> EditorContent(
                    editor = editor,
                    onValidate = { state.applyEdit() },
                    onValidateAndNext = { state.applyEdit { captureAgain = true } },
                    onCancel = state::closeEditor,
                    onNoSheetFound = { state.report("Aucune feuille nette trouvée : ajuste les coins à la main") }
                )
                state.pages.isEmpty() -> EmptyContent(onCapture = ::startCapture, onImport = ::startImport)
                else -> PagesContent(
                    pages = state.pages,
                    onEdit = state::edit,
                    onMove = state::move,
                    onDelete = state::delete,
                    onCapture = ::startCapture,
                    onImport = ::startImport,
                    onExport = { showExport = true }
                )
            }
            BusyOverlay(state.busyMessage)
        }
    }

    if (showExport) {
        ExportDialog(
            defaultTitle = remember { state.defaultTitle() },
            canOpen = onOpenPdf != null,
            onDismiss = { showExport = false },
            onSave = { title, format ->
                showExport = false
                state.withPdf(format, title) { bytes ->
                    pendingPdf = bytes
                    runCatching { savePdf.launch(pdfName(title)) }
                        .onFailure { state.report("Aucune application de fichiers n'est disponible") }
                }
            },
            onShare = { title, format ->
                showExport = false
                state.withPdf(format, title) { bytes ->
                    shareBytes(context, pdfName(title), "application/pdf", bytes) { state.report(it) }
                }
            },
            onOpen = { title, format ->
                showExport = false
                state.withPdf(format, title) { bytes -> onOpenPdf?.invoke(pdfName(title), bytes) }
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Nouveau scan ?") },
            text = { Text("Les ${state.pages.size} page(s) actuelles seront effacées. Pense à exporter le PDF avant si tu en as besoin.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; state.clearAll() }) { Text("Tout effacer") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annuler") } }
        )
    }
}

private fun pdfName(title: String): String {
    val base = title.trim()
        .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "")
        .trim('.', ' ')
        .take(80)
        .ifBlank { "Scan" }
    return "$base.pdf"
}

// ------------------------------------------------------------------ vide

@Composable
private fun EmptyContent(onCapture: () -> Unit, onImport: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.DocumentScanner,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("Scanne tes documents", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Factures, contrats, notes de cours, justificatifs : photographie la feuille, elle est recadrée et nettoyée, puis toutes les pages forment un PDF.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onCapture, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.CameraAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Prendre une photo")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Importer des photos")
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Astuce : pose la feuille bien à plat sur une surface plus sombre qu'elle, les coins sont alors trouvés tout seuls.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ----------------------------------------------------------------- pages

@Composable
private fun PagesContent(
    pages: List<ScanPage>,
    onEdit: (ScanPage) -> Unit,
    onMove: (ScanPage, Int) -> Unit,
    onDelete: (ScanPage) -> Unit,
    onCapture: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(pages, key = { _, page -> page.id }) { index, page ->
                PageCard(
                    page = page,
                    number = index + 1,
                    isFirst = index == 0,
                    isLast = index == pages.lastIndex,
                    onEdit = { onEdit(page) },
                    onMove = { delta -> onMove(page, delta) },
                    onDelete = { onDelete(page) }
                )
            }
        }
        Surface(tonalElevation = 3.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCapture) { Icon(Icons.Filled.CameraAlt, contentDescription = "Ajouter une photo") }
                IconButton(onClick = onImport) { Icon(Icons.Filled.PhotoLibrary, contentDescription = "Importer des photos") }
                Spacer(Modifier.weight(1f))
                Button(onClick = onExport) {
                    Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Exporter en PDF")
                }
            }
        }
    }
}

@Composable
private fun PageCard(
    page: ScanPage,
    number: Int,
    isFirst: Boolean,
    isLast: Boolean,
    onEdit: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit
) {
    val thumbnail by produceState<ImageBitmap?>(null, page.rendered.path, page.version) {
        value = withContext(Dispatchers.IO) {
            runCatching { ScanImaging.decodeThumbnail(page.rendered, 400)?.asImageBitmap() }.getOrNull()
        }
    }
    Card(shape = RoundedCornerShape(14.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onEdit)
                .semantics { contentDescription = "Page $number" },
            contentAlignment = Alignment.Center
        ) {
            thumbnail?.let {
                Image(it, contentDescription = "Vignette de la page $number", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(6.dp))
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
            ) {
                Text(
                    "$number",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = { onMove(-1) }, enabled = !isFirst) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Avancer la page $number")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Supprimer la page $number")
            }
            IconButton(onClick = { onMove(1) }, enabled = !isLast) {
                Icon(Icons.Filled.ArrowForward, contentDescription = "Reculer la page $number")
            }
        }
    }
}

// ---------------------------------------------------------------- éditeur

@Composable
private fun EditorContent(
    editor: PageEditor,
    onValidate: () -> Unit,
    onValidateAndNext: () -> Unit,
    onCancel: () -> Unit,
    onNoSheetFound: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = if (editor.showResult) 1 else 0) {
            Tab(selected = !editor.showResult, onClick = { editor.showResult = false }, text = { Text("Recadrer") })
            Tab(selected = editor.showResult, onClick = { editor.showResult = true }, text = { Text("Résultat") })
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1B1B1F))) {
            if (editor.showResult) ResultPreview(editor) else CropView(editor)
        }
        if (!editor.showResult) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ToolButton(Icons.Filled.AutoFixHigh, "Auto") { if (!editor.autoDetect()) onNoSheetFound() }
                ToolButton(Icons.Filled.CropFree, "Toute la photo", editor::useWholePhoto)
                ToolButton(Icons.Filled.RotateLeft, "Tourner à gauche") { editor.rotate(-1) }
                ToolButton(Icons.Filled.RotateRight, "Tourner à droite") { editor.rotate(1) }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(ScanFilter.values().toList()) { filter ->
                FilterChip(
                    selected = editor.filter == filter,
                    onClick = { editor.filter = filter },
                    label = { Text(filter.label) }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
        ) {
            if (editor.isNew) {
                OutlinedButton(onClick = onValidateAndNext) { Text("Page suivante") }
                Button(onClick = onValidate) { Text("Terminer") }
            } else {
                TextButton(onClick = onCancel) { Text("Annuler") }
                Button(onClick = onValidate) { Text("Valider") }
            }
        }
    }
}

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * La photo avec ses quatre poignées. Hors de la feuille, l'image est
 * assombrie ; pendant qu'on déplace un coin, une loupe montre l'endroit
 * exact sous le doigt, qui le cache sinon.
 */
@Composable
private fun CropView(editor: PageEditor) {
    val image = remember(editor.preview) { editor.preview.asImageBitmap() }
    var active by remember { mutableStateOf(-1) }
    val primary = MaterialTheme.colorScheme.primary

    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val pw = editor.preview.width.toFloat()
        val ph = editor.preview.height.toFloat()
        val fit = min(boxW / pw, boxH / ph)
        val left = (boxW - pw * fit) / 2
        val top = (boxH - ph * fit) / 2
        // Photo pleine résolution → écran.
        val sx = fit * editor.previewScaleX
        val sy = fit * editor.previewScaleY
        val currentSx by rememberUpdatedState(sx)
        val currentSy by rememberUpdatedState(sy)
        val currentLeft by rememberUpdatedState(left)
        val currentTop by rememberUpdatedState(top)

        fun toScreen(p: Pt) = Offset(left + p.x * sx, top + p.y * sy)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Zone de recadrage" }
                .pointerInput(editor) {
                    val grab = 44.dp.toPx()
                    detectDragGestures(
                        onDragStart = { start ->
                            val corners = editor.quad.points.map {
                                Offset(currentLeft + it.x * currentSx, currentTop + it.y * currentSy)
                            }
                            val nearest = corners.indices.minByOrNull { (corners[it] - start).getDistance() } ?: -1
                            active = if (nearest >= 0 && (corners[nearest] - start).getDistance() <= grab) nearest else -1
                        },
                        onDragEnd = { active = -1 },
                        onDragCancel = { active = -1 },
                        onDrag = { change, amount ->
                            val index = active
                            if (index >= 0) {
                                change.consume()
                                val corner = editor.quad.points[index]
                                editor.moveCorner(index, Pt(corner.x + amount.x / currentSx, corner.y + amount.y / currentSy))
                            }
                        }
                    )
                }
        ) {
            drawImage(
                image,
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize((pw * fit).roundToInt(), (ph * fit).roundToInt()),
                filterQuality = FilterQuality.Medium
            )
            val corners = editor.quad.points.map(::toScreen)
            val outline = Path().apply {
                moveTo(corners[0].x, corners[0].y)
                corners.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            val shade = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(androidx.compose.ui.geometry.Rect(left, top, left + pw * fit, top + ph * fit))
                addPath(outline)
            }
            drawPath(shade, Color.Black.copy(alpha = 0.45f))
            val valid = editor.quad.isConvex()
            drawPath(outline, if (valid) primary else Color(0xFFE53935), style = Stroke(width = 2.dp.toPx()))
            corners.forEachIndexed { i, c ->
                val r = if (i == active) 16.dp.toPx() else 12.dp.toPx()
                drawCircle(Color.White.copy(alpha = 0.85f), radius = r, center = c)
                drawCircle(primary, radius = r, center = c, style = Stroke(width = 3.dp.toPx()))
            }

            if (active >= 0) {
                // Loupe ×2,5 dans le coin opposé au doigt.
                val c = corners[active]
                val radius = 56.dp.toPx()
                val center = if (c.x < size.width / 2) Offset(size.width - radius - 8.dp.toPx(), radius + 8.dp.toPx())
                else Offset(radius + 8.dp.toPx(), radius + 8.dp.toPx())
                val zoom = 2.5f
                val srcHalf = radius / (fit * zoom)
                val px = (c.x - left) / fit
                val py = (c.y - top) / fit
                val loupe = Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(center, radius))
                }
                clipPath(loupe) {
                    drawRect(Color.Black, topLeft = center - Offset(radius, radius), size = Size(radius * 2, radius * 2))
                    drawImage(
                        image,
                        srcOffset = IntOffset((px - srcHalf).roundToInt(), (py - srcHalf).roundToInt()),
                        srcSize = IntSize((srcHalf * 2).roundToInt().coerceAtLeast(1), (srcHalf * 2).roundToInt().coerceAtLeast(1)),
                        dstOffset = IntOffset((center.x - radius).roundToInt(), (center.y - radius).roundToInt()),
                        dstSize = IntSize((radius * 2).roundToInt(), (radius * 2).roundToInt())
                    )
                }
                drawCircle(Color.White, radius = radius, center = center, style = Stroke(width = 2.dp.toPx()))
                drawLine(primary, center - Offset(10.dp.toPx(), 0f), center + Offset(10.dp.toPx(), 0f), strokeWidth = 2.dp.toPx())
                drawLine(primary, center - Offset(0f, 10.dp.toPx()), center + Offset(0f, 10.dp.toPx()), strokeWidth = 2.dp.toPx())
            }
        }
    }
}

@Composable
private fun ResultPreview(editor: PageEditor) {
    val valid = editor.quad.isConvex()
    val result by produceState<ImageBitmap?>(null, editor.quad, editor.quarterTurns, editor.filter, valid) {
        value = null
        if (valid) {
            value = withContext(Dispatchers.Default) {
                runCatching { editor.renderPreview().asImageBitmap() }.getOrNull()
            }
        }
    }
    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        val bitmap = result
        when {
            !valid -> Text(
                "Les coins se croisent : replace-les autour de la feuille dans l'onglet Recadrer.",
                color = Color.White,
                textAlign = TextAlign.Center
            )
            bitmap == null -> CircularProgressIndicator()
            else -> Image(
                bitmap,
                contentDescription = "Aperçu de la page",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// ------------------------------------------------------------------ export

@Composable
private fun ExportDialog(
    defaultTitle: String,
    canOpen: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, PageFormat) -> Unit,
    onShare: (String, PageFormat) -> Unit,
    onOpen: (String, PageFormat) -> Unit
) {
    var title by rememberSaveable { mutableStateOf(defaultTitle) }
    var format by rememberSaveable { mutableStateOf(PageFormat.A4) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exporter en PDF") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Nom du document") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("Format des pages", style = MaterialTheme.typography.labelLarge)
                PageFormat.values().forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = format == option, onClick = { format = option })
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = format == option, onClick = { format = option })
                        Text(option.label)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onSave(title, format) }, modifier = Modifier.fillMaxWidth()) { Text("Enregistrer") }
                OutlinedButton(onClick = { onShare(title, format) }, modifier = Modifier.fillMaxWidth()) { Text("Partager") }
                if (canOpen) {
                    OutlinedButton(onClick = { onOpen(title, format) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Ouvrir dans le lecteur PDF")
                    }
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
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(enabled = true, onClick = {}),
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

