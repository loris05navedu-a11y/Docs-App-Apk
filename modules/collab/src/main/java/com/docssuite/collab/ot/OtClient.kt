package com.docssuite.collab.ot

/**
 * Le côté « appareil » de la co-édition, l'automate d'ot.js :
 *
 * - **synchronisé** : rien en vol ; une frappe part aussitôt ;
 * - **en attente** : une opération est partie, pas encore confirmée ;
 * - **en attente avec tampon** : en plus, les frappes suivantes s'accumulent
 *   en une seule opération, envoyée dès la confirmation de la première.
 *
 * Une opération des autres qui arrive pendant l'attente est transformée par
 * rapport à ce qui est en vol, et inversement : chacun voit le même texte au
 * bout du compte, sans jamais attendre le réseau pour taper.
 *
 * [revision] : le nombre de révisions du journal déjà intégrées, c'est-à-dire
 * le numéro de la prochaine.
 */
class OtClient(revision: Int, private val listener: Listener) {

    interface Listener {
        /** Envoyer [operation] comme révision numéro [revision]. */
        fun sendOperation(revision: Int, operation: TextOperation)

        /** Appliquer au texte affiché une opération venue des autres. */
        fun applyOperation(operation: TextOperation)
    }

    sealed interface State {
        object Synchronized : State
        class AwaitingConfirm(val outstanding: TextOperation) : State
        class AwaitingWithBuffer(val outstanding: TextOperation, val buffer: TextOperation) : State
    }

    var revision: Int = revision
        private set

    var state: State = State.Synchronized
        private set

    // L'état change toujours avant d'appeler [listener] : une réponse qui
    // arriverait pendant l'appel trouve l'état à jour.

    /** Une frappe sur cet appareil. */
    fun applyClient(operation: TextOperation) {
        when (val s = state) {
            State.Synchronized -> {
                state = State.AwaitingConfirm(operation)
                listener.sendOperation(revision, operation)
            }
            is State.AwaitingConfirm -> state = State.AwaitingWithBuffer(s.outstanding, operation)
            is State.AwaitingWithBuffer -> state = State.AwaitingWithBuffer(s.outstanding, s.buffer.compose(operation))
        }
    }

    /** Une révision des autres, dans l'ordre du journal. */
    fun applyServer(operation: TextOperation) {
        revision++
        when (val s = state) {
            State.Synchronized -> listener.applyOperation(operation)
            is State.AwaitingConfirm -> {
                val (outstanding, received) = TextOperation.transform(s.outstanding, operation)
                state = State.AwaitingConfirm(outstanding)
                listener.applyOperation(received)
            }
            is State.AwaitingWithBuffer -> {
                val (outstanding, received) = TextOperation.transform(s.outstanding, operation)
                val (buffer, forUs) = TextOperation.transform(s.buffer, received)
                state = State.AwaitingWithBuffer(outstanding, buffer)
                listener.applyOperation(forUs)
            }
        }
    }

    /** Notre opération en vol est devenue la révision attendue. */
    fun serverAck() {
        revision++
        when (val s = state) {
            State.Synchronized -> error("Confirmation reçue alors que rien n'était en vol")
            is State.AwaitingConfirm -> state = State.Synchronized
            is State.AwaitingWithBuffer -> {
                state = State.AwaitingConfirm(s.buffer)
                listener.sendOperation(revision, s.buffer)
            }
        }
    }

    /**
     * La place visée était prise par quelqu'un d'autre : une fois sa révision
     * intégrée (et notre opération transformée), on renvoie à la suivante.
     */
    fun serverRetry() {
        when (val s = state) {
            State.Synchronized -> {}
            is State.AwaitingConfirm -> listener.sendOperation(revision, s.outstanding)
            is State.AwaitingWithBuffer -> listener.sendOperation(revision, s.outstanding)
        }
    }

    /** Oublier ce qui est en vol ou en attente : on repart du texte du serveur. */
    fun reset() {
        state = State.Synchronized
    }
}
