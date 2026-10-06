package com.docssuite.collab.cloud

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import com.docssuite.collab.live.LiveText
import com.docssuite.collab.ot.OperationCodec
import com.docssuite.collab.ot.Revision
import com.docssuite.collab.ot.RevisionLog
import com.docssuite.collab.store.Registration
import com.docssuite.collab.store.ServerTime
import com.docssuite.collab.store.Store
import com.docssuite.collab.store.StoreException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Un document partagé ouvert : son texte en direct, les personnes qui y ont
 * accès, les invitations, les commentaires, et qui d'autre l'a ouvert.
 *
 * Tout ce qu'il expose est de l'état Compose : l'écran se redessine seul.
 * À n'utiliser que sur le fil principal.
 */
class DocSession internal constructor(
    private val store: Store,
    val me: Account,
    val docId: String,
    private val scope: CoroutineScope,
    private val checkpointEvery: Int,
    private val heartbeatMillis: Long,
) {
    sealed interface State {
        object Loading : State
        object Open : State
        data class Closed(val reason: String) : State
    }

    /** Cet appareil, pour cette ouverture du document. */
    val clientId: String = store.newKey()

    var state: State by mutableStateOf(State.Loading)
        private set
    var text: LiveText? by mutableStateOf(null)
        private set
    var meta: DocMeta? by mutableStateOf(null)
        private set
    var role: Role? by mutableStateOf(null)
        private set
    var members: List<Member> by mutableStateOf(emptyList())
        private set
    var invites: List<PendingInvite> by mutableStateOf(emptyList())
        private set
    var comments: List<Comment> by mutableStateOf(emptyList())
        private set

    /** Les autres appareils qui ont le document ouvert. */
    var others: List<Collaborator> by mutableStateOf(emptyList())
        private set
    var connected: Boolean by mutableStateOf(true)
        private set

    /** Un message à montrer une fois, puis à effacer. */
    var notice: String? by mutableStateOf(null)

    val canEdit: Boolean get() = state == State.Open && role?.canEdit == true
    val canComment: Boolean get() = state == State.Open && role?.canComment == true
    val isOwner: Boolean get() = role == Role.OWNER

    private val registrations = ArrayList<Registration>()
    private val jobs = ArrayList<Job>()
    private val presencePath = "docs/$docId/presence/$clientId"
    private var presenceScheduled = false
    private var published: Pair<Int, Int>? = null
    private var activityAt = 0L
    private var disposed = false

    private val log = object : RevisionLog {
        override fun append(index: Int, revision: Revision) {
            if (disposed) return
            val value = mapOf(
                "c" to revision.clientId,
                "a" to revision.uid,
                "o" to OperationCodec.encode(revision.operation),
                "t" to ServerTime,
            )
            store.claim("docs/$docId/history/${Slots.key(index)}", value) { _, error ->
                when {
                    error == null || disposed -> {}
                    error is StoreException.PermissionDenied -> writeDenied()
                    else -> jobs += scope.launch {
                        delay(2_000)
                        append(index, revision)
                    }
                }
            }
        }

        override fun saveCheckpoint(nextIndex: Int, text: String) {
            jobs += scope.launch {
                runCatching { store.update(mapOf("docs/$docId/checkpoint" to mapOf("n" to nextIndex, "text" to text))) }
            }
        }
    }

    internal fun start() {
        watch("docs/$docId/members/${me.uid}/role") { value ->
            val now = Role.of(value)
            if (now == null) {
                close("Vous n'avez plus accès à ce document.")
            } else {
                val before = role
                role = now
                if (before?.canEdit == true && !now.canEdit) writeDenied()
            }
        }
        watch("docs/$docId/meta") { value ->
            val parsed = parseMeta(value)
            if (parsed == null) close("Ce document a été supprimé.") else meta = parsed
        }
        watch("docs/$docId/members") { members = parseMembers(it) }
        watch("docs/$docId/invites") { invites = parseInvites(it) }
        watch("docs/$docId/comments") { comments = parseComments(it) }
        watch("docs/$docId/presence") { showOthers(parsePresence(it)) }
        registrations += store.watchConnected { online ->
            val reconnected = online && !connected
            connected = online
            // La déconnexion a effacé notre présence : on la remet.
            if (reconnected && state == State.Open) announce(force = true)
        }
        jobs += scope.launch { load() }
    }

    private suspend fun load() {
        val checkpoint = try {
            store.read("docs/$docId/checkpoint").asMap()
        } catch (e: StoreException) {
            close(
                if (e is StoreException.PermissionDenied) "Vous n'avez pas accès à ce document."
                else "Le document ne s'ouvre pas : vérifiez votre connexion à Internet."
            )
            return
        }
        if (disposed || state is State.Closed) return
        val start = checkpoint?.long("n")?.toInt()?.coerceAtLeast(0) ?: 0
        val live = LiveText(clientId, me.uid, log, start, checkpoint?.str("text").orEmpty(), checkpointEvery)
        live.onLocalEdit = ::localEdit
        text = live
        registrations += store.watchChildren(
            "docs/$docId/history",
            Slots.key(start),
            onChild = { key, value -> Slots.index(key)?.let { live.receive(it, parseRevision(value)) } },
            onError = { close("Vous n'avez plus accès à ce document.") },
        )
        state = State.Open
        others.forEach { live.setCursor(it.clientId, it.anchor, it.caret) }
        announce(force = true)
        if (heartbeatMillis > 0) jobs += scope.launch {
            while (true) {
                delay(heartbeatMillis)
                announce(force = true)
            }
        }
    }

    /** Ce que propose le champ de saisie : frappe, curseur, sélection. */
    fun onValueChange(value: TextFieldValue) {
        val live = text ?: return
        live.onValueChange(value, editable = canEdit)
        announce()
    }

    /** Le champ affiche désormais [value]. */
    fun shown(value: TextFieldValue) {
        text?.shown(value)
    }

    private fun localEdit() {
        val now = store.serverNow()
        if (now - activityAt < 30_000) return
        activityAt = now
        jobs += scope.launch {
            runCatching {
                store.update(mapOf("docs/$docId/activity" to mapOf("at" to ServerTime, "by" to me.uid, "name" to me.name.take(100))))
            }
        }
    }

    /** Dire aux autres où est notre curseur ; pas plus de quelques fois par seconde. */
    private fun announce(force: Boolean = false) {
        if (disposed || state != State.Open) return
        if (force) {
            published = null
            jobs += scope.launch { writePresence(force = true) }
            return
        }
        if (presenceScheduled) return
        presenceScheduled = true
        jobs += scope.launch {
            delay(150)
            presenceScheduled = false
            writePresence(force = false)
        }
    }

    private suspend fun writePresence(force: Boolean) {
        val live = text ?: return
        if (disposed) return
        val selection = live.value.selection
        val position = selection.start to selection.end
        if (!force && position == published) return
        published = position
        runCatching {
            store.update(
                mapOf(
                    presencePath to mapOf(
                        "a" to me.uid,
                        "name" to me.name.take(100),
                        "color" to colorIndex(me.uid),
                        "s" to selection.start,
                        "c" to selection.end,
                        "at" to ServerTime,
                    )
                )
            )
            if (force) store.removeOnDisconnect(presencePath)
        }
    }

    private fun showOthers(all: List<Collaborator>) {
        val now = store.serverNow()
        val visible = all.filter { it.clientId != clientId && now - it.at < 3 * 60_000 }
        others = visible
        val live = text ?: return
        val ids = visible.map { it.clientId }.toSet()
        live.cursors.keys.filter { it !in ids }.forEach(live::removeCursor)
        visible.forEach { live.setCursor(it.clientId, it.anchor, it.caret) }
    }

    /** On ne peut plus écrire : on revient au texte enregistré, en le disant. */
    private fun writeDenied() {
        val live = text ?: return
        if (live.synced) return
        live.discardLocal()
        notice = "Vous ne pouvez plus modifier ce document : vos dernières frappes n'ont pas été enregistrées."
    }

    private fun watch(path: String, onValue: (Any?) -> Unit) {
        registrations += store.watch(path, onValue) { close("Vous n'avez plus accès à ce document.") }
    }

    private fun close(reason: String) {
        if (state is State.Closed) return
        state = State.Closed(reason)
        stop()
    }

    /** Arrêter de suivre le document et retirer notre présence. */
    fun dispose() {
        if (disposed) return
        stop()
        disposed = true
    }

    private fun stop() {
        registrations.forEach { it.remove() }
        registrations.clear()
        jobs.forEach { it.cancel() }
        jobs.clear()
        store.cancelOnDisconnect(presencePath)
        if (!disposed) scope.launch { runCatching { store.update(mapOf(presencePath to null)) } }
        disposed = true
    }

    // --- Les personnes ---

    /** Inviter une adresse e-mail (propriétaire seulement). */
    suspend fun invite(email: String, role: Role) {
        val address = email.trim()
        if (!isEmail(address)) throw CollabException("Cette adresse e-mail n'est pas valide.")
        if (address.equals(me.email, ignoreCase = true)) throw CollabException("C'est votre propre adresse.")
        if (members.any { it.email.equals(address, ignoreCase = true) }) {
            throw CollabException("Cette personne a déjà accès au document.")
        }
        if (invites.any { it.key == emailKey(address) }) {
            throw CollabException("Une invitation attend déjà cette adresse. Vous pouvez la renvoyer.")
        }
        owner { store.update(invitation(address, role)) }
    }

    /** Renvoyer l'e-mail d'une invitation : on l'efface et on la recrée. */
    suspend fun resend(invite: PendingInvite) {
        owner {
            store.update(mapOf("docs/$docId/invites/${invite.key}" to null))
            store.update(invitation(invite.email, invite.role))
        }
    }

    private fun invitation(address: String, role: Role): Map<String, Any?> {
        val key = emailKey(address)
        val name = me.name.take(100)
        return mapOf(
            "docs/$docId/invites/$key" to mapOf("email" to address, "role" to role.key, "at" to ServerTime, "by" to me.uid, "byName" to name),
            "invitesByEmail/$key/$docId" to mapOf("title" to meta?.title.orEmpty(), "role" to role.key, "byName" to name, "at" to ServerTime),
        )
    }

    suspend fun setRole(member: Member, role: Role) {
        require(role != Role.OWNER)
        owner { store.update(mapOf("docs/$docId/members/${member.uid}/role" to role.key)) }
    }

    suspend fun setRole(invite: PendingInvite, role: Role) {
        require(role != Role.OWNER)
        owner {
            store.update(
                mapOf(
                    "docs/$docId/invites/${invite.key}/role" to role.key,
                    "invitesByEmail/${invite.key}/$docId/role" to role.key,
                )
            )
        }
    }

    suspend fun cancel(invite: PendingInvite) {
        owner {
            store.update(mapOf("docs/$docId/invites/${invite.key}" to null, "invitesByEmail/${invite.key}/$docId" to null))
        }
    }

    suspend fun remove(member: Member) {
        owner { store.update(mapOf("docs/$docId/members/${member.uid}" to null, "userDocs/${member.uid}/$docId" to null)) }
    }

    suspend fun rename(title: String) {
        val clean = title.trim().take(120)
        if (clean.isEmpty()) throw CollabException("Donnez un titre au document.")
        owner {
            store.update(
                buildMap {
                    put("docs/$docId/meta/title", clean)
                    invites.forEach { put("invitesByEmail/${it.key}/$docId/title", clean) }
                }
            )
        }
    }

    /** Supprimer le document pour tout le monde. */
    suspend fun delete() {
        owner {
            store.update(
                buildMap {
                    put("docs/$docId", null)
                    members.forEach { put("userDocs/${it.uid}/$docId", null) }
                    put("userDocs/${me.uid}/$docId", null)
                    invites.forEach { put("invitesByEmail/${it.key}/$docId", null) }
                }
            )
        }
        close("Document supprimé.")
    }

    /** Ne plus avoir accès au document (sauf propriétaire). */
    suspend fun leave() {
        val ownerCannot = "Le propriétaire ne peut pas quitter son document ; il peut le supprimer."
        if (isOwner) throw CollabException(ownerCannot)
        attempt(ownerCannot) {
            store.update(
                mapOf(
                    presencePath to null,
                    "docs/$docId/members/${me.uid}" to null,
                    "userDocs/${me.uid}/$docId" to null,
                )
            )
        }
        close("Vous avez quitté le document.")
    }

    // --- Les commentaires ---

    suspend fun comment(text: String, quote: String?) {
        val clean = text.trim()
        if (clean.isEmpty()) throw CollabException("Le commentaire est vide.")
        if (clean.length > 2000) throw CollabException("Le commentaire est trop long (2 000 caractères au plus).")
        attempt("Votre rôle ne permet pas de commenter.") {
            store.update(
                mapOf(
                    "docs/$docId/comments/${store.newKey()}" to mapOf(
                        "a" to me.uid,
                        "name" to me.name.take(100),
                        "text" to clean,
                        "quote" to quote?.trim()?.take(300)?.ifEmpty { null },
                        "at" to ServerTime,
                    )
                )
            )
        }
    }

    suspend fun resolve(comment: Comment, resolved: Boolean) {
        attempt("Seuls son auteur, les éditeurs et le propriétaire peuvent régler ce commentaire.") {
            store.update(mapOf("docs/$docId/comments/${comment.id}/resolved" to resolved))
        }
    }

    suspend fun delete(comment: Comment) {
        attempt("Seuls son auteur et le propriétaire peuvent supprimer ce commentaire.") {
            store.update(mapOf("docs/$docId/comments/${comment.id}" to null))
        }
    }

    private suspend fun owner(block: suspend () -> Unit) =
        attempt("Seul le propriétaire du document peut faire cela.", block)
}

/** Une action sur la base, avec des erreurs à montrer telles quelles. */
internal suspend fun <T> attempt(denied: String, block: suspend () -> T): T =
    try {
        block()
    } catch (e: StoreException.PermissionDenied) {
        throw CollabException(denied, e)
    } catch (e: StoreException.Unavailable) {
        throw CollabException("Pas de connexion à Internet. Réessayez dans un instant.", e)
    }
