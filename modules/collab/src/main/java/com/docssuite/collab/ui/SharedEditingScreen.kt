package com.docssuite.collab.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docssuite.collab.Collab
import com.docssuite.collab.CollabSetup
import com.docssuite.collab.cloud.Account
import com.docssuite.collab.cloud.CollabException
import com.docssuite.collab.cloud.DocSession
import com.docssuite.collab.cloud.DocSummary
import com.docssuite.collab.cloud.Invitation
import com.docssuite.collab.cloud.Role
import com.docssuite.collab.cloud.SharedDocs
import com.docssuite.core.DocType
import com.docssuite.core.DocumentStorage
import com.docssuite.texteditor.loadTextDocument
import kotlinx.coroutines.launch

/** Les pages de l'édition partagée. */
private sealed interface Page {
    object Home : Page
    object New : Page
    object Account : Page
    data class Doc(val id: String) : Page
    data class People(val id: String) : Page
    data class Comments(val id: String, val quote: String?) : Page

    /** Un lien d'invitation, en attendant de savoir quoi en faire. */
    data class Link(val id: String) : Page

    val docId: String?
        get() = when (this) {
            is Doc -> id
            is People -> id
            is Comments -> id
            else -> null
        }

    companion object {
        val saver = Saver<Page, String>(
            save = {
                when (it) {
                    Home -> "home"
                    New -> "new"
                    Account -> "account"
                    is Doc -> "doc:${it.id}"
                    is People -> "people:${it.id}"
                    is Comments -> "comments:${it.id}:${it.quote.orEmpty()}"
                    is Link -> "link:${it.id}"
                }
            },
            restore = {
                val parts = it.split(':', limit = 3)
                when (parts[0]) {
                    "new" -> New
                    "account" -> Account
                    "doc" -> Doc(parts[1])
                    "people" -> People(parts[1])
                    "comments" -> Comments(parts[1], parts.getOrNull(2)?.ifEmpty { null })
                    "link" -> Link(parts[1])
                    else -> Home
                }
            },
        )
    }
}

/**
 * L'édition partagée : se connecter, ses documents partagés et les
 * invitations, puis l'éditeur en direct.
 *
 * @param openDocId un document à ouvrir tout de suite (lien d'invitation).
 * @param onOpenLocal ouvrir dans l'éditeur un document de l'app (une copie).
 */
@Composable
fun SharedEditingScreen(
    onBack: () -> Unit,
    openDocId: String? = null,
    onOpenLocal: (String) -> Unit = {},
    setup: CollabSetup? = null,
) {
    val context = LocalContext.current
    var configured by remember { mutableStateOf(setup ?: Collab.setup(context)) }
    when (val resolved = configured) {
        is CollabSetup.Missing -> NotConfiguredPage(resolved.what, onBack, onImported = { configured = it })
        is CollabSetup.Ready -> {
            val docs = resolved.docs
            val account by docs.accounts.current.collectAsState()
            val me = account
            when {
                me == null -> AuthPage(docs.accounts, onBack)
                !me.verified -> VerifyPage(docs.accounts, me, onBack)
                else -> SignedIn(docs, me, openDocId, onBack, onOpenLocal)
            }
        }
    }
}

@Composable
private fun SignedIn(docs: SharedDocs, me: Account, openDocId: String?, onBack: () -> Unit, onOpenLocal: (String) -> Unit) {
    var page by rememberSaveable(stateSaver = Page.saver) { mutableStateOf<Page>(Page.Home) }
    val documents by remember(me.uid) { docs.documents(me) }.collectAsState(initial = null)
    val invitations by remember(me.uid) { docs.invitations(me) }.collectAsState(initial = null)
    var handledLink by rememberSaveable { mutableStateOf<String?>(null) }

    // Un lien d'invitation ouvert depuis un e-mail.
    LaunchedEffect(openDocId) {
        if (openDocId != null && openDocId != handledLink) {
            handledLink = openDocId
            page = Page.Link(openDocId)
        }
    }

    val docId = page.docId
    if (docId != null) {
        val session = remember(docId, me.uid) { docs.acquire(docId) }
        DisposableEffect(session) { onDispose { docs.release(session) } }
        when (val p = page) {
            is Page.Doc -> DocPage(
                session = session,
                docs = docs,
                onBack = { page = Page.Home },
                onPeople = { page = Page.People(docId) },
                onComments = { quote -> page = Page.Comments(docId, quote) },
                onOpenLocal = onOpenLocal,
            )
            is Page.People -> PeoplePage(session, docs, onBack = { page = Page.Doc(docId) })
            is Page.Comments -> CommentsPage(session, p.quote, onBack = { page = Page.Doc(docId) })
            else -> {}
        }
        return
    }

    when (val p = page) {
        Page.Home -> HomePage(
            me = me,
            docs = docs,
            documents = documents,
            invitations = invitations,
            onBack = onBack,
            onOpen = { page = Page.Doc(it) },
            onNew = { page = Page.New },
            onAccount = { page = Page.Account },
        )
        Page.New -> NewDocPage(docs, onBack = { page = Page.Home }, onCreated = { page = Page.Doc(it) })
        Page.Account -> AccountPage(docs, me, onBack = { page = Page.Home })
        is Page.Link -> LinkPage(
            docs = docs,
            me = me,
            docId = p.id,
            invitations = invitations,
            onBack = { page = Page.Home },
            onOpen = { page = Page.Doc(p.id) },
        )
        else -> {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomePage(
    me: Account,
    docs: SharedDocs,
    documents: List<DocSummary>?,
    invitations: List<Invitation>?,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onAccount: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var answering by remember { mutableStateOf<String?>(null) }

    fun answer(invitation: Invitation, accept: Boolean) {
        if (answering != null) return
        answering = invitation.docId
        scope.launch {
            try {
                if (accept) {
                    docs.accept(invitation)
                    onOpen(invitation.docId)
                } else {
                    docs.decline(invitation)
                    snackbar.showSnackbar("Invitation refusée.")
                }
            } catch (e: CollabException) {
                snackbar.showSnackbar(e.message ?: "Erreur")
            } finally {
                answering = null
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Édition partagée", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
                actions = {
                    IconButton(onClick = onAccount) { Avatar(me.name, Accent, size = 32.dp, description = "Mon compte") }
                },
            )
        },
        floatingActionButton = {
            // La forme à contenu libre : son texte reste lisible par TalkBack.
            ExtendedFloatingActionButton(onClick = onNew) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("Nouveau document")
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val pending = invitations.orEmpty()
            if (pending.isNotEmpty()) {
                item { SectionTitle(if (pending.size == 1) "Une invitation" else "${pending.size} invitations") }
                items(pending, key = { "invite-" + it.docId }) { invitation ->
                    InvitationCard(
                        invitation,
                        busy = answering == invitation.docId,
                        onAccept = { answer(invitation, true) },
                        onDecline = { answer(invitation, false) },
                    )
                }
            }
            when {
                documents == null -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                documents.isEmpty() -> item {
                    Explanation(
                        "Écrire à plusieurs, en direct",
                        "Crée un document, puis invite des personnes par leur adresse e-mail : elles reçoivent un " +
                            "e-mail et le retrouvent ici. Chaque lettre tapée apparaît aussitôt chez tout le monde, " +
                            "avec le curseur de chacun. Tu choisis qui peut modifier, commenter ou seulement lire.",
                    )
                }
                else -> {
                    val mine = documents.filter { it.role == Role.OWNER }
                    val shared = documents.filter { it.role != Role.OWNER }
                    if (mine.isNotEmpty()) {
                        item { SectionTitle("Mes documents") }
                        items(mine, key = { it.id }) { DocCard(it, showOwner = false) { onOpen(it.id) } }
                    }
                    if (shared.isNotEmpty()) {
                        item { SectionTitle("Partagés avec moi") }
                        items(shared, key = { it.id }) { DocCard(it, showOwner = true) { onOpen(it.id) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun InvitationCard(invitation: Invitation, busy: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = Accent.copy(alpha = 0.10f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.MailOutline, contentDescription = null, tint = Accent)
                Spacer(Modifier.width(10.dp))
                Text(
                    "${invitation.byName.ifBlank { "Quelqu'un" }} t'invite à ${verb(invitation.role)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(invitation.title.ifBlank { "Document sans titre" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoleChip(invitation.role)
                Spacer(Modifier.width(8.dp))
                Text(invitation.role.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDecline, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Refuser") }
                Button(onClick = onAccept, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Accepter") }
            }
        }
    }
}

internal fun verb(role: Role): String = when (role) {
    Role.EDITOR, Role.OWNER -> "modifier"
    Role.COMMENTER -> "commenter"
    Role.READER -> "consulter"
}

@Composable
private fun DocCard(doc: DocSummary, showOwner: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = Accent.copy(alpha = 0.12f), modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Description, contentDescription = null, tint = Accent) }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(doc.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                val change = when {
                    doc.updatedAt != null && doc.updatedBy != null -> "Modifié ${ago(doc.updatedAt)} par ${doc.updatedBy}"
                    doc.updatedAt != null -> "Modifié ${ago(doc.updatedAt)}"
                    else -> "Créé ${ago(doc.createdAt)}"
                }
                Text(
                    if (showOwner) "De ${doc.ownerName} · $change" else change,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            RoleChip(doc.role)
        }
    }
}

/** Créer un document partagé, vide ou à partir d'un document de l'app. */
@Composable
private fun NewDocPage(docs: SharedDocs, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val context = LocalContext.current
    val storage = remember { DocumentStorage(context) }
    val local = remember { storage.list(DocType.TEXT) }
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)

    Scaffold(topBar = { SimpleTopBar("Nouveau document partagé", onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(120) },
                label = { Text("Titre") },
                placeholder = { Text("Compte rendu de la réunion") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SectionTitle("Pour commencer")
            SourceRow("Un document vide", null, selected = source == null) { source = null }
            if (local.isNotEmpty()) {
                Text(
                    "Ou une copie d'un de tes documents (le texte, sans la mise en forme) :",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                local.forEach { meta ->
                    SourceRow(meta.name, meta.id, selected = source == meta.id) {
                        source = meta.id
                        if (title.isBlank()) title = meta.name
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    if (busy) return@Button
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val text = source?.let { id -> loadTextDocument(storage, id)?.let(::plainText) }.orEmpty()
                            onCreated(docs.create(title, text))
                        } catch (e: CollabException) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (busy) "Création…" else "Créer") }
        }
    }
}

@Composable
private fun SourceRow(label: String, id: String?, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = SemanticsRole.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (id == null) FontWeight.Medium else FontWeight.Normal)
    }
}

/** Le texte d'un document de l'app, paragraphe par paragraphe. */
internal fun plainText(document: com.docssuite.fileformats.TextDocument): String =
    document.paragraphs.joinToString("\n") { paragraph -> paragraph.runs.joinToString("") { it.text } }

@Composable
private fun AccountPage(docs: SharedDocs, me: Account, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(topBar = { SimpleTopBar("Mon compte", onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(me.name, Accent, size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(me.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(me.email, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (me.viaGoogle) "Connecté avec Google" else "Adresse vérifiée",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Explanation(
                "Tes documents restent en ligne",
                "Après la déconnexion, tu les retrouves en te reconnectant avec ${me.email}, sur ce téléphone ou un autre.",
            )
            OutlinedButton(
                onClick = {
                    docs.closeAll()
                    docs.accounts.signOut()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Se déconnecter") }
        }
    }
}

/** Un lien d'invitation : accepter l'invitation, ou ouvrir le document. */
@Composable
private fun LinkPage(
    docs: SharedDocs,
    me: Account,
    docId: String,
    invitations: List<Invitation>?,
    onBack: () -> Unit,
    onOpen: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val invitation = invitations?.firstOrNull { it.docId == docId }
    BackHandler(onBack = onBack)

    // Pas d'invitation pour ce document : on l'ouvre, il dira si on y a accès.
    LaunchedEffect(invitations) {
        if (invitations != null && invitation == null) onOpen()
    }

    Scaffold(topBar = { SimpleTopBar("Invitation", onBack) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (invitation == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    InvitationCard(
                        invitation,
                        busy = busy,
                        onAccept = {
                            busy = true
                            scope.launch {
                                try {
                                    docs.accept(invitation)
                                    onOpen()
                                } catch (e: CollabException) {
                                    error = e.message
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        onDecline = {
                            busy = true
                            scope.launch {
                                try {
                                    docs.decline(invitation)
                                    onBack()
                                } catch (e: CollabException) {
                                    error = e.message
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    )
                    Text("Connecté en tant que ${me.email}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = onBack) { Text("Plus tard") }
                }
            }
        }
    }
}

/** Le document ouvert, tant que la page le montre. */
internal val DocSession.title: String get() = meta?.title ?: ""
