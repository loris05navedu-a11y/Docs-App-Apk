package com.docssuite.collab.store

/** À écrire à la place d'une valeur : l'heure du serveur au moment où il l'enregistre. */
object ServerTime

/** Ce qui peut empêcher une lecture ou une écriture. */
sealed class StoreException(message: String) : Exception(message) {
    /** Les règles de la base refusent : pas (ou plus) le droit. */
    class PermissionDenied : StoreException("Accès refusé")

    /** Le réseau ou le serveur ne répond pas. */
    class Unavailable(message: String) : StoreException(message)
}

/** Un suivi en cours, à arrêter. */
fun interface Registration {
    fun remove()
}

/**
 * Une base en arbre, comme la base temps réel de Firebase : des chemins
 * (`docs/abc/meta/title`) qui mènent à des valeurs (Map<String, Any?>, String,
 * nombre, booléen) ou à rien (`null`).
 *
 * Les rappels arrivent sur le fil principal.
 */
interface Store {
    /** La valeur en [path], quand le serveur l'a donnée. */
    suspend fun read(path: String): Any?

    /** Écrire plusieurs chemins d'un coup, tout ou rien ; `null` efface. */
    suspend fun update(values: Map<String, Any?>)

    /** Suivre la valeur en [path] : [onValue] tout de suite, puis à chaque changement. */
    fun watch(path: String, onValue: (Any?) -> Unit, onError: (StoreException) -> Unit): Registration

    /**
     * Suivre les enfants de [path] dans l'ordre de leurs clés, à partir de
     * [fromKey] : ceux qui existent, puis chaque nouveau.
     */
    fun watchChildren(
        path: String,
        fromKey: String?,
        onChild: (key: String, value: Any?) -> Unit,
        onError: (StoreException) -> Unit,
    ): Registration

    /**
     * Écrire [value] en [path] seulement si la place est libre. [onDone] dit
     * si c'est nous qui l'avons remplie. Une coupure réseau n'est pas une
     * réponse : l'écriture attend le retour de la connexion.
     */
    fun claim(path: String, value: Map<String, Any?>, onDone: (written: Boolean, error: StoreException?) -> Unit)

    /** Effacer [path] dès que la connexion de cet appareil se perd. */
    fun removeOnDisconnect(path: String)

    fun cancelOnDisconnect(path: String)

    /** Une clé neuve, unique, rangée dans l'ordre du temps. */
    fun newKey(): String

    /** Suivre l'état de la connexion au serveur. */
    fun watchConnected(onChange: (Boolean) -> Unit): Registration

    /** L'heure du serveur, estimée. */
    fun serverNow(): Long
}
