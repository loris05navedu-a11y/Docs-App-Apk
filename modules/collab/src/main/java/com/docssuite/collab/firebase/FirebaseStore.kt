package com.docssuite.collab.firebase

import com.docssuite.collab.store.Registration
import com.docssuite.collab.store.ServerTime
import com.docssuite.collab.store.Store
import com.docssuite.collab.store.StoreException
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.ServerValue
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** La base temps réel de Firebase, vue comme un [Store]. */
class FirebaseStore(private val database: FirebaseDatabase) : Store {

    private val root = database.reference

    @Volatile
    private var serverOffset = 0L

    init {
        database.getReference(".info/serverTimeOffset").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                serverOffset = (snapshot.value as? Number)?.toLong() ?: 0L
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    override suspend fun read(path: String): Any? = suspendCancellableCoroutine { continuation ->
        val ref = root.child(path)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (continuation.isActive) continuation.resume(snapshot.value)
            }

            override fun onCancelled(error: DatabaseError) {
                if (continuation.isActive) continuation.resumeWithException(error.toStoreException())
            }
        }
        ref.addListenerForSingleValueEvent(listener)
        continuation.invokeOnCancellation { ref.removeEventListener(listener) }
    }

    override suspend fun update(values: Map<String, Any?>) = suspendCancellableCoroutine { continuation ->
        root.updateChildren(values.mapValues { toFirebase(it.value) }) { error, _ ->
            if (!continuation.isActive) return@updateChildren
            if (error == null) continuation.resume(Unit) else continuation.resumeWithException(error.toStoreException())
        }
    }

    override fun watch(path: String, onValue: (Any?) -> Unit, onError: (StoreException) -> Unit): Registration {
        val ref = root.child(path)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) = onValue(snapshot.value)
            override fun onCancelled(error: DatabaseError) = onError(error.toStoreException())
        }
        ref.addValueEventListener(listener)
        return Registration { ref.removeEventListener(listener) }
    }

    override fun watchChildren(
        path: String,
        fromKey: String?,
        onChild: (key: String, value: Any?) -> Unit,
        onError: (StoreException) -> Unit,
    ): Registration {
        val ordered = root.child(path).orderByKey()
        val query = if (fromKey != null) ordered.startAt(fromKey) else ordered
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previous: String?) {
                snapshot.key?.let { onChild(it, snapshot.value) }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previous: String?) {
                snapshot.key?.let { onChild(it, snapshot.value) }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previous: String?) {}
            override fun onCancelled(error: DatabaseError) = onError(error.toStoreException())
        }
        query.addChildEventListener(listener)
        return Registration { query.removeEventListener(listener) }
    }

    override fun claim(path: String, value: Map<String, Any?>, onDone: (written: Boolean, error: StoreException?) -> Unit) {
        val data = toFirebase(value)
        root.child(path).runTransaction(object : Transaction.Handler {
            // Appelé hors du fil principal : on ne touche qu'à des valeurs figées.
            override fun doTransaction(current: MutableData): Transaction.Result {
                if (current.value != null) return Transaction.abort()
                current.value = data
                return Transaction.success(current)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                onDone(committed && error == null, error?.toStoreException())
            }
        }, false)
    }

    override fun removeOnDisconnect(path: String) {
        root.child(path).onDisconnect().removeValue()
    }

    override fun cancelOnDisconnect(path: String) {
        root.child(path).onDisconnect().cancel()
    }

    override fun newKey(): String = root.push().key!!

    override fun watchConnected(onChange: (Boolean) -> Unit): Registration {
        val ref = database.getReference(".info/connected")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) = onChange(snapshot.value == true)
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        return Registration { ref.removeEventListener(listener) }
    }

    override fun serverNow(): Long = System.currentTimeMillis() + serverOffset

    private companion object {
        fun toFirebase(value: Any?): Any? = when (value) {
            ServerTime -> ServerValue.TIMESTAMP
            is Map<*, *> -> HashMap<String, Any?>().also { map ->
                value.forEach { (k, v) -> if (v != null) map[k.toString()] = toFirebase(v) }
            }
            else -> value
        }

        fun DatabaseError.toStoreException(): StoreException = when (code) {
            DatabaseError.PERMISSION_DENIED -> StoreException.PermissionDenied()
            else -> StoreException.Unavailable(message)
        }
    }
}
