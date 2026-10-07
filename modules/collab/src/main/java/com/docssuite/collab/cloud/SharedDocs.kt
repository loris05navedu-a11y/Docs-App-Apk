package com.docssuite.collab.cloud

import com.docssuite.collab.store.Registration
import com.docssuite.collab.store.ServerTime
import com.docssuite.collab.store.Store
import com.docssuite.collab.store.StoreException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * L'édition partagée : les documents de chacun, les invitations reçues, la
 * création d'un document, et les documents ouverts.
 *
 * @param linkHost le site des liens d'invitation (`projet.web.app`), s'il est connu.
 * @param keepOpenMillis combien de temps un document fermé reste branché, au
 *   cas où on le rouvre aussitôt (l'écran qui tourne, par exemple).
 */
private const val DENIED_HELP =
    "Le serveur refuse l'écriture. Ferme et rouvre l'application, puis réessaie. " +
        "Si ça persiste, les règles de sécurité de la base ne sont sans doute pas encore mises en ligne (étape deploy.sh)."

class SharedDocs(
    val accounts: Accounts,
    private val store: Store,
    private val linkHost: String?,
    private val scope: CoroutineScope,
    private val checkpointEvery: Int = 100,
    private val heartbeatMillis: Long = 60_000,
    private val keepOpenMillis: Long = 15_000,
) {
    /** Le lien à envoyer pour ouvrir un document. */
    fun link(docId: String): String? = linkHost?.let { "https://$it/d/$docId" }

    /** Les documents de [me], les plus récemment modifiés d'abord. */
    fun documents(me: Account): Flow<List<DocSummary>> = callbackFlow {
        class Entry(val id: String) {
            var meta: DocMeta? = null
            var role: Role? = null
            var updatedAt: Long? = null
            var updatedBy: String? = null
            val registrations = ArrayList<Registration>()

            fun summary(): DocSummary? {
                val m = meta ?: return null
                val r = role ?: return null
                return DocSummary(id, m.title, r, m.ownerName, m.createdAt, updatedAt, updatedBy)
            }
        }

        val entries = LinkedHashMap<String, Entry>()
        var listed = false

        fun publish() {
            if (listed) trySend(entries.values.mapNotNull { it.summary() }.sortedByDescending { it.lastChange })
        }

        // Un document disparu ou retiré : on le sort de la liste, pour de bon.
        fun drop(id: String, forget: Boolean) {
            entries.remove(id)?.registrations?.forEach { it.remove() }
            if (forget) launch { runCatching { store.update(mapOf("userDocs/${me.uid}/$id" to null)) } }
            publish()
        }

        fun follow(id: String) {
            val entry = Entry(id)
            entries[id] = entry
            val lost = { e: StoreException -> drop(id, forget = e is StoreException.PermissionDenied) }
            entry.registrations += store.watch("docs/$id/meta", { value ->
                val meta = parseMeta(value)
                if (meta == null) drop(id, forget = true) else {
                    entry.meta = meta
                    publish()
                }
            }, lost)
            entry.registrations += store.watch("docs/$id/members/${me.uid}/role", { value ->
                val role = Role.of(value)
                if (role == null) drop(id, forget = true) else {
                    entry.role = role
                    publish()
                }
            }, lost)
            entry.registrations += store.watch("docs/$id/activity", { value ->
                val activity = value.asMap()
                entry.updatedAt = activity?.long("at")
                entry.updatedBy = activity?.str("name")
                publish()
            }, {})
        }

        val list = store.watch("userDocs/${me.uid}", { value ->
            val ids = value.asMap()?.keys.orEmpty()
            entries.keys.filter { it !in ids }.forEach { drop(it, forget = false) }
            ids.filter { it !in entries }.forEach(::follow)
            listed = true
            publish()
        }, {
            listed = true
            publish()
        })
        awaitClose {
            list.remove()
            entries.values.forEach { entry -> entry.registrations.forEach { it.remove() } }
        }
    }

    /** Les invitations reçues par [me], les plus récentes d'abord. */
    fun invitations(me: Account): Flow<List<Invitation>> = callbackFlow {
        val registration = store.watch(
            "invitesByEmail/${emailKey(me.email)}",
            { trySend(parseInvitations(it)) },
            { trySend(emptyList()) },
        )
        awaitClose { registration.remove() }
    }

    /** Créer un document partagé dont on est propriétaire ; renvoie son identifiant. */
    suspend fun create(title: String, text: String): String {
        val me = verified()
        val clean = title.trim().take(120).ifEmpty { "Document sans titre" }
        val id = store.newKey()
        val writes = mapOf(
            "docs/$id/meta" to mapOf(
                "title" to clean,
                "ownerUid" to me.uid,
                "ownerName" to me.name.take(100),
                "ownerEmail" to me.email,
                "createdAt" to ServerTime,
            ),
            "docs/$id/members/${me.uid}" to mapOf("role" to Role.OWNER.key, "name" to me.name.take(100), "email" to me.email, "at" to ServerTime),
            "docs/$id/checkpoint" to mapOf("n" to 0, "text" to text),
            "userDocs/${me.uid}/$id" to true,
        )
        attempt(DENIED_HELP) { writeWithFreshToken(writes) }
        return id
    }

    /**
     * Le serveur lit « adresse vérifiée » dans le jeton de connexion, qui ne
     * change qu'au rafraîchissement : juste après la vérification, il peut
     * encore dire le contraire. Sur un refus, on rafraîchit et on réessaie.
     */
    private suspend fun writeWithFreshToken(values: Map<String, Any?>) {
        try {
            store.update(values)
        } catch (e: StoreException.PermissionDenied) {
            runCatching { accounts.refresh() }
            delay(1_500)
            store.update(values)
        }
    }

    /** Accepter une invitation : on entre dans le document avec le rôle donné. */
    suspend fun accept(invitation: Invitation) {
        val me = verified()
        val key = emailKey(me.email)
        attempt("Cette invitation n'est plus valable : elle a pu être annulée ou modifiée.") {
            writeWithFreshToken(
                mapOf(
                    "docs/${invitation.docId}/members/${me.uid}" to mapOf(
                        "role" to invitation.role.key,
                        "name" to me.name.take(100),
                        "email" to me.email,
                        "at" to ServerTime,
                    ),
                    "docs/${invitation.docId}/invites/$key" to null,
                    "invitesByEmail/$key/${invitation.docId}" to null,
                    "userDocs/${me.uid}/${invitation.docId}" to true,
                )
            )
        }
    }

    suspend fun decline(invitation: Invitation) {
        val me = verified()
        val key = emailKey(me.email)
        attempt("Cette invitation n'a pas pu être refusée.") {
            store.update(mapOf("docs/${invitation.docId}/invites/$key" to null, "invitesByEmail/$key/${invitation.docId}" to null))
        }
    }

    private fun verified(): Account {
        val me = accounts.current.value ?: throw CollabException("Connecte-toi d'abord.")
        if (!me.verified) throw CollabException("Vérifie d'abord ton adresse e-mail.")
        return me
    }

    // --- Les documents ouverts ---

    private class Open(val session: DocSession, var users: Int, var closing: Job? = null)

    private val open = HashMap<String, Open>()

    /** Ouvrir (ou reprendre) un document ; à rendre avec [release]. */
    fun acquire(docId: String): DocSession {
        val me = accounts.current.value ?: throw CollabException("Connecte-toi d'abord.")
        open[docId]?.let { entry ->
            if (entry.session.me.uid == me.uid && entry.session.state !is DocSession.State.Closed) {
                entry.closing?.cancel()
                entry.closing = null
                entry.users++
                return entry.session
            }
            entry.closing?.cancel()
            entry.session.dispose()
        }
        val session = DocSession(store, me, docId, scope, checkpointEvery, heartbeatMillis)
        open[docId] = Open(session, users = 1)
        session.start()
        return session
    }

    fun release(session: DocSession) {
        val entry = open[session.docId]?.takeIf { it.session === session } ?: return session.dispose()
        entry.users--
        if (entry.users > 0) return
        if (keepOpenMillis <= 0) {
            open.remove(session.docId)
            session.dispose()
            return
        }
        entry.closing = scope.launch {
            delay(keepOpenMillis)
            if (open[session.docId] === entry && entry.users == 0) {
                open.remove(session.docId)
                session.dispose()
            }
        }
    }

    /** Fermer tous les documents (à la déconnexion). */
    fun closeAll() {
        open.values.forEach {
            it.closing?.cancel()
            it.session.dispose()
        }
        open.clear()
    }
}
