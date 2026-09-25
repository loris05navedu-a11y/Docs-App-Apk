package com.docssuite.compare

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.docssuite.core.DocType
import com.docssuite.core.rememberFileOpener
import com.docssuite.fileformats.TextDocument

private val accent = Color(0xFF0D9488)
private val addedInk = Color(0xFF15803D)
private val addedPaper = Color(0xFFDCFCE7)
private val removedInk = Color(0xFFB91C1C)

/**
 * Comparer deux versions : ce qui a été ajouté, retiré ou retouché d'un
 * document à l'autre, mot par mot dans les paragraphes retouchés.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(onBack: () -> Unit, onSaveReport: (TextDocument, String) -> Unit) {
    val context = LocalContext.current
    val state = remember { CompareState(context) }
    val snackbar = remember { SnackbarHostState() }
    var picking by remember { mutableStateOf<Slot?>(null) }

    LaunchedEffect(state.problem) {
        state.problem?.let {
            snackbar.showSnackbar(it)
            state.dismissProblem()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Comparer deux versions", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { if (picking != null) picking = null else onBack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    if (state.before != null && state.after != null) {
                        IconButton(onClick = { state.swap() }) {
                            Icon(Icons.Filled.SwapVert, contentDescription = "Inverser les versions")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val slot = picking
            if (slot != null) {
                Picker(state, slot) { picking = null }
            } else {
                Comparison(state, onPick = { picking = it }, onSaveReport = onSaveReport)
            }
        }
    }
}

@Composable
private fun Picker(state: CompareState, slot: Slot, done: () -> Unit) {
    val opener = rememberFileOpener(onError = {}) {
        state.chooseFile(slot, it.name, it.bytes)
        done()
    }
    val documents = state.documents

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                if (slot == Slot.BEFORE) "Quelle est l'ancienne version ?" else "Quelle est la nouvelle version ?",
                fontWeight = FontWeight.Bold
            )
        }
        item {
            PickRow("Ouvrir un fichier", "Word, OpenDocument, PDF, texte…", Icons.Filled.FolderOpen) {
                opener.open(arrayOf("*/*"))
            }
        }
        if (documents.isNotEmpty()) {
            item { Text("Mes documents", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
            items(documents, key = { it.id }) { meta ->
                PickRow(meta.name, label(meta.type), icon(meta.type)) {
                    state.chooseSaved(slot, meta.id)
                    done()
                }
            }
        }
    }
}

@Composable
private fun Comparison(
    state: CompareState,
    onPick: (Slot) -> Unit,
    onSaveReport: (TextDocument, String) -> Unit
) {
    val result = state.comparison

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "Deux versions d'un même texte, et ce qui a changé de l'une à l'autre.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item { Chosen("Avant", state.before?.name, Slot.BEFORE, state, onPick) }
        item { Chosen("Après", state.after?.name, Slot.AFTER, state, onPick) }

        if (result == null) return@LazyColumn

        item {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Text(
                    result.summary,
                    modifier = Modifier.padding(14.dp).semantics { contentDescription = result.summary },
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { state.includeUnchanged = !state.includeUnchanged }
            ) {
                Checkbox(
                    checked = state.includeUnchanged,
                    onCheckedChange = { state.includeUnchanged = it }
                )
                Text(
                    "Montrer aussi ce qui n'a pas changé",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { contentDescription = "Montrer aussi ce qui n'a pas changé" }
                )
            }
        }

        val shown = result.rows.filter { state.includeUnchanged || it.kind != Kind.SAME }
        if (shown.isEmpty()) {
            item {
                Text(
                    "Rien n'a changé d'une version à l'autre.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        itemsIndexed(shown) { index, row -> RowCard(row, index) }

        if (!result.identical) {
            item {
                Button(
                    onClick = { state.report()?.let { onSaveReport(it, "Comparaison") } },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Enregistrer le rapport")
                }
            }
        }
    }
}

@Composable
private fun RowCard(row: Row, index: Int) {
    val (mark, tint) = when (row.kind) {
        Kind.ADDED -> "Ajouté" to addedInk
        Kind.REMOVED -> "Supprimé" to removedInk
        Kind.CHANGED -> "Retouché" to Color(0xFFB45309)
        Kind.SAME -> "Inchangé" to Color(0xFF64748B)
    }
    Card(
        Modifier.fillMaxWidth().semantics { contentDescription = "$mark ${index + 1}" },
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(Modifier.padding(12.dp)) {
            Box(Modifier.size(width = 4.dp, height = 20.dp).background(tint, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(mark, style = MaterialTheme.typography.labelSmall, color = tint)
                Text(text = rowText(row), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Un paragraphe retouché se lit d'un trait : mots retirés barrés, mots ajoutés surlignés. */
private fun rowText(row: Row): AnnotatedString = when (row.kind) {
    Kind.SAME -> AnnotatedString(row.before)
    Kind.ADDED -> buildAnnotatedString {
        withStyle(SpanStyle(background = addedPaper, color = addedInk)) { append(row.after) }
    }
    Kind.REMOVED -> buildAnnotatedString {
        withStyle(SpanStyle(color = removedInk, textDecoration = TextDecoration.LineThrough)) {
            append(row.before)
        }
    }
    Kind.CHANGED -> buildAnnotatedString {
        row.words.forEachIndexed { i, edit ->
            if (i > 0) append(" ")
            when (edit.change) {
                Change.KEPT -> append(edit.value)
                Change.ADDED -> withStyle(SpanStyle(background = addedPaper, color = addedInk)) {
                    append(edit.value)
                }
                Change.REMOVED -> withStyle(
                    SpanStyle(color = removedInk, textDecoration = TextDecoration.LineThrough)
                ) { append(edit.value) }
            }
        }
    }
}

@Composable
private fun Chosen(
    label: String,
    name: String?,
    slot: Slot,
    state: CompareState,
    onPick: (Slot) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onPick(slot) }
            .semantics { contentDescription = "$label : ${name ?: "à choisir"}" },
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).background(if (name == null) Color(0xFF94A3B8) else accent, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (slot == Slot.BEFORE) "1" else "2",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(name ?: "Choisir un document ou un fichier", fontWeight = FontWeight.SemiBold)
            }
            if (name != null) {
                IconButton(onClick = { state.clear(slot) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Retirer $label")
                }
            }
        }
    }
}

@Composable
private fun PickRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .semantics { contentDescription = title },
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).background(accent, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun label(type: DocType): String = when (type) {
    DocType.TEXT -> "Document"
    DocType.SHEET -> "Tableur"
    DocType.DECK -> "Présentation"
}

private fun icon(type: DocType): ImageVector = when (type) {
    DocType.TEXT -> Icons.Filled.Description
    DocType.SHEET -> Icons.Filled.TableChart
    DocType.DECK -> Icons.Filled.Slideshow
}
