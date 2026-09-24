package com.docssuite.library

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docssuite.core.DocMeta
import com.docssuite.core.DocType
import com.docssuite.core.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * « Mes documents » : chercher un mot dans tous les documents d'un coup,
 * les ranger en dossiers, marquer des favoris. [onOpen] ouvre un document
 * dans son éditeur.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onBack: () -> Unit, onOpen: (DocMeta) -> Unit) {
    val context = LocalContext.current
    val state = remember { LibraryState(context) }
    var results by remember { mutableStateOf<List<SearchHit>?>(null) }
    var renaming by remember { mutableStateOf<DocMeta?>(null) }
    var deleting by remember { mutableStateOf<DocMeta?>(null) }
    var moving by remember { mutableStateOf<DocMeta?>(null) }
    var folderDialog by remember { mutableStateOf<FolderDialog?>(null) }

    val candidates = state.filtered()
    LaunchedEffect(state.query, candidates) {
        val query = state.query
        if (query.isNotBlank()) delay(150) // on attend que la frappe se pose
        results = withContext(Dispatchers.Default) { state.results(query, candidates) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mes documents", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Retour") }
                },
                actions = {
                    TextButton(onClick = {
                        state.sort = if (state.sort == SortOrder.RECENT) SortOrder.NAME else SortOrder.RECENT
                    }) {
                        Icon(Icons.Filled.Sort, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(state.sort.label)
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = { state.query = it },
                placeholder = { Text("Chercher un mot dans tous les documents") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { state.query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Effacer la recherche") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = state.favoritesOnly,
                        onClick = { state.favoritesOnly = !state.favoritesOnly },
                        label = { Text("Favoris") },
                        leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }
                items(KindFilter.values().toList()) { kind ->
                    FilterChip(selected = state.kind == kind, onClick = { state.kind = kind }, label = { Text(kind.label) })
                }
            }
            FolderBar(
                state = state,
                onCreate = { folderDialog = FolderDialog.Create },
                onEdit = { folderDialog = FolderDialog.Edit(it.id, it.name) }
            )

            val list = results
            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.documents.isEmpty() -> Message(
                        "Aucun document pour l'instant",
                        "Les documents, tableurs et présentations que tu crées ou ouvres dans l'app apparaîtront ici."
                    )
                    list == null -> {}
                    list.isEmpty() && state.query.isNotBlank() -> Message(
                        "Aucun résultat",
                        "Aucun document ne contient « ${state.query.trim()} » avec ces filtres."
                    )
                    list.isEmpty() -> Message("Rien ici", "Aucun document ne correspond à ces filtres.")
                    else -> LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(list, key = { it.meta.id }) { hit ->
                            DocumentRow(
                                hit = hit,
                                folderName = state.folderOf(hit.meta)?.name,
                                favorite = state.isFavorite(hit.meta),
                                onOpen = { onOpen(hit.meta) },
                                onFavorite = { state.toggleFavorite(hit.meta) },
                                onRename = { renaming = hit.meta },
                                onMove = { moving = hit.meta },
                                onDelete = { deleting = hit.meta }
                            )
                        }
                    }
                }
            }
        }
    }

    renaming?.let { doc ->
        NameDialog("Renommer le document", doc.name, "Renommer", onDismiss = { renaming = null }) { name ->
            state.rename(doc, name)
            renaming = null
        }
    }
    deleting?.let { doc ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Supprimer « ${doc.name} » ?") },
            text = { Text("Le document sera définitivement effacé de l'app.") },
            confirmButton = { TextButton(onClick = { state.delete(doc); deleting = null }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annuler") } }
        )
    }
    moving?.let { doc ->
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text("Ranger « ${doc.name} »") },
            text = {
                Column {
                    val current = state.folderOf(doc)?.id
                    FolderChoice("Sans dossier", current == null) { state.moveTo(doc, null); moving = null }
                    state.folders.forEach { folder ->
                        FolderChoice(folder.name, current == folder.id) { state.moveTo(doc, folder.id); moving = null }
                    }
                    TextButton(onClick = { folderDialog = FolderDialog.CreateFor(doc); moving = null }) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Nouveau dossier…")
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moving = null }) { Text("Fermer") } }
        )
    }
    when (val dialog = folderDialog) {
        FolderDialog.Create -> NameDialog("Nouveau dossier", "", "Créer", onDismiss = { folderDialog = null }) { name ->
            state.createFolder(name)
            folderDialog = null
        }
        is FolderDialog.CreateFor -> NameDialog("Nouveau dossier", "", "Créer et ranger", onDismiss = { folderDialog = null }) { name ->
            state.moveTo(dialog.doc, state.createFolder(name).id)
            folderDialog = null
        }
        is FolderDialog.Edit -> NameDialog(
            "Dossier « ${dialog.name} »",
            dialog.name,
            "Renommer",
            onDismiss = { folderDialog = null },
            extra = {
                TextButton(onClick = { state.deleteFolder(dialog.id); folderDialog = null }) {
                    Text("Supprimer le dossier", color = MaterialTheme.colorScheme.error)
                }
            }
        ) { name ->
            state.renameFolder(dialog.id, name)
            folderDialog = null
        }
        null -> {}
    }
}

private sealed interface FolderDialog {
    data object Create : FolderDialog
    data class CreateFor(val doc: DocMeta) : FolderDialog
    data class Edit(val id: String, val name: String) : FolderDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderBar(state: LibraryState, onCreate: () -> Unit, onEdit: (com.docssuite.core.Folder) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        item {
            FilterChip(
                selected = state.folder == FolderFilter.Any,
                onClick = { state.folder = FolderFilter.Any },
                label = { Text("Tous les dossiers") }
            )
        }
        if (state.folders.isNotEmpty()) {
            item {
                FilterChip(
                    selected = state.folder == FolderFilter.None,
                    onClick = { state.folder = FolderFilter.None },
                    label = { Text("Sans dossier") }
                )
            }
        }
        items(state.folders, key = { it.id }) { folder ->
            val selected = state.folder == FolderFilter.In(folder.id)
            // Une fois le dossier choisi, ⋮ permet de le renommer ou de le supprimer.
            FilterChip(
                selected = selected,
                onClick = { state.folder = if (selected) FolderFilter.Any else FolderFilter.In(folder.id) },
                label = { Text("${folder.name} (${state.countIn(folder.id)})") },
                leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = if (selected) {
                    {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "Gérer le dossier ${folder.name}",
                            modifier = Modifier.size(18.dp).clickable { onEdit(folder) }
                        )
                    }
                } else null
            )
        }
        item {
            IconButton(onClick = onCreate) { Icon(Icons.Filled.CreateNewFolder, contentDescription = "Nouveau dossier") }
        }
    }
}

@Composable
private fun DocumentRow(
    hit: SearchHit,
    folderName: String?,
    favorite: Boolean,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    val meta = hit.meta
    val (icon, color) = typeLook(meta.type)
    val highlight = SpanStyle(background = Color(0x66FFD54F), fontWeight = FontWeight.SemiBold)
    var menu by remember { mutableStateOf(false) }
    val formatter = remember { SimpleDateFormat("d MMM yyyy", Locale.FRANCE) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).semantics { contentDescription = "Document ${meta.name}" },
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).background(color.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, contentDescription = null, tint = color) }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(highlighted(meta.name, hit.nameMatches, highlight), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (hit.snippet.isNotEmpty()) {
                    Text(
                        highlighted(hit.snippet, hit.snippetMatches, highlight),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    listOfNotNull(formatter.format(Date(meta.updatedAt)), folderName?.let { "Dossier : $it" }).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (favorite) "Retirer des favoris" else "Ajouter aux favoris",
                    tint = if (favorite) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Actions pour ${meta.name}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Renommer") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Ranger dans un dossier") }, onClick = { menu = false; onMove() })
                    DropdownMenuItem(text = { Text("Supprimer") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

private fun highlighted(text: String, ranges: List<IntRange>, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    ranges.forEach { r -> if (r.first >= 0 && r.last < text.length) addStyle(style, r.first, r.last + 1) }
}

private fun typeLook(type: DocType): Pair<ImageVector, Color> = when (type) {
    DocType.TEXT -> Icons.Filled.Description to Color(0xFF2563EB)
    DocType.SHEET -> Icons.Filled.TableChart to Color(0xFF16A34A)
    DocType.DECK -> Icons.Filled.Slideshow to Color(0xFFEA580C)
}

@Composable
private fun FolderChoice(label: String, current: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, tint = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(label, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, label = { Text("Nom") })
                extra?.invoke()
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun Message(title: String, detail: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
