package com.docssuite.collab.cloud

import com.docssuite.collab.ot.OperationCodec
import com.docssuite.collab.ot.Revision

// Lire ce que la base renvoie, sans jamais faire confiance à sa forme : une
// donnée incomplète ou abîmée est ignorée, jamais une cause de plantage.

internal fun Any?.asMap(): Map<String, Any?>? =
    (this as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v }

internal fun Map<String, Any?>.str(key: String): String? = this[key] as? String

internal fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()

/** Les places du journal : r000000000, r000000001… */
internal object Slots {
    fun key(index: Int): String = "r" + index.toString().padStart(9, '0')

    fun index(key: String): Int? =
        if (key.length == 10 && key[0] == 'r' && key.drop(1).all { it.isDigit() }) key.drop(1).toInt() else null
}

internal fun parseMeta(value: Any?): DocMeta? {
    val m = value.asMap() ?: return null
    return DocMeta(
        title = m.str("title") ?: return null,
        ownerUid = m.str("ownerUid") ?: return null,
        ownerName = m.str("ownerName").orEmpty(),
        ownerEmail = m.str("ownerEmail").orEmpty(),
        createdAt = m.long("createdAt") ?: 0L,
    )
}

internal fun parseMembers(value: Any?): List<Member> =
    value.asMap().orEmpty().mapNotNull { (uid, raw) ->
        val m = raw.asMap() ?: return@mapNotNull null
        Member(uid, m.str("name").orEmpty(), m.str("email").orEmpty(), Role.of(m["role"]) ?: return@mapNotNull null)
    }.sortedWith(compareBy<Member>({ it.role.ordinal }, { it.name.lowercase() }))

internal fun parseInvites(value: Any?): List<PendingInvite> =
    value.asMap().orEmpty().mapNotNull { (key, raw) ->
        val m = raw.asMap() ?: return@mapNotNull null
        val mail = m["mail"].asMap()
        PendingInvite(
            key = key,
            email = m.str("email") ?: return@mapNotNull null,
            role = Role.of(m["role"]) ?: return@mapNotNull null,
            at = m.long("at") ?: 0L,
            mail = MailState.of(mail?.get("state")),
            mailAt = mail?.long("at"),
        )
    }.sortedBy { it.at }

internal fun parseInvitations(value: Any?): List<Invitation> =
    value.asMap().orEmpty().mapNotNull { (docId, raw) ->
        val m = raw.asMap() ?: return@mapNotNull null
        Invitation(
            docId = docId,
            title = m.str("title").orEmpty(),
            role = Role.of(m["role"]) ?: return@mapNotNull null,
            byName = m.str("byName").orEmpty(),
            at = m.long("at") ?: 0L,
        )
    }.sortedByDescending { it.at }

internal fun parseComments(value: Any?): List<Comment> =
    value.asMap().orEmpty().mapNotNull { (id, raw) ->
        val m = raw.asMap() ?: return@mapNotNull null
        Comment(
            id = id,
            uid = m.str("a") ?: return@mapNotNull null,
            name = m.str("name").orEmpty(),
            text = m.str("text") ?: return@mapNotNull null,
            quote = m.str("quote")?.takeIf { it.isNotBlank() },
            at = m.long("at") ?: 0L,
            resolved = m["resolved"] == true,
        )
    }.sortedBy { it.at }

internal fun parsePresence(value: Any?): List<Collaborator> =
    value.asMap().orEmpty().mapNotNull { (clientId, raw) ->
        val m = raw.asMap() ?: return@mapNotNull null
        val caret = m.long("c")?.toInt() ?: 0
        Collaborator(
            clientId = clientId,
            uid = m.str("a") ?: return@mapNotNull null,
            name = m.str("name").orEmpty(),
            color = ((m.long("color") ?: 0L).toInt()).mod(8),
            anchor = m.long("s")?.toInt() ?: caret,
            caret = caret,
            at = m.long("at") ?: 0L,
        )
    }.sortedBy { it.name.lowercase() }

/** Une révision du journal ; `null` si elle est illisible. */
internal fun parseRevision(value: Any?): Revision? {
    val m = value.asMap() ?: return null
    val operation = OperationCodec.decode(m.str("o")) ?: return null
    return Revision(m.str("c") ?: return null, m.str("a").orEmpty(), operation)
}
