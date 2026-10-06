package com.docssuite.collab.store

/**
 * Une base en mémoire qui se comporte comme la base temps réel : chemins,
 * écritures groupées tout ou rien, suivis, places à ne remplir qu'une fois,
 * effacement à la déconnexion. Pour les tests et les aperçus.
 *
 * Chaque appareil s'y branche avec [device] ; [rules] joue le rôle des règles
 * de sécurité. Les rappels passent par [post], dans l'ordre où ils sont nés.
 */
class MemoryServer(
    private val post: (() -> Unit) -> Unit = { it() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    enum class Access { READ, WRITE }

    /** Qui a le droit de quoi ; tout est permis si rien n'est donné. */
    var rules: ((access: Access, path: String, uid: String?) -> Boolean)? = null

    private val root = LinkedHashMap<String, Any?>()
    private val devices = ArrayList<MemoryStore>()
    private val queue = ArrayList<() -> Unit>()
    private var draining = false
    private var keys = 0L

    fun device(uid: String? = null): MemoryStore = MemoryStore(this, uid).also { devices += it }

    /** Lire sans règles, pour vérifier. */
    fun value(path: String): Any? = copy(nodeAt(segments(path)))

    /** Écrire sans règles (comme le serveur lui-même). */
    fun write(path: String, value: Any?) {
        put(segments(path), normalize(value, clock()))
        changed()
    }

    internal fun now(): Long = clock()

    internal fun allowed(access: Access, path: String, uid: String?): Boolean =
        rules?.invoke(access, path.trim('/'), uid) ?: true

    internal fun newKey(): String = "-m" + (++keys).toString().padStart(12, '0')

    internal fun read(path: String): Any? = copy(nodeAt(segments(path)))

    internal fun apply(values: Map<String, Any?>) {
        val now = clock()
        for ((path, value) in values) put(segments(path), normalize(value, now))
        changed()
    }

    /** Remplit la place si elle est libre ; vrai si c'est fait. */
    internal fun claim(path: String, value: Map<String, Any?>): Boolean {
        val at = segments(path)
        if (nodeAt(at) != null) return false
        put(at, normalize(value, clock()))
        changed()
        return true
    }

    internal fun schedule(callback: () -> Unit) {
        queue += callback
        if (draining) return
        post {
            if (!draining) {
                draining = true
                try {
                    while (queue.isNotEmpty()) queue.removeAt(0)()
                } finally {
                    draining = false
                }
            }
        }
    }

    /** Prévenir chaque suivi dont la valeur a changé (ou dont l'accès est retiré). */
    internal fun changed() {
        devices.forEach { it.dispatch() }
    }

    private fun segments(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

    private fun nodeAt(at: List<String>): Any? {
        var node: Any? = root
        for (key in at) node = (node as? Map<*, *>)?.get(key) ?: return null
        return node
    }

    @Suppress("UNCHECKED_CAST")
    private fun put(at: List<String>, value: Any?) {
        if (at.isEmpty()) {
            root.clear()
            (value as? Map<String, Any?>)?.let(root::putAll)
            return
        }
        // Le chemin jusqu'au parent, créé au besoin.
        val chain = ArrayList<LinkedHashMap<String, Any?>>()
        var node = root
        for (key in at.dropLast(1)) {
            chain += node
            val next = node[key] as? LinkedHashMap<String, Any?> ?: LinkedHashMap<String, Any?>().also {
                if (value == null) return
                node[key] = it
            }
            node = next
        }
        if (value == null) node.remove(at.last()) else node[at.last()] = value
        // Un dossier vide n'existe pas.
        for (i in at.size - 2 downTo 0) {
            val parent = chain[i]
            val child = parent[at[i]] as? Map<*, *>
            if (child != null && child.isEmpty()) parent.remove(at[i]) else break
        }
    }

    internal companion object {
        /** Comme la base : pas de dossier vide, les entiers en Long, l'heure du serveur résolue. */
        fun normalize(value: Any?, now: Long): Any? = when (value) {
            null -> null
            ServerTime -> now
            is Map<*, *> -> {
                val map = LinkedHashMap<String, Any?>()
                for ((k, v) in value) normalize(v, now)?.let { map[k.toString()] = it }
                map.ifEmpty { null }
            }
            is Boolean, is String, is Long -> value
            is Int -> value.toLong()
            is Short -> value.toLong()
            is Byte -> value.toLong()
            is Float -> normalize(value.toDouble(), now)
            is Double -> if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 9.0e15) value.toLong() else value
            is List<*> -> normalize(value.withIndex().associate { (i, v) -> i.toString() to v }, now)
            else -> throw IllegalArgumentException("Type non pris en charge : ${value::class.java.name}")
        }

        fun copy(value: Any?): Any? = when (value) {
            is Map<*, *> -> LinkedHashMap<String, Any?>().also { map -> value.forEach { (k, v) -> map[k.toString()] = copy(v) } }
            else -> value
        }
    }
}

/** Un appareil branché sur un [MemoryServer], connecté sous le compte [uid]. */
class MemoryStore internal constructor(private val server: MemoryServer, var uid: String?) : Store {

    private val watchers = ArrayList<Watcher>()
    private val connectionWatchers = ArrayList<(Boolean) -> Unit>()
    private val onDisconnect = LinkedHashSet<String>()
    private val waitingClaims = ArrayList<Triple<String, Map<String, Any?>, (Boolean, StoreException?) -> Unit>>()

    /** Couper ou rétablir la connexion de cet appareil. */
    var connected: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (!value && onDisconnect.isNotEmpty()) {
                val removals = onDisconnect.associateWith { null }
                onDisconnect.clear()
                server.apply(removals)
            }
            connectionWatchers.toList().forEach { watcher -> server.schedule { watcher(value) } }
            if (value) {
                dispatch()
                val claims = waitingClaims.toList()
                waitingClaims.clear()
                claims.forEach { (path, claimed, onDone) -> claim(path, claimed, onDone) }
            }
        }

    private abstract inner class Watcher(val path: String, val onError: (StoreException) -> Unit) {
        var active = true
        abstract fun check()
    }

    private inner class ValueWatcher(path: String, val onValue: (Any?) -> Unit, onError: (StoreException) -> Unit) :
        Watcher(path, onError) {
        private var delivered = false
        private var last: Any? = null

        override fun check() {
            val value = server.read(path)
            if (delivered && value == last) return
            delivered = true
            last = value
            server.schedule { if (active) onValue(MemoryServer.copy(value)) }
        }
    }

    private inner class ChildWatcher(
        path: String,
        val fromKey: String?,
        val onChild: (String, Any?) -> Unit,
        onError: (StoreException) -> Unit,
    ) : Watcher(path, onError) {
        private val delivered = HashMap<String, Any?>()

        override fun check() {
            val children = server.read(path) as? Map<*, *> ?: return
            for (key in children.keys.map { it.toString() }.sorted()) {
                if (fromKey != null && key < fromKey) continue
                val value = children[key]
                if (delivered.containsKey(key) && delivered[key] == value) continue
                delivered[key] = value
                server.schedule { if (active) onChild(key, MemoryServer.copy(value)) }
            }
        }
    }

    internal fun dispatch() {
        if (!connected) return
        for (watcher in watchers.toList()) {
            if (!watcher.active) continue
            if (!server.allowed(MemoryServer.Access.READ, watcher.path, uid)) {
                watcher.active = false
                watchers -= watcher
                server.schedule { watcher.onError(StoreException.PermissionDenied()) }
            } else {
                watcher.check()
            }
        }
    }

    private fun register(watcher: Watcher): Registration {
        if (!server.allowed(MemoryServer.Access.READ, watcher.path, uid)) {
            server.schedule { watcher.onError(StoreException.PermissionDenied()) }
            return Registration {}
        }
        watchers += watcher
        if (connected) watcher.check()
        return Registration {
            watcher.active = false
            watchers -= watcher
        }
    }

    override suspend fun read(path: String): Any? {
        if (!connected) throw StoreException.Unavailable("Hors ligne")
        if (!server.allowed(MemoryServer.Access.READ, path, uid)) throw StoreException.PermissionDenied()
        return server.read(path)
    }

    override suspend fun update(values: Map<String, Any?>) {
        if (!connected) throw StoreException.Unavailable("Hors ligne")
        if (values.keys.any { !server.allowed(MemoryServer.Access.WRITE, it, uid) }) throw StoreException.PermissionDenied()
        server.apply(values)
    }

    override fun watch(path: String, onValue: (Any?) -> Unit, onError: (StoreException) -> Unit): Registration =
        register(ValueWatcher(path, onValue, onError))

    override fun watchChildren(
        path: String,
        fromKey: String?,
        onChild: (key: String, value: Any?) -> Unit,
        onError: (StoreException) -> Unit,
    ): Registration = register(ChildWatcher(path, fromKey, onChild, onError))

    override fun claim(path: String, value: Map<String, Any?>, onDone: (written: Boolean, error: StoreException?) -> Unit) {
        when {
            // Hors ligne, l'écriture attend le retour de la connexion.
            !connected -> waitingClaims += Triple(path, value, onDone)
            !server.allowed(MemoryServer.Access.WRITE, path, uid) ->
                server.schedule { onDone(false, StoreException.PermissionDenied()) }
            else -> {
                val written = server.claim(path, value)
                server.schedule { onDone(written, null) }
            }
        }
    }

    override fun removeOnDisconnect(path: String) {
        onDisconnect += path
    }

    override fun cancelOnDisconnect(path: String) {
        onDisconnect -= path
    }

    override fun newKey(): String = server.newKey()

    override fun watchConnected(onChange: (Boolean) -> Unit): Registration {
        connectionWatchers += onChange
        val now = connected
        server.schedule { onChange(now) }
        return Registration { connectionWatchers -= onChange }
    }

    override fun serverNow(): Long = server.now()
}
