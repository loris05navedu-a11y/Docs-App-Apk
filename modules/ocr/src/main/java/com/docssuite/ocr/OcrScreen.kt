package com.docssuite.ocr

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.docssuite.core.shareText
import com.docssuite.scanner.ScanImaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Texte depuis une photo : photographier une feuille, une affiche, un
 * tableau blanc, et récupérer le texte pour le modifier, le copier ou en
 * faire un document, sans rien retaper.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrScreen(
    onBack: () -> Unit,
    onCreateDocument: (title: String, text: String) -> Unit,
    state: OcrState = rememberOcrState { MlKitOcrEngine() }
) {
    val context = LocalContext.current
    DisposableEffect(state) { onDispose { state.dispose() } }
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        pendingCapture?.let { state.onPhotoCaptured(File(it), ok) }
        pendingCapture = null
    }
    fun capture() {
        val file = state.newCaptureFile()
        runCatching {
            pendingCapture = file.absolutePath
            camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
        }.onFailure {
            pendingCapture = null
            state.report("Aucune application d'appareil photo n'est disponible")
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { state.importImages(it) }
    fun import() {
        runCatching { gallery.launch("image/*") }.onFailure { state.report("Aucune galerie n'est disponible") }
    }

    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        state.dismissMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Texte depuis une photo", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Retour") } },
                actions = {
                    if (state.images.isNotEmpty()) {
                        IconButton(onClick = state::clear) { Icon(Icons.Filled.DeleteSweep, contentDescription = "Recommencer") }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.images.isEmpty() && state.text.isEmpty()) {
                EmptyContent(onCapture = ::capture, onImport = ::import)
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    LazyRow(
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.height(190.dp)
                    ) {
                        itemsIndexed(state.images) { index, read ->
                            ReadThumbnail(read, onRemove = { state.removeImage(index) })
                        }
                    }
                    OutlinedTextField(
                        value = state.text,
                        onValueChange = { state.text = it },
                        label = { Text("Texte reconnu — à relire · ${state.wordCount} mot(s)") },
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp)
                    )
                    Surface(tonalElevation = 3.dp) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = ::capture) { Icon(Icons.Filled.CameraAlt, contentDescription = "Ajouter une photo") }
                                IconButton(onClick = ::import) { Icon(Icons.Filled.PhotoLibrary, contentDescription = "Ajouter des images") }
                                Spacer(Modifier.weight(1f))
                                IconButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(state.text))
                                        state.report("Texte copié")
                                    },
                                    enabled = state.text.isNotBlank()
                                ) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copier le texte") }
                                IconButton(
                                    onClick = { runCatching { shareText(context, state.suggestedTitle(), state.text) } },
                                    enabled = state.text.isNotBlank()
                                ) { Icon(Icons.Filled.Share, contentDescription = "Partager le texte") }
                            }
                            Button(
                                onClick = { onCreateDocument(state.suggestedTitle(), state.text) },
                                enabled = state.text.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Créer un document avec ce texte") }
                        }
                    }
                }
            }
            state.busyMessage?.let { message ->
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
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
        }
    }
}

@Composable
private fun EmptyContent(onCapture: () -> Unit, onImport: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.TextSnippet, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(16.dp))
        Text("Récupère le texte d'une photo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "Une lettre, un cours, une affiche, un tableau blanc : le texte devient modifiable, à copier ou à transformer en document, sans rien retaper.",
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
            Text("Importer des images")
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Tout se fait sur le téléphone, sans connexion : aucune image n'est envoyée. Alphabet latin (français, anglais, espagnol, allemand…). Le texte manuscrit est mal reconnu.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun rememberOcrState(engineFactory: () -> OcrEngine): OcrState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember { OcrState(context.applicationContext, scope, engineFactory()) }
}

/** L'image lue, avec un cadre autour de chaque bloc de texte trouvé. */
@Composable
private fun ReadThumbnail(read: ReadImage, onRemove: () -> Unit) {
    val image by produceState<ImageBitmap?>(null, read.image) {
        value = withContext(Dispatchers.IO) { runCatching { ScanImaging.decodeThumbnail(read.image, 600)?.asImageBitmap() }.getOrNull() }
    }
    val primary = MaterialTheme.colorScheme.primary
    val ratio = read.page.width.toFloat() / maxOf(1, read.page.height)
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width((166 * ratio).coerceIn(80f, 260f).dp)
            .background(Color(0xFFEEEEEE), RoundedCornerShape(8.dp))
            .semantics { contentDescription = "Image lue, ${read.page.blocks.size} bloc(s) de texte" }
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            Canvas(modifier = Modifier.fillMaxSize()) {
                val bitmap = image ?: return@Canvas
                val scale = min(w / read.page.width, h / read.page.height)
                val left = (w - read.page.width * scale) / 2
                val top = (h - read.page.height * scale) / 2
                drawImage(
                    bitmap,
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize((read.page.width * scale).roundToInt(), (read.page.height * scale).roundToInt())
                )
                read.page.blocks.forEach { block ->
                    drawRect(
                        primary,
                        topLeft = Offset(left + block.box.left * scale, top + block.box.top * scale),
                        size = Size(block.box.width * scale, block.box.height * scale),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(28.dp).background(Color.White.copy(alpha = 0.85f), CircleShape)
        ) { Icon(Icons.Filled.Close, contentDescription = "Retirer l'image", modifier = Modifier.size(16.dp)) }
    }
}
