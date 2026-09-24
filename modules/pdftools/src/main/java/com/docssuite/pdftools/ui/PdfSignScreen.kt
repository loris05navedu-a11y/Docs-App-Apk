package com.docssuite.pdftools.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.docssuite.core.shareBytes
import kotlin.math.roundToInt

private val INK = Color(0xFF1E3A8A)

/**
 * Signer et remplir un PDF : signature dessinée au doigt, texte, date,
 * coches, posés et ajustés sur les pages, puis un nouveau PDF est produit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfSignScreen(
    onBack: () -> Unit,
    onOpenPdf: ((name: String, bytes: ByteArray) -> Unit)? = null,
    state: PdfSignState = rememberSignState()
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var drawing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Placed?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var pendingPdf by remember { mutableStateOf<ByteArray?>(null) }
    DisposableEffect(state) { onDispose { state.close() } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(state::open) }
    fun pick() = runCatching { picker.launch(arrayOf("application/pdf")) }.onFailure { state.report("Aucune application de fichiers n'est disponible") }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val bytes = pendingPdf
        pendingPdf = null
        if (uri != null && bytes != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Emplacement inaccessible") }
                .onSuccess { state.report("PDF signé enregistré") }
                .onFailure { state.report(it.message ?: "Enregistrement impossible") }
        }
    }

    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        state.dismissMessage()
    }

    fun arm(tool: SignTool) {
        if (tool == SignTool.SIGNATURE && state.savedSignature == null) {
            drawing = true
        } else {
            state.selectedId = null
            state.armed = if (state.armed == tool) null else tool
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (state.file == null) "Signer un PDF" else state.name, fontWeight = FontWeight.Bold, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Retour") } },
                actions = {
                    if (state.file != null) {
                        TextButton(onClick = { state.selectedId = null; exporting = true }, enabled = state.placed.isNotEmpty()) { Text("Terminer") }
                    }
                }
            )
        },
        bottomBar = {
            if (state.file != null) {
                Surface(tonalElevation = 3.dp) {
                    val selected = state.placed.firstOrNull { it.id == state.selectedId }
                    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        state.armed?.let {
                            Text(
                                "Touche la page à l'endroit où placer : ${it.label.lowercase()}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                            )
                        }
                        if (selected != null) {
                            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                                if (selected.image == null && !selected.check) {
                                    TextButton(onClick = { editing = selected }) { Text("Modifier") }
                                }
                                TextButton(onClick = { state.update(selected.id) { it.copy(size = it.size / 1.2f) } }) { Text("Plus petit") }
                                TextButton(onClick = { state.update(selected.id) { it.copy(size = it.size * 1.2f) } }) { Text("Plus grand") }
                                TextButton(onClick = { state.remove(selected.id) }) { Text("Retirer", color = MaterialTheme.colorScheme.error) }
                                TextButton(onClick = { state.selectedId = null }) { Text("OK") }
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                                ToolButton(Icons.Filled.Draw, "Signature", state.armed == SignTool.SIGNATURE) { arm(SignTool.SIGNATURE) }
                                ToolButton(Icons.Filled.TextFields, "Texte", state.armed == SignTool.TEXT) { arm(SignTool.TEXT) }
                                ToolButton(Icons.Filled.CalendarToday, "Date", state.armed == SignTool.DATE) { arm(SignTool.DATE) }
                                ToolButton(Icons.Filled.Check, "Coche", state.armed == SignTool.CHECK) { arm(SignTool.CHECK) }
                                ToolButton(Icons.Filled.Edit, "Ma signature", false) { drawing = true }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val f = state.file
            if (f == null) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.Draw, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Signe et remplis tes PDF", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Contrat, bail, autorisation, formulaire : ajoute ta signature, ton nom, la date et des coches, sans imprimer ni scanner.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { pick() }, modifier = Modifier.fillMaxWidth()) { Text("Choisir un PDF") }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Le fichier d'origine n'est pas modifié : un nouveau PDF signé est créé.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().background(Color(0xFFE5E7EB))
                ) {
                    items(f.pages.indices.toList()) { index -> SignPage(state, index) }
                }
            }
            state.busy?.let { message ->
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                    Surface(shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(message)
                        }
                    }
                }
            }
        }
    }

    if (drawing) {
        SignaturePad(
            onDismiss = { drawing = false },
            onDone = { bitmap ->
                state.rememberSignature(bitmap)
                drawing = false
                state.selectedId = null
                state.armed = SignTool.SIGNATURE
            }
        )
    }
    editing?.let { item ->
        var text by remember(item.id) { mutableStateOf(item.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Texte") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("Nom, « Lu et approuvé »…") }) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = { state.update(item.id) { it.copy(text = text) }; editing = null }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Annuler") } }
        )
    }
    if (exporting) {
        val fileName = state.signedTitle().replace(Regex("[\\\\/:*?\"<>|]"), "") + ".pdf"
        AlertDialog(
            onDismissRequest = { exporting = false },
            title = { Text("PDF signé") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = {
                        exporting = false
                        state.withPdf { bytes -> pendingPdf = bytes; runCatching { saver.launch(fileName) } }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Enregistrer") }
                    OutlinedButton(onClick = {
                        exporting = false
                        state.withPdf { bytes -> shareBytes(context, fileName, "application/pdf", bytes) { state.report(it) } }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Partager") }
                    if (onOpenPdf != null) {
                        OutlinedButton(onClick = {
                            exporting = false
                            state.withPdf { bytes -> onOpenPdf(fileName, bytes) }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Ouvrir dans le lecteur PDF") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { exporting = false }) { Text("Fermer") } }
        )
    }
}

@Composable
fun rememberSignState(): PdfSignState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember { PdfSignState(context.applicationContext, scope) }
}

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(10.dp))
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = label }
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** Une page, avec ce qui y est posé. Sa taille vient du PDF lui-même, pas du rendu. */
@Composable
private fun SignPage(state: PdfSignState, index: Int) {
    val geometry = remember(state.file, index) { state.geometry(index) }
    val aspect = geometry.displayWidth / geometry.displayHeight
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .background(Color.White)
            .semantics { contentDescription = "Page ${index + 1}" }
            .pointerInput(index) {
                detectTapGestures { at ->
                    if (state.armed != null) state.placeAt(index, at.x / size.width, at.y / size.height, aspect)
                    else state.selectedId = null
                }
            }
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val render by produceState<ImageBitmap?>(null, state.file, index) {
            value = state.render(index, constraints.maxWidth.coerceAtMost(1600))?.asImageBitmap()
        }
        render?.let { Image(it, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
        state.placed.filter { it.page == index }.forEach { item ->
            PlacedView(state, item, widthPx, heightPx)
        }
    }
}

@Composable
private fun PlacedView(state: PdfSignState, item: Placed, pageW: Float, pageH: Float) {
    val density = LocalDensity.current
    val selected = state.selectedId == item.id
    Box(
        modifier = Modifier
            .offset { IntOffset((item.u * pageW).roundToInt(), (item.v * pageH).roundToInt()) }
            .then(if (selected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)) else Modifier)
            .semantics { contentDescription = if (item.image != null) "Signature posée" else if (item.check) "Coche posée" else "Texte posé : ${item.text}" }
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { state.selectedId = item.id; state.armed = null },
                    onDoubleTap = { state.selectedId = item.id }
                )
            }
            .pointerInput(item.id, pageW, pageH) {
                detectDragGestures(onDragStart = { state.selectedId = item.id }) { change, drag ->
                    change.consume()
                    state.update(item.id) {
                        it.copy(u = (it.u + drag.x / pageW).coerceIn(-0.05f, 0.98f), v = (it.v + drag.y / pageH).coerceIn(-0.02f, 0.99f))
                    }
                }
            }
    ) {
        val image = item.image
        when {
            image != null -> {
                val w = with(density) { (item.size * pageW).toDp() }
                val h = with(density) { (item.size * pageW * image.height / image.width).toDp() }
                Image(image.asImageBitmap(), contentDescription = null, modifier = Modifier.size(w, h), contentScale = ContentScale.FillBounds)
            }
            else -> {
                val fontSize = with(density) { (item.size * pageW).toSp() }
                Text(
                    if (item.check) "✔" else item.text,
                    fontSize = fontSize,
                    lineHeight = fontSize * 1.2f,
                    fontFamily = FontFamily.SansSerif,
                    color = Color.Black
                )
            }
        }
        if (selected) {
            // Poignée d'agrandissement : on tire le coin.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(10.dp, 10.dp)
                    .size(22.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .semantics { contentDescription = "Agrandir" }
                    .pointerInput(item.id, pageW) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            state.update(item.id) {
                                val itemWidth = maxOf(24f, it.size * pageW * (if (it.image != null) 1f else maxOf(1, it.text.length) * 0.55f))
                                it.copy(size = (it.size * (1 + drag.x / itemWidth)).coerceIn(0.01f, 0.9f))
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.OpenInFull, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp)) }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(10.dp, (-10).dp)
                    .size(22.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape)
                    .pointerInput(item.id) { detectTapGestures { state.remove(item.id) } }
                    .semantics { contentDescription = "Retirer l'élément" },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp)) }
        }
    }
}

/** Zone où l'on signe au doigt ; la signature devient une image transparente, recadrée au plus près. */
@Composable
private fun SignaturePad(onDismiss: () -> Unit, onDone: (Bitmap) -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val strokeWidth = with(LocalDensity.current) { 3.5.dp.toPx() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ta signature") },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp))
                        .semantics { contentDescription = "Zone de signature" }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { current = listOf(it) },
                                onDragEnd = { if (current.size > 1) strokes.add(current); current = emptyList() },
                                onDragCancel = { current = emptyList() }
                            ) { change, _ ->
                                change.consume()
                                current = current + change.position
                            }
                        }
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        (strokes + listOf(current)).forEach { points ->
                            if (points.size > 1) drawPath(smoothPath(points), INK, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                    if (strokes.isEmpty() && current.isEmpty()) {
                        Text("Signe ici avec le doigt", color = Color.LightGray, modifier = Modifier.align(Alignment.Center))
                    }
                }
                TextButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) { Text("Effacer") }
            }
        },
        confirmButton = {
            TextButton(enabled = strokes.isNotEmpty(), onClick = { signatureBitmap(strokes, strokeWidth)?.let(onDone) }) { Text("Utiliser") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

/** Courbe lissée passant par les milieux des points : un trait de stylo, pas une ligne brisée. */
private fun smoothPath(points: List<Offset>): Path = Path().apply {
    moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val mid = Offset((prev.x + points[i].x) / 2, (prev.y + points[i].y) / 2)
        quadraticBezierTo(prev.x, prev.y, mid.x, mid.y)
    }
    lineTo(points.last().x, points.last().y)
}

/** Les traits dessinés, recadrés au plus près, sur fond transparent. */
internal fun signatureBitmap(strokes: List<List<Offset>>, strokeWidth: Float): Bitmap? {
    val all = strokes.flatten()
    if (all.isEmpty()) return null
    val pad = strokeWidth * 2
    val left = all.minOf { it.x } - pad
    val top = all.minOf { it.y } - pad
    val width = (all.maxOf { it.x } + pad - left).coerceAtLeast(1f)
    val height = (all.maxOf { it.y } + pad - top).coerceAtLeast(1f)
    val bitmap = Bitmap.createBitmap(width.roundToInt(), height.roundToInt(), Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK.toArgb()
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    strokes.filter { it.size > 1 }.forEach { points ->
        val path = android.graphics.Path()
        path.moveTo(points[0].x - left, points[0].y - top)
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            path.quadTo(prev.x - left, prev.y - top, (prev.x + points[i].x) / 2 - left, (prev.y + points[i].y) / 2 - top)
        }
        path.lineTo(points.last().x - left, points.last().y - top)
        canvas.drawPath(path, paint)
    }
    return bitmap
}
