package com.docssuite.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Surface
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.docssuite.core.AppFont
import com.docssuite.core.AppFonts
import com.docssuite.core.ColorPickerDialog
import com.docssuite.core.ConfirmDialog
import com.docssuite.core.DocType
import com.docssuite.core.DocumentStorage
import com.docssuite.core.EditorColors
import com.docssuite.core.FontSizes
import com.docssuite.core.ListPickerDialog
import com.docssuite.core.TextInputDialog
import com.docssuite.core.fontFamilyAt
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
import org.json.JSONArray
import org.json.JSONObject

internal data class Slide(
    val title: String = "",
    val content: String = "",
    val background: Long = 0xFF1E293BL,
    val textColor: Long = 0xFFFFFFFFL,
    val titleSize: Int = 32,
    val contentSize: Int = 20,
    val align: Int = 1,
    val fontIndex: Int = 0,
    val transition: Int = 1,
    val notes: String = ""
)

private val TransitionNames = listOf(
    "Aucune",
    "Fondu",
    "Glissement",
    "Zoom avant",
    "Glissement vertical",
    "Zoom arrière"
)

private fun transitionName(index: Int): String =
    TransitionNames.getOrElse(index) { TransitionNames[0] }

private data class DeckTheme(val label: String, val background: Long, val text: Long)

private val DeckThemes = listOf(
    DeckTheme("Nuit", 0xFF1E293BL, 0xFFFFFFFFL),
    DeckTheme("Clair", 0xFFFFFFFFL, 0xFF111827L),
    DeckTheme("Océan", 0xFF0B4F6CL, 0xFFE0F7FAL),
    DeckTheme("Forêt", 0xFF14532DL, 0xFFECFDF5L),
    DeckTheme("Corail", 0xFF7F1D1DL, 0xFFFFF1F2L),
    DeckTheme("Violet", 0xFF4C1D95L, 0xFFF5F3FFL),
    DeckTheme("Sable", 0xFFFDF6E3L, 0xFF4A3B1FL),
    DeckTheme("Contraste", 0xFF000000L, 0xFFFACC15L)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresentationScreen(onBack: () -> Unit, initialDocId: String? = null) {
    val context = LocalContext.current
    val storage = remember { DocumentStorage(context) }

    val slides = remember {
        mutableStateListOf(
            Slide(title = "Ma présentation", content = "Appuie sur ▶ pour lancer le diaporama"),
            Slide(title = "Deuxième slide", content = "Clique à droite pour avancer,\nà gauche pour reculer")
        )
    }
    var docId by remember { mutableStateOf(storage.newId()) }
    var docName by remember { mutableStateOf("Présentation sans titre") }

    var playing by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showOpen by remember { mutableStateOf(false) }
    var showThemes by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()

    // Format retenu le temps que l'utilisateur choisisse où ranger le fichier.
    var pendingFormat by remember { mutableStateOf(FileFormat.PPTX) }
    var colorTargetIndex by remember { mutableStateOf(-1) }
    var colorTargetIsBackground by remember { mutableStateOf(true) }
    var fontTargetIndex by remember { mutableStateOf(-1) }
    var sizeTargetIndex by remember { mutableStateOf(-1) }
    var transitionTargetIndex by remember { mutableStateOf(-1) }
    var showAllTransitions by remember { mutableStateOf(false) }

    fun deckAsText(): String = slides.mapIndexed { index, slide ->
        "— Slide ${index + 1} —\n${slide.title}\n${slide.content}"
    }.joinToString("\n\n")

    fun openDocument(id: String, name: String) {
        val decoded = storage.load(id)?.let { decodeDeck(it) } ?: return
        if (decoded.isEmpty()) return
        slides.clear()
        slides.addAll(decoded)
        docId = id
        docName = name
    }

    LaunchedEffect(initialDocId) {
        if (initialDocId != null) {
            storage.list(DocType.DECK).firstOrNull { it.id == initialDocId }
                ?.let { openDocument(it.id, it.name) }
        }
    }

    fun currentDeck() = buildDeck(docName, slides.toList())

    val fileSaver = rememberFileSaver(
        onError = { notice = "Enregistrement impossible" to it },
        onSaved = { notice = "Fichier enregistré" to "« $it » est disponible à l'emplacement choisi." },
        content = { FileFormats.exportDeck(pendingFormat, currentDeck()) }
    )

    val fileOpener = rememberFileOpener(
        onError = { notice = "Import impossible" to it }
    ) { picked ->
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { FileFormats.import(picked.name, picked.bytes) }
            }
            result
                .onSuccess { imported ->
                    if (imported is Imported.AsDeck) {
                        val decoded = decodeDeckModel(imported.deck)
                        if (decoded.isEmpty()) {
                            notice = "Présentation vide" to
                                "« ${picked.name} » ne contient aucune diapositive lisible."
                        } else {
                            slides.clear()
                            slides.addAll(decoded)
                            // Un import devient une nouvelle présentation : on ne
                            // veut pas écraser celle qui était ouverte.
                            docId = storage.newId()
                            docName = imported.suggestedName
                        }
                    } else {
                        notice = "Ce n'est pas une présentation" to
                            "« ${picked.name} » est un document, un tableur ou un PDF. " +
                            "Ouvrez-le depuis l'accueil."
                    }
                }
                .onFailure { notice = "Import impossible" to (it.message ?: "Fichier illisible") }
        }
    }

    fun export(format: FileFormat, action: ExportAction) {
        showExport = false
        pendingFormat = format
        val fileName = safeFileName(docName, format)
        when (action) {
            ExportAction.SAVE -> fileSaver.save(fileName, format.mime)
            ExportAction.SHARE -> scope.launch {
                val deck = currentDeck()
                val produced = withContext(Dispatchers.IO) {
                    runCatching { FileFormats.exportDeck(format, deck) }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable { showRename = true }) {
                        Text(docName, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${slides.size} slide${if (slides.size > 1) "s" else ""}",
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
                    IconButton(onClick = { if (slides.isNotEmpty()) playing = true }) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Lancer le diaporama",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = {
                        storage.save(docId, docName, DocType.DECK, encodeDeck(slides))
                        notice = "Présentation enregistrée" to
                            "« $docName » a bien été enregistrée dans l'application."
                    }) {
                        Icon(Icons.Filled.Save, contentDescription = "Enregistrer")
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Nouvelle présentation") },
                                onClick = {
                                    showMenu = false
                                    slides.clear()
                                    slides.add(Slide(title = "Nouvelle présentation"))
                                    docId = storage.newId()
                                    docName = "Présentation sans titre"
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
                                text = { Text("Appliquer un thème") },
                                onClick = { showMenu = false; showThemes = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Transition pour toutes les slides") },
                                onClick = { showMenu = false; showAllTransitions = true }
                            )
                            Divider()
                            DropdownMenuItem(
                                text = { Text("Importer un fichier…") },
                                leadingIcon = {
                                    Icon(Icons.Filled.FileOpen, contentDescription = null)
                                },
                                onClick = {
                                    showMenu = false
                                    fileOpener.open(importMimeTypes(DocKind.DECK))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Exporter (PowerPoint, PDF…)") },
                                leadingIcon = {
                                    Icon(Icons.Filled.FileDownload, contentDescription = null)
                                },
                                onClick = { showMenu = false; showExport = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Partager le texte") },
                                onClick = { showMenu = false; shareText(context, docName, deckAsText()) }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    val model = slides.lastOrNull() ?: Slide()
                    slides.add(
                        Slide(
                            background = model.background,
                            textColor = model.textColor,
                            titleSize = model.titleSize,
                            contentSize = model.contentSize,
                            align = model.align,
                            fontIndex = model.fontIndex
                        )
                    )
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Slide") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(slides.size) { index ->
                SlideEditorCard(
                    index = index,
                    slide = slides[index],
                    total = slides.size,
                    onChange = { slides[index] = it },
                    onMoveUp = {
                        if (index > 0) {
                            val slide = slides.removeAt(index)
                            slides.add(index - 1, slide)
                        }
                    },
                    onMoveDown = {
                        if (index < slides.size - 1) {
                            val slide = slides.removeAt(index)
                            slides.add(index + 1, slide)
                        }
                    },
                    onDuplicate = { slides.add(index + 1, slides[index].copy()) },
                    onDelete = { if (slides.size > 1) slides.removeAt(index) },
                    onPickBackground = { colorTargetIndex = index; colorTargetIsBackground = true },
                    onPickTextColor = { colorTargetIndex = index; colorTargetIsBackground = false },
                    onPickFont = { fontTargetIndex = index },
                    onPickSize = { sizeTargetIndex = index },
                    onPickTransition = { transitionTargetIndex = index }
                )
            }
        }
    }

    if (playing) {
        SlideShow(slides = slides.toList(), onExit = { playing = false })
    }

    if (colorTargetIndex >= 0) {
        val index = colorTargetIndex
        ColorPickerDialog(
            title = if (colorTargetIsBackground) "Couleur de fond" else "Couleur du texte",
            colors = EditorColors,
            onPick = { color ->
                if (color != null && index < slides.size) {
                    val argb = colorToLong(color)
                    slides[index] = if (colorTargetIsBackground) {
                        slides[index].copy(background = argb)
                    } else {
                        slides[index].copy(textColor = argb)
                    }
                }
                colorTargetIndex = -1
            },
            onDismiss = { colorTargetIndex = -1 }
        )
    }

    if (fontTargetIndex >= 0) {
        val index = fontTargetIndex
        ListPickerDialog(
            title = "Police de la slide",
            items = AppFonts,
            label = { it.label },
            selected = AppFonts.getOrNull(slides.getOrNull(index)?.fontIndex ?: 0),
            itemContent = { font: AppFont ->
                Text(font.label, fontSize = 18.sp, style = TextStyle(fontFamily = font.family))
            },
            onPick = { font ->
                if (index < slides.size) {
                    slides[index] = slides[index].copy(fontIndex = AppFonts.indexOf(font))
                }
                fontTargetIndex = -1
            },
            onDismiss = { fontTargetIndex = -1 }
        )
    }

    if (sizeTargetIndex >= 0) {
        val index = sizeTargetIndex
        ListPickerDialog(
            title = "Taille du titre",
            items = FontSizes.filter { it >= 16 },
            label = { "$it pt" },
            selected = slides.getOrNull(index)?.titleSize,
            onPick = { size ->
                if (index < slides.size) {
                    slides[index] = slides[index].copy(
                        titleSize = size,
                        contentSize = (size * 0.62f).toInt().coerceAtLeast(12)
                    )
                }
                sizeTargetIndex = -1
            },
            onDismiss = { sizeTargetIndex = -1 }
        )
    }

    if (transitionTargetIndex >= 0) {
        val index = transitionTargetIndex
        ListPickerDialog(
            title = "Transition de la slide ${index + 1}",
            items = TransitionNames,
            label = { it },
            selected = TransitionNames.getOrNull(slides.getOrNull(index)?.transition ?: 0),
            onPick = { name ->
                if (index < slides.size) {
                    slides[index] = slides[index].copy(transition = TransitionNames.indexOf(name))
                }
                transitionTargetIndex = -1
            },
            onDismiss = { transitionTargetIndex = -1 }
        )
    }

    if (showAllTransitions) {
        ListPickerDialog(
            title = "Transition pour toutes les slides",
            items = TransitionNames,
            label = { it },
            onPick = { name ->
                val kind = TransitionNames.indexOf(name)
                for (i in slides.indices) {
                    slides[i] = slides[i].copy(transition = kind)
                }
                showAllTransitions = false
            },
            onDismiss = { showAllTransitions = false }
        )
    }

    if (showThemes) {
        ListPickerDialog(
            title = "Thème de la présentation",
            items = DeckThemes,
            label = { it.label },
            itemContent = { theme: DeckTheme ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(Color(theme.background), CircleShape)
                            .border(1.dp, Color(0x33000000), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(theme.label)
                }
            },
            onPick = { theme ->
                for (i in slides.indices) {
                    slides[i] = slides[i].copy(background = theme.background, textColor = theme.text)
                }
                showThemes = false
            },
            onDismiss = { showThemes = false }
        )
    }

    if (showRename) {
        TextInputDialog(
            title = "Renommer la présentation",
            initialValue = docName,
            onConfirm = { docName = it; showRename = false },
            onDismiss = { showRename = false }
        )
    }

    if (showOpen) {
        val documents = storage.list(DocType.DECK)
        ListPickerDialog(
            title = if (documents.isEmpty()) "Aucune présentation enregistrée" else "Ouvrir une présentation",
            items = documents,
            label = { it.name },
            onPick = { meta ->
                openDocument(meta.id, meta.name)
                showOpen = false
            },
            onDismiss = { showOpen = false }
        )
    }

    if (showExport) {
        ExportFormatDialog(
            kind = DocKind.DECK,
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
private fun SlideEditorCard(
    index: Int,
    slide: Slide,
    total: Int,
    onChange: (Slide) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onPickBackground: () -> Unit,
    onPickTextColor: () -> Unit,
    onPickFont: () -> Unit,
    onPickSize: () -> Unit,
    onPickTransition: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column {
            // Aperçu de la slide
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(Color(slide.background))
                    .padding(12.dp),
                contentAlignment = when (slide.align) {
                    0 -> Alignment.CenterStart
                    2 -> Alignment.CenterEnd
                    else -> Alignment.Center
                }
            ) {
                Column(horizontalAlignment = alignmentFor(slide.align)) {
                    Text(
                        slide.title.ifBlank { "Titre de la slide" },
                        color = Color(slide.textColor),
                        fontSize = (slide.titleSize * 0.5f).sp,
                        fontWeight = FontWeight.Bold,
                        style = TextStyle(fontFamily = fontFamilyAt(slide.fontIndex)),
                        textAlign = textAlignFor(slide.align),
                        maxLines = 2
                    )
                    if (slide.content.isNotBlank()) {
                        Text(
                            slide.content,
                            color = Color(slide.textColor).copy(alpha = 0.85f),
                            fontSize = (slide.contentSize * 0.5f).sp,
                            style = TextStyle(fontFamily = fontFamilyAt(slide.fontIndex)),
                            textAlign = textAlignFor(slide.align),
                            maxLines = 3
                        )
                    }
                }
                Text(
                    "${index + 1}/$total",
                    modifier = Modifier.align(Alignment.TopEnd),
                    color = Color(slide.textColor).copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
                Text(
                    transitionName(slide.transition),
                    modifier = Modifier.align(Alignment.TopStart),
                    color = Color(slide.textColor).copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
            }

            Column(modifier = Modifier.padding(12.dp)) {
                OutlinedTextField(
                    value = slide.title,
                    onValueChange = { onChange(slide.copy(title = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Titre") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = slide.content,
                    onValueChange = { onChange(slide.copy(content = it)) },
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                    label = { Text("Contenu") }
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = slide.notes,
                    onValueChange = { onChange(slide.copy(notes = it)) },
                    modifier = Modifier.fillMaxWidth().height(90.dp),
                    label = { Text("Notes du présentateur") },
                    supportingText = { Text("Visibles pendant le diaporama, jamais projetées") }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onPickBackground) {
                        Icon(Icons.Filled.FormatColorFill, contentDescription = "Couleur de fond")
                    }
                    IconButton(onClick = onPickTextColor) {
                        Icon(Icons.Filled.FormatColorText, contentDescription = "Couleur du texte")
                    }
                    IconButton(onClick = onPickFont) {
                        Icon(Icons.Filled.TextFields, contentDescription = "Police")
                    }
                    IconButton(onClick = onPickSize) {
                        Icon(Icons.Filled.FormatAlignCenter, contentDescription = "Taille")
                    }
                    IconButton(onClick = { onChange(slide.copy(align = (slide.align + 1) % 3)) }) {
                        Icon(
                            Icons.Filled.FormatAlignCenter,
                            contentDescription = "Alignement",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onPickTransition) {
                        Icon(Icons.Filled.Animation, contentDescription = "Transition")
                    }
                    IconButton(onClick = onMoveUp, enabled = index > 0) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = "Monter")
                    }
                    IconButton(onClick = onMoveDown, enabled = index < total - 1) {
                        Icon(Icons.Filled.ArrowDownward, contentDescription = "Descendre")
                    }
                    IconButton(onClick = onDuplicate) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Dupliquer")
                    }
                    IconButton(onClick = onDelete, enabled = total > 1) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Supprimer",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

private fun transitionFor(kind: Int, forward: Boolean): ContentTransform {
    val spec = tween<Float>(durationMillis = 420)
    val slideSpec = tween<IntOffset>(durationMillis = 420)
    return when (kind) {
        1 -> fadeIn(spec) togetherWith fadeOut(spec)
        2 -> (slideInHorizontally(slideSpec) { width -> if (forward) width else -width } + fadeIn(spec)) togetherWith
            (slideOutHorizontally(slideSpec) { width -> if (forward) -width else width } + fadeOut(spec))
        3 -> (scaleIn(spec, initialScale = 0.75f) + fadeIn(spec)) togetherWith
            (scaleOut(spec, targetScale = 1.2f) + fadeOut(spec))
        4 -> (slideInVertically(slideSpec) { height -> if (forward) height else -height } + fadeIn(spec)) togetherWith
            (slideOutVertically(slideSpec) { height -> if (forward) -height else height } + fadeOut(spec))
        5 -> (scaleIn(spec, initialScale = 1.25f) + fadeIn(spec)) togetherWith
            (scaleOut(spec, targetScale = 0.8f) + fadeOut(spec))
        else -> fadeIn(tween(1)) togetherWith fadeOut(tween(1))
    }
}

/** Diaporama plein écran : clic à droite = slide suivante, à gauche = précédente. */
@Composable
private fun SlideShow(slides: List<Slide>, onExit: () -> Unit) {
    var index by remember { mutableStateOf(0) }
    var forward by remember { mutableStateOf(true) }
    var showNotes by remember { mutableStateOf(false) }
    val slide = slides.getOrNull(index) ?: return

    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(slide.background))
                .pointerInput(slides.size) {
                    detectTapGestures { offset ->
                        if (offset.x > size.width / 2) {
                            if (index < slides.size - 1) {
                                forward = true
                                index++
                            } else {
                                onExit()
                            }
                        } else if (index > 0) {
                            forward = false
                            index--
                        }
                    }
                }
        ) {
            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    transitionFor(slides.getOrNull(targetState)?.transition ?: 1, forward)
                },
                label = "slide"
            ) { slideIndex ->
                val current = slides.getOrNull(slideIndex) ?: return@AnimatedContent
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(current.background))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 32.dp, vertical = 64.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = alignmentFor(current.align)
                    ) {
                        Text(
                            current.title,
                            color = Color(current.textColor),
                            fontSize = current.titleSize.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = textAlignFor(current.align),
                            style = TextStyle(fontFamily = fontFamilyAt(current.fontIndex)),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (current.content.isNotBlank()) {
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                current.content,
                                color = Color(current.textColor).copy(alpha = 0.9f),
                                fontSize = current.contentSize.sp,
                                textAlign = textAlignFor(current.align),
                                style = TextStyle(fontFamily = fontFamilyAt(current.fontIndex)),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            Row(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                if (slides.any { it.notes.isNotBlank() }) {
                    IconButton(onClick = { showNotes = !showNotes }) {
                        Icon(
                            Icons.Filled.Notes,
                            contentDescription = if (showNotes) "Masquer les notes" else "Afficher les notes",
                            tint = Color(slide.textColor)
                                .copy(alpha = if (showNotes) 1f else 0.7f)
                        )
                    }
                }
                IconButton(onClick = onExit) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Quitter le diaporama",
                        tint = Color(slide.textColor).copy(alpha = 0.7f)
                    )
                }
            }

            // Les notes se posent par-dessus la diapositive, sur un fond opaque :
            // elles ne doivent jamais se confondre avec le contenu projeté.
            if (showNotes && slide.notes.isNotBlank()) {
                Surface(
                    color = Color(0xE6101828),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 20.dp, end = 20.dp, bottom = 56.dp)
                        .fillMaxWidth(0.9f)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            "Notes",
                            color = Color(0xFF9CA3AF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            slide.notes,
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .heightIn(max = 160.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                slides.indices.forEach { i ->
                    Box(
                        modifier = Modifier
                            .size(if (i == index) 10.dp else 7.dp)
                            .background(
                                Color(slide.textColor).copy(alpha = if (i == index) 0.95f else 0.35f),
                                CircleShape
                            )
                    )
                }
            }

            Text(
                "${index + 1} / ${slides.size}",
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                color = Color(slide.textColor).copy(alpha = 0.6f),
                fontSize = 13.sp
            )
        }
    }
}

private fun alignmentFor(align: Int): Alignment.Horizontal = when (align) {
    0 -> Alignment.Start
    2 -> Alignment.End
    else -> Alignment.CenterHorizontally
}

private fun textAlignFor(align: Int): TextAlign = when (align) {
    0 -> TextAlign.Start
    2 -> TextAlign.End
    else -> TextAlign.Center
}

internal fun encodeDeck(slides: List<Slide>): String {
    val array = JSONArray()
    slides.forEach { slide ->
        array.put(JSONObject().apply {
            put("title", slide.title)
            put("content", slide.content)
            put("bg", slide.background)
            put("fg", slide.textColor)
            put("ts", slide.titleSize)
            put("cs", slide.contentSize)
            put("al", slide.align)
            put("fi", slide.fontIndex)
            put("tr", slide.transition)
            put("nt", slide.notes)
        })
    }
    return JSONObject().apply { put("slides", array) }.toString()
}

internal fun decodeDeck(payload: String): List<Slide> {
    val array = JSONObject(payload).optJSONArray("slides") ?: return emptyList()
    val out = ArrayList<Slide>()
    for (i in 0 until array.length()) {
        val o = array.getJSONObject(i)
        out.add(
            Slide(
                title = o.optString("title"),
                content = o.optString("content"),
                background = o.optLong("bg", 0xFF1E293BL),
                textColor = o.optLong("fg", 0xFFFFFFFFL),
                titleSize = o.optInt("ts", 32),
                contentSize = o.optInt("cs", 20),
                align = o.optInt("al", 1),
                fontIndex = o.optInt("fi", 0),
                transition = o.optInt("tr", 1),
                notes = o.optString("nt")
            )
        )
    }
    return out
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
