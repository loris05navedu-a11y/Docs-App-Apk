package com.docssuite.collab.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.docssuite.collab.cloud.CollabException
import com.docssuite.collab.cloud.DocSession
import com.docssuite.collab.cloud.Role
import com.docssuite.collab.cloud.SharedDocs
import com.docssuite.collab.cloud.colorIndex
import com.docssuite.core.DocumentStorage
import com.docssuite.core.shareText
import com.docssuite.fileformats.TextDocument
import com.docssuite.fileformats.TextParagraph
import com.docssuite.fileformats.TextRun
import com.docssuite.texteditor.saveImportedTextDocument
import kotlinx.coroutines.launch

/** Le document partagé, en direct. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DocPage(
    session: DocSession,
    docs: SharedDocs,
    onBack: () -> Unit,
    onPeople: () -> Unit,
    onComments: (quote: String?) -> Unit,
    onOpenLocal: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmQuit by remember { mutableStateOf(false) }
    val live = session.text
    val unsynced = live != null && !live.synced

    fun act(done: String? = null, action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
                done?.let { snackbar.showSnackbar(it) }
            } catch (e: CollabException) {
                snackbar.showSnackbar(e.message ?: "Erreur")
            }
        }
    }

    fun leavePage() = if (unsynced && session.state == DocSession.State.Open) confirmQuit = true else onBack()

    BackHandler { leavePage() }

    session.notice?.let { notice ->
        LaunchedEffect(notice) {
            snackbar.showSnackbar(notice)
            session.notice = null
        }
    }

    val unresolved = session.comments.count { !it.resolved }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(session.title.ifEmpty { "Document partagé" }, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        SyncLine(session)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { leavePage() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
                actions = {
                    if (session.state == DocSession.State.Open) {
                        IconButton(onClick = { onComments(null) }) {
                            BadgedBox(badge = { if (unresolved > 0) Badge { Text("$unresolved") } }) {
                                Icon(Icons.AutoMirrored.Filled.Comment, contentDescription = "Commentaires")
                            }
                        }
                        IconButton(onClick = onPeople) { Icon(Icons.Filled.PersonAdd, contentDescription = "Personnes et partage") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Plus d'options") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Copier dans mes documents") }, onClick = {
                                    menu = false
                                    val text = session.text?.value?.text.orEmpty()
                                    val name = session.title.ifEmpty { "Document partagé" }
                                    val document = TextDocument(name, text.split('\n').map { TextParagraph(listOf(TextRun(it))) })
                                    onOpenLocal(saveImportedTextDocument(DocumentStorage(context), document, name))
                                })
                                docs.link(session.docId)?.let { link ->
                                    DropdownMenuItem(text = { Text("Partager le lien") }, onClick = {
                                        menu = false
                                        shareText(context, session.title, "« ${session.title} » dans DocsApp Suite : $link")
                                    })
                                }
                                if (session.isOwner) {
                                    DropdownMenuItem(text = { Text("Renommer") }, onClick = { menu = false; renaming = true })
                                    DropdownMenuItem(text = { Text("Supprimer le document") }, onClick = { menu = false; confirmDelete = true })
                                } else {
                                    DropdownMenuItem(text = { Text("Quitter le document") }, onClick = { menu = false; confirmLeave = true })
                                }
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (session.canComment) {
                SmallFloatingActionButton(onClick = {
                    val value = session.text?.value
                    val quote = value?.let { v -> v.text.substring(v.selection.min.coerceAtMost(v.text.length), v.selection.max.coerceAtMost(v.text.length)) }
                    onComments(quote?.trim()?.take(300)?.ifEmpty { null })
                }) { Icon(Icons.AutoMirrored.Filled.Comment, contentDescription = "Commenter") }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (val state = session.state) {
                DocSession.State.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Ouverture du document…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is DocSession.State.Closed -> Closed(state.reason, session, onBack)
                DocSession.State.Open -> {
                    Presence(session)
                    if (!session.connected) Banner("Hors ligne : tes frappes partiront dès le retour du réseau.", Color(0xFFD97706))
                    when (session.role) {
                        Role.READER -> Banner("Lecture seule : tu vois les modifications en direct.", MaterialTheme.colorScheme.onSurfaceVariant)
                        Role.COMMENTER -> Banner("Tu peux commenter (sélectionne du texte), pas modifier.", MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> {}
                    }
                    if (session.isOwner && session.members.size <= 1 && session.invites.isEmpty()) {
                        Surface(color = Accent.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Personne d'autre n'a encore accès.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = onPeople) { Text("Inviter") }
                            }
                        }
                    }
                    if (live != null) LiveTextField(session)
                }
            }
        }
    }

    if (renaming) {
        var title by remember { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Renommer") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it.take(120) }, singleLine = true, label = { Text("Titre") }) },
            confirmButton = {
                TextButton(onClick = { renaming = false; act { session.rename(title) } }, enabled = title.isNotBlank()) { Text("Renommer") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Annuler") } },
        )
    }
    if (confirmDelete) {
        Confirm(
            "Supprimer le document ?",
            "« ${session.title} » sera supprimé pour tout le monde, avec ses commentaires. C'est définitif.",
            "Supprimer",
            onConfirm = { confirmDelete = false; act { session.delete() } },
            onDismiss = { confirmDelete = false },
        )
    }
    if (confirmLeave) {
        Confirm(
            "Quitter le document ?",
            "Tu n'y auras plus accès, sauf si son propriétaire t'invite à nouveau.",
            "Quitter",
            onConfirm = { confirmLeave = false; act { session.leave() } },
            onDismiss = { confirmLeave = false },
        )
    }
    if (confirmQuit) {
        Confirm(
            "Modifications pas encore enregistrées",
            if (session.connected) "Tes dernières frappes sont en cours d'envoi. Patiente un instant, ou quitte quand même."
            else "Tu es hors ligne : tes dernières frappes n'ont pas pu partir. Si tu quittes maintenant, elles seront perdues.",
            "Quitter quand même",
            onConfirm = { confirmQuit = false; onBack() },
            onDismiss = { confirmQuit = false },
        )
    }
}

@Composable
private fun Confirm(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun SyncLine(session: DocSession) {
    val live = session.text
    val (icon, text) = when {
        session.state != DocSession.State.Open || live == null -> return
        !session.connected -> Icons.Filled.CloudOff to "Hors ligne"
        !live.synced -> Icons.Filled.CloudUpload to "Enregistrement…"
        else -> Icons.Filled.CloudDone to "Enregistré"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Banner(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.10f), modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** Qui a le document ouvert en ce moment. */
@Composable
private fun Presence(session: DocSession) {
    val others = session.others
    LazyRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            Text(
                if (others.isEmpty()) "Personne d'autre en ce moment" else "Aussi ici :",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(others, key = { it.clientId }) { other ->
            Surface(shape = RoundedCornerShape(50), color = personColor(other.color).copy(alpha = 0.12f)) {
                Row(Modifier.padding(start = 3.dp, end = 10.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(other.name, personColor(other.color), size = 22.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(other.name.substringBefore(' '), style = MaterialTheme.typography.labelMedium, color = personColor(other.color))
                }
            }
        }
    }
}

@Composable
private fun Closed(reason: String, session: DocSession, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(reason, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (reason.startsWith("Tu n'as pas accès") || reason.startsWith("Tu n'as plus accès")) {
            Text(
                "Pour l'ouvrir, demande à son propriétaire de t'inviter avec l'adresse ${session.me.email}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onBack) { Text("Retour à mes documents") }
    }
}

/** Le champ de texte partagé, avec les curseurs et sélections des autres. */
@Composable
private fun LiveTextField(session: DocSession) {
    val live = session.text ?: return
    val value = live.value
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    val others = session.others.associateBy { it.clientId }
    val cursors = live.cursors.toMap()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BasicTextField(
            value = value,
            onValueChange = session::onValueChange,
            readOnly = !session.canEdit,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, lineHeight = 26.sp),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 480.dp)
                .semantics { contentDescription = "Texte du document" }
                // Dessiné autour de la marge : le prénom de la première ligne tient au-dessus.
                .drawWithContent {
                    val l = layout
                    if (l == null) {
                        drawContent()
                        return@drawWithContent
                    }
                    val length = l.layoutInput.text.length
                    val left = 20.dp.toPx()
                    val top = 16.dp.toPx()
                    // Les sélections des autres, sous le texte.
                    translate(left, top) {
                        for ((id, cursor) in cursors) {
                            val color = personColor(others[id]?.color ?: 0)
                            val start = minOf(cursor.anchor, cursor.caret).coerceIn(0, length)
                            val end = maxOf(cursor.anchor, cursor.caret).coerceIn(0, length)
                            if (start < end) drawPath(l.getPathForRange(start, end), color.copy(alpha = 0.22f))
                        }
                    }
                    drawContent()
                    // Leurs curseurs, avec leur prénom.
                    translate(left, top) {
                        for ((id, cursor) in cursors) {
                            val other = others[id] ?: continue
                            val color = personColor(other.color)
                            val rect = l.getCursorRect(cursor.caret.coerceIn(0, length))
                            drawRect(color, Offset(rect.left, rect.top), Size(2.dp.toPx(), rect.height))
                            val label = measurer.measure(other.name.substringBefore(' ').take(14), labelStyle)
                            val padding = 4.dp.toPx()
                            val width = label.size.width + padding * 2
                            val height = label.size.height.toFloat()
                            val x = rect.left.coerceIn(-left, size.width - left - width)
                            val y = (rect.top - height).coerceAtLeast(-top)
                            drawRoundRect(color, Offset(x, y), Size(width, height), CornerRadius(4.dp.toPx()))
                            drawText(label, topLeft = Offset(x + padding, y))
                        }
                    }
                }
                .padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
    SideEffect { session.shown(value) }
}

/** Écrire à quelqu'un depuis sa propre messagerie. */
internal fun mailIntent(to: String, subject: String, body: String): Intent =
    Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:")).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }

/** La couleur d'une personne, d'après son compte. */
internal fun colorOf(uid: String): Color = personColor(colorIndex(uid))
