package com.docssuite.collab.ot

/** Une révision du journal : l'appareil qui l'a écrite (et son compte), et l'opération. */
data class Revision(val clientId: String, val uid: String, val operation: TextOperation)

/**
 * Le journal partagé des révisions, numérotées 0, 1, 2… Chaque place ne
 * reçoit qu'une révision : la première écrite gagne.
 */
interface RevisionLog {
    /**
     * Écrire [revision] à la place [index] si elle est encore libre. Une
     * coupure réseau ne compte pas comme un refus : l'implémentation réessaie
     * jusqu'à ce que la place soit remplie, par nous ou par un autre. La
     * réponse arrive de toute façon par le journal lui-même.
     */
    fun append(index: Int, revision: Revision)

    /** Le texte après les révisions 0 … [nextIndex] − 1, pour ne plus relire tout le journal. */
    fun saveCheckpoint(nextIndex: Int, text: String)
}

/**
 * La synchronisation d'un document partagé, sur le principe de Firepad.
 *
 * Chaque frappe part tout de suite vers la prochaine place libre du journal.
 * Si quelqu'un d'autre l'a prise entre-temps, sa révision arrive par le
 * journal : on la transforme avec la nôtre, on l'affiche, et on renvoie la
 * nôtre — transformée — à la place suivante. Personne n'arbitre : l'ordre du
 * journal suffit, et tous les appareils convergent vers le même texte.
 *
 * Les révisions peuvent arriver dans le désordre ou en double : on les
 * intègre une à une, dans l'ordre des numéros. Une révision illisible
 * (donnée abîmée) compte comme « ne rien changer », pour tout le monde pareil.
 *
 * [onRemote] reçoit les opérations à appliquer au texte affiché ; il est
 * appelé pendant [receive], jamais ailleurs.
 */
class SyncEngine(
    private val clientId: String,
    private val uid: String,
    private val log: RevisionLog,
    startIndex: Int,
    startText: String,
    private val onRemote: (TextOperation) -> Unit,
    private val checkpointEvery: Int = 100
) {
    /** Le texte tel que le journal le donne, sans nos frappes encore en vol. */
    var serverText: String = startText
        private set

    private val received = HashMap<Int, Revision?>()
    private var sent: Pair<Int, TextOperation>? = null

    private val client = OtClient(startIndex, object : OtClient.Listener {
        override fun sendOperation(revision: Int, operation: TextOperation) {
            sent = revision to operation
            log.append(revision, Revision(clientId, uid, operation))
        }

        override fun applyOperation(operation: TextOperation) = onRemote(operation)
    })

    /** Le numéro de la prochaine révision attendue. */
    val revision: Int get() = client.revision

    /** Vrai quand toutes nos frappes sont dans le journal. */
    val synced: Boolean get() = client.state == OtClient.State.Synchronized

    /** Une frappe locale, déjà appliquée au texte affiché. */
    fun local(operation: TextOperation) {
        if (!operation.isNoop) client.applyClient(operation)
    }

    /** Une révision lue dans le journal ; `null` si elle est illisible. */
    fun receive(index: Int, revision: Revision?) {
        if (index < client.revision) return
        received[index] = revision
        drain()
    }

    private fun drain() {
        var retry = false
        while (received.containsKey(client.revision)) {
            val index = client.revision
            val revision = received.remove(index)
            val operation = revision?.operation?.takeIf { it.baseLength == serverText.length }
                ?: TextOperation.identity(serverText.length)
            serverText = operation.apply(serverText)

            val pending = sent
            if (pending != null && pending.first == index) {
                sent = null
                if (revision != null && revision.clientId == clientId && revision.operation == pending.second) {
                    client.serverAck()
                    if ((index + 1) % checkpointEvery == 0) log.saveCheckpoint(index + 1, serverText)
                } else {
                    // Place prise par un autre : on intègre la sienne, puis on renvoie la nôtre.
                    retry = true
                    client.applyServer(operation)
                }
            } else {
                client.applyServer(operation)
            }
        }
        if (retry) client.serverRetry()
    }
}
