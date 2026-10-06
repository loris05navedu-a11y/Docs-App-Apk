package com.docssuite.collab.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docssuite.collab.cloud.CollabException
import com.docssuite.collab.cloud.Comment
import com.docssuite.collab.cloud.DocSession
import com.docssuite.collab.cloud.Role
import kotlinx.coroutines.launch

/** Les commentaires du document, à écrire, régler ou supprimer. */
@Composable
internal fun CommentsPage(session: DocSession, initialQuote: String?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var draft by rememberSaveable { mutableStateOf("") }
    var quote by rememberSaveable { mutableStateOf(initialQuote) }
    var busy by remember { mutableStateOf(false) }
    var showResolved by remember { mutableStateOf(false) }
    val role = session.role
    BackHandler(onBack = onBack)

    fun act(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: CollabException) {
                snackbar.showSnackbar(e.message ?: "Erreur")
            }
        }
    }

    val open = session.comments.filter { !it.resolved }
    val resolved = session.comments.filter { it.resolved }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { SimpleTopBar("Commentaires", onBack) },
        bottomBar = {
            if (session.canComment) {
                Surface(tonalElevation = 3.dp, modifier = Modifier.imePadding()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        quote?.let { q ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Quote(q, Modifier.weight(1f))
                                IconButton(onClick = { quote = null }) { Icon(Icons.Filled.Close, contentDescription = "Ne plus citer") }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = draft,
                                onValueChange = { draft = it.take(2000) },
                                placeholder = { Text("Votre commentaire") },
                                modifier = Modifier.weight(1f),
                                maxLines = 5,
                            )
                            IconButton(
                                onClick = {
                                    if (busy) return@IconButton
                                    busy = true
                                    scope.launch {
                                        try {
                                            session.comment(draft, quote)
                                            draft = ""
                                            quote = null
                                        } catch (e: CollabException) {
                                            snackbar.showSnackbar(e.message ?: "Erreur")
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                                enabled = draft.isNotBlank() && !busy,
                            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Publier le commentaire") }
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!session.canComment && role != null) {
                item { Explanation("Lecture seule", "Votre rôle (${role.label}) permet de lire les commentaires, pas d'en écrire.") }
            }
            if (open.isEmpty()) {
                item {
                    Text(
                        if (resolved.isEmpty()) "Aucun commentaire pour l'instant. Sélectionnez un passage du texte, puis touchez le bouton « Commenter »."
                        else "Tous les commentaires sont réglés.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(open, key = { it.id }) { comment -> CommentCard(comment, session, ::act) }
            if (resolved.isNotEmpty()) {
                item {
                    TextButton(onClick = { showResolved = !showResolved }) {
                        Text(if (showResolved) "Masquer les commentaires réglés" else "Voir les commentaires réglés (${resolved.size})")
                    }
                }
                if (showResolved) items(resolved, key = { it.id }) { comment -> CommentCard(comment, session, ::act) }
            }
        }
    }
}

@Composable
private fun Quote(text: String, modifier: Modifier = Modifier) {
    Row(modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(Accent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CommentCard(comment: Comment, session: DocSession, act: (suspend () -> Unit) -> Unit) {
    val mine = comment.uid == session.me.uid
    val role = session.role
    val canResolve = session.state == DocSession.State.Open && (mine || role == Role.OWNER || role == Role.EDITOR)
    val canDelete = session.state == DocSession.State.Open && (mine || role == Role.OWNER)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (comment.resolved) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(comment.name, colorOf(comment.uid), size = 28.dp)
                Spacer(Modifier.width(8.dp))
                Text(comment.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ago(comment.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            comment.quote?.let { Quote(it) }
            Text(comment.text, style = MaterialTheme.typography.bodyMedium)
            if (canResolve || canDelete) {
                HorizontalDivider()
                Row {
                    if (canResolve) {
                        TextButton(onClick = { act { session.resolve(comment, !comment.resolved) } }) {
                            Text(if (comment.resolved) "Rouvrir" else "Marquer comme réglé")
                        }
                    }
                    if (canDelete) {
                        TextButton(onClick = { act { session.delete(comment) } }) {
                            Text("Supprimer", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
