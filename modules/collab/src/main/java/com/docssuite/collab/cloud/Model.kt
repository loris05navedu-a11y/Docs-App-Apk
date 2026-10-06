package com.docssuite.collab.cloud

/** Ce qu'une personne peut faire dans un document partagé. */
enum class Role(val key: String, val label: String, val description: String) {
    OWNER("owner", "Propriétaire", "Tout, et seul à gérer qui a accès"),
    EDITOR("editor", "Éditeur", "Modifie le texte et commente"),
    COMMENTER("commenter", "Commentateur", "Lit et commente, sans modifier"),
    READER("reader", "Lecteur", "Lit seulement");

    val canEdit: Boolean get() = this == OWNER || this == EDITOR
    val canComment: Boolean get() = this != READER

    companion object {
        /** Les rôles qu'on peut donner à quelqu'un. */
        val grantable = listOf(EDITOR, COMMENTER, READER)

        fun of(key: Any?): Role? = values().firstOrNull { it.key == key }
    }
}

/** La personne connectée. */
data class Account(
    val uid: String,
    val name: String,
    val email: String,
    val verified: Boolean,
    val viaGoogle: Boolean = false,
)

/** Un document de la liste. */
data class DocSummary(
    val id: String,
    val title: String,
    val role: Role,
    val ownerName: String,
    val createdAt: Long,
    val updatedAt: Long?,
    val updatedBy: String?,
) {
    val lastChange: Long get() = updatedAt ?: createdAt
}

/** Une invitation reçue, en attente de réponse. */
data class Invitation(
    val docId: String,
    val title: String,
    val role: Role,
    val byName: String,
    val at: Long,
)

/** Le titre et la ou le propriétaire d'un document. */
data class DocMeta(
    val title: String,
    val ownerUid: String,
    val ownerName: String,
    val ownerEmail: String,
    val createdAt: Long,
)

/** Une personne qui a accès au document. */
data class Member(val uid: String, val name: String, val email: String, val role: Role)

/** Où en est l'e-mail d'une invitation, selon le serveur. */
enum class MailState(val key: String) {
    SENDING("sending"), SENT("sent"), FAILED("failed"), QUOTA("quota"), REFUSED("refused");

    companion object {
        fun of(key: Any?): MailState? = values().firstOrNull { it.key == key }
    }
}

/** Une invitation envoyée, pas encore acceptée. */
data class PendingInvite(
    val key: String,
    val email: String,
    val role: Role,
    val at: Long,
    val mail: MailState?,
    val mailAt: Long?,
)

data class Comment(
    val id: String,
    val uid: String,
    val name: String,
    val text: String,
    val quote: String?,
    val at: Long,
    val resolved: Boolean,
)

/** Un autre appareil qui a le document ouvert. */
data class Collaborator(
    val clientId: String,
    val uid: String,
    val name: String,
    val color: Int,
    val anchor: Int,
    val caret: Int,
    val at: Long,
)

/** Une erreur à montrer telle quelle. */
class CollabException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Une adresse e-mail comme clé de la base : en minuscules, points remplacés par des virgules. */
fun emailKey(email: String): String = email.trim().lowercase().replace('.', ',')

private val EMAIL = Regex("^[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$")

fun isEmail(text: String): Boolean = text.length <= 254 && EMAIL.matches(text.trim())

/** Le numéro de couleur (0 à 7) d'une personne, toujours le même. */
fun colorIndex(uid: String): Int = Math.floorMod(uid.hashCode(), 8)
