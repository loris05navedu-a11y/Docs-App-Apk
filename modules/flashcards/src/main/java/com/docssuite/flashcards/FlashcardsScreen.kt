package com.docssuite.flashcards

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docssuite.core.ConfirmDialog
import com.docssuite.core.DocType
import com.docssuite.core.DocumentStorage
import com.docssuite.core.TextInputDialog
import com.docssuite.core.rememberFileOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val accent = Color(0xFFDB2777)
private val knewColor = Color(0xFF16A34A)

private sealed interface Page {
    object Decks : Page
    data class Open(val id: String) : Page
    data class Study(val id: String) : Page
    /** [cardId] : la fiche à modifier, ou `null` pour en saisir de nouvelles. */
    data class Edit(val deckId: String, val cardId: String?) : Page
    /** [deckId] : le paquet où ajouter, ou `null` pour en créer un. */
    data class Import(val deckId: String?) : Page
}

/**
 * Fiches de révision : une question d'un côté, la réponse de l'autre, et
 * l'app qui repropose chaque fiche au bon moment — souvent ce qu'on ne sait
 * pas, rarement ce qu'on sait.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashcardsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { FlashcardStore(context) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<Page>(Page.Decks) }
    var version by remember { mutableIntStateOf(0) }
    var session by remember { mutableStateOf<StudySession?>(null) }
    var reversed by remember { mutableStateOf(false) }

    fun refresh() = version++
    fun say(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    fun up() {
        page = when (val p = page) {
            Page.Decks -> Page.Decks
            is Page.Open -> Page.Decks
            is Page.Study -> Page.Open(p.id)
            is Page.Edit -> Page.Open(p.deckId)
            is Page.Import -> p.deckId?.let { Page.Open(it) } ?: Page.Decks
        }
        if (page !is Page.Study) session = null
    }

    BackHandler(enabled = page != Page.Decks) { up() }

    val today = remember(version) { Days.today() }
    val decks = remember(version) { store.decks() }
    val current = (page as? Page.Open)?.id?.let { id -> decks.firstOrNull { it.id == id } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (val p = page) {
                            Page.Decks -> "Fiches de révision"
                            is Page.Open -> current?.name ?: ""
                            is Page.Study -> decks.firstOrNull { it.id == p.id }?.name ?: "Révision"
                            is Page.Import -> "Importer des fiches"
                            is Page.Edit -> if (p.cardId == null) "Nouvelles fiches" else "Modifier la fiche"
                        },
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (page == Page.Decks) onBack() else up() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val p = page) {
                Page.Decks -> DeckList(
                    decks = decks,
                    today = today,
                    onOpen = { page = Page.Open(it.id) },
                    onCreate = { name ->
                        val deck = store.createDeck(name)
                        refresh()
                        page = Page.Open(deck.id)
                    },
                    onImport = { page = Page.Import(null) }
                )
                is Page.Open -> current?.let { deck ->
                    DeckPage(
                        deck = deck,
                        today = today,
                        reversed = reversed,
                        onReversed = { reversed = it },
                        onStudy = { all ->
                            session = if (all) StudySession.everything(deck, today, reversed) else StudySession.of(deck, today, reversed)
                            page = Page.Study(deck.id)
                        },
                        onNewCard = { page = Page.Edit(deck.id, null) },
                        onOpenCard = { page = Page.Edit(deck.id, it.id) },
                        onRename = { name ->
                            store.renameDeck(deck.id, name)
                            refresh()
                        },
                        onReset = {
                            store.reset(deck.id)
                            refresh()
                            say("Progression remise à zéro")
                        },
                        onDelete = {
                            store.deleteDeck(deck.id)
                            refresh()
                            page = Page.Decks
                        },
                        onImport = { page = Page.Import(deck.id) }
                    )
                }
                is Page.Study -> session?.let { s ->
                    StudyPage(
                        session = s,
                        deck = decks.firstOrNull { it.id == p.id },
                        today = today,
                        onAnswer = { knew ->
                            s.answer(knew)
                            store.record(p.id, s.results())
                        },
                        onFinish = {
                            refresh()
                            session = null
                            page = Page.Open(p.id)
                        }
                    )
                }
                is Page.Edit -> decks.firstOrNull { it.id == p.deckId }?.let { deck ->
                    CardEditor(
                        card = p.cardId?.let { id -> deck.cards.firstOrNull { it.id == id } },
                        onAdd = { front, back ->
                            val added = store.addCards(deck.id, listOf(front to back)) > 0
                            refresh()
                            added
                        },
                        onSave = { card ->
                            store.updateCard(deck.id, card)
                            refresh()
                            page = Page.Open(deck.id)
                        },
                        onDelete = { card ->
                            store.deleteCard(deck.id, card.id)
                            refresh()
                            page = Page.Open(deck.id)
                        },
                        onClose = { page = Page.Open(deck.id) }
                    )
                }
                is Page.Import -> ImportPage(
                    target = p.deckId?.let { id -> decks.firstOrNull { it.id == id } },
                    onDone = { name, cards ->
                        val deckId = p.deckId ?: store.createDeck(name).id
                        val added = store.addCards(deckId, cards)
                        val duplicates = cards.size - added
                        say(
                            (if (added == 1) "1 fiche ajoutée" else "$added fiches ajoutées") +
                                if (duplicates > 0) " ($duplicates déjà présentes)" else ""
                        )
                        refresh()
                        page = Page.Open(deckId)
                    }
                )
            }
        }
    }
}

// ====================================================================== paquets

@Composable
private fun DeckList(
    decks: List<Deck>,
    today: Long,
    onOpen: (Deck) -> Unit,
    onCreate: (String) -> Unit,
    onImport: () -> Unit
) {
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (decks.isEmpty()) {
            item {
                Surface(shape = RoundedCornerShape(18.dp), color = accent.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Apprendre avec des fiches", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Une question d'un côté, la réponse de l'autre. Chaque fiche sue revient de plus en plus tard " +
                                "(1, 2, 4, 8 jours…), chaque fiche ratée revient tout de suite : tu révises ce que tu ne sais pas.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Pour importer un cours, écris une fiche par ligne : « Révolution française : 1789 ».",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            val due = decks.sumOf { it.dueCount(today) }
            item {
                Text(
                    when (due) {
                        0 -> "Rien à revoir aujourd'hui."
                        1 -> "1 fiche à revoir aujourd'hui."
                        else -> "$due fiches à revoir aujourd'hui."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(decks, key = { it.id }) { deck -> DeckRow(deck, today) { onOpen(deck) } }
        }
        if (naming) {
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Nom du paquet") },
                            placeholder = { Text("Histoire — chapitre 3") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { naming = false; name = "" }, modifier = Modifier.weight(1f)) { Text("Annuler") }
                            Button(
                                onClick = { naming = false; onCreate(name); name = "" },
                                enabled = name.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) { Text("Créer") }
                        }
                    }
                }
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { naming = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Nouveau paquet")
                    }
                    OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Importer")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeckRow(deck: Deck, today: Long, onClick: () -> Unit) {
    val due = deck.dueCount(today)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Paquet ${deck.name}" }
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).background(accent.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Style, contentDescription = null, tint = accent)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(deck.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            if (deck.cards.size == 1) "1 fiche" else "${deck.cards.size} fiches",
                            if (deck.newCount > 0) "${deck.newCount} nouvelles" else null
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (due > 0) {
                    Box(
                        Modifier.background(accent, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("$due à revoir", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (deck.cards.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { deck.masteredCount.toFloat() / deck.cards.size },
                    color = knewColor,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth().height(6.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${deck.masteredCount} sur ${deck.cards.size} maîtrisées",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ====================================================================== un paquet

@Composable
private fun DeckPage(
    deck: Deck,
    today: Long,
    reversed: Boolean,
    onReversed: (Boolean) -> Unit,
    onStudy: (all: Boolean) -> Unit,
    onNewCard: () -> Unit,
    onOpenCard: (Card) -> Unit,
    onRename: (String) -> Unit,
    onReset: () -> Unit,
    onDelete: () -> Unit,
    onImport: () -> Unit
) {
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val due = deck.dueCount(today)
    val waiting = due + minOf(deck.newCount, StudySession.NEW_PER_SESSION)

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("À revoir", "$due", accent, Modifier.weight(1f))
                Stat("Nouvelles", "${deck.newCount}", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                Stat("Maîtrisées", "${deck.masteredCount}/${deck.cards.size}", knewColor, Modifier.weight(1f))
            }
        }
        item {
            if (waiting > 0) {
                Button(
                    onClick = { onStudy(false) },
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text(if (waiting == 1) "Réviser 1 fiche" else "Réviser $waiting fiches", fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text(
                    if (deck.cards.isEmpty()) "Ajoute des fiches pour commencer."
                    else "Tout est à jour : rien à revoir aujourd'hui.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (deck.cards.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onStudy(true) }) { Text("Tout le paquet (${deck.cards.size})") }
                    Spacer(Modifier.weight(1f))
                    Text("Sens inversé", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = reversed,
                        onCheckedChange = onReversed,
                        modifier = Modifier.semantics { contentDescription = "Sens inversé" }
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onNewCard, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Ajouter")
                }
                OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Importer")
                }
            }
        }
        if (deck.cards.isNotEmpty()) {
            item { Text("Fiches", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        }
        items(deck.cards, key = { it.id }) { card -> CardRow(card) { onOpenCard(card) } }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { renaming = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Renommer")
                }
                if (deck.cards.any { !it.isNew }) {
                    TextButton(onClick = { confirmReset = true }) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Recommencer")
                    }
                }
                TextButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text("Supprimer le paquet", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (renaming) {
        TextInputDialog(
            title = "Renommer le paquet",
            initialValue = deck.name,
            onConfirm = { renaming = false; onRename(it) },
            onDismiss = { renaming = false }
        )
    }
    if (confirmReset) {
        ConfirmDialog(
            title = "Recommencer ce paquet ?",
            message = "Toutes les fiches redeviennent nouvelles. Les fiches elles-mêmes sont gardées.",
            confirmLabel = "Recommencer",
            onConfirm = { confirmReset = false; onReset() },
            onDismiss = { confirmReset = false }
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Supprimer « ${deck.name} » ?",
            message = "Le paquet et ses ${deck.cards.size} fiches seront supprimés.",
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false }
        )
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(14.dp), color = color.copy(alpha = 0.1f), modifier = modifier) {
        Column(Modifier.padding(vertical = 12.dp, horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardRow(card: Card, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Fiche ${card.front}" }
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(card.front, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(card.back, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            // Une pastille par boîte franchie : on voit d'un coup d'œil ce qui est su.
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (box in 1..Leitner.TOP) {
                    Box(
                        Modifier.size(7.dp).background(
                            if (card.box >= box) knewColor else MaterialTheme.colorScheme.surfaceContainerHighest,
                            CircleShape
                        )
                    )
                }
            }
        }
    }
}

/**
 * Saisie d'une fiche, sur toute la page : de la place pour une réponse
 * longue. En ajout, le formulaire se vide après chaque fiche, pour enchaîner.
 */
@Composable
private fun CardEditor(
    card: Card?,
    onAdd: (String, String) -> Boolean,
    onSave: (Card) -> Unit,
    onDelete: (Card) -> Unit,
    onClose: () -> Unit
) {
    var front by remember(card?.id) { mutableStateOf(card?.front.orEmpty()) }
    var back by remember(card?.id) { mutableStateOf(card?.back.orEmpty()) }
    var added by remember { mutableIntStateOf(0) }
    var duplicate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val ready = front.isNotBlank() && back.isNotBlank()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = front,
            onValueChange = { front = it; duplicate = false },
            label = { Text("Question (recto)") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = back,
            onValueChange = { back = it; duplicate = false },
            label = { Text("Réponse (verso)") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        if (duplicate) {
            Text("Cette fiche est déjà dans le paquet.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (card == null) {
            Button(
                onClick = {
                    if (onAdd(front.trim(), back.trim())) {
                        added++
                        front = ""
                        back = ""
                    } else {
                        duplicate = true
                    }
                },
                enabled = ready,
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Ajouter", fontWeight = FontWeight.SemiBold) }
            if (added > 0) {
                Text(
                    if (added == 1) "1 fiche ajoutée — tu peux saisir la suivante." else "$added fiches ajoutées — tu peux saisir la suivante.",
                    color = knewColor,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Terminé") }
        } else {
            Button(
                onClick = { onSave(card.copy(front = front.trim(), back = back.trim())) },
                enabled = ready,
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Enregistrer", fontWeight = FontWeight.SemiBold) }
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Supprimer la fiche", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmDelete && card != null) {
        ConfirmDialog(
            title = "Supprimer cette fiche ?",
            message = "« ${card.front} »",
            onConfirm = { confirmDelete = false; onDelete(card) },
            onDismiss = { confirmDelete = false }
        )
    }
}

// ====================================================================== séance

@Composable
private fun StudyPage(
    session: StudySession,
    deck: Deck?,
    today: Long,
    onAnswer: (Boolean) -> Unit,
    onFinish: () -> Unit
) {
    // La séance n'est pas un état Compose : un compteur force le réaffichage.
    var tick by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    key(tick) { StudyContent(session, deck, today, revealed, { revealed = true }, { knew -> onAnswer(knew); revealed = false; tick++ }, onFinish) }
}

@Composable
private fun StudyContent(
    session: StudySession,
    deck: Deck?,
    today: Long,
    revealed: Boolean,
    onReveal: () -> Unit,
    onAnswer: (Boolean) -> Unit,
    onFinish: () -> Unit
) {
    if (session.done) {
        // Le paquet affiché date d'avant la séance : on y reporte ce qui vient d'être révisé.
        val updated = session.results().associateBy { it.id }
        val cards = deck?.cards?.map { updated[it.id] ?: it } ?: updated.values.toList()
        val next = cards.filter { !it.isNew && it.due > today }.minOfOrNull { it.due }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Séance terminée", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                if (session.total == 0) "Rien à revoir pour l'instant."
                else "${session.total} fiches révisées · ${session.again} erreur${if (session.again > 1) "s" else ""}",
                style = MaterialTheme.typography.bodyLarge
            )
            if (next != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    when (val days = next - today) {
                        1L -> "Prochaine révision : demain"
                        else -> "Prochaine révision : dans $days jours"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onFinish, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("Terminer") }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = { if (session.total == 0) 0f else session.finished.toFloat() / session.total },
                color = accent,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.weight(1f).height(6.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text("${session.finished} / ${session.total}", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(16.dp))
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 3.dp,
            modifier = Modifier.fillMaxWidth().weight(1f).clickable(enabled = !revealed, onClick = onReveal)
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (session.reversed) "RÉPONSE → QUESTION" else "QUESTION",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    session.question,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { contentDescription = "Question : ${session.question}" }
                )
                if (revealed) {
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider(Modifier.fillMaxWidth(0.4f))
                    Spacer(Modifier.height(20.dp))
                    Text(
                        session.answer,
                        style = MaterialTheme.typography.titleLarge,
                        color = accent,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { contentDescription = "Réponse : ${session.answer}" }
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (!revealed) {
            Button(
                onClick = onReveal,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text("Voir la réponse", fontWeight = FontWeight.SemiBold) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { onAnswer(false) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f).height(56.dp)
                ) { Text("À revoir", fontWeight = FontWeight.SemiBold) }
                Button(
                    onClick = { onAnswer(true) },
                    colors = ButtonDefaults.buttonColors(containerColor = knewColor),
                    modifier = Modifier.weight(1f).height(56.dp)
                ) { Text("Je savais", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

// ====================================================================== import

private enum class Source(val label: String) { TEXT("Coller du texte"), SAVED("Mes documents"), FILE("Un fichier") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportPage(target: Deck?, onDone: (name: String, cards: List<Pair<String, String>>) -> Unit) {
    val context = LocalContext.current
    val storage = remember { DocumentStorage(context) }
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf(Source.TEXT) }
    var text by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Pair<String, CardImport.Result>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }

    val opener = rememberFileOpener(onError = { problem = it }) { file ->
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { FlashcardSources.fromFile(file.name, file.bytes) } }
            result.onSuccess {
                val title = file.name.substringBeforeLast('.')
                picked = title to it
                if (name.isBlank()) name = title
                problem = null
            }.onFailure { problem = "Ce fichier n'a pas pu être lu." }
        }
    }

    val result: CardImport.Result? = when (source) {
        Source.TEXT -> if (text.isBlank()) null else remember(text) { CardImport.fromText(text) }
        else -> picked?.second
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(
                if (target != null) "Ajouter au paquet « ${target.name} »" else "Chaque ligne devient une fiche : question, séparateur, réponse.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Source.values().forEach { s ->
                    FilterChip(selected = source == s, onClick = { source = s; problem = null }, label = { Text(s.label) })
                }
            }
        }
        when (source) {
            Source.TEXT -> item {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Tes fiches") },
                    placeholder = { Text("Révolution française : 1789\nH2O = eau\ndog ; chien") },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp)
                )
            }
            Source.SAVED -> {
                val saved = storage.list().filter { it.type != DocType.DECK }
                if (saved.isEmpty()) {
                    item { Text("Aucun document ni tableur enregistré.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(saved, key = { it.id }) { meta ->
                    Surface(
                        onClick = {
                            val found = FlashcardSources.fromSaved(storage, meta.id)
                            if (found == null) problem = "Ce document n'a pas pu être relu." else {
                                picked = meta.name to found
                                if (name.isBlank()) name = meta.name
                                problem = null
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = if (picked?.first == meta.name) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Source ${meta.name}" }
                    ) {
                        Row(Modifier.padding(14.dp)) {
                            Text(meta.name, modifier = Modifier.weight(1f))
                            Text(if (meta.type == DocType.SHEET) "Tableur" else "Document", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Source.FILE -> item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(onClick = { opener.open() }, modifier = Modifier.fillMaxWidth()) { Text("Choisir un fichier") }
                    Text(
                        "Word, OpenDocument, texte, Excel, CSV, PDF. Une présentation donne une fiche par diapositive : le titre au recto, le contenu au verso.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        problem?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }

        if (result != null) {
            item {
                Text(
                    when (result.cards.size) {
                        0 -> "Aucune fiche trouvée."
                        1 -> "1 fiche trouvée"
                        else -> "${result.cards.size} fiches trouvées"
                    } + if (result.skipped > 0) " · ${result.skipped} ligne${if (result.skipped > 1) "s" else ""} sans réponse ignorée${if (result.skipped > 1) "s" else ""}" else "",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { contentDescription = "Résultat de l'import" }
                )
            }
            items(result.cards.take(5)) { (front, back) ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(front, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(back, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (result.cards.size > 5) {
                item { Text("… et ${result.cards.size - 5} autres", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (result.cards.isNotEmpty()) {
                if (target == null) {
                    item {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Nom du paquet") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                item {
                    Button(
                        onClick = { onDone(name.ifBlank { "Mes fiches" }, result.cards) },
                        colors = ButtonDefaults.buttonColors(containerColor = accent),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Text(
                            if (target == null) "Créer le paquet (${result.cards.size})" else "Ajouter ${result.cards.size} fiches",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
