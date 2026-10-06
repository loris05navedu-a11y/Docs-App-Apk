package com.docssuite.collab.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docssuite.collab.cloud.CollabException
import com.docssuite.collab.cloud.DocSession
import com.docssuite.collab.cloud.Member
import com.docssuite.collab.cloud.PendingInvite
import com.docssuite.collab.cloud.Role
import com.docssuite.collab.cloud.SharedDocs
import com.docssuite.core.shareText
import kotlinx.coroutines.launch

/** Qui a accès au document, les invitations, et (pour le propriétaire) inviter. */
@Composable
internal fun PeoplePage(session: DocSession, docs: SharedDocs, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var email by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf(Role.EDITOR) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val owner = session.isOwner
    val now = System.currentTimeMillis()
    BackHandler(onBack = onBack)

    fun act(done: String?, action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
                done?.let { snackbar.showSnackbar(it) }
            } catch (e: CollabException) {
                snackbar.showSnackbar(e.message ?: "Erreur")
            }
        }
    }

    fun sendYourself(invite: PendingInvite) {
        val link = docs.link(session.docId) ?: ""
        val subject = "Invitation à ${verb(invite.role)} « ${session.title} »"
        val body = "Bonjour,\n\nJe vous invite à ${verb(invite.role)} le document « ${session.title} » dans l'application DocsApp Suite.\n\n" +
            (if (link.isNotEmpty()) "Ouvrez ce lien sur votre téléphone Android :\n$link\n\n" else "") +
            "Connectez-vous avec cette adresse (${invite.email}) : le document vous attend dans « Partagés avec moi ».\n\n${session.me.name}"
        try {
            context.startActivity(mailIntent(invite.email, subject, body))
        } catch (e: ActivityNotFoundException) {
            shareText(context, subject, body)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { SimpleTopBar("Personnes et partage", onBack) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (owner) {
                item {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Inviter quelqu'un", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it; error = null },
                                label = { Text("Adresse e-mail") },
                                placeholder = { Text("prenom.nom@exemple.fr") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Role.grantable.forEach { option ->
                                    FilterChip(selected = role == option, onClick = { role = option }, label = { Text(option.label) })
                                }
                            }
                            Text(role.description + ".", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                            Button(
                                onClick = {
                                    if (busy) return@Button
                                    busy = true
                                    val address = email.trim()
                                    scope.launch {
                                        try {
                                            session.invite(address, role)
                                            email = ""
                                            snackbar.showSnackbar("Invitation envoyée à $address.")
                                        } catch (e: CollabException) {
                                            error = e.message
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                                enabled = !busy && email.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Inviter") }
                            Text(
                                "La personne reçoit un e-mail, et retrouve le document dans l'application en se connectant avec cette adresse.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                item {
                    Explanation(
                        "Le propriétaire gère le partage",
                        "Seul ${session.meta?.ownerName?.ifBlank { null } ?: "le propriétaire"} peut inviter des personnes ou changer les rôles.",
                    )
                }
            }

            item { SectionTitle("Ont accès (${session.members.size})") }
            items(session.members, key = { it.uid }) { member ->
                MemberRow(
                    member = member,
                    isMe = member.uid == session.me.uid,
                    canManage = owner && member.role != Role.OWNER,
                    onRole = { act("${member.name} : ${it.label.lowercase()}.") { session.setRole(member, it) } },
                    onRemove = { act("${member.name} n'a plus accès.") { session.remove(member) } },
                )
            }

            if (session.invites.isNotEmpty()) {
                item { SectionTitle("Invitations en attente (${session.invites.size})") }
                items(session.invites, key = { "invite-" + it.key }) { invite ->
                    InviteRow(
                        invite = invite,
                        now = now,
                        canManage = owner,
                        onRole = { act("Rôle changé.") { session.setRole(invite, it) } },
                        onResend = { act("E-mail renvoyé à ${invite.email}.") { session.resend(invite) } },
                        onSendYourself = { sendYourself(invite) },
                        onCancel = { act("Invitation annulée.") { session.cancel(invite) } },
                    )
                }
            }

            item {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Role.values().forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RoleChip(r)
                            Spacer(Modifier.width(8.dp))
                            Text(r.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberRow(member: Member, isMe: Boolean, canManage: Boolean, onRole: (Role) -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(member.name, colorOf(member.uid))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (isMe) "${member.name} (toi)" else member.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(member.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!canManage) {
            RoleChip(member.role)
        } else {
            Box {
                TextButton(onClick = { menu = true }) {
                    Text(member.role.label, color = roleColor(member.role))
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "Changer le rôle de ${member.name}", tint = roleColor(member.role))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    Role.grantable.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label, fontWeight = if (option == member.role) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { menu = false; if (option != member.role) onRole(option) },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Retirer l'accès", color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onRemove() },
                    )
                }
            }
        }
    }
}

@Composable
private fun InviteRow(
    invite: PendingInvite,
    now: Long,
    canManage: Boolean,
    onRole: (Role) -> Unit,
    onResend: () -> Unit,
    onSendYourself: () -> Unit,
    onCancel: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val (status, problem) = mailStatus(invite, now)
    val statusColor = if (problem) Color(0xFFD97706) else Color(0xFF059669)
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(invite.email, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when {
                                problem -> Icons.Filled.ErrorOutline
                                invite.mail == com.docssuite.collab.cloud.MailState.SENT -> Icons.Filled.CheckCircle
                                else -> Icons.Filled.Schedule
                            },
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(status, style = MaterialTheme.typography.bodySmall, color = statusColor)
                    }
                }
                if (canManage) {
                    Box {
                        TextButton(onClick = { menu = true }) {
                            Text(invite.role.label, color = roleColor(invite.role))
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Gérer l'invitation de ${invite.email}", tint = roleColor(invite.role))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            Role.grantable.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label, fontWeight = if (option == invite.role) FontWeight.Bold else FontWeight.Normal) },
                                    onClick = { menu = false; if (option != invite.role) onRole(option) },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Renvoyer l'e-mail") }, onClick = { menu = false; onResend() })
                            DropdownMenuItem(text = { Text("Envoyer depuis ma messagerie") }, onClick = { menu = false; onSendYourself() })
                            DropdownMenuItem(
                                text = { Text("Annuler l'invitation", color = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; onCancel() },
                            )
                        }
                    }
                } else {
                    RoleChip(invite.role)
                }
            }
            if (canManage && problem) {
                TextButton(onClick = onSendYourself, contentPadding = PaddingValues(0.dp)) {
                    Text("Envoyer l'invitation depuis ma messagerie")
                }
            }
        }
    }
}
