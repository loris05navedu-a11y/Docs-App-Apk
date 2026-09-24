package com.docssuite.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.docssuite.core.DocMeta
import com.docssuite.core.DocType
import com.docssuite.core.DocumentStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/** Une ligne du texte et les phrases qu'elle contient. */
private class Line(val start: Int, val end: Int, val sentences: List<Int>)

/**
 * Lecture à voix haute d'un document, d'un fichier (PDF, Word…) ou d'un
 * texte collé : phrase par phrase, la phrase lue surlignée, reprise là
 * où on s'était arrêté.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(onBack: () -> Unit, speakerFactory: (Context) -> Speaker = ::TtsSpeaker) {
    val context = LocalContext.current
    val state = remember { ReaderState(speakerFactory(context), ReaderPositions(context)) }
    DisposableEffect(Unit) { onDispose { state.release() } }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun report(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    // L'écran reste allumé pendant la lecture, pour suivre le texte.
    val view = LocalView.current
    DisposableEffect(state.playing) {
        view.keepScreenOn = state.playing
        onDispose { view.keepScreenOn = false }
    }

    val back = {
        if (state.loaded) state.unload() else onBack()
    }
    BackHandler(enabled = state.loaded) { state.unload() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.loaded) state.title else "Lecture à voix haute",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.Filled.ArrowBack, contentDescription = "Retour") } }
            )
        },
        bottomBar = { if (state.loaded) PlayerBar(state) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.voiceProblem?.let { VoiceProblem(it) }
            if (state.loaded) {
                ReadingView(state)
            } else {
                SourcePicker(state, ::report)
            }
        }
    }
}

@Composable
private fun VoiceProblem(problem: String) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(problem, fontWeight = FontWeight.SemiBold)
            Text(
                "Installe la voix française (gratuite, fonctionne ensuite sans Internet) dans les réglages de synthèse vocale.",
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = { openVoiceSettings(context, install = true) }) { Text("Installer une voix") }
        }
    }
}

private fun openVoiceSettings(context: Context, install: Boolean) {
    val intents = buildList {
        if (install) add(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        add(Intent("com.android.settings.TTS_SETTINGS"))
        add(Intent(android.provider.Settings.ACTION_SETTINGS))
    }
    for (intent in intents) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
}

// ------------------------------------------------------------ choix du texte

@Composable
private fun SourcePicker(state: ReaderState, report: (String) -> Unit) {
    val context = LocalContext.current
    val storage = remember { DocumentStorage(context) }
    val positions = remember { ReaderPositions(context) }
    val documents = remember { storage.list() }
    val scope = rememberCoroutineScope()
    var pasted by rememberSaveable { mutableStateOf("") }
    var opening by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        opening = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = displayName(context, uri)
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Fichier illisible")
                    Triple(name, bytes.size, ReadableText.fromFile(name, bytes))
                }
            }
            opening = false
            result
                .onSuccess { (name, size, text) ->
                    if (text.isBlank()) report("Aucun texte à lire dans ce fichier (document scanné ? passe par « Texte depuis une photo »)")
                    else state.load("file:$name:$size", name.substringBeforeLast('.'), text)
                }
                .onFailure { report("Ce fichier n'a pas pu être lu : ${it.message ?: "format inconnu"}") }
        }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "Écoute un cours, un rapport ou un article pendant que tu fais autre chose. La phrase lue est surlignée.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Coller un texte", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        placeholder = { Text("Colle ici un article, un mail, une leçon…") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp, max = 220.dp)
                    )
                    Button(
                        onClick = { state.load("paste:${pasted.hashCode()}", "Texte collé", ReadableText.clean(pasted)); state.play() },
                        enabled = pasted.any { it.isLetterOrDigit() },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Écouter")
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !opening, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (opening) "Ouverture…" else "Ouvrir un fichier (PDF, Word, texte…)")
            }
        }
        if (documents.isNotEmpty()) {
            item { Text("Mes documents", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            items(documents, key = { it.id }) { meta ->
                DocumentRow(meta, positions.get("doc:${meta.id}")) {
                    val text = storage.load(meta.id)?.let { ReadableText.fromDocument(meta.type, it) }.orEmpty()
                    if (text.isBlank()) report("Ce document est vide")
                    else state.load("doc:${meta.id}", meta.name, text)
                }
            }
        }
    }
}

@Composable
private fun DocumentRow(meta: DocMeta, position: Pair<Int, Int>?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).semantics { contentDescription = "Écouter ${meta.name}" }
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (meta.type) {
                    DocType.TEXT -> Icons.Filled.Description
                    DocType.SHEET -> Icons.Filled.GridOn
                    DocType.DECK -> Icons.Filled.Slideshow
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(meta.name, fontWeight = FontWeight.Medium, maxLines = 1)
                if (position != null) {
                    Text(
                        "Reprendre à la phrase ${position.first + 1} sur ${position.second}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Icon(Icons.Filled.RecordVoiceOver, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document"

// ------------------------------------------------------------ lecture

@Composable
private fun ReadingView(state: ReaderState) {
    val lines = remember(state.text, state.sentences) {
        val text = state.text
        val out = ArrayList<Line>()
        var start = 0
        var s = 0
        while (start <= text.length) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            val inside = ArrayList<Int>()
            while (s < state.sentences.size && state.sentences[s].first < end) {
                inside.add(s)
                s++
            }
            if (text.substring(start, end).isNotBlank()) out.add(Line(start, end, inside))
            start = end + 1
        }
        out
    }
    val list = rememberLazyListState()
    val highlight = MaterialTheme.colorScheme.primaryContainer
    val current = state.index

    // La ligne lue reste visible : on la fait défiler vers le haut de l'écran.
    LaunchedEffect(current, lines) {
        val target = lines.indexOfFirst { current in it.sentences }
        if (target >= 0) {
            val visible = list.layoutInfo.visibleItemsInfo
            val shown = visible.any { it.index == target } && visible.lastOrNull()?.index != target
            if (!shown) list.animateScrollToItem(maxOf(0, target - 1))
        }
    }

    LazyColumn(state = list, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
        itemsIndexed(lines) { _, line ->
            val annotated = buildAnnotatedString {
                append(state.text.substring(line.start, line.end))
                line.sentences.forEach { i ->
                    val range = state.sentences[i]
                    addStringAnnotation("s", i.toString(), range.first - line.start, range.last + 1 - line.start)
                    if (i == current) {
                        addStyle(SpanStyle(background = highlight, fontWeight = FontWeight.SemiBold), range.first - line.start, range.last + 1 - line.start)
                    }
                }
            }
            ClickableText(
                text = annotated,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp, lineHeight = 29.sp, color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.padding(vertical = 6.dp),
                onClick = { offset ->
                    annotated.getStringAnnotations("s", offset, offset).firstOrNull()?.let { state.jumpTo(it.item.toInt()) }
                }
            )
        }
        item {
            Text(
                "Touche une phrase pour reprendre la lecture à cet endroit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }
    }
}

@Composable
private fun PlayerBar(state: ReaderState) {
    var tuning by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            val total = state.sentences.size
            LinearProgressIndicator(
                progress = if (total == 0) 0f else (state.index + if (state.finished) 1 else 0).toFloat() / total,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (state.finished) "Lecture terminée" else state.remainingMinutes().let { "Phrase ${state.index + 1} sur $total · ≈ $it min restante${if (it > 1) "s" else ""}" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { tuning = !tuning }) { Icon(Icons.Filled.Tune, contentDescription = "Réglages de la voix") }
                Spacer(Modifier.width(12.dp))
                IconButton(onClick = state::previous, enabled = state.index > 0) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Phrase précédente")
                }
                FilledIconButton(onClick = state::toggle, modifier = Modifier.size(64.dp)) {
                    Icon(
                        if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.playing) "Pause" else "Lire",
                        modifier = Modifier.size(36.dp)
                    )
                }
                IconButton(onClick = state::next, enabled = state.index < total - 1) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Phrase suivante")
                }
                Spacer(Modifier.width(60.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Vitesse", style = MaterialTheme.typography.labelMedium)
                speeds.forEach { speed ->
                    FilterChip(
                        selected = state.rate == speed,
                        onClick = { state.changeRate(speed) },
                        label = { Text("×" + speed.toString().removeSuffix(".0").replace('.', ','), fontSize = 12.sp) }
                    )
                }
            }
            if (tuning) {
                var pitch by remember { mutableStateOf(state.pitch) }
                Column {
                    Text("Hauteur de la voix : ${"%.2f".format(pitch).replace('.', ',')}", style = MaterialTheme.typography.labelMedium)
                    androidx.compose.material3.Slider(
                        value = pitch,
                        onValueChange = { pitch = it },
                        // On ne relance la phrase qu'une fois le curseur lâché.
                        onValueChangeFinished = { state.changePitch(pitch) },
                        valueRange = 0.6f..1.6f,
                        modifier = Modifier.semantics { contentDescription = "Hauteur de la voix" }
                    )
                    TextButton(onClick = { openVoiceSettings(context, install = false) }) { Text("Choisir une autre voix…") }
                }
            }
        }
    }
}
