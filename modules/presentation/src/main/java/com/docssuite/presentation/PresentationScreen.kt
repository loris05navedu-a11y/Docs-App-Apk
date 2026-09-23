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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.key
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
import com.docssuite.fileformats.SlideLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    val notes: String = "",
    /** Voir [SlideLayout]. */
    val layout: Int = SlideLayout.TITLE_AND_CONTENT,
    val secondContent: String = "",
    val bullets: Boolean = false
)

private val LayoutNames = listOf("Titre et contenu", "Section", "Deux colonnes")

private data class DeckSnapshot(val slides: List<Slide>, val settings: DeckSettings)

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
    var settings by remember { mutableStateOf(DeckSettings()) }
    var showFooter by remember { mutableStateOf(false) }

    val undoStack = remember { mutableListOf<DeckSnapshot>() }
    val redoStack = remember { mutableListOf<DeckSnapshot>() }
    var historyVersion by remember { mutableStateOf(0) }
    // Taper du texte crée une étape par modification ; on les regroupe par
    // diapositive et par champ tant que l'utilisateur écrit au même endroit.
    var lastEdit by remember { mutableStateOf("") }

    fun pushUndo(editKey: String = "") {
        if (editKey.isNotEmpty() && editKey == lastEdit) return
        lastEdit = editKey
        undoStack.add(DeckSnapshot(slides.toList(), settings))
        if (undoStack.size > 80) undoStack.removeAt(0)
        redoStack.clear()
        historyVersion++
    }

    fun restore(snapshot: DeckSnapshot) {
        slides.clear(); slides.addAll(snapshot.slides)
        settings = snapshot.settings
        lastEdit = ""
        historyVersion++
    }

    fun resetHistory() {
        undoStack.clear(); redoStack.clear(); lastEdit = ""; historyVersion++
    }

    fun deckAsText(): String = slides.mapIndexed { index, slide ->
        "— Slide ${index + 1} —\n${slide.title}\n${slide.content}" +
            if (slide.secondContent.isNotBlank()) "\n${slide.secondContent}" else ""
    }.joinToString("\n\n")

    fun openDocument(id: String, name: String) {
        val payload = storage.load(id) ?: return
        val decoded = runCatching { decodeDeck(payload) }.getOrNull()
        if (decoded.isNullOrEmpty()) {
            notice = "Ouverture impossible" to "« $name » est endommagée."
            return
        }
        slides.clear()
        slides.addAll(decoded)
        settings = decodeSettings(payload)
        docId = id
        docName = name
        resetHistory()
    }

    LaunchedEffect(initialDocId) {
        if (initialDocId != null) {
            storage.list(DocType.DECK).firstOrNull { it.id == initialDocId }
                ?.let { openDocument(it.id, it.name) }
        }
    }

    fun currentDeck() = buildDeck(docName, slides.toList(), settings)

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
                            settings = settingsOf(imported.deck)
                            // Un import devient une nouvelle présentation : on ne
                            // veut pas écraser celle qui était ouverte.
                            docId = storage.newId()
                            docName = imported.suggestedName
                            resetHistory()
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
                    key(historyVersion) {
                        IconButton(onClick = {
                            undoStack.removeLastOrNull()?.let { previous ->
                                redoStack.add(DeckSnapshot(slides.toList(), settings))
                                restore(previous)
                            }
                        }, enabled = undoStack.isNotEmpty()) {
                            Icon(Icons.Filled.Undo, contentDescription = "Annuler")
                        }
                        IconButton(onClick = {
                            redoStack.removeLastOrNull()?.let { next ->
                                undoStack.add(DeckSnapshot(slides.toList(), settings))
                                restore(next)
                            }
                        }, enabled = redoStack.isNotEmpty()) {
                            Icon(Icons.Filled.Redo, contentDescription = "Rétablir")
                        }
                    }
                    IconButton(onClick = {
                        storage.save(docId, docName, DocType.DECK, encodeDeck(slides, settings))
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
                                    slides.add(Slide(title = "Nouvelle présentation", layout = SlideLayout.SECTION))
                                    settings = DeckSettings()
                                    docId = storage.newId()
                                    docName = "Présentation sans titre"
                                    resetHistory()
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
                            DropdownMenuItem(
                                text = { Text("Pied de page et numéros…") },
                                onClick = { showMenu = false; showFooter = true }
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
                    pushUndo()
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
                    settings = settings,
                    onChange = { updated ->
                        val before = slides[index]
                        // Une frappe dans un champ ne crée qu'une étape d'annulation
                        // tant qu'on reste dans ce champ ; tout autre réglage en crée une.
                        val field = when {
                            updated.copy(title = before.title) == before -> "t$index"
                            updated.copy(content = before.content) == before -> "c$index"
                            updated.copy(secondContent = before.secondContent) == before -> "s$index"
                            updated.copy(notes = before.notes) == before -> "n$index"
                            else -> ""
                        }
                        pushUndo(field)
                        slides[index] = updated
                    },
                    onMoveUp = {
                        pushUndo()
                        if (index > 0) {
                            val slide = slides.removeAt(index)
                            slides.add(index - 1, slide)
                        }
                    },
                    onMoveDown = {
                        pushUndo()
                        if (index < slides.size - 1) {
                            val slide = slides.removeAt(index)
                            slides.add(index + 1, slide)
                        }
                    },
                    onDuplicate = { pushUndo(); slides.add(index + 1, slides[index].copy()) },
                    onDelete = { if (slides.size > 1) { pushUndo(); slides.removeAt(index) } },
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
        SlideShow(slides = slides.toList(), settings = settings, onExit = { playing = false })
    }

    if (colorTargetIndex >= 0) {
        val index = colorTargetIndex
        ColorPickerDialog(
            title = if (colorTargetIsBackground) "Couleur de fond" else "Couleur du texte",
            colors = EditorColors,
            onPick = { color ->
                if (color != null && index < slides.size) {
                    pushUndo()
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
                    pushUndo()
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
                    pushUndo()
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
                pushUndo()
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
                pushUndo()
                for (i in slides.indices) {
                    slides[i] = slides[i].copy(background = theme.background, textColor = theme.text)
                }
                showThemes = false
            },
            onDismiss = { showThemes = false }
        )
    }

    if (showFooter) {
        FooterDialog(
            initial = settings,
            onConfirm = {
                pushUndo()
                settings = it
                showFooter = false
            },
            onDismiss = { showFooter = false }
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
    settings: DeckSettings,
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
            // Aperçu de la slide, en 16:9 comme à la projection.
            Box {
                SlideCanvas(
                    slide = slide,
                    settings = settings,
                    number = index + 1,
                    scale = 0.5f,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                )
                Text(
                    "${index + 1}/$total",
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    color = Color(slide.textColor).copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
                Text(
                    transitionName(slide.transition),
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    color = Color(slide.textColor).copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
            }

            Column(modifier = Modifier.padding(12.dp)) {
                // Mise en page.
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LayoutNames.forEachIndexed { layout, name ->
                        FilterChip(
                            selected = slide.layout == layout,
                            onClick = { onChange(slide.copy(layout = layout)) },
                            label = { Text(name) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
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
                    label = {
                        Text(
                            when (slide.layout) {
                                SlideLayout.SECTION -> "Sous-titre"
                                SlideLayout.TWO_COLUMNS -> "Colonne de gauche"
                                else -> "Contenu"
                            }
                        )
                    }
                )
                if (slide.layout == SlideLayout.TWO_COLUMNS) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = slide.secondContent,
                        onValueChange = { onChange(slide.copy(secondContent = it)) },
                        modifier = Modifier.fillMaxWidth().height(110.dp),
                        label = { Text("Colonne de droite") }
                    )
                }
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
                    IconButton(onClick = { onChange(slide.copy(bullets = !slide.bullets)) }, enabled = slide.layout != SlideLayout.SECTION) {
                        Icon(
                            Icons.Filled.FormatListBulleted,
                            contentDescription = if (slide.bullets) "Retirer les puces" else "Liste à puces",
                            tint = if (slide.bullets) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                        Icon(Icons.Filled.FormatSize, contentDescription = "Taille")
                    }
                    IconButton(onClick = { onChange(slide.copy(align = (slide.align + 1) % 3)) }) {
                        Icon(
                            when (slide.align) {
                                0 -> Icons.Filled.FormatAlignLeft
                                2 -> Icons.Filled.FormatAlignRight
                                else -> Icons.Filled.FormatAlignCenter
                            },
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

/** Le texte d'un corps, avec une puce devant chaque ligne si demandé. */
private fun bodyText(text: String, bullets: Boolean): String =
    if (!bullets) text else text.lines().joinToString("\n") { if (it.isBlank()) it else "•  $it" }

/**
 * Une diapositive dessinée, identique dans l'aperçu, le diaporama et la vue
 * présentateur : [scale] règle la taille des textes (1 = projection).
 */
@Composable
private fun SlideCanvas(
    slide: Slide,
    settings: DeckSettings,
    number: Int,
    scale: Float,
    modifier: Modifier = Modifier
) {
    val family = TextStyle(fontFamily = fontFamilyAt(slide.fontIndex))
    val color = Color(slide.textColor)
    val section = slide.layout == SlideLayout.SECTION
    val align = if (section) 1 else slide.align
    Box(modifier = modifier.background(Color(slide.background))) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = (32 * scale * 1.5f).dp, vertical = (40 * scale * 1.5f).dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = alignmentFor(align)
        ) {
            if (slide.title.isNotBlank() || scale < 1f) {
                Text(
                    slide.title.ifBlank { "Titre de la slide" },
                    color = if (slide.title.isBlank()) color.copy(alpha = 0.45f) else color,
                    fontSize = (slide.titleSize * scale * if (section) 1.25f else 1f).sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = textAlignFor(align),
                    style = family,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            val hasBody = slide.content.isNotBlank() ||
                (slide.layout == SlideLayout.TWO_COLUMNS && slide.secondContent.isNotBlank())
            if (hasBody) {
                Spacer(modifier = Modifier.height((20 * scale).dp))
                if (slide.layout == SlideLayout.TWO_COLUMNS) {
                    Row(horizontalArrangement = Arrangement.spacedBy((24 * scale).dp)) {
                        listOf(slide.content, slide.secondContent).forEach { column ->
                            Text(
                                bodyText(column, slide.bullets),
                                color = color.copy(alpha = 0.92f),
                                fontSize = (slide.contentSize * scale).sp,
                                textAlign = textAlignFor(slide.align),
                                style = family,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                } else {
                    Text(
                        bodyText(slide.content, slide.bullets && !section),
                        color = color.copy(alpha = if (section) 0.75f else 0.92f),
                        fontSize = (slide.contentSize * scale).sp,
                        textAlign = textAlignFor(align),
                        style = family,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        if (settings.footer.isNotBlank()) {
            Text(
                settings.footer,
                modifier = Modifier.align(Alignment.BottomStart).padding((16 * scale * 1.5f).dp),
                color = color.copy(alpha = 0.65f),
                fontSize = (13 * scale * 1.3f).sp,
                maxLines = 1
            )
        }
        if (settings.slideNumbers) {
            Text(
                "$number",
                modifier = Modifier.align(Alignment.BottomEnd).padding((16 * scale * 1.5f).dp),
                color = color.copy(alpha = 0.65f),
                fontSize = (13 * scale * 1.3f).sp
            )
        }
    }
}

@Composable
private fun FooterDialog(initial: DeckSettings, onConfirm: (DeckSettings) -> Unit, onDismiss: () -> Unit) {
    var footer by remember { mutableStateOf(initial.footer) }
    var numbers by remember { mutableStateOf(initial.slideNumbers) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pied de page et numéros") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = footer,
                    onValueChange = { footer = it },
                    label = { Text("Texte du pied de page") },
                    placeholder = { Text("Ex. : Réunion du 12 mars") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { numbers = !numbers }
                ) {
                    Checkbox(checked = numbers, onCheckedChange = { numbers = it })
                    Text("Numéroter les diapositives")
                }
                Text(
                    "Ils apparaissent sur toutes les diapositives, et passent dans PowerPoint, OpenDocument et PDF.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(DeckSettings(footer.trim(), numbers)) }) { Text("Appliquer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
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

/**
 * Diaporama plein écran : toucher à droite avance, à gauche recule. Le mode
 * présentateur ajoute, sur l'appareil, un chronomètre, la diapositive
 * suivante et les notes.
 */
@Composable
private fun SlideShow(slides: List<Slide>, settings: DeckSettings, onExit: () -> Unit) {
    var index by remember { mutableStateOf(0) }
    var forward by remember { mutableStateOf(true) }
    var showNotes by remember { mutableStateOf(false) }
    var presenter by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0L) }
    var startedAt by remember { mutableStateOf(System.currentTimeMillis()) }
    val slide = slides.getOrNull(index) ?: return

    // Le chronomètre ne tourne que lorsqu'on l'affiche.
    LaunchedEffect(startedAt, presenter) {
        if (!presenter) return@LaunchedEffect
        while (true) {
            elapsed = (System.currentTimeMillis() - startedAt) / 1000
            delay(500)
        }
    }

    fun next() {
        if (index < slides.size - 1) {
            forward = true
            index++
        } else {
            onExit()
        }
    }

    fun previous() {
        if (index > 0) {
            forward = false
            index--
        }
    }

    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(slide.background))
                    .pointerInput(slides.size) {
                        detectTapGestures { offset ->
                            if (offset.x > size.width / 2) next() else previous()
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
                    SlideCanvas(
                        slide = current,
                        settings = settings,
                        number = slideIndex + 1,
                        scale = 1f,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Row(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                    IconButton(onClick = { presenter = !presenter }) {
                        Icon(
                            Icons.Filled.Timer,
                            contentDescription = if (presenter) "Quitter le mode présentateur" else "Mode présentateur",
                            tint = Color(slide.textColor).copy(alpha = if (presenter) 1f else 0.7f)
                        )
                    }
                    if (!presenter && slides.any { it.notes.isNotBlank() }) {
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
                if (!presenter && showNotes && slide.notes.isNotBlank()) {
                    Surface(
                        color = Color(0xE6101828),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(start = 20.dp, end = 20.dp, bottom = 56.dp)
                            .fillMaxWidth(0.9f)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("Notes", color = Color(0xFF9CA3AF), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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

                if (!presenter) {
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
                }
            }

            if (presenter) {
                PresenterPanel(
                    slides = slides,
                    index = index,
                    settings = settings,
                    elapsed = elapsed,
                    onPrevious = ::previous,
                    onNext = ::next,
                    onResetTimer = { startedAt = System.currentTimeMillis(); elapsed = 0 }
                )
            }
        }
    }
}

/** Vue présentateur : chronomètre, diapositive suivante, notes et navigation. */
@Composable
private fun PresenterPanel(
    slides: List<Slide>,
    index: Int,
    settings: DeckSettings,
    elapsed: Long,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onResetTimer: () -> Unit
) {
    Surface(color = Color(0xFF111827), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "%02d:%02d".format(elapsed / 60, elapsed % 60),
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onResetTimer) { Text("Remettre à zéro", color = Color(0xFF93C5FD)) }
                Spacer(modifier = Modifier.weight(1f))
                Text("${index + 1} / ${slides.size}", color = Color(0xFFD1D5DB), fontSize = 15.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.heightIn(max = 180.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Suivante", color = Color(0xFF9CA3AF), fontSize = 11.sp)
                    val next = slides.getOrNull(index + 1)
                    if (next != null) {
                        SlideCanvas(
                            slide = next,
                            settings = settings,
                            number = index + 2,
                            scale = 0.3f,
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        )
                    } else {
                        Text("Fin du diaporama", color = Color.White, fontSize = 14.sp)
                    }
                }
                Column(modifier = Modifier.weight(1.4f)) {
                    Text("Notes", color = Color(0xFF9CA3AF), fontSize = 11.sp)
                    Text(
                        slides[index].notes.ifBlank { "Aucune note pour cette diapositive." },
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPrevious, enabled = index > 0) { Text("◀ Précédente", color = Color.White) }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onNext) { Text(if (index < slides.size - 1) "Suivante ▶" else "Terminer", color = Color.White) }
            }
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

internal fun encodeDeck(slides: List<Slide>, settings: DeckSettings = DeckSettings()): String {
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
            put("ly", slide.layout)
            put("c2", slide.secondContent)
            put("bu", slide.bullets)
        })
    }
    return JSONObject().apply {
        put("slides", array)
        put("footer", settings.footer)
        put("numbers", settings.slideNumbers)
    }.toString()
}

internal fun decodeSettings(payload: String): DeckSettings = runCatching {
    val root = JSONObject(payload)
    DeckSettings(root.optString("footer"), root.optBoolean("numbers"))
}.getOrDefault(DeckSettings())

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
                notes = o.optString("nt"),
                layout = o.optInt("ly", 0).coerceIn(0, 2),
                secondContent = o.optString("c2"),
                bullets = o.optBoolean("bu", false)
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
